export async function request(path, { method = 'GET', body, signal } = {}) {
  const timeout = AbortSignal.timeout(60000);
  const response = await fetch(`/api${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-Portfolio-Client': 'portfolio-web' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || 'The request could not be completed.');
  return data;
}
export function readStored(key, fallback) {
  try {
    return JSON.parse(localStorage.getItem(key)) ?? fallback;
  } catch {
    return fallback;
  }
}
export function store(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* Browsing still works without storage. */
  }
}
export const money = (value) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(value || 0);
export const percent = (value) => `${value >= 0 ? '+' : ''}${(value || 0).toFixed(2)}%`;
export const signedMoney = (value) => `${value >= 0 ? '+' : '−'}${money(Math.abs(value))}`;
export const tone = (value) => (value < 0 ? 'negative' : value > 0 ? 'positive' : '');
export const go = (path) => {
  window.location.hash = path;
};
