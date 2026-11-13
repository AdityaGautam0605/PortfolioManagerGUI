import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowDownLeft,
  ArrowRight,
  ArrowUpRight,
  Bookmark,
  Check,
  ChevronLeft,
  ChevronDown,
  LogOut,
  ChevronRight,
  CircleHelp,
  Command,
  Compass,
  Download,
  Home,
  Layers,
  LoaderCircle,
  Moon,
  Plus,
  RefreshCw,
  Search,
  Settings2,
  ShieldCheck,
  Sparkles,
  Sun,
  Trash2,
  TrendingUp,
  TrendingDown,
  Minus,
  Wallet,
  X,
} from 'lucide-react';
import { announceAccountChange, go, percent, readStored, request, store, tone } from './api.js';
import PriceChart from './PriceChart.jsx';
import { stockLibrary, marketOf } from './stockLibrary.js';
import { CurrencyProvider, CurrencyPicker, useCurrency } from './Currency.jsx';
import PortfolioOutlook from './PortfolioOutlook.jsx';
import PerformanceSummary from './PerformanceSummary.jsx';
import Brand from './Brand.jsx';

import { trackSession } from './session.js';

const icons = { AAPL: 'A', NVDA: 'N', MSFT: 'M', GOOGL: 'G', AMZN: 'a', TSLA: 'T', META: '∞' };
function StockMark({ symbol, small = false }) {
  return (
    <span aria-hidden="true" className={`stock-mark ${small ? 'small' : ''} mark-${symbol}`}>
      {icons[symbol] || symbol?.slice(0, 2)}
    </span>
  );
}
function Empty({ icon: Icon = Layers, title, children, action }) {
  return (
    <div className="empty">
      <Icon size={30} />
      <h3>{title}</h3>
      <p>{children}</p>
      {action}
    </div>
  );
}
function Modal({ title, children, close, pending = false }) {
  const ref = useRef(null);
  useEffect(() => {
    ref.current.showModal();
  }, []);
  return (
    <dialog
      ref={ref}
      className="modal"
      aria-label={title}
      onCancel={(e) => {
        e.preventDefault();
        if (!pending) close();
      }}
      onClick={(e) => {
        if (e.target === e.currentTarget && !pending) close();
      }}
    >
      <div className="modal-inner">
        <div className="modal-heading">
          <h2>{title}</h2>
          <button
            className="icon-button"
            aria-label="Close dialog"
            disabled={pending}
            onClick={close}
          >
            <X size={20} />
          </button>
        </div>
        {children}
      </div>
    </dialog>
  );
}
function TradeModal({ initial, pending, submit, close, demo, failureMessage }) {
  const [side, setSide] = useState(initial.side || 'BUY');
  const [symbol, setSymbol] = useState(initial.symbol || '');
  const [quantity, setQuantity] = useState('');
  const [error, setError] = useState('');
  return (
    <Modal title="Record a trade" close={close} pending={pending}>
      <p className="modal-description">
        {demo
          ? 'Try a trade with sample holdings. Your real portfolio is untouched.'
          : 'Keep your holdings up to date. This records a trade; it does not place a brokerage order.'}
      </p>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          setError('');
          if (
            !Number.isSafeInteger(Number(quantity)) ||
            Number(quantity) <= 0 ||
            Number(quantity) > 2147483647
          ) {
            setError('Enter a positive whole number of shares.');
            return;
          }
          const saved = await submit({
            symbol: symbol.trim().toUpperCase(),
            quantity: Number(quantity),
            side,
          });
          if (saved) close();
          else
            setError(
              'Trade could not be confirmed. Check the status message, then reload holdings before retrying.',
            );
        }}
      >
        <div className="trade-tabs">
          {['BUY', 'SELL'].map((item) => (
            <button
              type="button"
              key={item}
              disabled={pending}
              aria-pressed={side === item}
              className={side === item ? 'active' : ''}
              onClick={() => setSide(item)}
            >
              {item === 'BUY' ? <ArrowDownLeft size={16} /> : <ArrowUpRight size={16} />}{' '}
              {item === 'BUY' ? 'Buy shares' : 'Sell shares'}
            </button>
          ))}
        </div>
        <label>
          Stock symbol
          <input
            autoFocus
            value={symbol}
            disabled={pending}
            maxLength={20}
            required
            pattern="[a-zA-Z0-9][a-zA-Z0-9.:\-]*"
            placeholder="AAPL, TSX:SHOP or BSE:TCS"
            onChange={(e) => setSymbol(e.target.value)}
          />
        </label>
        <label>
          Number of shares
          <input
            type="number"
            value={quantity}
            disabled={pending}
            min="1"
            step="1"
            max="2147483647"
            required
            placeholder="e.g. 10"
            onChange={(e) => setQuantity(e.target.value)}
          />
        </label>
        <div className="form-note">
          <ShieldCheck size={17} />
          {side === 'BUY'
            ? 'Recorded at the latest available quote.'
            : 'Your remaining cost basis is preserved.'}
        </div>
        {error && (
          <p role="alert" className="form-error">
            {failureMessage || error}
          </p>
        )}
        <button className="button primary full" type="submit" disabled={pending}>
          {pending ? <LoaderCircle className="spin" size={17} /> : <Plus size={17} />}{' '}
          {pending ? 'Saving trade…' : `Record ${side.toLowerCase()}`}
        </button>
      </form>
    </Modal>
  );
}

