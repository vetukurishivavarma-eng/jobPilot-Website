// Talks to the local JobPilot API. Content scripts can't (the job site's page origin and Chrome's local-network
// rules block it), so they send messages here and this worker calls the API with the user's token.
const API = 'http://localhost:8080/api';

async function call(path, opts = {}) {
  const { token } = await chrome.storage.local.get('token');
  if (!token) throw new Error('Open JobPilot (http://localhost:5173) and sign in once, so the extension can use your account.');
  let res;
  try {
    res = await fetch(API + path, { ...opts, headers: { Authorization: 'Bearer ' + token } });
  } catch {
    throw new Error("Can't reach JobPilot on this PC. Is it running? (start.ps1)");
  }
  if (res.status === 401) throw new Error('Your JobPilot sign-in expired. Open http://localhost:5173 and sign in again.');
  if (!res.ok) {
    let m = 'JobPilot error ' + res.status;
    try { m = (await res.json()).message || m } catch { /* not JSON */ }
    throw new Error(m);
  }
  return res;
}

async function resume(id) {
  const res = await call(`/applications/${id}/resume`);
  const cd = res.headers.get('Content-Disposition') || '';
  const star = cd.match(/filename\*=UTF-8''([^;]+)/i);
  const plain = cd.match(/filename="([^"]+)"/i);
  const name = star ? decodeURIComponent(star[1]) : plain ? plain[1] : 'Resume.pdf';
  const buf = new Uint8Array(await res.arrayBuffer());
  let s = '';
  for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000));
  return { name, type: res.headers.get('Content-Type') || 'application/pdf', b64: btoa(s) };
}

// ---------- auto mode queue ----------
// With auto mode on in JobPilot, this works through the READY auto applications one at a time: opens the form in a
// background tab, fill.js fills it (and presses Submit if "Submit for me" is on). A CAPTCHA challenge, a field it
// couldn't fill, or review mode brings the tab to the front for the user. Nothing here touches a CAPTCHA.
const get = async (k) => (await chrome.storage.session.get(k))[k];
const skipped = async () => (await chrome.storage.local.get('skip')).skip || [];
async function skip(id) { const s = await skipped(); if (!s.includes(id)) chrome.storage.local.set({ skip: [...s, id].slice(-500) }) }

let ticking = null;
function tick() { return (ticking ||= next().catch((e) => console.warn('JobPilot queue:', e.message)).finally(() => (ticking = null))) }

async function next() {
  const cur = await get('cur');
  if (cur) {
    if (await chrome.tabs.get(cur.tabId).catch(() => null)) return; // still working on it
    await chrome.storage.session.remove('cur');
  }
  const s = await (await call('/auto-apply')).json();
  if (!s.enabled || s.submittedToday >= s.dailyLimit) return;
  const skip = await skipped();
  const app = (await (await call('/applications')).json())
    .find((a) => a.auto && a.status === 'READY' && a.missing === 0 && a.applyUrl && !skip.includes(a.id));
  if (!app) return;
  const tab = await chrome.tabs.create({ url: app.applyUrl, active: false });
  await chrome.storage.session.set({ cur: { tabId: tab.id, appId: app.id, autoSubmit: s.autoSubmit, title: `${app.jobTitle} at ${app.company}` } });
}

async function needsYou(tab, reason) {
  const cur = await get('cur');
  if (cur?.tabId === tab.id) await skip(cur.appId); // the user finishes it; the queue won't reopen it
  chrome.tabs.update(tab.id, { active: true });
  chrome.windows.update(tab.windowId, { focused: true });
  chrome.notifications.create({ type: 'basic', iconUrl: 'icon.png', title: 'JobPilot needs you' + (cur ? ': ' + cur.title : ''), message: reason });
}

chrome.alarms.create('queue', { periodInMinutes: 2 });
chrome.alarms.onAlarm.addListener(tick);
chrome.runtime.onStartup.addListener(tick);
chrome.tabs.onRemoved.addListener(async (tabId) => {
  const cur = await get('cur');
  if (cur?.tabId !== tabId) return;
  await skip(cur.appId); // closed without submitting: leave it for the user
  await chrome.storage.session.remove('cur');
  tick();
});

chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  (async () => {
    try {
      if (msg.type === 'forPage') reply({ data: await (await call('/applications/for-page?url=' + encodeURIComponent(msg.url))).json() });
      else if (msg.type === 'resume') reply(await resume(msg.id));
      else if (msg.type === 'role') { const cur = await get('cur'); reply(cur?.tabId === sender.tab?.id ? { auto: true, autoSubmit: cur.autoSubmit } : { auto: false }) }
      else if (msg.type === 'needsYou') { await needsYou(sender.tab, msg.reason); reply({}) }
      else if (msg.type === 'submitted') {
        const data = await (await call(`/applications/${msg.id}/submitted`, { method: 'POST' })).json();
        reply({ data });
        const cur = await get('cur');
        if (cur?.tabId === sender.tab?.id) {
          await chrome.storage.session.remove('cur');
          setTimeout(() => chrome.tabs.remove(sender.tab.id).catch(() => {}), 3000);
          tick();
        }
      }
      else if (msg.type === 'kick') { tick(); reply({}) }
      else reply({ error: 'unknown message' });
    } catch (e) {
      reply({ error: e.message });
    }
  })();
  return true; // reply asynchronously
});
