import { test, expect } from '@playwright/test';

const snapshot = { mode: 'hosted', account: { id: 'test-alice', username: 'alice' }, positions: [], stocks: [], loadedAt: new Date().toISOString() };

test('hosted registration, account identity, logout, and failed login', async ({ page }) => {
  let signedIn = false;
  await page.route('**/api/auth/options', route => route.fulfill({ json: { emailEnabled: true } }));
  await page.route('**/api/auth/send-code', route => route.fulfill({ json: { sent: true } }));
  await page.route('**/api/auth/verify', route => route.fulfill({ json: { ticket: 'verified', email: 'alice@example.com' } }));
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
  await expect(page.getByRole('heading', { name: 'Create your account' })).toBeVisible();
  await page.screenshot({ path: 'test-results/hosted-signin-desktop.png', fullPage: true });
  await page.getByLabel('Email address').fill('alice@example.com');
  await page.getByLabel('First name').fill('Alice');
  await page.getByLabel('Last name').fill('Lee');
  await page.getByLabel('Password', { exact: true }).fill('a-strong-test-password');
  await page.getByRole('button', { name: 'Verify email' }).click();
  await page.getByLabel('Email code', { exact: true }).fill('test-code');
  await page.getByRole('button', { name: 'Continue', exact: true }).click();
  await page.getByLabel('Username', { exact: true }).fill('alice');
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(page.getByLabel('Account: alice', { exact: true })).toBeVisible();
  await page.getByLabel('Account: alice', { exact: true }).click();
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await page.getByRole('button', { name: 'Already have an account? Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Welcome back.' })).toBeVisible();
  await expect(page.locator('.portfolio-value')).toHaveCount(0);
  await page.getByLabel('Email or username').fill('alice');
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
  await expect(page.getByLabel('Account: alice', { exact: true })).toBeVisible();
  expired = true;
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Create your account' })).toBeVisible();
  await expect(page.getByLabel('Account: alice', { exact: true })).toHaveCount(0);
});

test('another tab changing accounts clears the old workspace', async ({ page, context }) => {
  let current = snapshot;
  await context.route('**/api/snapshot', route => route.fulfill({ json: current }));
  await page.goto('/');
  await expect(page.getByLabel('Account: alice', { exact: true })).toBeVisible();
  const otherTab = await context.newPage();
  await otherTab.goto('/');
  current = { ...snapshot, account: { id: 'test-bob', username: 'b'.repeat(40) } };
  await otherTab.evaluate(() => localStorage.setItem('folio-account-change', crypto.randomUUID()));
  await expect(page.getByLabel('Account: alice', { exact: true })).toHaveCount(0);
  await expect(page.getByLabel(`Account: ${'b'.repeat(40)}`, { exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
