import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError, type Insights } from '../api'

const msg = (e: unknown) => (e instanceof ApiError ? e.message : 'Cannot reach the server')

export default function InsightsPage() {
  const [d, setD] = useState<Insights | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api.insights().then(setD).catch(e => setError(msg(e))) }, [])

  if (error) return <p className="text-danger">{error}</p>
  if (!d) return <p className="text-muted">Loading…</p>

  const f = d.funnel
  const stages: [string, number][] = [
    ['Applied', f.applied], ['Screening', f.screening], ['Interview', f.interview], ['Offer', f.offer],
  ]
  const maxStage = Math.max(1, ...stages.map(s => s[1]))
  const maxGap = Math.max(1, ...d.skillGaps.map(g => g.jobs))
  const nothingYet = f.submitted === 0 && d.skillGaps.length === 0

  return (
    <div className="space-y-6">
      <div>
        <h1 className="font-display text-3xl font-bold">Insights</h1>
        <p className="mt-1 max-w-xl text-sm text-muted">How your search is actually going — and what to learn next to land the roles you're seeing.</p>
      </div>

      {nothingYet ? (
        <div className="card text-center text-sm text-muted">
          Nothing to show yet. Apply to a few jobs from <Link to="/jobs" className="underline">Jobs</Link> and track their outcomes; your funnel and skill gaps appear here.
        </div>
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-3">
            <Stat label="Submitted" value={f.submitted} sub={`${f.submittedThisWeek} this week`} />
            <Stat label="Reply rate" value={f.replyRatePct == null ? '—' : `${f.replyRatePct}%`} sub={f.replyRatePct == null ? 'no replies yet' : 'of submitted applications'} />
            <Stat label="Avg. reply time" value={f.avgDaysToReply == null ? '—' : `${f.avgDaysToReply}d`} sub={f.avgDaysToReply == null ? 'no replies yet' : 'from applying to first response'} />
          </div>

          <section className="card space-y-3">
            <h2 className="font-display text-lg font-bold">Your funnel</h2>
            <div className="space-y-2">
              {stages.map(([name, n]) => (
                <div key={name} className="flex items-center gap-3">
                  <span className="w-20 shrink-0 text-sm text-muted">{name}</span>
                  <div className="h-6 flex-1 overflow-hidden rounded bg-paper">
                    <div className="flex h-full items-center justify-end rounded bg-signal px-2 text-xs font-semibold text-white transition-all"
                      style={{ width: `${Math.max(n ? 8 : 0, (n / maxStage) * 100)}%` }}>{n > 0 && n}</div>
                  </div>
                </div>
              ))}
            </div>
            {(f.offer > 0 || f.accepted > 0 || f.rejected > 0) && (
              <p className="text-xs text-muted">
                {f.offer > 0 && `${f.offer} offer${f.offer === 1 ? '' : 's'}. `}
                {f.accepted > 0 && `${f.accepted} accepted. `}
                {f.rejected > 0 && `${f.rejected} rejected. `}
              </p>
            )}
          </section>

          <section className="card space-y-3">
            <div>
              <h2 className="font-display text-lg font-bold">Skills to learn next</h2>
              <p className="text-xs text-muted">Most asked for across your {d.jobsConsidered} open job{d.jobsConsidered === 1 ? '' : 's'}, and not yet on your profile. Add them if you have them, or learn them to match more roles.</p>
            </div>
            {d.skillGaps.length === 0 ? (
              <p className="text-sm text-muted">No gaps — your profile covers the skills your jobs ask for. 🎯</p>
            ) : (
              <div className="space-y-2">
                {d.skillGaps.map(g => (
                  <div key={g.skill} className="flex items-center gap-3">
                    <span className="w-32 shrink-0 truncate text-sm font-medium" title={g.skill}>{g.skill}</span>
                    <div className="h-5 flex-1 overflow-hidden rounded bg-paper">
                      <div className="h-full rounded bg-violet-400" style={{ width: `${(g.jobs / maxGap) * 100}%` }} />
                    </div>
                    <span className="w-24 shrink-0 text-right text-xs text-muted">{g.jobs} job{g.jobs === 1 ? '' : 's'}</span>
                  </div>
                ))}
              </div>
            )}
          </section>
        </>
      )}
    </div>
  )
}

function Stat({ label, value, sub }: { label: string; value: string | number; sub: string }) {
  return (
    <div className="card">
      <p className="text-xs uppercase tracking-wide text-muted">{label}</p>
      <p className="mt-1 font-display text-3xl font-bold">{value}</p>
      <p className="text-xs text-muted">{sub}</p>
    </div>
  )
}
