import { useEffect, useState } from 'react'
import { api, type Approval, type AuditEvent, type RunDetail, type Verification } from '../api'
import { fmtMs, useOperator, usePoll } from '../hooks'
import Graph from './Graph'
import StatusBadge from './StatusBadge'

type Tab = 'artifacts' | 'decisions' | 'audit' | 'requirement'

function ApprovalCard({ runId, a, operator, view }: { runId: string; a: Approval; operator: string; view: (n: string) => void }) {
  const [answers, setAnswers] = useState<Record<string, string>>(
    () => Object.fromEntries(a.questions.map(q => [q.slot, q.assumption])))
  const [comment, setComment] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function send(decision: 'APPROVE' | 'REJECT') {
    setBusy(true)
    setError(null)
    try {
      await api.decide(runId, a.id, { decision, by: operator, comment: comment.trim() || undefined, answers })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Decision failed')
      setBusy(false)
    }
  }

  const clarify = a.kind === 'CLARIFICATION'
  return (
    <section className="card attention" aria-label={clarify ? 'Clarification needed' : 'Approval needed'}>
      <h3>{clarify ? 'Clarification needed' : 'Approval needed'}: {a.stageId}</h3>
      <p>{a.summary}</p>
      {clarify ? (
        <>
          <p className="muted">The agent will not guess. Each answer is prefilled with its proposed default; edit any, then confirm.</p>
          {a.questions.map(q => (
            <label key={q.slot}>{q.text}
              <input value={answers[q.slot] ?? ''} onChange={e => setAnswers({ ...answers, [q.slot]: e.target.value })} />
            </label>
          ))}
          <div className="actions">
            <button className="primary" disabled={busy} onClick={() => send('APPROVE')}>Confirm answers and re-plan</button>
            <button disabled={busy} onClick={() => send('REJECT')}>Decline and stop</button>
          </div>
        </>
      ) : (
        <>
          <p className="muted">Review the artifact, then decide. A rejection needs a comment: the agent receives it as feedback.</p>
          <div className="actions">
            {a.summary.includes('Artifacts:') && a.summary.split('Artifacts:')[1]!.split(',').map(n => n.trim()).filter(Boolean)
              .map(n => <button key={n} onClick={() => view(n)}>View {n}</button>)}
          </div>
          <label>Comment
            <input value={comment} onChange={e => setComment(e.target.value)} placeholder="Required to reject" />
          </label>
          <div className="actions">
            <button className="primary" disabled={busy} onClick={() => send('APPROVE')}>Approve</button>
            <button disabled={busy || !comment.trim()} onClick={() => send('REJECT')}>Reject with feedback</button>
          </div>
        </>
      )}
      {error && <p className="error" role="alert">{error}</p>}
    </section>
  )
}

function ArtifactsTab({ d, runId, viewing, setViewing }: {
  d: RunDetail; runId: string; viewing: string | null; setViewing: (n: string) => void
}) {
  const [content, setContent] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const latest = new Map<string, number>()
  d.artifacts.forEach(a => { if ((latest.get(a.name) ?? 0) < a.version) latest.set(a.name, a.version) })
  const [name, versionStr] = viewing ? viewing.split('@') : [null, null]
  const version = versionStr ? Number(versionStr) : (name ? latest.get(name) : undefined)
  const sig = d.artifacts.filter(a => a.name === name).map(a => `${a.version}${a.status}`).join()

  useEffect(() => {
    if (!name) return
    api.artifact(runId, name, version).then(c => { setContent(c); setErr(null) }).catch(e => setErr(String(e.message ?? e)))
  }, [runId, name, version, sig])

  return (
    <div className="split">
      <ul className="plain pick" aria-label="Artifacts">
        {d.artifacts.length === 0 && <li className="muted">Nothing produced yet.</li>}
        {d.artifacts.map(a => (
          <li key={`${a.name}@${a.version}`}>
            <button className={name === a.name && version === a.version ? 'sel' : ''} onClick={() => setViewing(`${a.name}@${a.version}`)}>
              <span>{a.name} <small>v{a.version}</small></span>
              <small className={`state ${a.status.toLowerCase()}`}>{a.status.toLowerCase()}</small>
            </button>
          </li>
        ))}
      </ul>
      <div>
        {!name && <p className="muted">Choose an artifact to read it. Each one lists the input versions it was derived from.</p>}
        {name && (
          <>
            {d.artifacts.filter(a => a.name === name && a.version === version).map(a => (
              <p key={a.version} className="muted small">From stage <code>{a.stageId}</code> · derived from {a.derivedFrom.length ? a.derivedFrom.join(', ') : 'the requirement text'}</p>
            ))}
            {err ? <p className="error" role="alert">{err}</p> : <pre className="doc" tabIndex={0}>{content}</pre>}
          </>
        )}
      </div>
    </div>
  )
}

