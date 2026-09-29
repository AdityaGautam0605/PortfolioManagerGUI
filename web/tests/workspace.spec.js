import { test, expect } from '@playwright/test';
import path from 'node:path';

const headers = { 'X-Portfolio-Client': 'portfolio-web' };
test.beforeAll(async ({ request }) => {
  const health = await (await request.get('/api/health')).json();
  expect(health.mode, 'Only run these checks against the explicit demo server').toBe('demo');
});
async function ready(page) {
  await page.goto('/');
  await expect(page.locator('.portfolio-value')).toBeVisible();
  await expect(page.getByRole('img', { name: /daily closing price chart/ })).toBeVisible();
}
async function snapshot(page, name) {
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: path.join('test-results', name), fullPage: true });
}
test('overview and responsive multi-page navigation', async ({ page }) => {
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await ready(page);
  await expect(page.getByText('Demo · sample data')).toBeVisible();
  await snapshot(page, 'overview-desktop.png');
  await page.getByRole('link', { name: 'Holdings', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'All positions' })).toBeVisible();
  await expect(page.locator('tbody tr')).toHaveCount(5);
  await snapshot(page, 'holdings-desktop.png');
  await page.getByRole('link', { name: 'Stock library', exact: true }).click();
  await page.getByRole('textbox', { name: 'Filter stocks' }).fill('NVIDIA');
  await expect(page.locator('.stock-card')).toHaveCount(1);
  await page.getByRole('button', { name: 'Details' }).click();
  await expect(page).toHaveURL(/#\/stock\/NVDA/);
  await expect(page.getByRole('img', { name: /NVDA daily/ })).toBeVisible();
  await snapshot(page, 'stock-detail.png');
  await page.getByRole('link', { name: 'Overview', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('img', { name: /daily closing price chart/ })).toBeVisible();
  await snapshot(page, 'overview-mobile.png');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.getByRole('link', { name: 'Stock library', exact: true }).click();
  await expect(page.locator('.stock-card')).toHaveCount(7);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  expect(errors).toEqual([]);
});
test('watchlist, keyboard search and theme persist', async ({ page }) => {
  await ready(page);
  await page.getByRole('link', { name: 'Stock library', exact: true }).click();
  await page.getByRole('button', { name: 'Watch MSFT', exact: true }).click();
  await page.getByRole('link', { name: 'Watchlist', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'MSFT', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'MSFT', exact: true })).toBeVisible();
  await page.keyboard.press('Control+k');
  await page.getByRole('textbox', { name: 'Search saved stocks' }).fill('Tesla');
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /TSLA Tesla/ })
    .click();
  await expect(page).toHaveURL(/#\/stock\/TSLA/);
  await page.getByRole('button', { name: 'Toggle color theme' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
});
test('buy and sell persist through the Java API in demo mode', async ({ page, request }) => {
  const start = await (await request.get('/api/snapshot')).json();
  const shares = start.positions.find((p) => p.symbol === 'AAPL').quantity;
  await ready(page);
  await page.getByRole('button', { name: 'Record trade', exact: true }).click();
  await page.getByLabel('Stock symbol').fill('AAPL');
  await page.getByLabel('Number of shares').fill('2');
  await page.getByRole('button', { name: 'Record buy', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('status')).toContainText('Buy recorded: 2 AAPL');
  const updated = await (await request.get('/api/snapshot')).json();
  expect(updated.positions.find((p) => p.symbol === 'AAPL').quantity).toBe(shares + 2);
  await page.getByRole('button', { name: 'Record trade', exact: true }).click();
  await page.getByRole('button', { name: 'Sell shares', exact: true }).click();
  await page.getByLabel('Stock symbol').fill('AAPL');
  await page.getByLabel('Number of shares').fill('2');
  await page.getByRole('button', { name: 'Record sell', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  const final = await (await request.get('/api/snapshot')).json();
  expect(final.positions.find((p) => p.symbol === 'AAPL').quantity).toBe(shares);
});
test('failed trade retains input, and a saved trade survives a failed reload', async ({ page }) => {
  await ready(page);
  const before = await page.locator('.portfolio-value').innerText();
  await page.route('**/api/trades', (route) =>
    route.fulfill({ status: 503, json: { error: 'Database write failed.' } }),
  );
  await page.getByRole('button', { name: 'Record trade', exact: true }).click();
  await page.getByLabel('Stock symbol').fill('AAPL');
  await page.getByLabel('Number of shares').fill('1');
  await page.getByRole('button', { name: 'Record buy', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByLabel('Number of shares')).toHaveValue('1');
  await expect(page.locator('.form-error')).toBeVisible();
  await page.getByRole('button', { name: 'Close dialog' }).click();
  await page.unroute('**/api/trades');
  await page.route('**/api/trades', (route) =>
    route.fulfill({ json: { message: 'Buy recorded: 1 AAPL shares.' } }),
  );
  await page.route('**/api/snapshot', (route) =>
    route.fulfill({ status: 503, json: { error: 'Read failed.' } }),
  );
  await page.getByRole('button', { name: 'Record trade', exact: true }).click();
  await page.getByLabel('Stock symbol').fill('AAPL');
  await page.getByLabel('Number of shares').fill('1');
  await page.getByRole('button', { name: 'Record buy', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('alert')).toContainText(
    'Buy recorded: 1 AAPL shares. View could not reload.',
  );
  await expect(page.locator('.portfolio-value')).toHaveText(before);
});
test('refresh reports partial failures and exposes their reasons', async ({ page }) => {
  await ready(page);
  await page.route('**/api/refresh', (route) =>
    route.fulfill({ status: 202, json: { id: 'test-job' } }),
  );
  await page.route('**/api/jobs/test-job', (route) =>
    route.fulfill({
      json: {
        done: true,
        completed: 7,
        total: 7,
        result: { total: 7, updated: 6, failures: { MSFT: 'Rate limit reached.' } },
      },
    }),
  );
  await page.getByRole('button', { name: 'Refresh prices', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('6 of 7');
  await page.getByText('View failed updates').click();
  await expect(page.getByText('MSFT: Rate limit reached.')).toBeVisible();
});
test('delayed quote requests do not block navigation or replace newer charts', async ({ page }) => {
  await ready(page);
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  await page.route('**/api/history?symbol=NVDA', async (route) => {
    await gate;
    try {
      await route.fulfill({
        json: {
          symbol: 'NVDA',
          points: [
            { date: '2026-01-01', price: 10 },
            { date: '2026-01-02', price: 11 },
          ],
        },
      });
    } catch {
      /* Superseded request was aborted. */
    }
  });
  await page.getByLabel('Explore a holding').selectOption('NVDA');
  await expect(page.getByText('Loading NVDA history…')).toBeVisible();
  await page.getByLabel('Explore a holding').selectOption('MSFT');
  await expect(page.getByRole('img', { name: /MSFT daily/ })).toBeVisible();
  release();
  await expect(page.getByRole('img', { name: /MSFT daily/ })).toBeVisible();
  await page.getByRole('link', { name: 'Holdings', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'All positions' })).toBeVisible();
});
test('API validates trades and rejects untrusted writes', async ({ request }) => {
  const invalid = await request.post('/api/trades', {
    headers,
    data: { symbol: 'AAPL', quantity: 1.5, side: 'BUY' },
  });
  expect(invalid.status()).toBe(400);
  const oversell = await request.post('/api/trades', {
    headers,
    data: { symbol: 'AAPL', quantity: 999999, side: 'SELL' },
  });
  expect(oversell.status()).toBe(503);
  expect((await oversell.json()).error).toContain('Not enough shares');
  const untrusted = await request.post('/api/trades', {
    headers: { ...headers, Origin: 'https://untrusted.example' },
    data: { symbol: 'AAPL', quantity: 1, side: 'BUY' },
  });
  expect(untrusted.status()).toBe(403);
  const refresh = await request.post('/api/refresh', { headers });
  expect(refresh.status()).toBe(202);
  const job = await refresh.json();
  await expect
    .poll(async () => (await (await request.get(`/api/jobs/${job.id}`)).json()).done)
    .toBe(true);
});

test('position removal requires confirmation and updates the demo portfolio', async ({
  page,
  request,
}) => {
  const buy = await request.post('/api/trades', {
    headers,
    data: { symbol: 'TSLA', quantity: 1, side: 'BUY' },
  });
  expect(buy.ok()).toBe(true);
  await ready(page);
  await page.goto('/#/stock/TSLA');
  await page.getByRole('button', { name: 'Remove position', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Remove TSLA?' })).toBeVisible();
  await page.getByRole('button', { name: 'Keep position' }).click();
  const preserved = await (await request.get('/api/snapshot')).json();
  expect(preserved.positions.some((p) => p.symbol === 'TSLA')).toBe(true);
  await page.getByRole('button', { name: 'Remove position', exact: true }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Remove position', exact: true })
    .click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('status')).toContainText('Removed TSLA');
  await expect(page.getByRole('heading', { name: 'Not held yet' })).toBeVisible();
});
test('pending trades cannot be submitted twice', async ({ page }) => {
  await ready(page);
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  let calls = 0;
  await page.route('**/api/trades', async (route) => {
    calls++;
    await gate;
    await route.fulfill({ json: { message: 'Buy recorded: 1 AAPL shares.' } });
  });
  await page.getByRole('button', { name: 'Record trade', exact: true }).click();
  await page.getByLabel('Stock symbol').fill('AAPL');
  await page.getByLabel('Number of shares').fill('1');
  await page.getByRole('button', { name: 'Record buy', exact: true }).evaluate((button) => {
    button.click();
    button.click();
  });
  await expect(page.getByRole('button', { name: 'Saving trade…', exact: true })).toBeDisabled();
  await expect.poll(() => calls).toBe(1);
  release();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(calls).toBe(1);
});
