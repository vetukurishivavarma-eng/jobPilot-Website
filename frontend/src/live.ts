// Live interview engine, shared by the website and the phone app (which shows /live/:id in a WebView).
// Camera + mic in the page. Face, gaze, expression and phone detection run ON THE DEVICE (MediaPipe); only per-answer
// percentages leave it. Records the whole interview (uploaded once at the end, kept 1 hour) and each answer's audio
// (sent for speech-to-text, not kept).
import type { Signals } from './api'

const VISION = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14'
const FACE_MODEL = 'https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task'
const OBJECT_MODEL = 'https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite'

const pick = (types: string[]) => types.find(t => typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(t)) ?? ''
type Counts = { frames: number; away: number; eyesOff: number; tense: number; smile: number; started: number; pasted: boolean }
const fresh = (): Counts => ({ frames: 0, away: 0, eyesOff: 0, tense: 0, smile: 0, started: Date.now(), pasted: false })
// Looking-away thresholds. Calibration knobs: tune on real phones (degrees from the starting pose; blendshape 0..1).
// Everything is measured against how they sat and looked in the first seconds: the camera is above the screen, so
// reading the screen already reads as "eyes down" (that baseline made the first version nag people who were looking).
const MAX_YAW = 30, MAX_PITCH = 25, EYES_SIDE = 0.35, EYES_DOWN = 0.35
const CALIBRATION_FRAMES = 12 // ~3 s at 4 checks/s, eyes open: the pose they hold while the interview starts
const AWAY_MS = 2500 // looking away this long before the warning
const median = (xs: number[]) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)]
const pct = (n: number, d: number) => (d ? Math.round((100 * n) / d) : 0)

export class Engine {
  stream: MediaStream | null = null
  /** fired after 2s of no face / head turned, and when the face is back */
  onAway: (away: boolean) => void = () => {}
  /** fired once each time the face leaves the frame for 2s (no face at all, not a head turn); fires before onAway */
  onGone: () => void = () => {}
  goneNow = false
  /** fired when a phone is seen in 2 checks in a row (then quiet for 15s) */
  onPhone: () => void = () => {}
  level = 0 // mic level 0..1, for the meter

  private full: MediaRecorder | null = null
  private fullChunks: Blob[] = []
  private clip: MediaRecorder | null = null
  private clipChunks: Blob[] = []
  private counts = fresh()
  private measuring = false
  private typing = false
  private ctx: AudioContext | null = null
  private analyser: AnalyserNode | null = null
  private timers: number[] = []
  private face: any = null
  private objects: any = null
  private awaySince = 0
  private awayNow = false
  private goneSince = 0
  private calib: { yaw: number; pitch: number; side: number; down: number }[] = []
  private base: { yaw: number; pitch: number; side: number; down: number } | null = null
  private phoneHits = 0
  private phoneQuietUntil = 0

