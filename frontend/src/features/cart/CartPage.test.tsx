import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { addCartItem, useCartStore } from './store';
import { CartPage } from './CartPage';

beforeEach(() => {
  useCartStore.getState().logout();
  useCartStore.getState().switchUser(42);
});
afterEach(() => {
  cleanup();
  useCartStore.getState().logout();
});

describe('cart page', () => {
  it('updates quantities and keeps line and order totals in integer fen', async () => {
    addCartItem({
      dishId: 14, name: 'Herb chicken bowl', imageUrl: '/chicken.jpg', basePriceFen: 1250,
      unitPriceFen: 1375, optionValueIds: [3], optionNames: ['Brown rice'], allergens: ['SOY'], quantity: 1,
    });
    const user = userEvent.setup();
    render(<MemoryRouter><CartPage /></MemoryRouter>);

    expect(screen.getAllByText('¥13.75')).toHaveLength(3);
    await user.click(screen.getByRole('button', { name: 'Increase Herb chicken bowl quantity' }));
    expect(within(screen.getByLabelText('Order summary')).getByText('¥27.50', { selector: 'strong' })).toBeInTheDocument();
    expect(screen.getByText('2 items selected')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Decrease Herb chicken bowl quantity' }));
    expect(within(screen.getByLabelText('Order summary')).getByText('¥13.75', { selector: 'strong' })).toBeInTheDocument();
  });

  it('removes a configured line and offers a menu link for an empty cart', async () => {
    addCartItem({
      dishId: 14, name: 'Herb chicken bowl', imageUrl: '/chicken.jpg', basePriceFen: 1250,
      unitPriceFen: 1375, optionValueIds: [3], optionNames: ['Brown rice'], allergens: [], quantity: 1,
    });
    const user = userEvent.setup();
    render(<MemoryRouter><CartPage /></MemoryRouter>);
    await user.click(screen.getByRole('button', { name: 'Remove Herb chicken bowl from order' }));

    expect(screen.getByRole('heading', { name: 'Your order is ready when you are.' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Explore the menu' })).toHaveAttribute('href', '/menu');
    expect(useCartStore.getState().lines).toEqual([]);
  });
});
