import type { Stage } from '../api'

const W = 188
const H = 46
const GX = 56
const GY = 18

/** Layered dependency graph. Columns are execution waves: nodes in one column can run in parallel. */
export default function Graph({ stages, waves, onSelect, selected }: {
  stages: Stage[]; waves: string[][]; onSelect: (id: string) => void; selected: string | null
}) {
  const byId = new Map(stages.map(s => [s.spec.id, s]))
  const pos = new Map<string, { x: number; y: number }>()
  waves.forEach((wave, c) => wave.forEach((id, r) => pos.set(id, { x: 12 + c * (W + GX), y: 12 + r * (H + GY) })))
  const width = 24 + waves.length * (W + GX) - GX
  const height = 24 + Math.max(...waves.map(w => w.length), 1) * (H + GY) - GY

  return (
    <div className="graph-scroll">
      <svg width={width} height={height} role="group" aria-label="Dependency graph. A table with the same information follows.">
        <defs>
          <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">
            <path d="M0 0L10 5L0 10z" className="arrow" />
          </marker>
        </defs>
        {stages.flatMap(s => s.spec.dependsOn.map(d => {
          const a = pos.get(d)
          const b = pos.get(s.spec.id)
          if (!a || !b) return null
          const x1 = a.x + W, y1 = a.y + H / 2, x2 = b.x, y2 = b.y + H / 2
          const mid = (x1 + x2) / 2
          return <path key={`${d}-${s.spec.id}`} d={`M${x1} ${y1} C${mid} ${y1} ${mid} ${y2} ${x2 - 2} ${y2}`}
                       className="edge" markerEnd="url(#arrow)" />
        }))}
        {[...pos.entries()].map(([id, p]) => {
          const s = byId.get(id)
          if (!s) return null
          const st = s.status.toLowerCase()
          return (
            <g key={id} transform={`translate(${p.x} ${p.y})`} className={`node ${st} ${selected === id ? 'sel' : ''}`}
               tabIndex={0} role="button" aria-label={`${s.spec.title}: ${s.status}`}
               onClick={() => onSelect(id)} onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(id) } }}>
              <rect width={W} height={H} rx={10} />
              <text x={12} y={19} className="n-title">{s.spec.title.length > 24 ? s.spec.title.slice(0, 23) + '…' : s.spec.title}</text>
              <text x={12} y={35} className="n-sub">
                {s.status === 'RUNNING' ? 'running…' : s.status === 'AWAITING_APPROVAL' ? 'needs human' : s.status.toLowerCase().replace('_', ' ')}
                {s.retries > 0 ? ` · ${s.retries} retr${s.retries === 1 ? 'y' : 'ies'}` : ''}
                {s.spec.highImpact ? ' · gated' : ''}
              </text>
            </g>
          )
        })}
      </svg>
    </div>
  )
}
