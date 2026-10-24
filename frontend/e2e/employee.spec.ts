import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

test('employee configures a dish and completes an asynchronously confirmed order', async ({ page }) => {
  const confirmedOrder = {
    id: 181, orderNumber: 'WB-20261025-000181', userId: 42, userDisplayName: 'Lee Chen', deliveryDate: '2026-10-25', mealSlot: 'LUNCH',
    deliveryAddress: 'Office · 18 Garden Road', status: 'PENDING', total: '14.00', version: 0,
    items: [{ id: 1, dishId: 14, dishName: 'Herb chicken bowl', basePrice: '14.00', unitPrice: '14.00', quantity: 1, subtotal: '14.00', options: [{ groupName: 'Side', valueName: 'Brown rice', priceDelta: '0.00' }] }],
    statusHistory: [{ fromStatus: null, toStatus: 'PENDING', actorRole: 'EMPLOYEE', actorDisplayName: 'Lee Chen', reason: null, createdAt: '2026-10-24T08:00:00+08:00' }], createdAt: '2026-10-24T08:00:00+08:00',
  };
  let submissionPolls = 0;
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (path.endsWith('/auth/refresh')) return route.fulfill({ status: 401, contentType: 'application/problem+json', body: JSON.stringify({ status: 401, code: 'REFRESH_REQUIRED', detail: 'Sign in to continue.' }) });
    if (path.endsWith('/auth/login') || path.endsWith('/auth/register')) return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ accessToken: 'employee-token', tokenType: 'Bearer', expiresIn: 900, user: { id: 42, email: 'lee@example.com', displayName: 'Lee Chen', role: 'EMPLOYEE' } }) });
    if (path.endsWith('/auth/logout')) return route.fulfill({ status: 204 });
    if (path.endsWith('/app/menu/dishes/14')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ menuItemId: 11, dishId: 14, name: 'Herb chicken bowl', description: 'Roasted chicken with herbs.', imageUrl: '', basePrice: '14.00', category: 'CHINESE', allergens: [], spiceLevel: 'MILD', remainingStock: 8, stockVersion: 1, lowStock: false, soldOut: false, preferenceMatches: [], proteinSource: 'Chicken', optionGroups: [{ id: 4, name: 'Side', selectionType: 'SINGLE', required: true, minSelections: 1, maxSelections: 1, values: [{ id: 5, name: 'Brown rice', priceDelta: '0.00' }] }] }) });
    if (path.endsWith('/app/menu')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ date: '2026-10-25', page: 0, size: 12, totalItems: 1, totalPages: 1, items: [{ menuItemId: 11, dishId: 14, name: 'Herb chicken bowl', description: 'Roasted chicken with herbs.', imageUrl: '', basePrice: '14.00', category: 'CHINESE', allergens: [], spiceLevel: 'MILD', remainingStock: 8, stockVersion: 1, lowStock: false, soldOut: false, preferenceMatches: [] }] }) });
    if (path.endsWith('/app/preferences')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ allergens: [], preferredCategories: [], spiceLevel: null, tasteIntensity: null, budgetMin: null, budgetMax: null }) });
    if (path.endsWith('/app/addresses')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ id: 5, label: 'Office', addressText: '18 Garden Road', isDefault: true }]) });
    if (path.endsWith('/app/orders/quote')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ resolvedDeliveryDate: '2026-10-25', resolvedMealSlot: 'LUNCH', slotAdjusted: false, lines: [{ dishId: 14, dishName: 'Herb chicken bowl', quantity: 1, optionValueIds: [5], unitPrice: '14.00', subtotal: '14.00' }], total: '14.00', warnings: [], unavailableItems: [], existingOrder: null }) });
    if (path.endsWith('/app/orders') && request.method() === 'POST') return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({ submissionId: 'submission-1', state: 'PROCESSING', statusUrl: '/api/v1/app/order-submissions/submission-1' }) });
    if (path.endsWith('/app/order-submissions/submission-1')) {
      submissionPolls += 1;
      const body = submissionPolls < 2 ? { submissionId: 'submission-1', state: 'PROCESSING' } : { submissionId: 'submission-1', state: 'COMPLETED', order: confirmedOrder };
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    }
    if (path.endsWith('/app/orders/181') && request.method() === 'GET') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(confirmedOrder) });
    if (path.endsWith('/app/orders/181/cancel')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...confirmedOrder, status: 'CANCELLED', version: 1 }) });
    return route.fulfill({ status: 404, contentType: 'application/problem+json', body: JSON.stringify({ status: 404, code: 'NOT_FOUND', detail: 'No matching mock endpoint.' }) });
  });

  await page.goto('/register');
  await page.getByLabel('Your name').fill('Lee Chen');
  await page.getByLabel('Email address').fill('lee@example.com');
  await page.getByLabel('Create a password').fill('Lunch123');
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('heading', { name: 'Good afternoon, good food.' })).toBeVisible();
  const menuAccessibility = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  expect(menuAccessibility.violations).toEqual([]);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.getByLabel('Email address').fill('lee@example.com');
  await page.getByLabel('Password').fill('Lunch123');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Good afternoon, good food.' })).toBeVisible();
  await page.getByRole('button', { name: 'Choose meal: Herb chicken bowl' }).click();
  await page.getByRole('radio', { name: /Brown rice/ }).check();
  await page.getByRole('button', { name: 'Add to order' }).click();
  await page.getByRole('link', { name: /Cart/ }).click();
  await page.getByRole('link', { name: /Continue to checkout/ }).click();
  await page.getByRole('button', { name: 'Get order quote' }).click();
  await page.getByRole('button', { name: 'Place order' }).click();
  await expect(page.getByText('Confirming your order…')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Your order is confirmed.' })).toBeVisible({ timeout: 8000 });
  await page.getByRole('link', { name: 'View your order' }).click();
  await page.getByRole('button', { name: 'Cancel order' }).click();
  await expect(page.getByText('Order cancelled.')).toBeVisible();
});
