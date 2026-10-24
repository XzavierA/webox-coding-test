import { describe, expect, it } from 'vitest';
import type { DishCard } from './types';
import { http, HttpResponse } from 'msw';
import { clearAccessToken, setAccessToken } from './client';
import { applyStockChange, connectStockStream, parseStockFrames } from './sse';
import { server } from '../test/server';

const dishes: DishCard[] = [
  { menuItemId: 11, dishId: 51, name: 'Salmon bowl', description: '', imageUrl: '', basePrice: '18.50', category: 'JAPANESE', allergens: [], spiceLevel: 'MILD', remainingStock: 5, stockVersion: 4, lowStock: false, soldOut: false, preferenceMatches: [] },
  { menuItemId: 12, dishId: 52, name: 'Mushroom plate', description: '', imageUrl: '', basePrice: '14.00', category: 'LIGHT_MEAL', allergens: [], spiceLevel: 'NONE', remainingStock: 3, stockVersion: 9, lowStock: true, soldOut: false, preferenceMatches: [] },
];

describe('stock stream event handling', () => {
  it('applies only newer stock versions and disables a dish when it sells out', () => {
    const stale = applyStockChange(dishes, '2026-10-25', { date: '2026-10-25', menuItemId: 11, remainingStock: 2, stockVersion: 3 });
    expect(stale).toBe(dishes);
    const updated = applyStockChange(dishes, '2026-10-25', { date: '2026-10-25', menuItemId: 11, remainingStock: 0, stockVersion: 5 });
    expect(updated[0]).toMatchObject({ remainingStock: 0, stockVersion: 5, soldOut: true, lowStock: false });
    expect(updated[1]).toBe(dishes[1]);
  });

  it('parses complete frames while retaining a partial event for the next chunk', () => {
    const first = parseStockFrames('event: stock.changed\ndata: {"date":"2026-10-25"}\n\nevent: stock.ch', '');
    expect(first.events).toEqual([{ event: 'stock.changed', data: '{"date":"2026-10-25"}' }]);
    expect(first.remainder).toBe('event: stock.ch');
    const second = parseStockFrames('anged\ndata: {"menuItemId":11}\n\n', first.remainder);
    expect(second.events).toEqual([{ event: 'stock.changed', data: '{"menuItemId":11}' }]);
    expect(second.remainder).toBe('');
  });

  it('opens the stock feed with the memory bearer token and delivers stock changes', async () => {
    let authorization = '';
    server.use(http.get('*/api/v1/app/menu/stock-stream', ({ request }) => {
      authorization = request.headers.get('authorization') ?? '';
      return new HttpResponse('event: stock.changed\ndata: {"date":"2026-10-25","menuItemId":11,"remainingStock":2,"stockVersion":5}\n\n', { headers: { 'Content-Type': 'text/event-stream' } });
    }));
    setAccessToken('stream-access-token');
    const received: Array<{ menuItemId: number; remainingStock: number; stockVersion: number }> = [];
    await connectStockStream('2026-10-25', new AbortController().signal, (event) => received.push(event), () => undefined);

    expect(authorization).toBe('Bearer stream-access-token');
    expect(received).toEqual([{ date: '2026-10-25', menuItemId: 11, remainingStock: 2, stockVersion: 5 }]);
    clearAccessToken();
  });
});
