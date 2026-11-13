import { useState } from 'react';
import { ArrowRight, Bookmark, ChartNoAxesCombined, Globe2, Layers } from 'lucide-react';
import './home.css';

// Illustrative observations, independent of the visitor's account and live quote services.
const samples = [
  { symbol: 'AAPL', name: 'Apple', market: 'US', currency: 'USD', prices: [181,182,180,183,187,185,189,192,190,188,193,191,194,196,193,197,201,198,202,200,204,206] },
  { symbol: 'TCS', name: 'Tata Consultancy Services', market: 'Bombay', currency: 'INR', prices: [3520,3490,3515,3460,3485,3450,3490,3500,3455,3420,3440,3380,3400,3415,3390,3350,3370,3340,3360,3330,3350,3365] },
  { symbol: 'SHOP', name: 'Shopify', market: 'Toronto', currency: 'CAD', prices: [103,102,101,104,107,108,106,109,108,110,112,111,113,114,110,112,115,114,118,117,120,119] },
];

function MarketPreview() {
  const [selected, select] = useState(0);
  const { symbol, name, market, currency, prices } = samples[selected];
  const price = prices.at(-1);
  const change = (price / prices[0] - 1) * 100;
  const min = Math.min(...prices), max = Math.max(...prices);
  const line = prices.map((p,i) => `${i ? 'L' : 'M'}${4+i*472/(prices.length-1)},${123-(p-min)*108/(max-min)}`).join(' ');
  return <section className="market-preview" aria-label="Interactive sample stock chart">
    <header><span><span className="preview-dot" />Stock explorer</span><small>Sample data</small></header>
    <div className="preview-body">
      <div className="preview-switch" aria-label="Choose an example stock">{samples.map((s,i)=>
        <button key={s.symbol} aria-pressed={i===selected} onClick={()=>select(i)}>{s.symbol}</button>)}</div>
      <div className="preview-quote" aria-live="polite"><div><small>{market} · {name}</small>
        <strong>{new Intl.NumberFormat('en-US',{minimumFractionDigits:2}).format(price)} <em>{currency}</em></strong></div>
        <span className={change<0?'down':''}>{change>0?'+':''}{change.toFixed(2)}%</span></div>
      <svg className={change<0?'down':''} viewBox="0 0 480 140" role="img" aria-label={`${symbol} sample closing prices over 22 sessions`}>
        <defs><linearGradient id="sample-fill" x2="0" y2="1" gradientUnits="objectBoundingBox"><stop stopColor="currentColor" stopOpacity=".28"/><stop offset="100%" stopColor="currentColor" stopOpacity="0"/></linearGradient></defs>
        {[20,65,110].map(y=><path key={y} d={`M0 ${y}h480`} stroke="#ffffff20" strokeDasharray="3 5"/>)}
        <path d={line+' L476 140 H4 Z'} fill="url(#sample-fill)"/>
        <path d={line} fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinejoin="round"/>
      </svg>
      <div className="preview-caption"><span>Daily closing price</span><span>22 trading sessions</span></div>
    </div>
  </section>;
}

export default function HomePage({ mark }) {
  const jump = id => document.getElementById(id)?.focus();
  return <div className="home-page"><div className="home-wrap">
    <header className="home-header"><a href="#/" className="brand" aria-label="Folio home">{mark}folio.</a>
      <nav aria-label="Product navigation"><button onClick={()=>jump('home-tools')}>Features</button><button onClick={()=>jump('home-start')}>How it works</button></nav>
      <a className="home-signin" href="#/signin">Sign in <ArrowRight size={15}/></a></header>
    <main>
      <section className="home-hero"><div className="home-intro">
        <h1>Your portfolio,<br/><em>in perspective.</em></h1>
        <p>Track what you own, research your next holding, and see how a change in returns could shape your portfolio.</p>
        <a className="home-cta" href="#/signup">Create an account <ArrowRight size={17}/></a>
      </div><MarketPreview /></section>
      <div className="home-coverage">
        <div><Globe2/><span><strong>3 markets</strong>US, Bombay and Toronto</span></div>
        <div><Layers/><span><strong>6 display currencies</strong>USD, INR, EUR, GBP, CAD and JPY</span></div>
        <div><ChartNoAxesCombined/><span><strong>1–10 year outlook</strong>Adjustable return scenarios</span></div>
      </div>
      <section className="home-tools" aria-labelledby="home-tools"><h2 id="home-tools" tabIndex={-1}>Inside your workspace</h2>
        <div className="home-tools-grid">
          <article><Layers/><h3>Understand your holdings</h3><p>Record a purchase or sale and see how it changes your cost basis, unrealized return and allocation.</p></article>
          <article><Bookmark/><h3>Research before you buy</h3><p>Filter stocks by market, save a watchlist and read a performance summary based on recent closing prices.</p></article>
          <article><ChartNoAxesCombined/><h3>Explore your next move</h3><p>Review concentration with the portfolio analyzer, then change the time horizon and assumed return to compare projected values.</p></article>
        </div></section>
      <section className="home-start" aria-labelledby="home-start"><div><h2 id="home-start" tabIndex={-1}>Start with one holding.</h2>
        <p>Create your account, record a ticker and share count, then open Overview to see your position. Folio tracks trades you enter. Your brokerage handles the orders.</p></div>
        <a href="#/signup" className="home-cta">Get started <ArrowRight size={17}/></a></section>
    </main></div></div>;
}
