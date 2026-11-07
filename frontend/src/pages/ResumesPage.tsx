import { useEffect, useRef, useState } from 'react'
import { api, ApiError, type Resume } from '../api'

const kb = (n: number) => (n < 1024 * 1024 ? `${Math.round(n / 1024)} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`)

export default function ResumesPage() {
  const [items, setItems] = useState<Resume[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [uploading, setUploading] = useState(false)
  const [preview, setPreview] = useState<{ id: string; text: string } | null>(null)
  const input = useRef<HTMLInputElement>(null)

  const refresh = () => api.listResumes().then(setItems).catch(fail)
  useEffect(() => { refresh() }, [])

  function fail(e: unknown) {
    setError(e instanceof ApiError ? e.message : 'Cannot reach the server')
  }

  async function upload(file: File | undefined) {
    if (!file) return
    setError(null)
    if (file.size > 5 * 1024 * 1024) return setError('Resume must be 5 MB or smaller')
    setUploading(true)
    try { await api.uploadResume(file); await refresh() } catch (e) { fail(e) } finally {
      setUploading(false)
      if (input.current) input.current.value = ''
    }
  }

  async function act(fn: () => Promise<unknown>) {
    setError(null)
    try { await fn(); await refresh() } catch (e) { fail(e) }
  }

  async function togglePreview(r: Resume) {
    if (preview?.id === r.id) return setPreview(null)
    try { const t = await api.resumeText(r.id); setPreview({ id: r.id, text: t.extractedText }) } catch (e) { fail(e) }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="font-display text-3xl font-bold">Resumes</h1>
        <p className="mt-1 max-w-xl text-sm text-muted">
          Upload up to 10 versions (PDF or DOCX, 5 MB max). The agent picks the best match for each job; the starred one is the default.
        </p>
      </div>

      <label
        className="card flex cursor-pointer flex-col items-center gap-2 border-dashed py-10 text-center transition hover:border-signal"
        onDragOver={e => e.preventDefault()}
        onDrop={e => { e.preventDefault(); upload(e.dataTransfer.files[0]) }}
      >
        <span className="font-display text-lg font-bold">{uploading ? 'Uploading…' : 'Drop a resume here or click to choose'}</span>
        <span className="text-xs text-muted">PDF or DOCX</span>
        <input ref={input} type="file" className="sr-only" disabled={uploading}
          accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
          onChange={e => upload(e.target.files?.[0])} />
      </label>

      {error && <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}

      {items === null ? <p className="text-muted">Loading…</p> : items.length === 0 ? (
        <p className="text-sm text-muted">No resumes yet.</p>
      ) : (
        <ul className="space-y-3">
          {items.map(r => (
            <li key={r.id} className="card">
              <div className="flex flex-wrap items-center gap-3">
                <button title={r.primary ? 'Default resume' : 'Make default'} aria-label={r.primary ? 'Default resume' : 'Make default'}
                  className={`text-xl leading-none ${r.primary ? 'text-signal' : 'text-line hover:text-muted'}`}
                  onClick={() => !r.primary && act(() => api.updateResume(r.id, { primary: true }))}>★</button>
                <div className="min-w-0 flex-1">
                  <input
                    className="w-full truncate bg-transparent font-semibold outline-none focus:underline"
                    defaultValue={r.label} maxLength={120} aria-label="Resume label"
                    onBlur={e => { const v = e.target.value.trim(); if (v && v !== r.label) act(() => api.updateResume(r.id, { label: v })) }}
                  />
                  <p className="truncate text-xs text-muted">
                    {r.originalFilename} · {kb(r.sizeBytes)} · {new Date(r.createdAt).toLocaleDateString()}
                  </p>
                </div>
                {!r.textExtracted && (
                  <span className="rounded bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800" title="Looks like a scanned image. The agent can't read it.">
                    No readable text
                  </span>
                )}
                <div className="flex gap-2">
                  <button className="btn-ghost" onClick={() => togglePreview(r)}>{preview?.id === r.id ? 'Hide text' : 'View text'}</button>
                  <button className="btn-ghost" onClick={() => api.downloadResume(r).catch(fail)}>Download</button>
                  <button className="btn-ghost hover:border-danger hover:text-danger"
                    onClick={() => confirmDelete(r) && act(() => api.deleteResume(r.id))}>Delete</button>
                </div>
              </div>
              {preview?.id === r.id && (
                <pre className="mt-4 max-h-80 overflow-auto whitespace-pre-wrap rounded-md bg-paper p-3 text-xs text-ink">
                  {preview.text || 'No text could be extracted from this file.'}
                </pre>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

const confirmDelete = (r: Resume) => window.confirm(`Delete "${r.label}"? This cannot be undone.`)
