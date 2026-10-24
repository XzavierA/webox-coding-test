import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { Allergen } from '../../api/types';

export interface CartItemInput {
  dishId: number;
  name: string;
  imageUrl: string;
  basePriceFen: number;
  unitPriceFen: number;
  optionValueIds: number[];
  optionNames: string[];
  allergens: Allergen[];
  quantity: number;
}

export interface CartLine extends Omit<CartItemInput, 'optionValueIds'> {
  identity: string;
  optionValueIds: number[];
}

interface CartState {
  userId: number | null;
  lines: CartLine[];
  switchUser: (userId: number) => void;
  logout: () => void;
  addItem: (item: CartItemInput) => 'added' | 'MAX_ITEMS';
  setQuantity: (identity: string, quantity: number) => boolean;
  removeItem: (identity: string) => void;
  clear: () => void;
  totalFen: () => number;
  itemCount: () => number;
}

export function cartIdentity(dishId: number, optionValueIds: number[]): string {
  return `${dishId}:${[...new Set(optionValueIds)].sort((left, right) => left - right).join(',')}`;
}

export const useCartStore = create<CartState>()(persist((set, get) => ({
  userId: null,
  lines: [],
  switchUser: (userId) => set((state) => state.userId === userId
    ? state
    : { userId, lines: [] }),
  logout: () => set({ userId: null, lines: [] }),
  addItem: (item) => {
    if (!Number.isInteger(item.quantity) || item.quantity < 1 || get().itemCount() + item.quantity > 5) {
      return 'MAX_ITEMS';
    }
    const sortedIds = [...new Set(item.optionValueIds)].sort((left, right) => left - right);
    const identity = cartIdentity(item.dishId, sortedIds);
    set((state) => {
      const matching = state.lines.find((line) => line.identity === identity);
      if (matching) {
        return {
          lines: state.lines.map((line) => line.identity === identity
            ? { ...line, quantity: line.quantity + item.quantity }
            : line),
        };
      }
      return { lines: [...state.lines, { ...item, optionValueIds: sortedIds, identity }] };
    });
    return 'added';
  },
  setQuantity: (identity, quantity) => {
    const state = get();
    const target = state.lines.find((line) => line.identity === identity);
    if (!target || !Number.isInteger(quantity) || quantity < 1) return false;
    const otherCount = state.itemCount() - target.quantity;
    if (otherCount + quantity > 5) return false;
    set((current) => ({
      lines: current.lines.map((line) => line.identity === identity ? { ...line, quantity } : line),
    }));
    return true;
  },
  removeItem: (identity) => set((state) => ({ lines: state.lines.filter((line) => line.identity !== identity) })),
  clear: () => set({ lines: [] }),
  totalFen: () => get().lines.reduce((total, line) => total + line.unitPriceFen * line.quantity, 0),
  itemCount: () => get().lines.reduce((total, line) => total + line.quantity, 0),
}), {
  name: 'webox-cart',
  version: 1,
  storage: createJSONStorage(() => window.localStorage),
  partialize: (state) => ({ userId: state.userId, lines: state.lines }),
}));

export function addCartItem(item: CartItemInput): 'added' | 'MAX_ITEMS' {
  return useCartStore.getState().addItem(item);
}
