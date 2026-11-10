import { test } from 'node:test';
import assert from 'node:assert/strict';
import { request } from '../src/api.js';

test('missing hosted API reports setup guidance instead of a JSON parser error', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => new Response('The page could not be found', {
    status: 404, headers: { 'content-type': 'text/plain' },
  }));
  await assert.rejects(request('/snapshot'), /needs a connected Java backend/);
});

test('HTML startup pages are not treated as portfolio data', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => new Response('<html>Starting</html>', {
    headers: { 'content-type': 'text/html' },
  }));
  await assert.rejects(request('/snapshot'), /unexpected response/);
});

test('JSON errors retain the server explanation', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => Response.json({ error: 'Database unavailable.' }, { status: 503 }));
  await assert.rejects(request('/snapshot'), /Database unavailable/);
});

test('malformed JSON has a readable error', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => new Response('{', {
    headers: { 'content-type': 'application/json' },
  }));
  await assert.rejects(request('/snapshot'), /unreadable data/);
});

test('valid data and pending history responses are preserved', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => Response.json({ pending: true }, { status: 202 }));
  assert.deepEqual(await request('/history?symbol=AAPL'), { pending: true });
});
