import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import type { AdminDish } from '../../../api/types';
import { server } from '../../../test/server';
import { CatalogPage } from './CatalogPage';

const dish: AdminDish = {
  id: 51, name: 'Miso salmon bowl', description: 'Miso salmon with greens.', basePrice: '18.50', category: 'JAPANESE',
  proteinSource: 'Salmon', spiceLevel: 'MILD', allergens: ['FISH'], imageUrl: '/salmon.jpg', published: false, version: 7, optionGroups: [],
};

function renderCatalog() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter><CatalogPage /></MemoryRouter></QueryClientProvider>);
}

afterEach(cleanup);

describe('Admin catalog', () => {
  it('loads server-paged dishes and publishes with the expected version', async () => {
    let publication: unknown;
    let currentDish = dish;
    server.use(
      http.get('*/api/v1/admin/dishes', () => HttpResponse.json({ page: 0, size: 20, totalItems: 1, totalPages: 1, items: [currentDish] })),
      http.patch('*/api/v1/admin/dishes/51/publication', async ({ request }) => {
        publication = await request.json();
        currentDish = { ...dish, published: true, version: 8 };
        return HttpResponse.json(currentDish);
      }),
    );
    const user = userEvent.setup();
    renderCatalog();

    expect(await screen.findByText('Miso salmon bowl')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Publish Miso salmon bowl' }));
    expect(await screen.findByText('Published', { selector: '.status-pill' })).toBeInTheDocument();
    expect(publication).toEqual({ published: true, expectedVersion: 7 });
  });

  it('rejects malformed option groups before submitting a dish', async () => {
    server.use(http.get('*/api/v1/admin/dishes', () => HttpResponse.json({ page: 0, size: 20, totalItems: 0, totalPages: 0, items: [] })));
    const user = userEvent.setup();
    renderCatalog();
    await user.click(await screen.findByRole('button', { name: 'Add dish' }));
    await user.type(screen.getByLabelText('Dish name'), 'New meal');
    await user.type(screen.getByLabelText('Description'), 'A fresh meal');
    const basePrice = screen.getByLabelText('Base price');
    await user.clear(basePrice);
    await user.type(basePrice, '12.50');
    await user.type(screen.getByLabelText('Protein source'), 'Tofu');
    await user.type(screen.getByLabelText('Image URL'), '/meal.jpg');
    fireEvent.change(screen.getByLabelText('Option groups JSON'), { target: { value: '{broken' } });
    await user.click(screen.getByRole('button', { name: 'Save dish' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Enter valid option groups JSON.');
  });

  it('rejects option groups whose required selection count exceeds the maximum', async () => {
    server.use(http.get('*/api/v1/admin/dishes', () => HttpResponse.json({ page: 0, size: 20, totalItems: 0, totalPages: 0, items: [] })));
    const user = userEvent.setup();
    renderCatalog();
    await user.click(await screen.findByRole('button', { name: 'Add dish' }));
    await user.type(screen.getByLabelText('Dish name'), 'New meal');
    await user.type(screen.getByLabelText('Description'), 'A fresh meal');
    const basePrice = screen.getByLabelText('Base price');
    await user.clear(basePrice);
    await user.type(basePrice, '12.50');
    await user.type(screen.getByLabelText('Protein source'), 'Tofu');
    await user.type(screen.getByLabelText('Image URL'), '/meal.jpg');
    fireEvent.change(screen.getByLabelText('Option groups JSON'), { target: { value: JSON.stringify([{
      id: null, name: 'Choose a side', selectionType: 'SINGLE', required: true, minSelections: 2, maxSelections: 1,
      displayOrder: 0, active: true, values: [{ id: null, name: 'Brown rice', priceDelta: '0.00', displayOrder: 0, active: true }],
    }]) } });
    await user.click(screen.getByRole('button', { name: 'Save dish' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Each option group needs valid selection limits and at least one valid option value.');
  });
});
