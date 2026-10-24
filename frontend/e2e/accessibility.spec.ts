import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

test('login page has no axe WCAG A or AA violations', async ({ page }) => {
  await page.route('**/api/v1/auth/refresh', (route) => route.fulfill({
    status: 401,
    contentType: 'application/problem+json',
    body: JSON.stringify({ status: 401, code: 'REFRESH_REQUIRED', detail: 'Sign in to continue.' }),
  }));
  await page.goto('/login');
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  expect(results.violations).toEqual([]);
});
