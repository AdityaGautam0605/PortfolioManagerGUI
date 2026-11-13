import { useEffect, useRef, useState } from 'react';
import { ArrowRight, Sun, Moon, Search } from 'lucide-react';
import { marketOf, quoteCurrency, stockLibrary } from './stockLibrary';
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
  const [session, setSession] = useState(prices.length - 1);
  const [autoRotate, setAutoRotate] = useState(() => !window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  useEffect(() => {
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
    const syncPreference = () => setAutoRotate(!preference.matches);
    preference.addEventListener('change', syncPreference);
    return () => preference.removeEventListener('change', syncPreference);
  }, []);
  useEffect(() => {
    if (!autoRotate) return;
    const next = (selected + 1) % samples.length;
    const timer = window.setTimeout(() => {
      select(next);
      setSession(samples[next].prices.length - 1);
    }, 8000);
    return () => window.clearTimeout(timer);
  }, [selected, autoRotate]);
  const price = prices[session];
  const change = (price / prices[0] - 1) * 100;
  const min = Math.min(...prices), max = Math.max(...prices);
  const line = prices.map((p,i) => `${i ? 'L' : 'M'}${4+i*472/(prices.length-1)},${123-(p-min)*108/(max-min)}`).join(' ');
  return <section className="market-preview" aria-label="Interactive sample stock chart">
    <header><span><span className="preview-dot" />Stock explorer</span></header>
    <div className="preview-layout">
      <div className="preview-switch" aria-label="Choose an example stock">{samples.map((stock,i)=>
        <button key={stock.symbol} data-motion-part aria-label={stock.symbol} aria-pressed={i===selected} onClick={()=>{select(i); setSession(stock.prices.length-1);}}>
          <strong>{stock.symbol}</strong><span>{stock.name}</span><small>{stock.currency} {stock.prices.at(-1).toFixed(2)}</small>
        </button>)}</div>
      <div className="preview-body" data-motion-part><div className="preview-quote" aria-live={autoRotate ? 'off' : 'polite'}><div><small>{market} · {name}</small>
        <strong>{new Intl.NumberFormat('en-US',{minimumFractionDigits:2}).format(price)} <em>{currency}</em></strong></div>
        <span className={change<0?'down':''}>{change>0?'+':''}{change.toFixed(2)}%</span></div>
      <svg className={prices.at(-1)<prices[0]?'down':''} preserveAspectRatio="none" viewBox="0 0 480 140" role="img" aria-label={`${symbol} sample closing prices over 22 sessions`}>
        <defs><linearGradient id="sample-fill" x2="0" y2="1" gradientUnits="objectBoundingBox"><stop stopColor="currentColor" stopOpacity=".28"/><stop offset="100%" stopColor="currentColor" stopOpacity="0"/></linearGradient></defs>
        <path d={line+' L476 140 H4 Z'} fill="url(#sample-fill)"/>
        <path key={symbol} className="sample-line" pathLength="1" d={line} fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinejoin="round"/>
        <circle cx={4+session*472/(prices.length-1)} cy={123-(price-min)*108/(max-min)} r="4" fill="currentColor"/>
      </svg>
      <input className="session-slider" type="range" min="0" max={prices.length-1} value={session} aria-label="Inspect sample trading session" aria-valuetext={`Session ${session+1}, ${price} ${currency}`} onChange={e=>setSession(Number(e.target.value))}/>
      <div className="preview-caption"><span>Daily closing price</span><span>Session {session+1} of {prices.length}</span></div></div>
    </div>
  </section>;
}

const directory = stockLibrary([], [], false);
const markets = ['All', 'US', 'Bombay', 'Toronto'];

