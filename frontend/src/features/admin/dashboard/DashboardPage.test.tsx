import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { server } from '../../../test/server';
import { DashboardPage } from './DashboardPage';

afterEach(cleanup);

describe('Admin dashboard', () => {
  it('shows live order metrics, low stock, chart data table, and manual refresh', async () => {
    let requests = 0;
    server.use(http.get('*/api/v1/admin/dashboard', () => {
      requests += 1;
      return HttpResponse.json({
        asOf: '2026-10-24T09:00:00+08:00', date: '2026-10-24',
        overview: { totalOrders: 4, revenue: '80.00', completed: 2, pending: 1, confirmed: 1, cancelled: 0 },
        topDishes: [{ dishId: 1, name: 'Miso salmon bowl', quantity: 3 }],
        mealDistribution: [{ mealSlot: 'LUNCH', orders: 3 }, { mealSlot: 'DINNER', orders: 1 }],
        trend: Array.from({ length: 7 }, (_, index) => ({ date: `2026-10-${String(index + 18).padStart(2, '0')}`, orders: index, revenue: '12.00' })),
        lowStock: [{ dishId: 1, name: 'Miso salmon bowl', remainingStock: 2 }],
      });
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<QueryClientProvider client={client}><MemoryRouter><DashboardPage /></MemoryRouter></QueryClientProvider>);

    expect((await screen.findAllByText('Miso salmon bowl')).length).toBeGreaterThan(0);
    expect(screen.getByText('¥80.00')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Seven-day order and revenue trend' })).toBeInTheDocument();
    const before = requests;
    await user.click(screen.getByRole('button', { name: 'Refresh dashboard' }));
    expect(requests).toBeGreaterThan(before);
  });
});
