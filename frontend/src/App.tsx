import { useEffect, useState } from 'react'
import Shortener from './components/Shortener'
import Orchestrator from './components/Orchestrator'
import RunView from './components/RunView'
import MetricsView from './components/MetricsView'

type Route = { page: 'shortener' | 'sdlc' | 'metrics' } | { page: 'run'; id: string }

function parse(hash: string): Route {
  const h = hash.replace(/^#\/?/, '')
  if (h.startsWith('runs/')) return { page: 'run', id: h.slice(5) }
  if (h === 'metrics') return { page: 'metrics' }
  if (h === 'shortener') return { page: 'shortener' }
  return { page: 'sdlc' }
}

type Theme = 'system' | 'light' | 'dark'

function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(() => {
    try {
      const t = localStorage.getItem('theme')
      return t === 'light' || t === 'dark' ? t : 'system'
    } catch { return 'system' }
  })
  useEffect(() => {
    const root = document.documentElement
    if (theme === 'system') delete root.dataset.theme
    else root.dataset.theme = theme
    try {
      if (theme === 'system') localStorage.removeItem('theme')
      else localStorage.setItem('theme', theme)
    } catch { /* storage unavailable */ }
  }, [theme])
  const next = () => setTheme(t => (t === 'system' ? 'light' : t === 'light' ? 'dark' : 'system'))
  return [theme, next]
}

export default function App() {
  const [route, setRoute] = useState<Route>(() => parse(window.location.hash))
  const [theme, cycleTheme] = useTheme()

  useEffect(() => {
    const on = () => setRoute(parse(window.location.hash))
    window.addEventListener('hashchange', on)
    return () => window.removeEventListener('hashchange', on)
  }, [])

  const active = route.page === 'run' ? 'sdlc' : route.page
  const link = (page: string, label: string) => (
    <a href={`#/${page}`} aria-current={active === page ? 'page' : undefined}>{label}</a>
  )

  return (
    <>
      <a className="skip" href="#main">Skip to content</a>
      <header className="top">
        <div className="brand"><span className="logo" aria-hidden="true" />Agentic SDLC Shortener</div>
        <nav aria-label="Primary">
          {link('sdlc', 'Agentic SDLC')}
          {link('shortener', 'URL shortener')}
          {link('metrics', 'Reliability')}
        </nav>
        <button className="ghost" onClick={cycleTheme} aria-label={`Theme: ${theme}. Switch theme`}>
          {theme === 'system' ? 'Auto' : theme === 'light' ? 'Light' : 'Dark'}
        </button>
      </header>
      <main id="main">
        {route.page === 'shortener' && <Shortener />}
        {route.page === 'sdlc' && <Orchestrator />}
        {route.page === 'run' && <RunView id={route.id} />}
        {route.page === 'metrics' && <MetricsView />}
      </main>
    </>
  )
}
