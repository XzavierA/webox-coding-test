import { http, HttpResponse } from 'msw';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { moneyToFen } from '../../shared/money';
import { server } from '../../test/server';
import { useCartStore } from '../cart/store';
import { DishDetailPage } from './DishDetailPage';
import { MenuPage } from './MenuPage';
import type { DishDetail, MenuPage as MenuPageResponse } from '../../api/types';

const menuResponse: MenuPageResponse = {
  date: '2026-10-25', page: 0, size: 20, totalItems: 2, totalPages: 1,
  items: [
    {
      menuItemId: 11, dishId: 51, name: 'Miso salmon bowl', description: 'Miso glazed salmon with greens.',
      imageUrl: '/salmon.jpg', basePrice: '18.50', category: 'JAPANESE', allergens: ['FISH'],
      spiceLevel: 'MILD', remainingStock: 2, stockVersion: 4, lowStock: true, soldOut: false, preferenceMatches: [],
    },
    {
      menuItemId: 12, dishId: 52, name: 'Roasted mushroom plate', description: 'Roasted mushrooms and warm grains.',
      imageUrl: '/mushroom.jpg', basePrice: '14.00', category: 'LIGHT_MEAL', allergens: [],
      spiceLevel: 'NONE', remainingStock: 0, stockVersion: 6, lowStock: false, soldOut: true, preferenceMatches: [],
    },
  ],
};

const dishDetail: DishDetail = {
  ...menuResponse.items[0]!,
  proteinSource: 'Salmon',
  allergens: ['FISH', 'PEANUTS'],
  optionGroups: [{
    id: 8, name: 'Choose a side', selectionType: 'SINGLE', required: true,
    minSelections: 1, maxSelections: 1,
    values: [
      { id: 101, name: 'Steamed rice', priceDelta: '0.00' },
      { id: 102, name: 'Extra greens', priceDelta: '2.25' },
    ],
  }],
};

function renderWithQuery(element: React.ReactNode, path = '/menu') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}>
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/menu" element={element} />
        <Route path="/dishes/:dishId" element={<DishDetailPage />} />
      </Routes>
    </MemoryRouter>
  </QueryClientProvider>);
}

beforeEach(() => {
  useCartStore.getState().logout();
  useCartStore.getState().switchUser(42);
});
afterEach(() => {
  cleanup();
  useCartStore.getState().logout();
});

describe('employee menu and dish configuration', () => {
  it('shows low-stock counts and disables a sold-out dish', async () => {
    server.use(http.get('*/api/v1/app/menu', () => HttpResponse.json(menuResponse)));
    renderWithQuery(<MenuPage />);

    expect(await screen.findByText('Only 2 left')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Choose meal: Miso salmon bowl' })).toBeEnabled();
    expect(screen.getByText('Sold out')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Unavailable: Roasted mushroom plate' })).toBeDisabled();
  });

  it('requires mandatory options and confirms matching allergens before adding', async () => {
    server.use(
      http.get('*/api/v1/app/menu/dishes/51', () => HttpResponse.json(dishDetail)),
      http.get('*/api/v1/app/preferences', () => HttpResponse.json({
        allergens: ['PEANUTS'], preferredCategories: [], spiceLevel: null, tasteIntensity: null,
        budgetMin: null, budgetMax: null,
      })),
    );
    const user = userEvent.setup();
    renderWithQuery(<DishDetailPage />, '/dishes/51');

    expect(await screen.findByRole('heading', { name: 'Miso salmon bowl' })).toBeInTheDocument();
    const addButton = screen.getByRole('button', { name: 'Add to order' });
    expect(addButton).toBeDisabled();
    await user.click(screen.getByRole('radio', { name: /Steamed rice/ }));
    expect(addButton).toBeEnabled();
    await user.click(addButton);

    expect(await screen.findByRole('dialog', { name: 'Allergen match' })).toHaveTextContent('Peanuts');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(useCartStore.getState().lines).toEqual([]);

    await user.click(addButton);
    await user.click(screen.getByRole('button', { name: 'Add anyway' }));
    await waitFor(() => expect(useCartStore.getState().lines).toHaveLength(1));
    expect(useCartStore.getState().lines[0]?.optionValueIds).toEqual([101]);
    expect(useCartStore.getState().lines[0]?.unitPriceFen).toBe(moneyToFen('18.50'));
    expect(screen.getByText('Added to your order.')).toBeInTheDocument();
  });
});
