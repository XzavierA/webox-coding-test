import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

test('administrator reviews the dashboard, edits a menu, and transitions an order', async ({ page }) => {
  let dishes: Array<Record<string, unknown>> = [];
  let orderStatus = 'PENDING';
  const order = {
    id: 91, orderNumber: 'WB-20261025-000091', userId: 42, userDisplayName: 'Lee Chen', deliveryDate: '2026-10-25', mealSlot: 'LUNCH',
    deliveryAddress: 'Office', status: 'PENDING', total: '12.50', version: 5, items: [],
    statusHistory: [{ fromStatus: null, toStatus: 'PENDING', actorRole: 'EMPLOYEE', actorDisplayName: 'Lee Chen', reason: null, createdAt: '2026-10-24T08:00:00+08:00' }],
    createdAt: '2026-10-24T08:00:00+08:00',
  };
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (path.endsWith('/auth/refresh')) return route.fulfill({ status: 401, contentType: 'application/problem+json', body: JSON.stringify({ status: 401, code: 'REFRESH_REQUIRED', detail: 'Sign in to continue.' }) });
    if (path.endsWith('/auth/login')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ accessToken: 'admin-token', tokenType: 'Bearer', expiresIn: 900, user: { id: 2, email: 'admin@example.com', displayName: 'Morgan Park', role: 'ADMIN' } }) });
    if (path.endsWith('/admin/dashboard')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ asOf: '2026-10-24T09:00:00+08:00', date: '2026-10-24', overview: { totalOrders: 0, revenue: '0.00', completed: 0, pending: 0, confirmed: 0, cancelled: 0 }, topDishes: [], mealDistribution: [], trend: Array.from({ length: 7 }, (_, index) => ({ date: `2026-10-${String(index + 18).padStart(2, '0')}`, orders: 0, revenue: '0.00' })), lowStock: [] }) });
    if (path.endsWith('/admin/dishes') && request.method() === 'GET') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ page: 0, size: 20, totalItems: dishes.length, totalPages: dishes.length ? 1 : 0, items: dishes }) });
    if (path.endsWith('/admin/dishes') && request.method() === 'POST') {
      const body = await request.postDataJSON() as Record<string, unknown>;
      const created = { ...body, id: 31 };
      dishes = [created];
      return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(created) });
    }
    if (path.includes('/admin/menus/') && path.endsWith('/publish')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ date: path.split('/').at(-2), status: 'PUBLISHED', publicationVersion: 6, items: [] }) });
    if (path.includes('/admin/menus/') && request.method() === 'GET') {
      const menuDate = path.split('/').at(-1);
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ date: menuDate, status: 'DRAFT', publicationVersion: 5, items: [{ menuItemId: 301, dishId: 31, dishName: 'Ginger tofu bowl', initialStock: 15, remainingStock: 15, visible: true, stockVersion: 0 }] }) });
    }
    if (path.includes('/admin/menus/') && request.method() === 'PUT') {
      const menuDate = path.split('/').at(-1);
      const body = await request.postDataJSON() as { items: Array<{ dishId: number; initialStock: number; visible: boolean }> };
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ date: menuDate, status: 'DRAFT', publicationVersion: 6, items: body.items.map((item, index) => ({ menuItemId: index + 1, dishId: item.dishId, dishName: 'Ginger tofu bowl', initialStock: item.initialStock, remainingStock: item.initialStock, visible: item.visible, stockVersion: 0 })) }) });
    }
    if (path.endsWith('/admin/orders') && request.method() === 'GET') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ page: 0, size: 20, totalItems: 1, totalPages: 1, items: [{ ...order, status: orderStatus }] }) });
    if (path.endsWith('/admin/orders/91/transitions')) {
      orderStatus = 'CONFIRMED';
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...order, status: orderStatus, version: 6 }) });
    }
    return route.fulfill({ status: 404, contentType: 'application/problem+json', body: JSON.stringify({ status: 404, code: 'NOT_FOUND', detail: 'No matching mock endpoint.' }) });
  });

  await page.goto('/login');
  await page.getByLabel('Email address').fill('admin@example.com');
  await page.getByLabel('Password').fill('Lunch123');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Good work, in view.' })).toBeVisible();
  const dashboardAccessibility = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  expect(dashboardAccessibility.violations).toEqual([]);
  await page.getByRole('link', { name: 'Dishes' }).click();
  await page.getByRole('button', { name: 'Add dish' }).click();
  await page.getByLabel('Dish name').fill('Ginger tofu bowl');
  await page.getByLabel('Description').fill('Golden tofu with crisp greens.');
  await page.getByLabel('Base price').fill('12.50');
  await page.getByLabel('Protein source').fill('Tofu');
  await page.getByLabel('Image URL').fill('/tofu.jpg');
  await page.getByLabel('Publish this dish').check();
  await page.getByRole('button', { name: 'Save dish' }).click();
  await expect(page.getByText('Ginger tofu bowl')).toBeVisible();
  await page.getByRole('link', { name: 'Daily menu' }).click();
  await page.getByLabel('Initial stock for Ginger tofu bowl').fill('18');
  await page.getByRole('button', { name: 'Save menu' }).click();
  await expect(page.getByRole('status')).toHaveText('Daily menu saved.');
  await page.getByRole('button', { name: 'Publish menu' }).click();
  await expect(page.getByRole('status')).toHaveText('Daily menu published.');
  await page.getByRole('link', { name: 'Orders' }).click();
  await page.getByRole('button', { name: 'Confirm WB-20261025-000091' }).click();
  await expect(page.getByText('WB-20261025-000091 is now confirmed.')).toBeVisible();
  await page.getByRole('link', { name: 'Overview' }).click();
  await expect(page.getByRole('heading', { name: 'Good work, in view.' })).toBeVisible();
});
