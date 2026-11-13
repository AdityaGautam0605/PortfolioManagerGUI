import { test, expect } from '@playwright/test';

const snapshot = { mode: 'hosted', account: { id: 'test-alice', username: 'alice' }, positions: [], stocks: [], loadedAt: new Date().toISOString() };

test('Firebase signup opens a verification link step and resumes username setup', async ({ page }) => {
  await page.route('**/api/snapshot', route => route.fulfill({ status: 401, json: {} }));
  await page.route('**/api/auth/options', route => route.fulfill({ json: { firebaseEnabled: true, emailEnabled: false } }));
  const email = 'alex@example.com';
  await page.route('**/api/auth/firebase-signup', route => route.fulfill({ json: { awaitingVerification: true, linkSent: true, email } }));
  await page.route('**/api/auth/firebase-check', route => route.fulfill({ json: { ticket: 'test', firebase: true, email } }));
  await page.route('**/api/auth/firebase-reset', route => route.fulfill({ json: { sent: true } }));
  await page.goto('/');
  await page.getByRole('button', { name: 'Forgot password?' }).click();
  await page.getByLabel('Email address').fill(email);
  await page.getByRole('button', { name: 'Send reset link' }).click();
  await expect(page.getByRole('status')).toContainText('reset link');
  await page.getByRole('button', { name: 'Create an account' }).click();
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('First name').fill('Alex');
  await page.getByLabel('Last name').fill('Lee');
  await page.getByLabel('Password', { exact: true }).fill('a secure test password');
  await page.getByRole('button', { name: 'Verify email' }).click();
  await expect(page.getByText('Open the verification link', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'I verified my email' }).click();
  await expect(page.getByLabel('Username', { exact: true })).toBeVisible();
});

test('unconfigured email leaves password login and Google signup available', async ({ page }) => {
  await page.route('**/api/snapshot', route => route.fulfill({ status: 401, json: {} }));
  await page.route('**/api/auth/options', route => route.fulfill({ json: { emailEnabled: false, googleClientId: 'test' } }));
  await page.route('https://accounts.google.com/gsi/client', route => route.fulfill({ body: '' }));
  await page.goto('/');
  await expect(page.getByText('Email codes and password resets are currently unavailable.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Forgot password?' })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Sign in with an email code' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Create an account' }).click();
  await expect(page.getByRole('button', { name: 'Continue with Google' })).toBeEnabled();
  await expect(page.getByRole('button', { name: 'Verify email' })).toHaveCount(0);
});

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
  await expect(page.getByRole('heading', { name: 'Welcome back.' })).toBeVisible();
  await page.screenshot({ path: 'test-results/hosted-signin-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 320, height: 640 });
  const forgot = page.getByRole('button', { name: 'Forgot password?' });
  const signup = page.getByRole('button', { name: 'Create an account', exact: true });
  const left = await forgot.boundingBox();
  const right = await signup.boundingBox();
  expect(left.y).toBe(right.y);
  expect(left.x + left.width).toBeLessThan(right.x);
  expect(right.x + right.width).toBeLessThanOrEqual(320);
  await forgot.click();
  await page.getByRole('button', { name: 'Back to sign in' }).click();
  await signup.click();
  await expect(page.getByRole('heading', { name: 'Create your account' })).toBeVisible();
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

test('idle workspace locks without a reload and input extends its deadline', async ({ page }) => {
  await page.clock.install();
  const duration = 15 * 60 * 1000;
  const session = { id: 'test-session', remainingMs: duration };
  await page.route('**/api/snapshot', route => route.fulfill({ json: { ...snapshot, session } }));
  await page.route('**/api/session/activity', route => route.fulfill({ json: session }));
  await page.route('**/api/auth/options', route => route.fulfill({ json: {} }));
  await page.goto('/');
  await expect(page.getByLabel('Account: alice', { exact: true })).toBeVisible();
  await page.clock.fastForward(14 * 60 * 1000);
  await page.keyboard.press('Shift');
  await page.waitForFunction(() => localStorage.getItem('folio-session-test-session') !== null);
  await page.clock.fastForward(14 * 60 * 1000);
  await expect(page.getByLabel('Account: alice', { exact: true })).toBeVisible();
  await page.clock.fastForward(61000);
  await expect(page.getByRole('heading', { name: 'Welcome back.' })).toBeVisible();
  await expect(page.getByLabel('Account: alice', { exact: true })).toHaveCount(0);
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
  await expect(page.getByRole('heading', { name: 'Welcome back.' })).toBeVisible();
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
