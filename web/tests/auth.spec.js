import { test, expect } from '@playwright/test';

const snapshot = { mode: 'hosted', account: { id: 'test-alice', username: 'alice' }, positions: [], stocks: [], loadedAt: new Date().toISOString() };

test('hosted registration, account identity, logout, and failed login', async ({ page }) => {
  let signedIn = false;
  await page.route('**/api/snapshot', route => route.fulfill(signedIn
    ? { json: snapshot }
    : { status: 401, json: { error: 'Sign in to access your portfolio.' } }));
  await page.route('**/api/auth/register', async route => {
    expect(route.request().postDataJSON().username).toBe('alice');
    signedIn = true;
    await route.fulfill({ json: { message: 'Signed in.' } });
  });
  await page.route('**/api/auth/logout', async route => {
    signedIn = false;
    await route.fulfill({ json: { message: 'Signed out.' } });
  });
  await page.route('**/api/auth/login', route => route.fulfill({ status: 401, json: { error: 'Incorrect username or password.' } }));
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Welcome back.' })).toBeVisible();
  await page.screenshot({ path: 'test-results/hosted-signin-desktop.png', fullPage: true });
  await page.getByRole('button', { name: 'New to Folio? Create an account' }).click();
  await page.getByLabel('Username', { exact: true }).fill('alice');
  await page.getByLabel('Password', { exact: true }).fill('a-strong-test-password');
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Sign out · alice' })).toBeVisible();
  await page.getByRole('button', { name: 'Sign out · alice' }).click();
  await expect(page.getByRole('heading', { name: 'Welcome back.' })).toBeVisible();
  await expect(page.locator('.portfolio-value')).toHaveCount(0);
  await page.getByLabel('Username', { exact: true }).fill('alice');
  await page.getByLabel('Password', { exact: true }).fill('incorrect-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('Incorrect username or password.');
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/hosted-signin-mobile.png', fullPage: true });
});

test('session expiry unmounts private portfolio data', async ({ page }) => {
  let expired = false;
  await page.route('**/api/snapshot', route => route.fulfill(expired
    ? { status: 401, json: { error: 'Sign in to access your portfolio.' } }
    : { json: snapshot }));
  await page.goto('/');
  await expect(page.getByRole('button', { name: 'Sign out · alice' })).toBeVisible();
  expired = true;
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Welcome back.' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sign out · alice' })).toHaveCount(0);
});

test('another tab changing accounts clears the old workspace', async ({ page, context }) => {
  let current = snapshot;
  await context.route('**/api/snapshot', route => route.fulfill({ json: current }));
  await page.goto('/');
  await expect(page.getByRole('button', { name: 'Sign out · alice' })).toBeVisible();
  const otherTab = await context.newPage();
  await otherTab.goto('/');
  current = { ...snapshot, account: { id: 'test-bob', username: 'b'.repeat(40) } };
  await otherTab.evaluate(() => localStorage.setItem('folio-account-change', crypto.randomUUID()));
  await expect(page.getByRole('button', { name: 'Sign out · alice' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: `Sign out · ${'b'.repeat(40)}` })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
