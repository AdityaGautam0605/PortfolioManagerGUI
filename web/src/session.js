import { request, readStored, store } from './api.js';

// Only confirmed activity from tabs sharing this session can move its deadline.
export function trackSession(session) {
  const key = 'folio-session-' + session.id;
  const controller = new AbortController();
  let deadline = Math.max(Date.now() + session.remainingMs, Number(readStored(key, 0)));
  let expiryTimer, sendTimer, inFlight = false, lastSent = 0, latestInput = 0;

  function check() {
    clearTimeout(expiryTimer);
    deadline = Math.max(deadline, Number(readStored(key, 0)));
    const remaining = deadline - Date.now();
    if (remaining <= 0) {
      try { localStorage.removeItem(key); } catch { /* Storage may be unavailable. */ }
      window.dispatchEvent(new Event('folio-auth-required'));
      return false;
    }
    expiryTimer = setTimeout(check, remaining);
    return true;
  }
  function scheduleSend() {
    clearTimeout(sendTimer);
    if (inFlight || !latestInput) return;
    const delay = Math.max(0, Math.min(lastSent + 10000, deadline - 2000) - Date.now());
    sendTimer = setTimeout(send, delay);
  }
  async function send() {
    if (!check() || !latestInput) return;
    inFlight = true;
    lastSent = Date.now();
    const elapsedMillis = Math.max(0,lastSent - latestInput);
    latestInput = 0;
    try {
      const update = await request('/session/activity', {
        method: 'POST', body: { elapsedMillis }, signal: controller.signal,
      });
      if (controller.signal.aborted || update.id !== session.id) return;
      deadline = Math.max(deadline, lastSent + update.remainingMs, Number(readStored(key, 0)));
      store(key,deadline);
      check();
    } catch {
      // The API handles rejected sessions. Offline tabs lock at the last confirmed deadline.
    } finally {
      inFlight = false;
      if (!controller.signal.aborted) scheduleSend();
    }
  }
  function activity(event) {
    if (!event.isTrusted || document.hidden || Date.now() >= deadline && !check()) return;
    latestInput = Date.now();
    scheduleSend();
  }
  function sync(event) { if (event.key === key) check(); }
  const events = ['pointerdown','pointermove','keydown','wheel','touchmove'];
  for (const type of events) window.addEventListener(type,activity,{ passive: true, capture: true });
  window.addEventListener('storage',sync);
  window.addEventListener('pageshow',check);
  document.addEventListener('visibilitychange',check);
  check();
  return () => {
    controller.abort();
    clearTimeout(sendTimer); clearTimeout(expiryTimer);
    for (const type of events) window.removeEventListener(type,activity,true);
    window.removeEventListener('storage',sync);
    window.removeEventListener('pageshow',check);
    document.removeEventListener('visibilitychange',check);
  };
}
