import { test, expect } from '@playwright/test';
import { applyBrowserPolicies } from './browser-policies.js';

test.beforeEach(async ({ page }) => {
  await applyBrowserPolicies(page);
  await page.route('**/api/snapshot', route => route.fulfill({ status: 401, json: {} }));
  await page.route('**/api/auth/options', route => route.fulfill({ json: { googleClientId: 'test', firebaseEnabled: true } }));
  await page.route('https://accounts.google.com/gsi/client', route => route.fulfill({ contentType: 'application/javascript', body: 'window.google = { accounts: { oauth2: {} } };' }));
  await page.addInitScript(() => {
    window.policyViolations = [];
    document.addEventListener('securitypolicyviolation', event => window.policyViolations.push(event.violatedDirective));
  });
});

test('homepage and sign-in load under production CSP including Google script', async ({ page }) => {
  const response = await page.goto('/');
  expect(response.headers()['x-frame-options']).toBe('DENY');
  await page.getByRole('link', { name: 'Sign in', exact: true }).first().click();
  await expect(page.getByRole('button', { name: 'Continue with Google' })).toBeEnabled();
  await expect(page.getByLabel('Password', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.policyViolations)).toEqual([]);
});

test('CSP blocks injected inline scripts and unapproved outbound connections', async ({ page }) => {
  await page.goto('/#/signin');
  await expect(page.getByLabel('Password', { exact: true })).toBeVisible();
  const result = await page.evaluate(async () => {
    const script = document.createElement('script');
    script.textContent = 'window.injectedScriptRan = true';
    document.head.append(script);
    let blocked = false;
    try { await fetch('https://unapproved.invalid/collect'); } catch { blocked = true; }
    return { injected: !!window.injectedScriptRan, blocked };
  });
  expect(result).toEqual({ injected: false, blocked: true });
  await expect.poll(() => page.evaluate(() => window.policyViolations)).toContain('script-src-elem');
  expect(await page.evaluate(() => window.policyViolations)).toContain('connect-src');
});
