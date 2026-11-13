import { test, expect } from '@playwright/test';

test('display currency converts totals and projections and persists across reloads', async ({ page }) => {
  const holding = { symbol:'AAPL',quantity:2,price:100,averageCost:80,profitLoss:40,returnPercent:25 };
  await page.route('**/api/snapshot',route=>route.fulfill({json:{mode:'connected',stocks:[{symbol:'AAPL',price:100,name:'Apple'}],positions:[holding]}}));
  await page.route('**/api/history?*',route=>route.fulfill({json:{symbol:'AAPL',points:[{date:'2026-11-10',price:100}]}}));
  await page.route('**/api/exchange-rates',route=>route.fulfill({json:{date:'2026-11-12',rates:{USD:1,INR:80,CAD:1.4,EUR:.9,GBP:.8,JPY:150}}}));
  await page.route('**/api/portfolio-analysis',route=>route.fulfill({json:{text:'One holding.',next:'Review the largest position.',confidence:'High',reason:'100% in AAPL.'}}));
  await page.goto('/');
  await page.getByLabel('Display currency').selectOption('INR');
  await expect(page.locator('.portfolio-value')).toContainText('16,000.00');
  await page.getByLabel('Time horizon').selectOption('1');
  await expect(page.locator('.outlook > strong')).toContainText('16,800.00');
  for (const width of [1440,820,390]) {
    await page.setViewportSize({width,height:900});
    const value = await page.locator('.portfolio-panel').boundingBox();
    const holdings = await page.locator('.holdings-preview:not(.outlook)').boundingBox();
    if (width>740) expect(holdings.x).toBeGreaterThanOrEqual(value.x+value.width);
    else expect(holdings.y).toBeGreaterThanOrEqual(value.y+value.height);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  }
  await page.getByRole('button',{name:'Analyze my portfolio'}).click();
  await expect(page.getByText('Review the largest position.')).toBeVisible();
  await page.reload();
  await expect(page.locator('.portfolio-value')).toContainText('16,000.00');
});

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
  await expect(page.locator('.stock-card')).toHaveCount(38);
  await page.getByLabel('Stock market').selectOption('Bombay');
  await expect(page.locator('.stock-card')).toHaveCount(3);
  await expect(page.getByRole('heading',{name:'BSE:TCS'})).toBeVisible();
  await page.getByLabel('Stock market').selectOption('All markets');
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