  /** Asks for camera + mic and starts the full recording. Throws if permission is denied. */
  async start(video: HTMLVideoElement) {
    this.stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'user', width: 320, height: 240 },
      // echo cancellation off: it erased the interviewer's voice (played on the speaker) from the recording. Answers are
      // recorded only while he's silent, and his spoken nudges are stripped from transcripts on the server.
      // ponytail: room capture, so silent with headphones; mix server-side TTS audio into the recording if that matters
      audio: { echoCancellation: false, noiseSuppression: true, autoGainControl: true },
    })
    video.srcObject = this.stream
    await video.play()
    this.ctx = new AudioContext()
    this.analyser = this.ctx.createAnalyser()
    this.analyser.fftSize = 1024
    this.ctx.createMediaStreamSource(this.stream).connect(this.analyser)
    // MP4 first (plays everywhere: phone gallery, WhatsApp, Windows); WebM only where the browser can't record MP4
    const mime = pick(['video/mp4;codecs=avc1.42E01E,mp4a.40.2', 'video/mp4;codecs=avc1,opus', 'video/mp4', 'video/webm;codecs=vp8,opus', 'video/webm'])
    if (mime) {
      this.full = new MediaRecorder(this.stream, { mimeType: mime, videoBitsPerSecond: 200_000, audioBitsPerSecond: 32_000 })
      this.full.ondataavailable = e => { if (e.data.size) this.fullChunks.push(e.data) }
      this.full.start(5000)
    }
    this.loadVision(video).catch(() => { /* interview works without it, just no signals */ })
  }

  private async loadVision(video: HTMLVideoElement) {
    const { FaceLandmarker, ObjectDetector, FilesetResolver } = await import(/* @vite-ignore */ `${VISION}/vision_bundle.mjs`)
    const files = await FilesetResolver.forVisionTasks(`${VISION}/wasm`)
    this.face = await FaceLandmarker.createFromOptions(files, {
      baseOptions: { modelAssetPath: FACE_MODEL }, runningMode: 'VIDEO', numFaces: 1, outputFaceBlendshapes: true,
      outputFacialTransformationMatrixes: true, // head pose (yaw / pitch)
    })
    this.objects = await ObjectDetector.createFromOptions(files, {
      baseOptions: { modelAssetPath: OBJECT_MODEL }, runningMode: 'VIDEO', scoreThreshold: 0.5, maxResults: 3, categoryAllowlist: ['cell phone'],
    })
    let tick = 0
    const loop = () => {
      if (!this.stream) return
      try {
        this.faceFrame(this.face.detectForVideo(video, performance.now()))
        if (++tick % 4 === 0) this.phoneFrame(this.objects.detectForVideo(video, performance.now()))
      } catch { /* a dropped frame */ }
      this.timers.push(window.setTimeout(loop, 250))
    }
    loop()
  }

  private faceFrame(r: any) {
    const lm = r.faceLandmarks?.[0]
    const bs: Record<string, number> = {}
    for (const c of r.faceBlendshapes?.[0]?.categories ?? []) bs[c.categoryName] = c.score
    // head pose from the transformation matrix (column-major 4x4; columns normalised because it carries scale)
    let yaw = 0, pitch = 0
    const m: number[] | undefined = r.facialTransformationMatrixes?.[0]?.data
    if (m) {
      const c0 = Math.hypot(m[0], m[1], m[2]) || 1, c1 = Math.hypot(m[4], m[5], m[6]) || 1, c2 = Math.hypot(m[8], m[9], m[10]) || 1
      yaw = Math.asin(Math.max(-1, Math.min(1, -m[2] / c0))) * 180 / Math.PI
      pitch = Math.atan2(m[6] / c1, m[10] / c2) * 180 / Math.PI
    } else if (lm) { // no matrix: nose offset from the eye midpoint, in eye-widths, as rough degrees
      const l = lm[33], rr = lm[263], nose = lm[1]
      yaw = 90 * (nose.x - (l.x + rr.x) / 2) / Math.max(Math.abs(rr.x - l.x), 0.01)
    }
    // where the eyes point, from the blendshapes (0..1)
    const side = Math.max(((bs.eyeLookOutLeft ?? 0) + (bs.eyeLookInRight ?? 0)) / 2, ((bs.eyeLookInLeft ?? 0) + (bs.eyeLookOutRight ?? 0)) / 2)
    const down = ((bs.eyeLookDownLeft ?? 0) + (bs.eyeLookDownRight ?? 0)) / 2
    const blinking = ((bs.eyeBlinkLeft ?? 0) + (bs.eyeBlinkRight ?? 0)) / 2 > 0.5
    let turned = false, eyesOff = false
    if (lm && !this.base) { // calibrating: no warnings yet
      if (!blinking) this.calib.push({ yaw, pitch, side, down })
      if (this.calib.length >= CALIBRATION_FRAMES) this.base = {
        yaw: median(this.calib.map(x => x.yaw)), pitch: median(this.calib.map(x => x.pitch)),
        side: median(this.calib.map(x => x.side)), down: median(this.calib.map(x => x.down)),
      }
    } else if (lm && this.base) {
      const b = this.base
      turned = Math.abs(yaw - b.yaw) > MAX_YAW || Math.abs(pitch - b.pitch) > MAX_PITCH
      eyesOff = !turned && !blinking && (side - b.side > EYES_SIDE || (!this.typing && down - b.down > EYES_DOWN)) // typing = looking down
    }
    const away = !lm || turned || eyesOff
    const tense = ((bs.browDownLeft ?? 0) + (bs.browDownRight ?? 0)) / 2 > 0.5 || ((bs.mouthPressLeft ?? 0) + (bs.mouthPressRight ?? 0)) / 2 > 0.5
    const smile = ((bs.mouthSmileLeft ?? 0) + (bs.mouthSmileRight ?? 0)) / 2 > 0.5
    if (this.measuring) {
      const c = this.counts
      c.frames++
      if (away) c.away++
      if (eyesOff) c.eyesOff++
      if (tense) c.tense++
      if (smile) c.smile++
    }
    const now = Date.now()
    if (lm) { this.goneSince = 0; this.goneNow = false }
    else if (!this.goneSince) this.goneSince = now
    else if (now - this.goneSince > 2000 && !this.goneNow) { this.goneNow = true; this.onGone() }
    if (!away) { this.awaySince = 0; if (this.awayNow) { this.awayNow = false; this.onAway(false) } }
    else if (!this.awaySince) this.awaySince = now
    else if (now - this.awaySince > AWAY_MS && !this.awayNow) { this.awayNow = true; this.onAway(true) }
  }

  private phoneFrame(r: any) {
    const seen = (r.detections ?? []).some((d: any) => d.categories?.some((c: any) => c.categoryName === 'cell phone' && c.score >= 0.5))
    this.phoneHits = seen ? this.phoneHits + 1 : 0
    if (this.phoneHits >= 2 && Date.now() > this.phoneQuietUntil) {
      this.phoneQuietUntil = Date.now() + 15_000
      this.phoneHits = 0
      this.onPhone()
    }
  }

  /** Starts measuring one answer; spoken answers also record an audio clip. */
  beginAnswer(typing: boolean) {
    this.counts = fresh()
    this.measuring = true
    this.typing = typing
    this.clipChunks = []
    this.clip = null
    const mime = pick(['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4'])
    if (!typing && this.stream && mime) {
      this.clip = new MediaRecorder(new MediaStream(this.stream.getAudioTracks()), { mimeType: mime, audioBitsPerSecond: 24_000 })
      this.clip.ondataavailable = e => { if (e.data.size) this.clipChunks.push(e.data) }
      this.clip.start(1000)
    }
  }

  markPasted() { this.counts.pasted = true }

  /** Stops measuring; returns the answer's audio (spoken) and what the camera saw. */
  async endAnswer(): Promise<{ audio: Blob | null; signals: Signals }> {
    this.measuring = false
    const c = this.counts
    const signals: Signals = {
      seconds: Math.round((Date.now() - c.started) / 1000),
      away: pct(c.away, c.frames), eyesOff: pct(c.eyesOff, c.frames), tense: pct(c.tense, c.frames), smile: pct(c.smile, c.frames),
      pasted: c.pasted || undefined,
    }
    const rec = this.clip
    this.clip = null
    if (!rec) return { audio: null, signals }
    await new Promise<void>(res => { rec.onstop = () => res(); rec.state === 'inactive' ? res() : rec.stop() })
    return { audio: this.clipChunks.length ? new Blob(this.clipChunks, { type: rec.mimeType }) : null, signals }
  }

  /**
   * Voice activity: calls onEnd after the candidate has spoken and then been quiet for `silenceMs`
   * (or after 4 minutes). Returns a cancel function.
   */
  listen(onEnd: () => void, silenceMs = 2500): () => void {
    const buf = new Float32Array(1024)
    const floor: number[] = []
    let spoke = 0, quiet = 0, total = 0, done = false
    const id = window.setInterval(() => {
      if (!this.analyser || done) return
      this.analyser.getFloatTimeDomainData(buf)
      let sum = 0
      for (const v of buf) sum += v * v
      const rms = Math.sqrt(sum / buf.length)
      this.level = Math.min(1, rms * 8)
      total += 100
      if (floor.length < 6) { floor.push(rms); return } // first 600 ms: room noise
      const threshold = Math.max(0.015, [...floor].sort()[3] * 2.5)
      if (rms > threshold) { spoke += 100; quiet = 0 } else quiet += 100
      if ((spoke >= 1200 && quiet >= silenceMs) || total >= 240_000) { done = true; clearInterval(id); this.level = 0; onEnd() }
    }, 100)
    return () => { done = true; clearInterval(id); this.level = 0 }
  }

  /** Stops camera, mic and vision; returns the full recording (null if the browser can't record). */
  async stop(): Promise<Blob | null> {
    this.timers.forEach(clearTimeout)
    this.timers = []
    this.measuring = false
    const full = this.full
    this.full = null
    if (full && full.state !== 'inactive') await new Promise<void>(res => { full.onstop = () => res(); full.stop() })
    this.stream?.getTracks().forEach(t => t.stop())
    this.stream = null
    try { this.face?.close(); this.objects?.close() } catch { /* already closed */ }
    this.ctx?.close().catch(() => {})
    return full && this.fullChunks.length ? new Blob(this.fullChunks, { type: full.mimeType }) : null
  }
}

