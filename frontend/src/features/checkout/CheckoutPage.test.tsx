import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { Order } from '../../api/types';
import { clearAccessToken, setAccessToken } from '../../api/client';
import { server } from '../../test/server';
import { addCartItem, useCartStore } from '../cart/store';
import { CheckoutPage } from './CheckoutPage';

const order: Order = {
  id: 181, orderNumber: 'WB-20261025-000181', userId: 42, userDisplayName: 'Lee Chen',
  deliveryDate: '2026-10-25', mealSlot: 'DINNER', deliveryAddress: 'Office · 18 Garden Road',
  status: 'PENDING', total: '14.00', version: 0,
  items: [{ id: 1, dishId: 14, dishName: 'Herb chicken bowl', basePrice: '14.00', unitPrice: '14.00', quantity: 1, subtotal: '14.00', options: [] }],
  statusHistory: [{ fromStatus: null, toStatus: 'PENDING', actorRole: 'EMPLOYEE', actorDisplayName: 'Lee Chen', reason: null, createdAt: '2026-10-24T08:00:00+08:00' }],
  createdAt: '2026-10-24T08:00:00+08:00',
};

function renderCheckout() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter><CheckoutPage /></MemoryRouter></QueryClientProvider>);
}

function prepareCart() {
  useCartStore.getState().switchUser(42);
  addCartItem({ dishId: 14, name: 'Herb chicken bowl', imageUrl: '/chicken.jpg', basePriceFen: 1400,
    unitPriceFen: 1400, optionValueIds: [], optionNames: [], allergens: [], quantity: 1 });
}

function profileHandlers() {
  server.use(
    http.get('*/api/v1/app/addresses', () => HttpResponse.json([{ id: 5, label: 'Office', addressText: '18 Garden Road', isDefault: true }])),
    http.get('*/api/v1/app/preferences', () => HttpResponse.json({
      allergens: [], preferredCategories: [], spiceLevel: null, tasteIntensity: null, budgetMin: null, budgetMax: null,
    })),
  );
}

beforeEach(() => {
  window.sessionStorage.clear();
  clearAccessToken();
  useCartStore.getState().logout();
});
afterEach(() => {
  cleanup();
  clearAccessToken();
  useCartStore.getState().logout();
});

describe('checkout and asynchronous order confirmation', () => {
  it('requires accepting an adjusted slot and clears the cart only after a confirmed order', async () => {
    let submitted: unknown;
    let idempotencyKey = '';
    profileHandlers();
    server.use(
      http.post('*/api/v1/app/orders/quote', () => HttpResponse.json({
        resolvedDeliveryDate: '2026-10-25', resolvedMealSlot: 'DINNER', slotAdjusted: true,
        lines: [{ dishId: 14, dishName: 'Herb chicken bowl', quantity: 1, optionValueIds: [], unitPrice: '14.00', subtotal: '14.00' }],
        total: '14.00', warnings: ['SLOT_ADJUSTED'], unavailableItems: [], existingOrder: null,
      })),
      http.post('*/api/v1/app/orders', async ({ request }) => {
        submitted = await request.json();
        idempotencyKey = request.headers.get('Idempotency-Key') ?? '';
        return HttpResponse.json(order, { status: 201 });
      }),
    );
    prepareCart();
    const user = userEvent.setup();
    renderCheckout();

    await user.click(await screen.findByRole('button', { name: 'Get order quote' }));
    expect(await screen.findByText('The requested time has changed to October 25, 2026 at Dinner.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Place order' })).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: 'Accept the adjusted delivery time' }));
    await user.click(screen.getByRole('button', { name: 'Place order' }));

    expect(await screen.findByText('Your order is confirmed.')).toBeInTheDocument();
    expect(submitted).toMatchObject({ acceptedResolvedDeliveryDate: '2026-10-25', acceptedResolvedMealSlot: 'DINNER' });
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/i);
    expect(useCartStore.getState().lines).toEqual([]);
  });

  it('keeps cart lines while a 202 submission is processing and completes only after polling', async () => {
    let polls = 0;
    profileHandlers();
    server.use(
      http.post('*/api/v1/app/orders/quote', () => HttpResponse.json({
        resolvedDeliveryDate: '2026-10-25', resolvedMealSlot: 'LUNCH', slotAdjusted: false,
        lines: [{ dishId: 14, dishName: 'Herb chicken bowl', quantity: 1, optionValueIds: [], unitPrice: '14.00', subtotal: '14.00' }],
        total: '14.00', warnings: [], unavailableItems: [], existingOrder: null,
      })),
      http.post('*/api/v1/app/orders', () => HttpResponse.json({ submissionId: 'submission-1', state: 'PROCESSING', statusUrl: '/api/v1/app/order-submissions/submission-1' }, { status: 202 })),
      http.get('*/api/v1/app/order-submissions/submission-1', () => {
        polls += 1;
        return HttpResponse.json(polls < 2
          ? { submissionId: 'submission-1', state: 'PROCESSING' }
          : { submissionId: 'submission-1', state: 'COMPLETED', order });
      }),
    );
    prepareCart();
    const user = userEvent.setup();
    renderCheckout();
    await user.click(await screen.findByRole('button', { name: 'Get order quote' }));
    await user.click(await screen.findByRole('button', { name: 'Place order' }));

    expect(await screen.findByText('Confirming your order…')).toBeInTheDocument();
    expect(useCartStore.getState().lines).toHaveLength(1);
    expect(await screen.findByText('Your order is confirmed.', {}, { timeout: 5000 })).toBeInTheDocument();
    expect(polls).toBeGreaterThanOrEqual(2);
    expect(useCartStore.getState().lines).toEqual([]);
  });

  it('sends the bearer token and durable idempotency key with order placement', async () => {
    let authorization = '';
    let key = '';
    profileHandlers();
    server.use(
      http.post('*/api/v1/app/orders/quote', () => HttpResponse.json({
        resolvedDeliveryDate: '2026-10-25', resolvedMealSlot: 'LUNCH', slotAdjusted: false,
        lines: [], total: '14.00', warnings: [], unavailableItems: [], existingOrder: null,
      })),
      http.post('*/api/v1/app/orders', ({ request }) => {
        authorization = request.headers.get('authorization') ?? '';
        key = request.headers.get('Idempotency-Key') ?? '';
        return HttpResponse.json(order, { status: 201 });
      }),
    );
    setAccessToken('employee-access-token');
    prepareCart();
    const user = userEvent.setup();
    renderCheckout();

    await user.click(await screen.findByRole('button', { name: 'Get order quote' }));
    await user.click(await screen.findByRole('button', { name: 'Place order' }));

    expect(await screen.findByText('Your order is confirmed.')).toBeInTheDocument();
    expect(authorization).toBe('Bearer employee-access-token');
    expect(key).toMatch(/^[0-9a-f-]{36}$/i);
  });
});
