import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import type { Order } from '../../../api/types';
import { server } from '../../../test/server';
import { AdminOrderDetailPage } from './AdminOrderDetailPage';

const order: Order = {
  id: 91, orderNumber: 'WB-20261025-000091', userId: 7, userDisplayName: 'Lee Chen', deliveryDate: '2026-10-25', mealSlot: 'LUNCH', deliveryAddress: 'Office', status: 'CONFIRMED', total: '14.00', version: 6,
  items: [{ id: 2, dishId: 51, dishName: 'Miso salmon bowl', basePrice: '14.00', unitPrice: '14.00', quantity: 1, subtotal: '14.00', options: [] }],
  statusHistory: [{ fromStatus: 'PENDING', toStatus: 'CONFIRMED', actorRole: 'ADMIN', actorDisplayName: 'Morgan Park', reason: null, createdAt: '2026-10-24T09:00:00+08:00' }], createdAt: '2026-10-24T08:00:00+08:00',
};

afterEach(cleanup);

describe('Admin order details', () => {
  it('shows the full order and completes only a confirmed order using its current version', async () => {
    let transition: unknown;
    server.use(
      http.get('*/api/v1/admin/orders/91', () => HttpResponse.json(order)),
      http.post('*/api/v1/admin/orders/91/transitions', async ({ request }) => {
        transition = await request.json();
        return HttpResponse.json({ ...order, status: 'COMPLETED', version: 7 });
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const user = userEvent.setup();
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/console/orders/91']}>
      <Routes><Route path="/console/orders/:orderId" element={<AdminOrderDetailPage />} /></Routes>
    </MemoryRouter></QueryClientProvider>);

    expect(await screen.findByRole('heading', { name: 'Miso salmon bowl × 1' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirm order' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Complete order' }));

    expect(await screen.findByText('Order marked complete.')).toBeInTheDocument();
    expect(transition).toEqual({ targetStatus: 'COMPLETED', expectedVersion: 6 });
  });
});