export default function Dashboard(props) {
  return <CurrencyProvider><Workspace {...props} /></CurrencyProvider>;
}

function Workspace({ initialSnapshot, theme, setTheme }) {
  const { money, signedMoney, rate, currency } = useCurrency();
  const accountMenu = useRef(null);
  useEffect(() => {
    const closeOutside = (e) => {
      if (accountMenu.current?.open && !accountMenu.current.contains(e.target)) accountMenu.current.open = false;
    };
    document.addEventListener('pointerdown', closeOutside);
    document.addEventListener('focusin', closeOutside);
    return () => {
      document.removeEventListener('pointerdown', closeOutside);
      document.removeEventListener('focusin', closeOutside);
    };
  }, []);
  const [route, setRoute] = useState(location.hash.slice(1) || '/');
  const [data, setData] = useState(initialSnapshot);
  useEffect(() => {
    if (data?.session) return trackSession(data.session);
  }, [data?.session]);
  const [loading, setLoading] = useState(false);
  const [pending, setPending] = useState(false);
  const busy = useRef(false);
  const [notice, setNotice] = useState(null);
  const [progress, setProgress] = useState(null);
  const [modal, setModal] = useState(null);

  const [watchlists, setWatchlists] = useState(() => {
    const value = readStored('folio-watchlists', {});
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  });
  const [query, setQuery] = useState('');
  const [libraryFilter, setLibraryFilter] = useState('all');
  const [market,setMarket] = useState('All markets');
  const [sort, setSort] = useState('value');
  const [chartSymbol, setChartSymbol] = useState('');
  const demo = data?.mode === 'demo';
  const mode = data?.account ? `hosted:${data.account.id}` : data?.mode || 'connected';
  const watched = Array.isArray(watchlists[mode])
    ? watchlists[mode]
    : demo
      ? ['AAPL', 'NVDA', 'TSLA', 'META']
      : [];
  const positions = data?.positions || [];
  const stocks = stockLibrary(data?.stocks || [], watched, demo);
  const stockBySymbol = useMemo(
    () => Object.fromEntries(stocks.map((s) => [s.symbol, s])),
    [stocks],
  );
  const total = positions.reduce((sum, p) => sum + p.quantity * p.price, 0);
  const invested = positions.reduce((sum, p) => sum + p.quantity * p.averageCost, 0);
  const profit = positions.reduce((sum, p) => sum + p.profitLoss, 0);
  const returns = invested ? (profit / invested) * 100 : 0;
  const byValue = [...positions].sort((a, b) => b.price * b.quantity - a.price * a.quantity);
  const largest = byValue[0];
  const weight = (p) => total > 0 ? 100 * p.price * p.quantity / total : 0;
  const ProfitIcon = profit > 0 ? TrendingUp : profit < 0 ? TrendingDown : Minus;
  const best = [...positions].sort((a, b) => b.returnPercent - a.returnPercent)[0];
  const worst = [...positions].sort((a, b) => a.returnPercent - b.returnPercent)[0];
  const currentSymbol = positions.some((p) => p.symbol === chartSymbol)
    ? chartSymbol
    : positions[0]?.symbol || data?.stocks?.[0]?.symbol;
  const detailSymbol = route.startsWith('/stock/') ? decodeURIComponent(route.slice(7)) : null;
  const pageTitle =
    route === '/'
      ? 'Overview'
      : route === '/holdings'
        ? 'Holdings'
        : route === '/library'
          ? 'Stock library'
          : route === '/watchlist'
            ? 'Watchlist'
            : route === '/settings'
              ? 'Settings'
              : detailSymbol || 'Page not found';

  useEffect(() => {
    const listener = () => {
      setRoute(location.hash.slice(1) || '/');
      setQuery('');
      window.scrollTo({ top: 0 });
    };
    window.addEventListener('hashchange', listener);
    return () => window.removeEventListener('hashchange', listener);
  }, []);


  useEffect(() => {
    const listener = (e) => {
      if (busy.current) return;
      if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
        e.preventDefault();
        setModal({ type: 'search' });
      }
    };
    window.addEventListener('keydown', listener);
    return () => window.removeEventListener('keydown', listener);
  }, []);

  function toggleWatch(symbol) {
    const next = watched.includes(symbol)
      ? watched.filter((s) => s !== symbol)
      : [...watched, symbol];
    const value = { ...watchlists, [mode]: next };
    setWatchlists(value);
    store('folio-watchlists', value);
  }
  async function reload(preceding) {
    setLoading(true);
    try {
      setData(await request('/snapshot'));
      setNotice(
        preceding || {
          type: 'success',
          message: 'Portfolio reloaded. Prices reflect the last saved quotes.',
        },
      );
    } catch (e) {
      setNotice({
        type: 'error',
        message: `${preceding?.message ? preceding.message + ' ' : ''}View could not reload. ${e.message}`,
        details: preceding?.details,
      });
    } finally {
      setLoading(false);
    }
  }
  async function mutate(operation) {
    if (busy.current) return false;
    busy.current = true;
    setPending(true);
    setNotice(null);
    try {
      const result = await operation();
      await reload(result);
      return true;
    } catch (e) {
      setNotice({
        type: 'error',
        message: `${e.message} Reload holdings before repeating an operation.`,
      });
      return false;
    } finally {
      busy.current = false;
      setPending(false);
      setProgress(null);
    }
  }
  async function refresh() {
    await mutate(async () => {
      setProgress({ completed: 0, total: 0 });
      const job = await request('/refresh', { method: 'POST' });
      while (true) {
        const state = await request(`/jobs/${job.id}`);
        setProgress(state);
        if (state.done) {
          if (state.error) throw new Error(state.error);
          const { total, updated, failures } = state.result;
          return {
            type: Object.keys(failures).length ? 'warning' : 'success',
            message:
              total === 0
                ? 'No saved stocks to refresh.'
                : `${demo ? 'Sample prices checked' : 'Prices updated'}: ${updated} of ${total}.${Object.keys(failures).length ? ' Some updates could not be confirmed.' : ''}`,
            details: Object.entries(failures).map(([symbol, reason]) => `${symbol}: ${reason}`),
          };
        }
        await new Promise((resolve) => setTimeout(resolve, 500));
      }
    });
  }
  const trade = (body) =>
    mutate(async () => {
      const result = await request('/trades', { method: 'POST', body });
      return { type: 'success', message: result.message };
    });
  const openStock = (symbol) => go(`/stock/${encodeURIComponent(symbol)}`);
  const openTrade = (symbol, side = 'BUY') => setModal({ type: 'trade', symbol, side });
  const disabled = pending || loading;
  const filteredStocks = stocks.filter((s) => {
    const held = positions.some((p) => p.symbol === s.symbol);
    return `${s.symbol} ${s.name} ${s.sector || ''}`.toLowerCase().includes(query.trim().toLowerCase())
      && (route !== '/library' || market === 'All markets' || marketOf(s.symbol) === market)
      && (route !== '/library' || libraryFilter === 'all' || (libraryFilter === 'held' ? held : !held));
  });
  const nav = [
    { path: '/', label: 'Overview', icon: Home },
    { path: '/holdings', label: 'Holdings', icon: Wallet },
    { path: '/library', label: 'Stock library', icon: Compass },
    { path: '/watchlist', label: 'Watchlist', icon: Bookmark },
    { path: '/settings', label: 'Settings', icon: Settings2 },
  ];

  function stockRow(stock, position, key) {
    return (
      <button
        key={key || stock.symbol}
        className="stock-row"
        onClick={() => openStock(stock.symbol)}
      >
        <StockMark symbol={stock.symbol} />
        <span className="stock-identity">
          <strong>{stock.symbol}</strong>
          <span>{stock.name}</span>
        </span>
        <span className="stock-numbers">
          <strong>{money(stock.price)}</strong>
          <span className={position ? tone(position.returnPercent) : 'muted'}>
            {position ? percent(position.returnPercent) + ' return' : 'Saved price'}
          </span>
        </span>
        <ChevronRight size={15} className="muted" />
      </button>
    );
  }
  function exportHoldings() {
    const rows = [
      ['Symbol', 'Shares', 'Average cost', 'Saved price', 'Market value', 'Unrealized P/L', 'Currency'],
      ...positions.map((p) => [
        p.symbol,
        p.quantity,
        p.averageCost*rate,
        p.price*rate,
        p.quantity * p.price*rate,
        p.profitLoss*rate,
        currency,
      ]),
    ];
    const blob = new Blob(
      [
        rows
          .map((row) => row.map((value) => `"${String(value).replaceAll('"', '""')}"`).join(','))
          .join('\r\n'),
      ],
      { type: 'text/csv' },
    );
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = 'folio-holdings.csv';
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
  }

  if (loading && !data) return <main className="auth-shell" aria-label="Opening Folio"><span className="brand"><Brand />folio.</span></main>;
  return (
    <div className="app-shell">
      <header className="app-header">
        <a className="brand" href="#/" aria-label="Folio overview">
          <Brand />
          <span>
            folio<span className="brand-dot">.</span>
          </span>
        </a>
        <div className="header-right">
          {(demo || !data) && (
            <span className={`connection-pill ${demo ? 'demo' : 'disconnected'}`}>
              <i />
              {demo ? 'Demo · sample data' : loading ? 'Connecting' : 'Unavailable'}
            </span>
          )}
          <button
            className="icon-button"
            title="Toggle color theme"
            aria-label="Toggle color theme"
            onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
          >
            {theme === 'dark' ? <Moon size={19} /> : <Sun size={19} />}
          </button>
          {data?.account ? (
            <details className="account-menu" ref={accountMenu} onKeyDown={e => {
              if (e.key === 'Escape' && e.currentTarget.open) {
                e.currentTarget.open = false;
                e.currentTarget.querySelector('summary').focus();
              }
            }}>
              <summary aria-label={`Account: ${data.account.username}`}>
                <span className="account-initial" aria-hidden="true">{data.account.username[0].toUpperCase()}</span>
                <span className="account-name">{data.account.username}</span>
                <ChevronDown size={14} aria-hidden="true" />
              </summary>
              <div className="account-popover">
                <small>Signed in as</small>
                <strong>{data.account.username}</strong>
                <button type="button" onClick={async () => {
                  accountMenu.current.open = false;
                  try {
                    await request('/auth/logout', { method: 'POST' });
                    announceAccountChange();
                    window.dispatchEvent(new Event('folio-auth-required'));
                  } catch (e) { setNotice({ type: 'error', message: e.message }); }
                }}><LogOut size={15} /> Sign out</button>
              </div>
            </details>
          ) : <span className="avatar" title="Personal portfolio">P</span>}
        </div>
      </header>
      <main className={route === '/holdings' ? 'holdings-page' : undefined}>
        <div className="page-heading">
          <div>
            {route !== '/' && <div className="breadcrumb">
              <span>Your workspace</span>
              <ChevronRight size={12} />
              {pageTitle}
            </div>}
            <h1>{route === '/' ? 'Portfolio overview' : pageTitle}</h1>
            <p>
              {route === '/'
                ? 'Holdings, allocation and performance at your last saved prices.'
                : route === '/holdings'
                  ? 'Every position. One clear picture.'
                  : route === '/library'
                    ? 'Find companies, follow stocks and explore their recent performance.'
                    : route === '/watchlist'
                      ? 'Keep your next move in sight.'
                      : detailSymbol
                        ? stockBySymbol[detailSymbol]?.name || 'Stock details'
                        : 'Make this space your own.'}
            </p>
          </div>
          <div className="heading-actions">
            <button className="button subtle refresh-button" disabled={disabled} onClick={refresh}>
              <RefreshCw size={15} className={progress ? 'spin' : ''} />
              {progress ? `${progress.completed}/${progress.total || '…'}` : 'Refresh prices'}
            </button>
            <button
              className="button primary"
              disabled={disabled || !data}
              onClick={() => openTrade('')}
            >
              <Plus size={16} /> Record trade
            </button>
          </div>
        </div>
        {notice && (
          <div
            className={`notice ${notice.type}`}
            role={notice.type === 'error' ? 'alert' : 'status'}
          >
            <div>
              {notice.type === 'success' ? <Check size={17} /> : <CircleHelp size={17} />}
              <span>{notice.message}</span>
            </div>
            {notice.details?.length > 0 && (
              <details>
                <summary>View failed updates</summary>
                {notice.details.map((line, i) => (
                  <p key={i}>{line}</p>
                ))}
              </details>
            )}
            <button
              className="icon-button"
              aria-label="Dismiss message"
              onClick={() => setNotice(null)}
            >
              <X size={16} />
            </button>
          </div>
        )}
        {!data ? (
          loading ? (
            <div className="loading-workspace">
              <LoaderCircle className="spin" />
              <h2>Opening your workspace</h2>
              <p>Loading saved holdings and prices…</p>
            </div>
          ) : (
            <Empty
              title="Your portfolio is out of reach"
              icon={Wallet}
              action={
                <button className="button primary" onClick={() => reload()}>
                  Try loading again
                </button>
              }
            >
              Check that this website is connected to the portfolio server and database. Your saved holdings have not
              been replaced.
            </Empty>
          )
        ) : (
          <>
            {route === '/' && (
              <div className="overview-grid">
                <div className="overview-left"><section className="panel portfolio-panel">
                  <div className="panel-topline">
                    <span className="eyebrow">Portfolio value</span>
                    <span className="tiny-tag">
                      <Wallet size={12} /> {positions.length} {positions.length === 1 ? 'holding' : 'holdings'}
                    </span>
                  </div>
                  <div className="portfolio-value">{money(total)}</div>
                  <div className={`portfolio-return ${tone(profit)}`}>
                    <ProfitIcon size={15} />
                    {signedMoney(profit)} <span>({percent(returns)})</span>
                    <span className="muted"> on current holdings</span>
                  </div>
                  <div className="portfolio-stats">
                    <div>
                      <span>Cost basis</span>
                      <strong>{money(invested)}</strong>
                    </div>
                    <div>
                      <span>Unrealized return</span>
                      <strong className={tone(returns)}>{percent(returns)}</strong>
                    </div>
                    <div>
                      <span>Currency</span>
                      <CurrencyPicker />
                    </div>
                  </div>
                  <div className="chart-selector">
                    <label htmlFor="chart-stock">Explore a holding</label>
                    <select
                      id="chart-stock"
                      value={currentSymbol || ''}
                      onChange={(e) => setChartSymbol(e.target.value)}
                    >
                      {(positions.length ? positions : data.stocks).map((p) => (
                        <option key={p.symbol}>{p.symbol}</option>
                      ))}
                    </select>
                  </div>
                  {currentSymbol ? (
                    <PriceChart symbol={currentSymbol} demo={demo} />
                  ) : (
                    <Empty title="Your story starts here">
                      Record your first trade to start tracking your holdings.
                    </Empty>
                  )}
                </section>
                  <PortfolioOutlook key={JSON.stringify(positions)} total={total} demo={demo} />
                </div>
                <div className="overview-right">
                  <section className="brief-panel">
                    <div className="panel-topline">
                      <span className="tiny-tag">
                        <Sparkles size={13} /> Portfolio brief
                      </span>
                      <span className="muted">From your saved holdings</span>
                    </div>
                    <h2>
                      {positions.length ? (
                        <>
                          {largest.symbol} <span className="soft">{positions.length === 1 ? 'is your only position.' : 'is your largest position.'}</span>
                        </>
                      ) : (
                        <>
                          Your first holding starts here.
                        </>
                      )}
                    </h2>
                    <p>
                      {positions.length ? (
                        <>
                          At saved prices, your portfolio is {Math.abs(profit) < 0.005 ? 'at its cost basis' :
                            <><strong className={tone(profit)}>{money(Math.abs(profit))}</strong> {profit > 0 ? 'above' : 'below'} its cost basis</>}.
                        </>
                      ) : (
                        'Build your portfolio one position at a time. Record a trade to see your allocation and performance here.'
                      )}
                    </p>
                    {largest && <div className="brief-allocation">
                      <div><span>Portfolio weight · {largest.symbol}</span><strong>{weight(largest).toFixed(1)}%</strong></div>
                      <div className="brief-meter"><i style={{ width: `${weight(largest)}%` }} /></div>
                    </div>}
                    <button className="text-button" onClick={() => go('/holdings')}>
                      Explore your portfolio <ArrowRight size={15} />
                    </button>
                  </section>

                  <section className="panel holdings-preview">
                    <div className="section-heading">
                      <h3>Your holdings</h3>
                      <button className="text-button" onClick={() => go('/holdings')}>
                        View all <ArrowUpRight size={14} />
                      </button>
                    </div>
                    {positions.length ? (
                      positions
                        .slice(0, 5)
                        .map((p) =>
                          stockRow(stockBySymbol[p.symbol] || { ...p, name: p.symbol }, p),
                        )
                    ) : (
                      <p className="muted">Your holdings will appear here.</p>
                    )}
                  </section>
                  <section className="panel allocation-panel">
                    <div className="section-heading">
                      <h3>Where you're invested</h3>
                      <button className="text-button" onClick={() => go('/holdings')}>
                        View all <ArrowUpRight size={14} />
                      </button>
                    </div>
                    <div className="allocation-track" aria-label="Portfolio allocation">
                      {byValue.map((p, i) => (
                        <span
                          key={p.symbol}
                          style={{
                            width: `${total ? ((p.quantity * p.price) / total) * 100 : 0}%`,
                            background: `var(--allocation-${i % 5})`,
                          }}
                          title={`${p.symbol}: ${weight(p).toFixed(1)}%`}
                        />
                      ))}
                    </div>
                    {byValue.slice(0, 5).map((p, i) => (
                      <button
                        className="allocation-row"
                        key={p.symbol}
                        onClick={() => openStock(p.symbol)}
                      >
                        <span
                          className="allocation-dot"
                          style={{ background: `var(--allocation-${i % 5})` }}
                        />
                        <span>{stockBySymbol[p.symbol]?.name || p.symbol}</span>
                        <strong>
                          {total ? (((p.quantity * p.price) / total) * 100).toFixed(1) : '0.0'}%
                        </strong>
                        <div className="bar-meter">
                          <span
                            style={{
                              width: `${total ? ((p.quantity * p.price) / total) * 100 : 0}%`,
                            }}
                          />
                        </div>
                      </button>
                    ))}
                  </section>
                  {positions.length > 1 && <section className="insight-grid">
                    <button
                      className="insight-card"
                      disabled={!best}
                      onClick={() => best && openStock(best.symbol)}
                    >
                      <span className="eyebrow">Strongest return</span>
                      <div>
                        {best ? (
                          <>
                            <StockMark symbol={best.symbol} small />
                            <strong>{best.symbol}</strong>
                            <ArrowUpRight size={16} />
                          </>
                        ) : (
                          '—'
                        )}
                      </div>
                      <span className={`insight-number ${tone(best?.returnPercent)}`}>
                        {best ? percent(best.returnPercent) : '—'}
                      </span>
                      <span className="muted">Since your purchase</span>
                    </button>
                    <button
                      className="insight-card"
                      disabled={!worst}
                      onClick={() => worst && openStock(worst.symbol)}
                    >
                      <span className="eyebrow">Lowest return</span>
                      <div>
                        {worst ? (
                          <>
                            <StockMark symbol={worst.symbol} small />
                            <strong>{worst.symbol}</strong>
                            <ArrowUpRight size={16} />
                          </>
                        ) : (
                          '—'
                        )}
                      </div>
                      <span className={`insight-number ${tone(worst?.returnPercent)}`}>
                        {worst ? percent(worst.returnPercent) : '—'}
                      </span>
                      <span className="muted">Since your purchase</span>
                    </button>
                  </section>}
                  <div className="quiet-note">
                    <ShieldCheck size={16} />
                    <p>
                      {demo
                        ? 'A safe place to explore. This workspace uses sample prices and history.'
                        : data.account ? 'Prices reflect the last saved quotes.'
                        : 'Your portfolio stays local. Prices reflect the last saved quotes.'}
                    </p>
                  </div>
                </div>
              </div>
            )}
            {route === '/holdings' && (
              <>
                <div className="stat-grid">
                  <Stat title="Portfolio value" value={money(total)} />
                  <Stat title="Cost basis" value={money(invested)} />
                  <Stat
                    title="Unrealized profit / loss"
                    value={signedMoney(profit)}
                    detail={percent(returns)}
                    color={tone(profit)}
                  />
                </div>
                <section className="panel table-panel">
                  <div className="table-toolbar">
                    <h2>
                      All positions <span className="count">{positions.length}</span>
                    </h2>
                    <div>
                      <select
                        aria-label="Sort holdings"
                        value={sort}
                        onChange={(e) => setSort(e.target.value)}
                      >
                        <option value="value">By market value</option>
                        <option value="return">By return</option>
                        <option value="symbol">By symbol</option>
                      </select>
                      <button
                        className="button subtle"
                        onClick={exportHoldings}
                        disabled={!positions.length}
                      >
                        <Download size={15} /> Export
                      </button>
                    </div>
                  </div>
                  {positions.length ? (
                    <div className="table-scroll">
                      <table>
                        <thead>
                          <tr>
                            <th>Asset</th>
                            <th>Shares</th>
                            <th>Average cost</th>
                            <th>Saved price</th>
                            <th>Market value</th>
                            <th>Unrealized P/L</th>
                            <th>Return</th>
                            <th>
                              <span className="sr-only">Actions</span>
                            </th>
                          </tr>
                        </thead>
                        <tbody>
                          {[...positions]
                            .sort((a, b) =>
                              sort === 'symbol'
                                ? a.symbol.localeCompare(b.symbol)
                                : sort === 'return'
                                  ? b.returnPercent - a.returnPercent
                                  : b.quantity * b.price - a.quantity * a.price,
                            )
                            .map((p) => (
                              <tr key={p.symbol}>
                                <td>
                                  <button
                                    className="asset-button"
                                    onClick={() => openStock(p.symbol)}
                                  >
                                    <StockMark symbol={p.symbol} />
                                    <span>
                                      <strong>{p.symbol}</strong>
                                      <small>{stockBySymbol[p.symbol]?.name}</small>
                                    </span>
                                  </button>
                                </td>
                                <td>{p.quantity}</td>
                                <td>{money(p.averageCost)}</td>
                                <td>{money(p.price)}</td>
                                <td>{money(p.quantity * p.price)}</td>
                                <td className={tone(p.profitLoss)}>{signedMoney(p.profitLoss)}</td>
                                <td className={tone(p.returnPercent)}>
                                  {percent(p.returnPercent)}
                                </td>
                                <td>
                                  <button
                                    className="icon-button"
                                    aria-label={`View ${p.symbol}`}
                                    onClick={() => openStock(p.symbol)}
                                  >
                                    <ArrowUpRight size={17} />
                                  </button>
                                </td>
                              </tr>
                            ))}
                        </tbody>
                      </table>
                    </div>
                  ) : (
                    <Empty
                      title="A clean slate"
                      action={
                        <button className="button primary" onClick={() => openTrade('')}>
                          Record your first trade
                        </button>
                      }
                    >
                      Add your first holding to start seeing the bigger picture.
                    </Empty>
                  )}
                  <div className="table-footnote">
                    Returns are unrealized and exclude fees, dividends, and previously sold
                    positions.
                  </div>
                </section>
              </>
            )}
            {(route === '/library' || route === '/watchlist') && (
              <>
                <div className="list-toolbar">
                  <div className="search-input">
                    <Search size={17} />
                    <input
                      aria-label="Filter stocks"
                      value={query}
                      placeholder="Company, symbol or sector…"
                      onChange={(e) => setQuery(e.target.value)}
                    />
                  </div>
                  {route === '/library' && <select aria-label="Filter by ownership" value={libraryFilter} onChange={e => setLibraryFilter(e.target.value)}>
                    <option value="all">All stocks</option><option value="unheld">Outside my portfolio</option><option value="held">My holdings</option>
                  </select>}
                  {route === '/library' && !demo && <select aria-label="Stock market" value={market} onChange={e=>setMarket(e.target.value)}>
                    {['All markets','US','Toronto','Bombay'].map(m=><option key={m}>{m}</option>)}
                  </select>}
                  <span className="muted">
                    {route === '/watchlist'
                      ? `${watched.length} saved to this browser`
                      : `${filteredStocks.length} stocks`}
                  </span>
                </div>
                <div className="stock-card-grid">
                  {filteredStocks
                    .filter((s) => route !== '/watchlist' || watched.includes(s.symbol))
                    .map((stock) => {
                      const position = positions.find((p) => p.symbol === stock.symbol);
                      return (
                        <article className="panel stock-card" key={stock.symbol}>
                          <div className="panel-topline">
                            <StockMark symbol={stock.symbol} />
                            <button
                              className={`icon-button ${watched.includes(stock.symbol) ? 'bookmarked' : ''}`}
                              aria-label={`${watched.includes(stock.symbol) ? 'Unwatch' : 'Watch'} ${stock.symbol}`}
                              onClick={() => toggleWatch(stock.symbol)}
                            >
                              <Bookmark
                                size={18}
                                fill={watched.includes(stock.symbol) ? 'currentColor' : 'none'}
                              />
                            </button>
                          </div>
                          <button
                            className="stock-card-title"
                            onClick={() => openStock(stock.symbol)}
                          >
                            <h2>
                              {stock.symbol}
                              <ArrowUpRight size={19} />
                            </h2>
                            <p>{stock.name}</p>
                          </button>
                          <div className={stock.price > 0 ? 'stock-card-price' : 'stock-card-sector'}>
                            {stock.price > 0 ? money(stock.price) : stock.sector || 'Market data'}
                            {stock.price > 0 && <span>Saved quote</span>}
                          </div>
                          <div className="stock-card-footer">
                            <span className={position ? tone(position.returnPercent) : 'muted'}>
                              {position
                                ? `${percent(position.returnPercent)} unrealized`
                                : 'Not in your portfolio'}
                            </span>
                            <button className="text-button" onClick={() => openStock(stock.symbol)}>
                              Details <ArrowRight size={14} />
                            </button>
                          </div>
                          <PerformanceSummary symbol={stock.symbol} demo={demo} />
                        </article>
                      );
                    })}
                </div>
                {!filteredStocks.some(
                  (s) => route !== '/watchlist' || watched.includes(s.symbol),
                ) && (
                  <Empty
                    icon={route === '/watchlist' ? Bookmark : Search}
                    title={
                      query
                        ? 'No matching stocks'
                        : route === '/library' && libraryFilter !== 'all' ? 'No stocks match this filter' : route === '/watchlist'
                          ? 'A little room for possibility'
                          : 'Your library is empty'
                    }
                    action={
                      route === '/watchlist' && !query ? (
                        <button className="button primary" onClick={() => go('/library')}>
                          Explore stock library
                        </button>
                      ) : route === '/library' && !demo && /^[A-Za-z][A-Za-z0-9.-]{0,9}$/.test(query.trim()) ? (
                        <button className="button subtle" onClick={() => openStock(query.trim().toUpperCase())}>
                          Look up ticker {query.trim().toUpperCase()}
                        </button>
                      ) : null
                    }
                  >
                    {query
                      ? 'Try a different company name or symbol.'
                      : 'Save stocks from the library to keep an eye on them.'}
                  </Empty>
                )}
              </>
            )}
            {detailSymbol && (
              <StockDetail
                symbol={detailSymbol}
                stock={stockBySymbol[detailSymbol]}
                position={positions.find((p) => p.symbol === detailSymbol)}
                demo={demo}
                watched={watched.includes(detailSymbol)}
                toggleWatch={() => toggleWatch(detailSymbol)}
                trade={(side) => openTrade(detailSymbol, side)}
                remove={() => setModal({ type: 'remove', symbol: detailSymbol })}
                disabled={disabled}
              />
            )}
            {route === '/settings' && (
              <div className="settings-grid">
                <section className="panel settings-panel">
                  <h2>Your workspace</h2>
                  <p className="muted">Small adjustments. A space that feels like you.</p>
                  <div className="setting-row">
                    <div>
                      <h3>Appearance</h3>
                      <p>Choose a lighter or quieter canvas.</p>
                    </div>
                    <div className="segmented">
                      <button
                        className={theme === 'dark' ? 'selected' : ''}
                        aria-pressed={theme === 'dark'}
                        onClick={() => setTheme('dark')}
                      >
                        <Moon size={14} /> Dark
                      </button>
                      <button
                        className={theme === 'light' ? 'selected' : ''}
                        aria-pressed={theme === 'light'}
                        onClick={() => setTheme('light')}
                      >
                        <Sun size={14} /> Light
                      </button>
                    </div>
                  </div>
                  <div className="setting-row">
                    <div>
                      <h3>Portfolio data</h3>
                      <p>Reload holdings and previously saved quotes.</p>
                    </div>
                    <button className="button subtle" disabled={disabled} onClick={() => reload()}>
                      Reload data
                    </button>
                  </div>
                  <div className="setting-row">
                    <div>
                      <h3>Export holdings</h3>
                      <p>A CSV copy of your current positions.</p>
                    </div>
                    <button
                      className="button subtle"
                      disabled={!positions.length}
                      onClick={exportHoldings}
                    >
                      <Download size={15} /> Export CSV
                    </button>
                  </div>
                </section>
                <section className="panel settings-panel">
                  <span className="tiny-tag">
                    <ShieldCheck size={13} /> {demo ? 'Demo workspace' : 'Local workspace'}
                  </span>
                  <h2>{demo ? 'Explore with confidence.' : 'Your data stays yours.'}</h2>
                  <p>
                    {demo
                      ? 'This preview uses sample holdings, prices, and chart history. Demo trades last until the server restarts. Your real database is never used in demo mode.'
                      : 'Holdings come from your configured database. API credentials stay on the local server. Refresh prices when you want a newer quote.'}
                  </p>
                  <div className="settings-fact">
                    <span>Currency</span>
                    <CurrencyPicker />
                  </div>
                  <div className="settings-fact">
                    <span>Watchlist storage</span>
                    <strong>This browser</strong>
                  </div>
                  <div className="settings-fact">
                    <span>Last portfolio load</span>
                    <strong>
                      {new Date(data.loadedAt).toLocaleTimeString([], {
                        hour: '2-digit',
                        minute: '2-digit',
                      })}
                    </strong>
                  </div>
                </section>
              </div>
            )}
            {!['/', '/holdings', '/library', '/watchlist', '/settings'].includes(route) &&
              !detailSymbol && (
                <Empty
                  title="This page isn't here"
                  action={
                    <button className="button primary" onClick={() => go('/')}>
                      Back to overview
                    </button>
                  }
                >
                  Let's get you back to your portfolio.
                </Empty>
              )}
            <footer className="page-footer">
              <span>
                <span className="live-dot" />
                {demo
                  ? 'Sample workspace · not live market data'
                  : 'Saved quotes · not streaming prices'}
              </span>
              <span>
                Thoughtfully tracked. <span className="footer-brand">folio.</span>
              </span>
            </footer>
          </>
        )}
      </main>
      <div className="dock-wrapper">
        <nav className="dock" aria-label="Main navigation">
          {nav.map(({ path, label, icon: Icon }) => (
            <a
              href={`#${path}`}
              key={path}
              aria-label={label}
              title={label}
              aria-current={route === path ? 'page' : undefined}
              className={route === path ? 'active' : ''}
            >
              <Icon size={20} />
              <span className="dock-label">{label}</span>
            </a>
          ))}
          <span className="dock-separator" />
          <button
            aria-label="Search stocks"
            title="Search stocks (Ctrl+K)"
            onClick={() => setModal({ type: 'search' })}
          >
            <Search size={21} />
          </button>
        </nav>
      </div>
      {modal?.type === 'trade' && (
        <TradeModal
          initial={modal}
          pending={pending}
          submit={trade}
          close={() => setModal(null)}
          demo={demo}
          failureMessage={notice?.type === 'error' ? notice.message : ''}
        />
      )}
      {modal?.type === 'remove' && (
        <Modal title={`Remove ${modal.symbol}?`} close={() => setModal(null)} pending={pending}>
          <p className="modal-description">
            This removes all saved portfolio entries for {modal.symbol}. It cannot be undone and
            does not record a sale.
          </p>
          <div className="modal-actions">
            <button className="button subtle" disabled={pending} onClick={() => setModal(null)}>
              Keep position
            </button>
            <button
              className="button danger"
              disabled={pending}
              onClick={async () => {
                const ok = await mutate(async () => {
                  const result = await request('/positions/remove', {
                    method: 'POST',
                    body: { symbol: modal.symbol },
                  });
                  return { type: 'success', message: result.message };
                });
                if (ok) setModal(null);
              }}
            >
              <Trash2 size={16} />
              {pending ? 'Removing…' : 'Remove position'}
            </button>
          </div>
        </Modal>
      )}
      {modal?.type === 'search' && (
        <SearchModal
          stocks={stocks}
          close={() => setModal(null)}
          select={(symbol) => {
            setModal(null);
            openStock(symbol);
          }}
        />
      )}
    </div>
  );
}
function Stat({ title, value, detail, color = '' }) {
  return (
    <div className="panel stat">
      <span className="eyebrow">{title}</span>
      <strong className={color}>{value}</strong>
      {detail && <span className={color}>{detail}</span>}
    </div>
  );
}
function StockDetail({
  symbol,
  stock,
  position,
  demo,
  watched,
  toggleWatch,
  trade,
  remove,
  disabled,
}) {
  const { money, signedMoney } = useCurrency();
  return (
    <>
      <button className="text-button back-link" onClick={() => go('/library')}>
        <ChevronLeft size={16} /> Stock library
      </button>
      <div className="detail-grid">
        <section className="panel detail-chart">
          <div className="detail-identity">
            <StockMark symbol={symbol} />
            <div>
              <h2>{symbol}</h2>
              <span className="muted">{stock?.name || symbol}</span>
            </div>
            <button
              className={`icon-button ${watched ? 'bookmarked' : ''}`}
              aria-label={watched ? `Unwatch ${symbol}` : `Watch ${symbol}`}
              onClick={toggleWatch}
            >
              <Bookmark size={20} fill={watched ? 'currentColor' : 'none'} />
            </button>
          </div>
          <PriceChart symbol={symbol} demo={demo} />
          <PerformanceSummary key={symbol} symbol={symbol} demo={demo} />
        </section>
        <section className="panel position-detail">
          <span className="eyebrow">Your position</span>
          <h2>{position ? money(position.quantity * position.price) : 'Not held yet'}</h2>
          <p className={position ? tone(position.profitLoss) : 'muted'}>
            {position
              ? `${signedMoney(position.profitLoss)} (${percent(position.returnPercent)}) unrealized`
              : 'Record a purchase to add this stock to your portfolio.'}
          </p>
          {[
            ['Shares', position?.quantity || '—'],
            ['Average cost', position ? money(position.averageCost) : '—'],
            ['Saved price', stock?.price > 0 ? money(stock.price) : '—'],
            ['Cost basis', position ? money(position.quantity * position.averageCost) : '—'],
          ].map(([name, value]) => (
            <div className="settings-fact" key={name}>
              <span>{name}</span>
              <strong>{value}</strong>
            </div>
          ))}
          <div className="position-actions">
            <button className="button primary" disabled={disabled} onClick={() => trade('BUY')}>
              <Plus size={16} /> Buy shares
            </button>
            <button
              className="button subtle"
              disabled={disabled || !position}
              onClick={() => trade('SELL')}
            >
              Sell shares
            </button>
          </div>
          {position && (
            <button className="text-button remove-link" disabled={disabled} onClick={remove}>
              <Trash2 size={14} /> Remove position
            </button>
          )}
        </section>
      </div>
    </>
  );
}
function SearchModal({ stocks, close, select }) {
  const [query, setQuery] = useState('');
  const matches = stocks.filter((s) =>
    `${s.symbol} ${s.name}`.toLowerCase().includes(query.toLowerCase()),
  );
  return (
    <Modal title="Find a stock" close={close}>
      <div className="search-input">
        <Search size={18} />
        <input
          autoFocus
          aria-label="Search saved stocks"
          placeholder="Company name or symbol…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <kbd>esc</kbd>
      </div>
      <div className="search-results">
        {matches.slice(0, 12).map((s) => (
          <button key={s.symbol} onClick={() => select(s.symbol)}>
            <StockMark symbol={s.symbol} small />
            <strong>{s.symbol}</strong>
            <span>{s.name}</span>
            <ArrowUpRight size={16} />
          </button>
        ))}
        {matches.length === 0 && <p className="muted">No matching saved stocks.</p>}
      </div>
      <div className="search-hint">
        <Command size={13} /> K to search your workspace
      </div>
    </Modal>
  );
}
