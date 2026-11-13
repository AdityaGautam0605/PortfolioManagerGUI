import { useEffect, useId, useRef, useState } from 'react';
import { ArrowUpRight, RefreshCw } from 'lucide-react';
import { percent, tone } from './api.js';
import { useCurrency } from './Currency.jsx';
import { quoteCurrency } from './stockLibrary.js';
import { cachedHistory, freshHistory, loadHistory } from './history.js';

export default function PriceChart({ symbol, demo = false }) {
  const { currency, fx, money } = useCurrency();
  const native = quoteCurrency(symbol);
  const chartCurrency = native === 'USD' || fx ? currency : native;
  const formatClose = value => native === 'USD' ? money(value) : fx ? money(value/fx.rates[native]) :
    new Intl.NumberFormat('en-US',{style:'currency',currency:native,currencyDisplay:'code'}).format(value);
  const [result, setResult] = useState(
    () => cachedHistory(symbol, demo) || { symbol: '', points: [] },
  );
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [range, setRange] = useState('3M');
  const [retry, setRetry] = useState(0);
  const lastRetry = useRef(0);
  const [hover, setHover] = useState(null);
  const fillId = useId().replaceAll(':', '');
  useEffect(() => {
    if (!symbol) return;
    let active = true;
    let timer;
    let polls = 0;
    const cached = cachedHistory(symbol, demo);
    const force = retry !== lastRetry.current;
    lastRetry.current = retry;
    setResult(cached || { symbol, points: [] });
    setLoading(force || !freshHistory(cached));
    setError('');
    setHover(null);
    const load = (force) =>
      loadHistory(symbol, demo, force)
        .then((data) => {
          if (!active) return;
          const keepPolling = data.refreshing && polls++ < 20;
          setResult(keepPolling ? data : { ...data, refreshing: false });
          setError(data.warning || '');
          if (keepPolling) timer = setTimeout(() => load(true), 1000);
        })
        .catch((e) => {
          if (active) {
            setError(e.message);
            setResult((old) => ({ ...old, refreshing: false }));
          }
        })
        .finally(() => {
          if (active) setLoading(false);
        });
    load(force);
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [symbol, demo, retry]);
  const source = result.symbol === symbol ? result.points : [];
  const count = range === '1M' ? 22 : range === '3M' ? 66 : source.length;
  const points = source.slice(-count);
  const values = points.map((p) => p.price);
  const rawMin = values.length ? Math.min(...values) : 0;
  const rawMax = values.length ? Math.max(...values) : 1;
  const padding = Math.max((rawMax - rawMin) * 0.17, rawMax * 0.002, 0.01);
  const min = rawMin - padding,
    max = rawMax + padding;
  const width = 760,
    height = 230;
  const x = (i) => 8 + (i / Math.max(points.length - 1, 1)) * (width - 16);
  const y = (price) => height - 15 - ((price - min) / (max - min)) * (height - 30);
  const line = points
    .map((p, i) => `${i ? 'L' : 'M'} ${x(i).toFixed(2)},${y(p.price).toFixed(2)}`)
    .join(' ');
  const active = hover === null ? null : points[hover];
  const last = points.at(-1);
  const change = points.length > 1 && points[0].price > 0 ? 100 * (last.price / points[0].price - 1) : null;
  return (
    <div className="chart-component">
      <div className="chart-heading">
        <div>
          <span className="eyebrow">{demo ? 'Sample price history' : 'Daily closing prices'}</span>
          <div className="chart-symbol">
            {symbol || 'Select a stock'} <ArrowUpRight size={14} />
          </div>
          {change !== null && <span className={`chart-period-change ${tone(change)}`}>{percent(change)} · {points.length} sessions</span>}
        </div>
        <div className="chart-quote">
          {active ? formatClose(active.price) : last ? formatClose(last.price) : '—'}
          <span>{active ? active.date : last ? `Close · ${last.date}` : 'Closing price'} · {chartCurrency}{chartCurrency!==native && fx ? ' at FX '+fx.date : ''}</span>
        </div>
      </div>
      <div className="chart-canvas">
        {loading && !points.length ? (
          <div className="chart-message">
            <RefreshCw className="spin" size={19} />
            <span>Loading {symbol} history…</span>
          </div>
        ) : error && !points.length ? (
          <div className="chart-message">
            <span>{error}</span>
            <button className="button subtle" onClick={() => setRetry((r) => r + 1)}>
              Retry history
            </button>
          </div>
        ) : !points.length ? (
          <div className="chart-message">No price history available.</div>
        ) : (
          <svg
            className={tone(change)}
            viewBox={`0 0 ${width} ${height}`}
            role="img"
            aria-label={`${symbol} daily closing price chart, ${points.length} sessions`}
            onMouseLeave={() => setHover(null)}
            onMouseMove={(event) => {
              const bounds = event.currentTarget.getBoundingClientRect();
              setHover(
                Math.min(
                  points.length - 1,
                  Math.max(
                    0,
                    Math.round(
                      ((event.clientX - bounds.left) / bounds.width) * (points.length - 1),
                    ),
                  ),
                ),
              );
            }}
          >
            <defs>
              <linearGradient id={fillId} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="currentColor" stopOpacity=".09" />
                <stop offset="100%" stopColor="currentColor" stopOpacity="0" />
              </linearGradient>
            </defs>
            {[0.2, 0.5, 0.8].map((n) => (
              <line
                key={n}
                x1="0"
                x2={width}
                y1={height * n}
                y2={height * n}
                className="chart-grid"
              />
            ))}
            <path
              d={`${line} L ${x(points.length - 1)},${height} L 8,${height} Z`}
              fill={`url(#${fillId})`}
            />
            <path d={line} className="price-line" />
            {active && (
              <g>
                <line x1={x(hover)} x2={x(hover)} y1="0" y2={height} className="crosshair" />
                <circle cx={x(hover)} cy={y(active.price)} r="4" fill="currentColor" />
              </g>
            )}
          </svg>
        )}
      </div>
      {!!points.length && (loading || error || result.stale) && (
        <div className="history-status" role="status">
          <span>
            {loading
              ? 'Updating history in the background…'
              : `Showing saved history from ${new Date(result.fetchedAt).toLocaleString()}. ${error || (result.refreshing ? 'Checking for newer data…' : '')}`}
          </span>
          {!loading && !result.refreshing && (
            <button className="text-button" onClick={() => setRetry((r) => r + 1)}>
              Retry update
            </button>
          )}
        </div>
      )}
      <div className="chart-footer">
        <span>
          {points[0]?.date || 'Daily history'}
          <span className="date-divider">—</span>
          {last?.date || ''}
        </span>
        <div className="segmented" aria-label="Chart range">
          {['1M', '3M', 'ALL'].map((item) => (
            <button
              key={item}
              aria-pressed={range === item}
              className={range === item ? 'selected' : ''}
              onClick={() => {
                setRange(item);
                setHover(null);
              }}
            >
              {item}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
