import { useState } from 'react'
import { api, type Fault, type Scenario } from '../api'
import { usePoll, useOperator, ago } from '../hooks'
import StatusBadge from './StatusBadge'

const FAULT_HELP: Record<string, string> = {
  TRANSIENT: 'fails twice, then succeeds: shows bounded retries',
  PRIMARY_DOWN: 'primary agent is down: shows the fallback agent',
}

function ScenarioCard({ s, operator }: { s: Scenario; operator: string }) {
  const [inject, setInject] = useState(s.suggestedFaults.length > 0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function start() {
    setBusy(true)
    setError(null)
    try {
      const { id } = await api.startRun({
        title: s.title, requirement: s.requirement, scenario: s.id,
        faults: inject ? s.suggestedFaults : [], actor: operator,
      })
      window.location.hash = `#/runs/${id}`
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not start the run')
      setBusy(false)
    }
  }

  return (
    <article className="card scenario">
      <span className="badge">{s.kind}</span>
      <h3>{s.title}</h3>
      <p>{s.summary}</p>
      <blockquote>{s.requirement}</blockquote>
      <ul className="plain checks">{s.demonstrates.map(d => <li key={d}>{d}</li>)}</ul>
      {s.suggestedFaults.length > 0 && (
        <label className="check">
          <input type="checkbox" checked={inject} onChange={e => setInject(e.target.checked)} />
          <span>Inject fault: <code>{s.suggestedFaults[0]!.stage}</code> {FAULT_HELP[s.suggestedFaults[0]!.mode] ?? ''}</span>
        </label>
      )}
      <button className="primary" onClick={start} disabled={busy}>{busy ? 'Starting…' : 'Run this scenario'}</button>
      {error && <p className="error" role="alert">{error}</p>}
    </article>
  )
}

export default function Orchestrator() {
  const scenarios = usePoll(api.scenarios, 60_000, 'scenarios')
  const runs = usePoll(api.runs, 2500, 'runs')
  const [operator, setOperator] = useOperator()
  const [text, setText] = useState('')
  const [faultStage, setFaultStage] = useState('')
  const [faultMode, setFaultMode] = useState<Fault['mode']>('TRANSIENT')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function startCustom() {
    setBusy(true)
    setError(null)
    try {
      const faults: Fault[] = faultStage ? [{ stage: faultStage, mode: faultMode, count: 2 }] : []
      const { id } = await api.startRun({ requirement: text.trim(), faults, actor: operator })
      window.location.hash = `#/runs/${id}`
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not start the run')
      setBusy(false)
    }
  }

  return (
    <div className="stack">
      <section className="card">
        <h1>Agentic software delivery</h1>
        <p className="muted">
          Give the system a requirement. Agents decompose it, work through a dependency graph in parallel, and stop for you
          at high-impact steps. Every action is audited. Pick a scenario to see it work.
        </p>
        <label className="inline">Acting as
          <input value={operator} onChange={e => setOperator(e.target.value)} maxLength={40} />
        </label>
      </section>

      <section aria-label="Scenarios" className="grid3">
        {scenarios.error && <p className="error" role="alert">{scenarios.error}</p>}
        {scenarios.data?.map(s => <ScenarioCard key={s.id} s={s} operator={operator} />)}
      </section>

      <section className="card">
        <h2>Your own requirement</h2>
        <textarea rows={4} value={text} onChange={e => setText(e.target.value)} maxLength={8000}
                  placeholder="Describe a change in plain language, for example: Add QR codes to every short link and bulk creation from CSV." />
        <details>
          <summary>Fault injection (optional)</summary>
          <div className="row">
            <label>Stage
              <select value={faultStage} onChange={e => setFaultStage(e.target.value)}>
                <option value="">None</option>
                {['implementation', 'architecture', 'threat-model', 'unit-tests', 'documentation', 'api-design'].map(s => <option key={s}>{s}</option>)}
              </select>
            </label>
            <label>Behaviour
              <select value={faultMode} onChange={e => setFaultMode(e.target.value as Fault['mode'])}>
                <option value="TRANSIENT">Transient failures (retry)</option>
                <option value="PRIMARY_DOWN">Primary agent down (fallback)</option>
                <option value="FATAL">Permanent failure (rollback)</option>
                <option value="ROGUE_ACTION">Agent attempts a production deploy (safe-stop)</option>
              </select>
            </label>
          </div>
        </details>
        <button className="primary" onClick={startCustom} disabled={busy || text.trim().length < 10}>
          {busy ? 'Starting…' : 'Start run'}
        </button>
        {error && <p className="error" role="alert">{error}</p>}
      </section>

      <section className="card">
        <h2>Runs</h2>
        {runs.data && runs.data.length === 0 && <p className="muted">No runs yet.</p>}
        <ul className="plain list">
          {runs.data?.map(r => (
            <li key={r.id}>
              <div className="link-head">
                <a href={`#/runs/${r.id}`}>{r.title}</a>
                <StatusBadge status={r.status} />
                <span className="grow" />
                <span className="muted small">{r.scenario} · plan v{r.planVersion} · {ago(r.createdAt)}</span>
              </div>
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}
