import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { server } from '../../../test/server';
import { DailyMenuPage } from './DailyMenuPage';

afterEach(cleanup);

describe('daily menu editor', () => {
  it('edits today’s stock and saves using the publication version', async () => {
    let write: unknown;
    server.use(
      http.get('*/api/v1/admin/menus/:date', ({ params }) => HttpResponse.json({ date: params.date, status: 'DRAFT', publicationVersion: 4, items: [{ menuItemId: 1, dishId: 51, dishName: 'Miso salmon bowl', initialStock: 12, remainingStock: 10, visible: true, stockVersion: 6 }] })),
      http.get('*/api/v1/admin/dishes', () => HttpResponse.json({ page: 0, size: 100, totalItems: 1, totalPages: 1, items: [{ id: 51, name: 'Miso salmon bowl', description: 'Fresh', basePrice: '18.50', category: 'JAPANESE', proteinSource: 'Salmon', spiceLevel: 'MILD', allergens: [], imageUrl: '/meal.jpg', published: true, version: 4, optionGroups: [] }] })),
      http.put('*/api/v1/admin/menus/2026-10-24', async ({ request }) => { write = await request.json(); return HttpResponse.json({ date: '2026-10-24', status: 'DRAFT', publicationVersion: 5, items: [] }); }),
    );
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter><DailyMenuPage /></MemoryRouter></QueryClientProvider>);

    fireEvent.change(await screen.findByLabelText('Menu date'), { target: { value: '2026-10-24' } });
    const stock = await screen.findByLabelText('Initial stock for Miso salmon bowl');
    await user.clear(stock);
    await user.type(stock, '15');
    await user.click(screen.getByRole('button', { name: 'Save menu' }));

    expect(await screen.findByText('Daily menu saved.')).toBeInTheDocument();
    expect(write).toEqual({ expectedPublicationVersion: 4, items: [{ dishId: 51, initialStock: 15, visible: true }] });
  });
});
