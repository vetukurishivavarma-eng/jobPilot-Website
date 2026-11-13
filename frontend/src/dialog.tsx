// App-styled replacement for window.confirm (same look as the phone app's dialog). <DialogHost/> is mounted in main.tsx.
import { useEffect, useState } from 'react'

type Ask = { title: string; message?: string; ok: string; danger: boolean; resolve: (yes: boolean) => void }
let show: ((a: Ask) => void) | null = null

export function confirmDialog(title: string, opts: { message?: string; ok?: string; danger?: boolean } = {}): Promise<boolean> {
  return new Promise(resolve => {
    if (!show) return resolve(window.confirm(opts.message ? `${title}\n\n${opts.message}` : title))
    show({ title, message: opts.message, ok: opts.ok ?? 'OK', danger: !!opts.danger, resolve })
  })
}

export function DialogHost() {
  const [a, setA] = useState<Ask | null>(null)
  useEffect(() => { show = setA; return () => { show = null } }, [])
  useEffect(() => {
    if (!a) return
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') done(false) }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  })
  if (!a) return null
  function done(yes: boolean) { a!.resolve(yes); setA(null) }
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-[#0a0814]/60 p-6" onClick={() => done(false)}>
      <div role="dialog" aria-modal="true" aria-labelledby="dlg-title" onClick={e => e.stopPropagation()}
        className="w-full max-w-sm animate-[pop_.18s_ease-out] space-y-3 rounded-2xl border border-line bg-white p-5 shadow-2xl">
        <div className="flex gap-1.5"><span className="h-1 w-6 rounded bg-signal" /><span className="h-1 w-2.5 rounded bg-pink-500" /></div>
        <h2 id="dlg-title" className="font-display text-lg font-bold">{a.title}</h2>
        {a.message && <p className="text-sm text-muted">{a.message}</p>}
        <div className="flex justify-end gap-2 pt-1">
          <button className="btn-ghost" onClick={() => done(false)}>Cancel</button>
          <button autoFocus className={a.danger ? 'rounded-md bg-red-600 px-4 py-2 text-sm font-semibold text-white hover:bg-red-700' : 'btn'} onClick={() => done(true)}>{a.ok}</button>
        </div>
      </div>
    </div>
  )
}
