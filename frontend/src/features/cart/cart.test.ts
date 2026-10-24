import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { addCartItem, useCartStore } from './store';

const makeItem = (overrides: Partial<Parameters<typeof addCartItem>[0]> = {}) => ({
  dishId: 14,
  name: 'Herb chicken bowl',
  imageUrl: '/chicken.jpg',
  basePriceFen: 1250,
  unitPriceFen: 1375,
  optionValueIds: [9, 3],
  optionNames: ['Brown rice', 'Extra chicken'],
  allergens: ['SOY' as const],
  quantity: 1,
  ...overrides,
});

beforeEach(() => {
  useCartStore.persist.clearStorage();
  useCartStore.setState({ userId: null, lines: [] });
});
afterEach(() => {
  useCartStore.persist.clearStorage();
  useCartStore.setState({ userId: null, lines: [] });
});

describe('per-user cart', () => {
  it('merges the same dish configuration and keeps different options separate', () => {
    useCartStore.getState().switchUser(42);
    addCartItem(makeItem());
    addCartItem(makeItem({ optionValueIds: [3, 9] }));
    addCartItem(makeItem({ optionValueIds: [3] }));

    const lines = useCartStore.getState().lines;
    expect(lines).toHaveLength(2);
    expect(lines[0]?.quantity).toBe(2);
    expect(lines[0]?.optionValueIds).toEqual([3, 9]);
  });

  it('caps the order at five servings without partially applying an add', () => {
    useCartStore.getState().switchUser(42);
    expect(addCartItem(makeItem({ quantity: 4 }))).toBe('added');
    expect(addCartItem(makeItem({ quantity: 2 }))).toBe('MAX_ITEMS');
    expect(useCartStore.getState().lines[0]?.quantity).toBe(4);
  });

  it('calculates totals using integer fen', () => {
    useCartStore.getState().switchUser(42);
    addCartItem(makeItem({ quantity: 2, unitPriceFen: 1375 }));
    expect(useCartStore.getState().totalFen()).toBe(2750);
  });

  it('clears the previous user cart when another user signs in', () => {
    useCartStore.getState().switchUser(42);
    addCartItem(makeItem());
    useCartStore.getState().switchUser(9);
    expect(useCartStore.getState().lines).toEqual([]);
    expect(useCartStore.getState().userId).toBe(9);
  });
});