function Discover({ full = false }) {
  const [market, setMarket] = useState('All');
  const [query, setQuery] = useState('');
  const [expanded, setExpanded] = useState(false);
  const [selected, setSelected] = useState('');
  useEffect(() => {
    if (full) {
      document.getElementById('discover')?.focus({ preventScroll: true });
      window.scrollTo(0, 0);
    }
  }, [full]);
  const Heading = full ? 'h1' : 'h2';
  const matches = directory.filter(stock => (market === 'All' || marketOf(stock.symbol) === market)
    && [stock.name, stock.symbol, stock.sector].join(' ').toLowerCase().includes(query.trim().toLowerCase()));
  return <section className="discovery" aria-labelledby="discover">
    <div className="section-heading"><div><Heading id="discover" tabIndex={-1}>Explore the stock library</Heading>
      <p>Search by name, ticker or industry. Select a company to explore its listing.</p></div>
      <label className="company-search"><Search size={17}/><input type="search" aria-label="Search companies" placeholder="Company, ticker or sector" value={query} onChange={e=>{setQuery(e.target.value);setExpanded(false);}}/></label></div>
    <div className="directory-toolbar"><div className="market-filters">{markets.map(item=><button key={item} aria-pressed={item===market} onClick={()=>{setMarket(item);setExpanded(false);}}>{item}</button>)}</div>
      <small aria-live="polite">{matches.length} {matches.length===1?'company':'companies'}</small></div>
    <ul className="company-grid" data-motion-group={full ? undefined : true}>{(full || expanded?matches:matches.slice(0,6)).map(({name,symbol,sector})=><li key={symbol} data-motion-part>
      <button className="company-row" aria-expanded={selected===symbol} onClick={()=>setSelected(selected===symbol?'':symbol)}>
        <span><strong>{name}</strong><small>{symbol} · {marketOf(symbol)}</small></span><span>{sector} {selected===symbol?'−':'+'}</span>
      </button>
      {selected===symbol && <div className="listing-details">Quote currency · {quoteCurrency(symbol)}
        <a href={'https://finance.yahoo.com/lookup/?s='+encodeURIComponent(name)} target="_blank" rel="noreferrer">Research {name} on Yahoo Finance ↗</a>
      </div>}</li>)}</ul>
    {!matches.length && <p role="status">No companies match this search. Try another ticker or market.</p>}
    {!full && matches.length>6 && <button className="catalog-more" aria-expanded={expanded} onClick={()=>setExpanded(!expanded)}>{expanded?'Show fewer companies':`Show all ${matches.length} companies`}</button>}
  </section>;
}

function TradeExample() {
  const [ticker, setTicker] = useState('AAPL');
  const [shares, setShares] = useState(10);
  const {name,prices,currency} = samples.find(stock=>stock.symbol===ticker);
  const opening = prices[0], closing = prices.at(-1);
  const money = amount=>new Intl.NumberFormat('en-US',{style:'currency',currency,currencyDisplay:'code',maximumFractionDigits:2}).format(amount);
  return <section className="trade-example" aria-labelledby="walkthrough"><div><h2 id="walkthrough" tabIndex={-1}>Try your first holding</h2>
    <p>A stock represents ownership in a company. A share is one unit of that ownership. Choose a company and a share count to see how a price change affects a holding.</p>
    <div className="example-controls"><label>Sample stock<select value={ticker} onChange={e=>setTicker(e.target.value)}>{samples.map(s=><option key={s.symbol}>{s.symbol}</option>)}</select></label>
    <label>Shares <strong>{shares}</strong><input type="range" min="1" max="50" value={shares} onChange={e=>setShares(Number(e.target.value))}/></label></div></div>
    <dl className="trade-values"><div><dt>Purchase cost <small>{shares} × {money(opening)}</small></dt><dd>{money(shares*opening)}</dd></div>
    <div><dt>Position value <small>{shares} × {money(closing)}</small></dt><dd>{money(shares*closing)}</dd></div>
    <div><dt>Unrealized {closing>=opening?'gain':'loss'}</dt><dd className={closing>=opening?'gain':'loss'}>{money(shares*(closing-opening))}</dd></div></dl>
    <p className="holding-explanation">In this example, {name} moved from {money(opening)} to {money(closing)} per share.
      Your {shares === 1 ? 'share is' : `${shares} shares are`} worth {money(shares*Math.abs(closing-opening))} {closing>=opening?'more':'less'} than you paid.
      The {closing>=opening?'gain':'loss'} is unrealized because you still own the {shares===1?'share':'shares'}.</p></section>;
}

