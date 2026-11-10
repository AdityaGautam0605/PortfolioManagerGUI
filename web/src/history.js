import { request } from './api.js';

const FRESH_MS = 15 * 60 * 1000;
const MAX_AGE_MS = 24 * 60 * 60 * 1000;
const MAX_ENTRIES = 40;
const cache = new Map();
const pending = new Map();
const failures = new Map();
const hydrated = new Set();
const keyFor = (symbol, demo) => `${demo ? 'demo' : 'connected'}:${symbol}`;
const storageKey = (demo) => `folio-history-v1:${demo ? 'demo' : 'connected'}`;

function valid(data) {
  return (
    data &&
    typeof data.symbol === 'string' &&
    Number.isFinite(data.fetchedAt) &&
    Array.isArray(data.points) &&
    data.points.length > 0 &&
    data.points.every(
      (point) => typeof point.date === 'string' && Number.isFinite(point.price) && point.price > 0,
    ) &&
    Date.now() - data.fetchedAt <= MAX_AGE_MS &&
    data.fetchedAt <= Date.now() + 60000
  );
}
function hydrate(demo) {
  const storage = storageKey(demo);
  if (hydrated.has(storage)) return;
  hydrated.add(storage);
  try {
    const saved = JSON.parse(sessionStorage.getItem(storage) || '[]');
    if (Array.isArray(saved))
      saved
        .slice(-MAX_ENTRIES)
        .filter(valid)
        .forEach((data) => {
          cache.set(keyFor(data.symbol, demo), { ...data, refreshing: false });
        });
  } catch {
    /* Cache storage is optional. */
  }
}
function save(data, demo) {
  const key = keyFor(data.symbol, demo);
  cache.delete(key);
  cache.set(key, data);
  while (cache.size > MAX_ENTRIES * 2) cache.delete(cache.keys().next().value);
  try {
    const prefix = demo ? 'demo:' : 'connected:';
    const entries = [...cache]
      .filter(([key]) => key.startsWith(prefix))
      .map(([, value]) => value)
      .slice(-MAX_ENTRIES);
    sessionStorage.setItem(storageKey(demo), JSON.stringify(entries));
  } catch {
    /* The memory cache still works if browser storage is unavailable. */
  }
}
export function cachedHistory(symbol, demo) {
  hydrate(demo);
  const data = cache.get(keyFor(symbol, demo));
  return valid(data) ? data : null;
}
export function freshHistory(data) {
  return data && !data.stale && Date.now() - data.fetchedAt < FRESH_MS;
}
async function fetchHistory(symbol) {
  const deadline = Date.now() + 30000;
  while (true) {
    const result = await request(`/history?symbol=${encodeURIComponent(symbol)}`, {
      timeoutMs: 6500,
    });
    if (!result.pending) return result;
    if (Date.now() >= deadline)
      throw new Error('The history provider is still unavailable. Try again later.');
    await new Promise((resolve) =>
      setTimeout(resolve, Math.min(1500, Math.max(500, result.retryAfterMs || 750))),
    );
  }
}
export function loadHistory(symbol, demo, force = false) {
  const cached = cachedHistory(symbol, demo);
  const key = keyFor(symbol, demo);
  if (!force && freshHistory(cached)) return Promise.resolve(cached);
  if (pending.has(key)) return pending.get(key);
  const failure = failures.get(key);
  if (!force && failure && Date.now() < failure.retryAt) return Promise.reject(failure.error);
  // The request belongs to the cache, not a single mounted chart. Switching pages does
  // not discard useful work or start another provider call for the same stock.
  const promise = fetchHistory(symbol)
    .then((result) => {
      const data = { ...result, fetchedAt: result.fetchedAt ?? Date.now() };
      if (data.symbol !== symbol || !valid(data))
        throw new Error('No valid daily price history was returned.');
      save(data, demo);
      failures.delete(key);
      return data;
    })
    .catch((error) => {
      const safe =
        error.name === 'TimeoutError' || error.name === 'AbortError'
          ? new Error('History is taking longer than expected. Try again shortly.')
          : error;
      failures.set(key, { error: safe, retryAt: Date.now() + 30000 });
      throw safe;
    })
    .finally(() => pending.delete(key));
  pending.set(key, promise);
  return promise;
}
