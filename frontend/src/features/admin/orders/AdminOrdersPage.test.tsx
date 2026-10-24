import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import type { Order } from '../../../api/types';
import { server } from '../../../test/server';
import { AdminOrdersPage } from './AdminOrdersPage';

const order: Order = {
  id: 91, orderNumber: 'WB-20261025-000091', userId: 7, userDisplayName: 'Lee Chen', deliveryDate: '2026-10-25', mealSlot: 'LUNCH', deliveryAddress: 'Office', status: 'PENDING', total: '14.00', version: 5,
  items: [], statusHistory: [{ fromStatus: null, toStatus: 'PENDING', actorRole: 'EMPLOYEE', actorDisplayName: 'Lee Chen', reason: null, createdAt: '2026-10-24T08:00:00+08:00' }], createdAt: '2026-10-24T08:00:00+08:00',
};

afterEach(cleanup);

describe('Admin order transitions', () => {
  it('offers only valid actions and sends the expected version', async () => {
    let transition: unknown;
    let activeOrder = order;
    server.use(
      http.get('*/api/v1/admin/orders', () => HttpResponse.json({ page: 0, size: 20, totalItems: 1, totalPages: 1, items: [activeOrder] })),
      http.post('*/api/v1/admin/orders/91/transitions', async ({ request }) => {
        transition = await request.json();
        activeOrder = { ...order, status: 'CONFIRMED', version: 6 };
        return HttpResponse.json(activeOrder);
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const user = userEvent.setup();
    render(<QueryClientProvider client={client}><MemoryRouter><AdminOrdersPage /></MemoryRouter></QueryClientProvider>);

    expect(await screen.findByRole('button', { name: 'Confirm WB-20261025-000091' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel WB-20261025-000091' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Complete WB-20261025-000091' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Confirm WB-20261025-000091' }));

    expect(await screen.findByText('Confirmed')).toBeInTheDocument();
    expect(transition).toEqual({ targetStatus: 'CONFIRMED', expectedVersion: 5 });
  });
});
