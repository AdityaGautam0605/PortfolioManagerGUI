import { useEffect, useRef, useState } from 'react';
import { Sparkles } from 'lucide-react';
import { request } from './api.js';
import { loadHistory } from './history.js';

export default function PerformanceSummary({ symbol, demo }) {
  const [open, setOpen] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const controller = useRef(null);
  useEffect(() => () => controller.current?.abort(), []);
  async function load() {
    controller.current?.abort();
    const abort = new AbortController();
    controller.current = abort;
    setError(''); setLoading(true);
    try {
      await loadHistory(symbol, demo, Boolean(error));
      if (abort.signal.aborted) return;
      const data = await request('/summary?symbol=' + encodeURIComponent(symbol), { method: 'POST', signal: abort.signal });
      if (data.pending) throw new Error('Price history is still loading. Try again shortly.');
      setResult(data);
    } catch (e) {
      if (!abort.signal.aborted) setError(e.message);
    } finally {
      if (!abort.signal.aborted) setLoading(false);
    }
  }
  return <div className="performance-summary">
    <button className="text-button" aria-expanded={open} onClick={() => {
      setOpen(!open);
      if (!open && !result && !loading) load();
    }}><Sparkles size={15} /> Performance summary</button>
    {open && <div aria-live="polite">
      {loading && <p>Reading daily closing prices…</p>}
      {error && <p>{error} <button className="text-button" onClick={load}>Retry</button></p>}
      {result && <>
        <small>{demo ? 'Sample prices' : result.ai ? 'AI summary' : 'Price summary'} · {result.from} – {result.through}{result.stale ? ' · Cached history' : ''}</small>
        <p>{result.text}</p>
        {result.fallback && <small>AI is unavailable. Showing calculated performance.</small>}
      </>}
    </div>}
  </div>;
}
