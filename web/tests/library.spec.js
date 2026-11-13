import { test, expect } from '@playwright/test';

test('an empty portfolio can discover, summarize and follow a stock', async ({ page }) => {
  const points = [{ date: '2026-11-10', price: 100 }, { date: '2026-11-11', price: 103 }];
  let historyRequests = 0;
  await page.route('**/api/snapshot', route => route.fulfill({ json: {
    mode: 'hosted', account: { id: 'library-test', username: 'alex' }, positions: [], stocks: [],
  } }));
  await page.route('**/api/history?symbol=JPM', route => {
    historyRequests++;
    return route.fulfill({ json: { symbol: 'JPM', points, fetchedAt: Date.now() } });
  });
  await page.route('**/api/summary?symbol=JPM', route => route.fulfill({ json: {
    ai: true, text: 'JPM gained 3% across two closes.', from: points[0].date, through: points[1].date,
  } }));
  await page.goto('/#/library');
  await expect(page.locator('.stock-card')).toHaveCount(32);
  await page.getByLabel('Filter by ownership').selectOption('unheld');
  await page.getByLabel('Filter stocks').fill('JPMorgan');
  expect(historyRequests).toBe(0);
  await page.getByRole('button', { name: 'Watch JPM', exact: true }).click();
  await page.getByRole('link', { name: 'Watchlist', exact: true }).click();
  await page.reload();
  await page.getByRole('button', { name: 'Performance summary' }).click();
  await expect(page.getByText('JPM gained 3% across two closes.')).toBeVisible();
  await expect(page.getByText(/AI summary ·/)).toBeVisible();
  await page.getByRole('button', { name: 'Unwatch JPM' }).click();
  await expect(page.locator('.stock-card')).toHaveCount(0);
});
