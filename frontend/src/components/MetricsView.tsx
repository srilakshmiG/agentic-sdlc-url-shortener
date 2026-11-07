import { api } from '../api'
import { fmtMs, usePoll } from '../hooks'

function Tile({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="tile">
      <div className="tile-value">{value}</div>
      <div className="tile-label">{label}</div>
      {hint && <div className="muted small">{hint}</div>}
    </div>
  )
}

export default function MetricsView() {
  const { data: m, error } = usePoll(api.metrics, 4000, 'metrics')
  if (error && !m) return <p className="error card" role="alert">{error}</p>
  if (!m) return <p className="muted">Loading metrics…</p>
  const pct = (x: number) => `${Math.round(x * 100)}%`
  const max = Math.max(1, ...m.stageTimings.map(t => t.avgActiveMs))
  return (
    <div className="stack">
      <section className="card">
        <h1>Orchestration reliability</h1>
        <p className="muted">Computed from the audit trail and stage records in PostgreSQL, so numbers survive restarts.</p>
        <div className="tiles">
          <Tile label="Run success rate" value={m.runsTotal ? pct(m.successRate) : 'n/a'} hint="completed / finished runs" />
          <Tile label="Runs" value={String(m.runsTotal)} hint={Object.entries(m.runsByStatus).map(([k, v]) => `${v} ${k.toLowerCase().replace(/_/g, ' ')}`).join(', ')} />
          <Tile label="Retries" value={String(m.retries)} hint={`${m.fallbacks} fallbacks · ${m.stageAttempts} stage starts`} />
          <Tile label="Rollbacks" value={String(m.rollbacks)} hint={`${pct(m.rollbackRate)} of runs`} />
          <Tile label="MTTR" value={fmtMs(m.mttrMs)} hint={`${m.recoveredStages} stages recovered after a failure`} />
          <Tile label="End-to-end latency" value={fmtMs(m.e2eAvgMs)} hint={`p95 ${fmtMs(m.e2eP95Ms)} · includes human wait`} />
          <Tile label="Agent work per run" value={fmtMs(m.agentActiveAvgMs)} hint={`human wait ${fmtMs(m.humanWaitAvgMs)}`} />
          <Tile label="Re-plans" value={String(m.replans)} hint={`${m.safeStops} safe-stops · ${m.policyViolations} policy violations`} />
          <Tile label="Human decisions" value={String(m.humanApprovals + m.humanRejections)} hint={`${m.humanApprovals} approved · ${m.humanRejections} rejected`} />
        </div>
      </section>
      <section className="card">
        <h2>Average agent time per stage</h2>
        {m.stageTimings.length === 0 && <p className="muted">No completed stages yet.</p>}
        <ul className="plain hbars">
          {m.stageTimings.map(t => (
            <li key={t.stage}>
              <span className="hb-label">{t.stage}</span>
              <span className="hb-track"><span className="hb-fill" style={{ width: `${(t.avgActiveMs / max) * 100}%` }} /></span>
              <span className="hb-val">{fmtMs(t.avgActiveMs)}</span>
            </li>
          ))}
        </ul>
        <p className="muted small">{m.definitions}</p>
      </section>
    </div>
  )
}
