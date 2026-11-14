export async function request(path, { method = 'GET', body, signal, timeoutMs = 60000 } = {}) {
  const timeout = AbortSignal.timeout(timeoutMs);
  const requestSignal = signal ? AbortSignal.any([signal, timeout]) : timeout;
  const options = {
    method,
    headers: { 'Content-Type': 'application/json', 'X-Portfolio-Client': 'portfolio-web' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: requestSignal,
  };
  let response;
  let gatewayFailure;
  // A sleeping host can return a gateway error before the Java server is ready.
  // Only reads are safe to repeat automatically.
  const retryDelays = method === 'GET' ? [2000, 4000, 8000, 12000, 12000] : [];
  for (let attempt = 0; ; attempt++) {
    response = await fetch(`/api${path}`, options);
    const jsonResponse = (response.headers.get('content-type') || '').toLowerCase().includes('application/json');
    gatewayFailure = [502, 504].includes(response.status) || (response.status === 503 && !jsonResponse);
    if (!gatewayFailure || attempt >= retryDelays.length) break;
    await response.body?.cancel();
    await waitForRetry(retryDelays[attempt], requestSignal);
  }
  if (gatewayFailure) {
    const error = new Error(method === 'GET'
      ? 'The server is temporarily unavailable. Please try again shortly.'
      : 'The server could not confirm this request. Check its status before trying again.');
    error.status = response.status;
    throw error;
  }
  const contentType = response.headers.get('content-type') || '';
  if (!contentType.toLowerCase().includes('application/json')) {
    throw new Error(
      response.status === 404
        ? 'The portfolio API was not found. This website needs a connected Java backend to load your holdings.'
        : `The portfolio server returned an unexpected response (HTTP ${response.status}). Please try again shortly.`,
    );
  }
  let data;
  try {
    data = await response.json();
  } catch (error) {
    if (error.name !== 'SyntaxError') throw error;
    throw new Error('The portfolio server returned unreadable data. Please try again shortly.');
  }
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith('/auth/') && typeof window !== 'undefined') {
      window.dispatchEvent(new Event('folio-auth-required'));
    }
    const error = new Error(typeof data?.error === 'string' ? data.error : 'The request could not be completed.');
    error.status = response.status;
    throw error;
  }
  return data;
}
function waitForRetry(delay, signal) {
  return new Promise((resolve, reject) => {
    signal.throwIfAborted();
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', cancel);
      resolve();
    }, delay);
    function cancel() {
      clearTimeout(timer);
      signal.removeEventListener('abort', cancel);
      reject(signal.reason);
    }
    signal.addEventListener('abort', cancel, { once: true });
  });
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
export function announceAccountChange() {
  store('folio-account-change', crypto.randomUUID());
}
export const money = (value) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(value || 0);
export const percent = (value) => `${value >= 0 ? '+' : ''}${(value || 0).toFixed(2)}%`;
export const signedMoney = (value) => `${value >= 0 ? '+' : '−'}${money(Math.abs(value))}`;
export const tone = (value) => (value < 0 ? 'negative' : value > 0 ? 'positive' : '');
export const go = (path) => {
  window.location.hash = path;
};
