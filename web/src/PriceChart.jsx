import { useEffect, useId, useState } from 'react';
import { ArrowUpRight, RefreshCw } from 'lucide-react';
import { money, request } from './api.js';

export default function PriceChart({ symbol, demo = false }) {
  const [result, setResult] = useState({ symbol: '', points: [] });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [range, setRange] = useState('3M');
  const [retry, setRetry] = useState(0);
  const [hover, setHover] = useState(null);
  const fillId = useId().replaceAll(':', '');
  useEffect(() => {
    if (!symbol) return;
    const controller = new AbortController();
    setLoading(true);
    setError('');
    setHover(null);
    request(`/history?symbol=${encodeURIComponent(symbol)}`, { signal: controller.signal })
      .then((data) => {
        if (!controller.signal.aborted) setResult(data);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(e.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [symbol, retry]);
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
  return (
    <div className="chart-component">
      <div className="chart-heading">
        <div>
          <span className="eyebrow">{demo ? 'Sample price history' : 'Daily closing prices'}</span>
          <div className="chart-symbol">
            {symbol || 'Select a stock'} <ArrowUpRight size={14} />
          </div>
        </div>
        <div className="chart-quote">
          {active ? money(active.price) : last ? money(last.price) : '—'}
          <span>{active ? active.date : 'Closing price'}</span>
        </div>
      </div>
      <div className="chart-canvas">
        {loading ? (
          <div className="chart-message">
            <RefreshCw className="spin" size={19} />
            <span>Loading {symbol} history…</span>
          </div>
        ) : error ? (
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