function AuditTab({ runId, live }: { runId: string; live: boolean }) {
  const events = usePoll<AuditEvent[]>(() => api.audit(runId), live ? 2000 : 15000, runId)
  const [ver, setVer] = useState<Verification | null>(null)
  return (
    <div>
      <div className="actions">
        <button onClick={async () => setVer(await api.verify(runId))}>Verify hash chain</button>
        {ver && <span role="status" className={`badge ${ver.valid ? 'good' : 'bad'}`}>{ver.valid ? `Intact · ${ver.events} events` : `Broken at #${ver.brokenAtSeq}`}</span>}
      </div>
      <div className="table-wrap">
        <table>
          <thead><tr><th>#</th><th>Time</th><th>Actor</th><th>Event</th><th>Stage</th><th>Details</th></tr></thead>
          <tbody>
            {events.data?.map(e => (
              <tr key={e.seq}>
                <td>{e.seq}</td>
                <td>{new Date(e.ts).toLocaleTimeString()}</td>
                <td>{e.actor}</td>
                <td><code>{e.type}</code></td>
                <td>{e.stageId ?? ''}</td>
                <td className="payload">{e.payload}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

export default function RunView({ id }: { id: string }) {
  const [operator, setOperator] = useOperator()
  const [live, setLive] = useState(true)
  const detail = usePoll(() => api.run(id), live ? 1200 : 6000, id)
  const [tab, setTab] = useState<Tab>('artifacts')
  const [viewing, setViewing] = useState<string | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [amend, setAmend] = useState<string | null>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const d = detail.data

  useEffect(() => { if (d) setLive(!['COMPLETED', 'FAILED_ROLLED_BACK', 'SAFE_STOPPED'].includes(d.run.status)) }, [d?.run.status])

  if (detail.error && !d) return <p className="error card" role="alert">{detail.error}</p>
  if (!d) return <p className="muted">Loading run…</p>

  const pending = d.approvals.filter(a => a.status === 'PENDING')
  const sel = d.stages.find(s => s.spec.id === selected)
  const terminal = !live

  async function act(fn: () => Promise<void>, ok: string) {
    setMsg(null)
    try { await fn(); setMsg(ok); detail.reload() } catch (e) { setMsg(e instanceof Error ? e.message : 'Failed') }
  }

  return (
    <div className="stack">
      <section className="card">
        <p className="small"><a href="#/sdlc">← All runs</a></p>
        <div className="link-head">
          <h1>{d.run.title}</h1>
          <StatusBadge status={d.run.status} />
        </div>
        <p className="muted">
          {d.run.scenario} · {d.run.mode.toLowerCase()} · plan v{d.run.planVersion} · requirement v{d.run.requirementVersion}
          {d.run.faults.length > 0 && <> · fault injected: <code>{d.run.faults.map(f => `${f.stage}:${f.mode}`).join(', ')}</code></>}
        </p>
        {d.run.stopReason && <p className="error" role="status">{d.run.stopReason}</p>}
        <div className="actions">
          <label className="inline">Acting as <input value={operator} onChange={e => setOperator(e.target.value)} maxLength={40} /></label>
          {!terminal && (
            <>
              <button onClick={() => act(() => api.safeStop(id, operator, 'Stopped from the UI', false), 'Run safe-stopped')}>Safe-stop</button>
              <button onClick={() => act(() => api.safeStop(id, operator, 'Stopped from the UI with rollback', true), 'Run safe-stopped and rolled back')}>Safe-stop and roll back</button>
            </>
          )}
        </div>
        {msg && <p role="status" className="muted">{msg}</p>}
      </section>

      {pending.map(a => <ApprovalCard key={a.id} runId={id} a={a} operator={operator}
                                      view={n => { setTab('artifacts'); setViewing(n) }} />)}

      <section className="card">
        <h2>Dependency graph</h2>
        <p className="muted small">Columns are waves. Nodes in the same column run in parallel; a node starts when all its inputs are done.</p>
        <Graph stages={d.stages} waves={d.waves} onSelect={setSelected} selected={selected} />
        {sel && (
          <div className="detail">
            <h3>{sel.spec.title} <StatusBadge status={sel.status} /></h3>
            <p className="muted small">Agent <code>{sel.activeAgent}</code>{sel.spec.fallbackAgent && <> (fallback <code>{sel.spec.fallbackAgent}</code>)</>}
              · depends on {sel.spec.dependsOn.join(', ') || 'nothing'} · produces {sel.spec.produces.join(', ')}
              · active {fmtMs(sel.activeMs)}{sel.spec.highImpact && ' · high impact: human approval required'}</p>
            {sel.lastError && <p className="error small">Last error: {sel.lastError}</p>}
          </div>
        )}
        <details>
          <summary>Stages as a table</summary>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Stage</th><th>Status</th><th>Agent</th><th>Retries</th><th>Active</th></tr></thead>
              <tbody>
                {d.stages.map(s => (
                  <tr key={s.spec.id}><td>{s.spec.id}</td><td><StatusBadge status={s.status} /></td><td>{s.activeAgent}</td>
                    <td>{s.retries}</td><td>{fmtMs(s.activeMs)}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      </section>

      <section className="card">
        <div role="tablist" className="tabs" aria-label="Run details">
          {(['artifacts', 'decisions', 'audit', 'requirement'] as Tab[]).map(t => (
            <button key={t} role="tab" aria-selected={tab === t} onClick={() => setTab(t)}>
              {t === 'decisions' ? 'Decision lineage' : t === 'audit' ? 'Audit trail' : t[0]!.toUpperCase() + t.slice(1)}
            </button>
          ))}
        </div>
        <div role="tabpanel">
          {tab === 'artifacts' && <ArtifactsTab d={d} runId={id} viewing={viewing} setViewing={setViewing} />}
          {tab === 'decisions' && (
            <ol className="plain timeline">
              {d.decisions.map(x => (
                <li key={x.id}>
                  <strong>{x.decision}</strong>
                  <div className="muted small">{x.actor}{x.stageId && <> · {x.stageId}</>} · {new Date(x.createdAt).toLocaleTimeString()}</div>
                  <div>{x.rationale}</div>
                  {x.inputs.length > 0 && <div className="muted small">based on {x.inputs.join(', ')}</div>}
                </li>
              ))}
            </ol>
          )}
          {tab === 'audit' && <AuditTab runId={id} live={live} />}
          {tab === 'requirement' && (
            <div className="stack">
              <pre className="doc" tabIndex={0}>{d.run.requirement}</pre>
              {!terminal && (
                <>
                  <p className="muted small">Change the requirement and the engine re-plans: it diffs the graph, resets only affected stages, and skips rebuilding anything whose output does not change.</p>
                  <textarea rows={4} value={amend ?? d.run.requirement} onChange={e => setAmend(e.target.value)} maxLength={8000} />
                  <div className="actions">
                    <button className="primary" disabled={amend === null || amend.trim() === d.run.requirement}
                            onClick={() => act(async () => { await api.amend(id, amend!, operator); setAmend(null) }, 'Requirement amended; re-planning')}>
                      Amend and re-plan
                    </button>
                  </div>
                </>
              )}
            </div>
          )}
        </div>
      </section>
    </div>
  )
}
