import { useEffect, useState } from 'react'
import { api, ApiError, type PendingPayment } from '../api'
import { confirmDialog } from '../dialog'

const msg = (e: unknown) => (e instanceof ApiError ? e.message : 'Cannot reach the server')

/** UPI payments waiting for a check: approve once the money shows in your bank/UPI app with this UTR and amount. */
export default function AdminPaymentsPage() {
  const [rows, setRows] = useState<PendingPayment[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const load = () => api.pendingPayments().then(setRows).catch(e => setError(msg(e)))
  useEffect(() => { load() }, [])

  async function act(p: PendingPayment, approve: boolean) {
    if (!approve && !(await confirmDialog(`Reject ${p.email}'s ₹${p.rupees} payment?`, { message: `UTR ${p.utr}. They can submit again.`, ok: 'Reject', danger: true }))) return
    setBusy(p.id); setError(null)
    try { await (approve ? api.approvePayment(p.id) : api.rejectPayment(p.id)); setRows(r => r && r.filter(x => x.id !== p.id)) }
    catch (e) { setError(msg(e)) } finally { setBusy(null) }
  }

  return (
    <div className="space-y-4">
      <div>
        <h1 className="font-display text-3xl font-bold">Payments to approve</h1>
        <p className="text-sm text-muted">Check your UPI/bank app for each UTR and amount (the note reads "JobPilot" + the ref). Approve only when the money has arrived; the plan starts at once.</p>
      </div>
      {error && <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-danger">{error}</p>}
      {rows === null ? <p className="text-sm text-muted">Loading…</p> : rows.length === 0 ? <p className="text-sm text-muted">Nothing waiting.</p> : (
        <ul className="divide-y divide-line rounded-lg border border-line bg-white">
          {rows.map(p => (
            <li key={p.id} className="flex flex-wrap items-center gap-3 p-4">
              <div className="min-w-0 flex-1 text-sm">
                <p className="font-semibold">₹{p.rupees} · {p.plan}</p>
                <p className="text-muted">{p.email} · UTR <b className="text-ink">{p.utr}</b> · ref {p.ref} · {new Date(p.createdAt).toLocaleString()}</p>
              </div>
              <button className="btn" disabled={busy === p.id} onClick={() => act(p, true)}>Approve</button>
              <button className="btn-ghost hover:border-danger hover:text-danger" disabled={busy === p.id} onClick={() => act(p, false)}>Reject</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