// ------------------------------------------------------------------ the interviewer's voice

/** In the phone app the WebView asks the app to speak (native Indian-English voice); in a browser, speechSynthesis. */
export const inApp = () => !!(window as any).ReactNativeWebView
export const toApp = (msg: object) => (window as any).ReactNativeWebView?.postMessage(JSON.stringify(msg))

function indianVoice(): SpeechSynthesisVoice | null {
  const vs = speechSynthesis.getVoices()
  const en = vs.filter(v => v.lang.replace('_', '-').toLowerCase() === 'en-in')
  return en.find(v => /ravi|prabhat|hemant|madhur|male/i.test(v.name) && !/female/i.test(v.name)) ?? en[0]
    ?? vs.find(v => /^en/i.test(v.lang) && /male/i.test(v.name) && !/female/i.test(v.name)) ?? null
}
if (typeof speechSynthesis !== 'undefined') speechSynthesis.getVoices() // Chrome loads voices lazily

export function speak(text: string): Promise<void> {
  if (inApp()) {
    return new Promise(res => {
      const done = () => { window.removeEventListener('jp-spoken', done); clearTimeout(t); res() }
      const t = window.setTimeout(done, 20_000 + text.length * 120)
      window.addEventListener('jp-spoken', done)
      toApp({ type: 'speak', text })
    })
  }
  if (typeof speechSynthesis === 'undefined') return Promise.resolve()
  return new Promise(res => {
    const u = new SpeechSynthesisUtterance(text)
    const v = indianVoice()
    if (v) u.voice = v
    u.lang = v?.lang ?? 'en-IN'
    u.rate = 0.97
    u.pitch = 0.9
    u.onend = u.onerror = () => res()
    speechSynthesis.cancel()
    speechSynthesis.speak(u)
  })
}

export function stopSpeaking() {
  if (inApp()) toApp({ type: 'stopSpeaking' })
  else if (typeof speechSynthesis !== 'undefined') speechSynthesis.cancel()
}
