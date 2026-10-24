import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import type { Order } from '../../api/types';
import { server } from '../../test/server';
import { OrderDetailPage } from './OrderDetailPage';
import { OrdersPage } from './OrdersPage';

const order: Order = {
  id: 81, orderNumber: 'WB-20261025-000081', userId: 7, userDisplayName: 'Lee Chen',
  deliveryDate: '2026-10-25', mealSlot: 'LUNCH', deliveryAddress: 'Office · 18 Garden Road',
  status: 'PENDING', total: '14.00', version: 3, items: [],
  statusHistory: [{ fromStatus: null, toStatus: 'PENDING', actorRole: 'EMPLOYEE', actorDisplayName: 'Lee Chen', reason: null, createdAt: '2026-10-24T08:00:00+08:00' }],
  createdAt: '2026-10-24T08:00:00+08:00',
};

function renderPage(page: React.ReactNode, path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>
    <Routes>
      <Route path="/orders" element={page} />
      <Route path="/orders/:orderId" element={<OrderDetailPage />} />
    </Routes>
  </MemoryRouter></QueryClientProvider>);
}

afterEach(cleanup);

describe('employee order history', () => {
  it('lists orders with status filtering and links to the order detail', async () => {
    let requestedUrl = '';
    server.use(http.get('*/api/v1/app/orders', ({ request }) => {
      requestedUrl = request.url;
      return HttpResponse.json({ page: 0, size: 20, totalItems: 1, totalPages: 1, items: [order] });
    }));
    renderPage(<OrdersPage />, '/orders');

    expect(await screen.findByRole('link', { name: /WB-20261025-000081/ })).toHaveAttribute('href', '/orders/81');
    expect(new URL(requestedUrl).searchParams.get('page')).toBe('0');
  });

  it('cancels only pending orders with the displayed version and refreshes the detail', async () => {
    let transitionBody: unknown;
    server.use(
      http.get('*/api/v1/app/orders/81', () => HttpResponse.json(order)),
      http.post('*/api/v1/app/orders/81/cancel', async ({ request }) => {
        transitionBody = await request.json();
        return HttpResponse.json({ ...order, status: 'CANCELLED', version: 4 });
      }),
    );
    const user = userEvent.setup();
    renderPage(<OrderDetailPage />, '/orders/81');

    await user.click(await screen.findByRole('button', { name: 'Cancel order' }));
    expect(await screen.findByText('Order cancelled.')).toBeInTheDocument();
    expect(transitionBody).toEqual({ expectedVersion: 3 });
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument();
  });
});
