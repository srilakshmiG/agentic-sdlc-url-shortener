import { useCallback, useEffect, useRef, useState } from 'react'

export interface Polled<T> { data: T | null; error: string | null; loading: boolean; reload: () => void }

/** Loads on mount and then every `ms` while the tab is visible. Keeps the last good data on errors. */
export function usePoll<T>(fn: () => Promise<T>, ms: number, key: unknown): Polled<T> {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const fnRef = useRef(fn)
  fnRef.current = fn

  const load = useCallback(async () => {
    try {
      setData(await fnRef.current())
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Request failed')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    setLoading(true)
    setData(null)
    void load()
    const id = window.setInterval(() => { if (!document.hidden) void load() }, ms)
    return () => window.clearInterval(id)
  }, [key, ms, load])

  return { data, error, loading, reload: () => void load() }
}

export function useOperator(): [string, (v: string) => void] {
  const [name, setName] = useState(() => {
    try { return localStorage.getItem('operator') ?? 'reviewer' } catch { return 'reviewer' }
  })
  const set = (v: string) => {
    setName(v)
    try { localStorage.setItem('operator', v) } catch { /* storage unavailable */ }
  }
  return [name, set]
}

export function fmtMs(ms: number | null | undefined): string {
  if (ms == null) return 'n/a'
  if (ms < 1000) return `${Math.round(ms)} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`
  return `${(ms / 60_000).toFixed(1)} min`
}

export function ago(iso: string): string {
  const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000))
  if (s < 60) return `${s}s ago`
  if (s < 3600) return `${Math.floor(s / 60)}m ago`
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`
  return `${Math.floor(s / 86400)}d ago`
}
