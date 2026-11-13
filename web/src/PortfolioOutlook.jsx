import { useEffect, useRef, useState } from 'react';
import { request } from './api.js';
import { useCurrency } from './Currency.jsx';

export default function PortfolioOutlook({ total, demo }) {
  const { money } = useCurrency();
  const [years,setYears] = useState(3);
  const [annual,setAnnual] = useState(5);
  const [analysis,setAnalysis] = useState(null);
  const [error,setError] = useState('');
  const [loading,setLoading] = useState(false);
  const controller = useRef(null);
  useEffect(() => () => controller.current?.abort(),[]);
  async function analyze() {
    controller.current = new AbortController();
    const { signal } = controller.current;
    setLoading(true); setError('');
    try { setAnalysis(await request('/portfolio-analysis',{method:'POST',signal})); }
    catch (e) { if (!signal.aborted) setError(e.message); }
    finally { if (!signal.aborted) setLoading(false); }
  }
  if (!total) return null;
  return <section className="panel holdings-preview outlook">
    <h3>Portfolio outlook</h3>
    <p>Review allocation and model how your current holdings could change.</p>
    <button className="button subtle" disabled={loading} onClick={analyze}>{loading ? 'Analyzing…' : 'Analyze my portfolio'}</button>
    {!demo && <small>AI analysis shares the largest ticker, weights, holding count and overall return with Gemini when configured.</small>}
    {error && <p role="alert">{error}</p>}
    {analysis && <div aria-live="polite"><h4>{analysis.ai ? 'AI analysis' : 'Allocation analysis'}</h4><p>{analysis.text}</p>
      <h4>Suggested next move</h4><p>{analysis.next}</p>
      <small>{analysis.confidence} confidence in this allocation review. {analysis.reason} This rating describes the evidence for a review, not expected gains.</small>
    </div>}
    <h4>Projected value</h4>
    <label>Time horizon <select value={years} onChange={e=>setYears(Number(e.target.value))}>
      {[1,3,5,10].map(y=><option key={y} value={y}>{y} {y===1?'year':'years'}</option>)}
    </select></label>
    <label>Assumed annual return · {annual}% <input type="range" min="-20" max="20" value={annual} onChange={e=>setAnnual(Number(e.target.value))}/></label>
    <strong>{money(total * (1+annual/100)**years)}</strong>
    <small>Illustrative scenario from saved prices. Assumes annual compounding, constant exchange rates, and no contributions, withdrawals, fees or taxes.</small>
  </section>;
}