function ProductOverview() {
  const [weight,setWeight] = useState(50);
  const [years,setYears] = useState(5);
  return <section className="product-overview" aria-labelledby="overview-title">
    <h2 id="overview-title" className="gradient-heading"><span>What can Folio</span> <span>tell you?</span></h2>
    <div className="overview-insights" data-motion-group>
      <article data-motion-part>
        <div><h3>Return contribution</h3><strong className="insight-value gain">+$70</strong></div>
        <dl className="return-example">
          <div><dt>$1,000 × 5%</dt><dd>+$50</dd></div>
          <div><dt>$200 × 10%</dt><dd>+$20</dd></div>
        </dl>
      </article>
      <article data-motion-part>
        <div><h3>Portfolio impact</h3><output className="insight-value loss" htmlFor="sample-weight">−{(weight/10).toFixed(1)}%</output>
          <small className="insight-assumption">Single-stock shock · −10%</small></div>
        <div className="insight-control"><label htmlFor="sample-weight">Position weight <b>{weight}%</b></label>
          <input id="sample-weight" type="range" min="1" max="100" value={weight} onChange={e=>setWeight(Number(e.target.value))}/></div>
      </article>
      <article data-motion-part>
        <div><h3>Projected value</h3><output className="insight-value" htmlFor="sample-years">{(1000*1.05**years).toLocaleString('en-US',{style:'currency',currency:'USD'})}</output>
          <small className="insight-assumption">$1,000 initial · 5% annually</small></div>
        <div className="insight-control"><label htmlFor="sample-years">Time horizon <b>{years} {years===1?'year':'years'}</b></label>
          <input id="sample-years" type="range" min="1" max="10" value={years} onChange={e=>setYears(Number(e.target.value))}/></div>
      </article>
    </div>
  </section>;
}
export default function HomePage({ mark, theme, setTheme, libraryPage }) {
  const tour = useRef(null);
  useEffect(() => {
    if (libraryPage || !tour.current || !window.IntersectionObserver || !Element.prototype.animate) return;
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
    const animations = new Set();
    const observer = new IntersectionObserver(entries => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        if (entry.target.contains(document.activeElement)) continue;
        if (preference.matches) continue;
        const parts = entry.target.querySelectorAll('[data-motion-part]');
        parts.forEach((part, index) => {
          const animation = part.animate([
            { opacity: 0, transform: 'translateY(20px)' },
            { opacity: 1, transform: 'translateY(0)' }
          ], { duration: 950, delay: Math.min(index, 5) * 135, easing: 'cubic-bezier(.2,.75,.2,1)', fill: 'backwards' });
          animations.add(animation);
          animation.onfinish = () => animations.delete(animation);
        });
        const line = entry.target.querySelector('.sample-line');
        if (line) {
          const trace = line.animate([{ strokeDashoffset: 1 }, { strokeDashoffset: 0 }], {
            duration: 1450, delay: 480, easing: 'ease-out', fill: 'backwards'
          });
          animations.add(trace);
          trace.onfinish = () => animations.delete(trace);
        }
      }
    }, { threshold: 0 });
    tour.current.querySelectorAll('[data-motion-group]').forEach(group => observer.observe(group));
    function stopMotion() {
      for (const animation of animations) animation.cancel();
      animations.clear();
    }
    preference.addEventListener('change', stopMotion);
    return () => {
      observer.disconnect();
      preference.removeEventListener('change', stopMotion);
      stopMotion();
    };
  }, [libraryPage]);
  const jump = id => document.getElementById(id)?.focus();
  const dark = theme === 'dark';
  return <div className="home-page"><div className="home-wrap">
    <header className="home-header"><a href="#/" className="brand" aria-label="Folio home">{mark}folio.</a>
      <nav aria-label="Product navigation"><a href="#/library" aria-current={libraryPage ? "page" : undefined}>Stock library</a>{libraryPage ? <a href="#/">Overview</a> : <button onClick={()=>jump('walkthrough')}>Try a holding</button>}</nav>
      <button className="home-theme" aria-label={dark?'Switch to light mode':'Switch to dark mode'} title={dark?'Switch to light mode':'Switch to dark mode'} onClick={()=>setTheme(dark?'light':'dark')}>{dark?<Sun size={19}/>:<Moon size={19}/>}</button>
      <a className="home-signin" href="#/signin">Sign in <ArrowRight size={15}/></a></header>
    <main ref={tour}>{libraryPage ? <Discover full/> : <><section className="home-hero"><div className="home-intro">
      <h1 className="gradient-heading"><span>Know the stocks</span> <span>you own.</span></h1>
      <p>Folio is a portfolio tracker for stocks across the US, India and Canada. Keep a watchlist of companies you’re researching and see how the shares you own perform together.</p>
      <div className="hero-actions"><a className="home-cta" href="#/signup">Create an account <ArrowRight size={17}/></a><a className="explore-link" href="#/library">Explore stocks →</a></div>
    </div><div className="market-stage" data-motion-group><MarketPreview/></div></section><ProductOverview/><Discover/><TradeExample/></>}
    </main></div></div>;
}
