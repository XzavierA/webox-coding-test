import type { DishCard } from './types';
import { bootstrapRefresh, getAccessToken } from './client';

export interface StockChangedEvent {
  date: string;
  menuItemId: number;
  remainingStock: number;
  stockVersion: number;
}

interface StockFrame { event: string; data: string }

export function applyStockChange(dishes: DishCard[], currentDate: string, event: StockChangedEvent): DishCard[] {
  if (event.date !== currentDate || !Number.isInteger(event.stockVersion) || !Number.isInteger(event.remainingStock)) return dishes;
  const index = dishes.findIndex((dish) => dish.menuItemId === event.menuItemId);
  if (index < 0 || event.stockVersion <= dishes[index]!.stockVersion) return dishes;
  const updated = [...dishes];
  updated[index] = {
    ...dishes[index]!,
    remainingStock: Math.max(0, event.remainingStock),
    stockVersion: event.stockVersion,
    lowStock: event.remainingStock > 0 && event.remainingStock <= 3,
    soldOut: event.remainingStock <= 0,
  };
  return updated;
}

export function parseStockFrames(input: string, buffer: string): { events: StockFrame[]; remainder: string } {
  const parts = `${buffer}${input}`.replaceAll('\r\n', '\n').split('\n\n');
  const remainder = parts.pop() ?? '';
  const events = parts.flatMap((frame) => {
    let event = 'message';
    const data: string[] = [];
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
    }
    return data.length ? [{ event, data: data.join('\n') }] : [];
  });
  return { events, remainder };
}

export async function connectStockStream(
  date: string,
  signal: AbortSignal,
  onStock: (event: StockChangedEvent) => void,
  onResync: () => void,
): Promise<void> {
  const token = getAccessToken();
  if (!token) return;
  let fetchSignal: AbortSignal | undefined = signal;
  try { new Request('http://localhost/', { signal }); }
  catch { fetchSignal = undefined; }
  const response = await fetch(`/api/v1/app/menu/stock-stream?date=${encodeURIComponent(date)}`, {
    headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
    signal: fetchSignal,
  });
  if (response.status === 401) await bootstrapRefresh();
  if (!response.ok || !response.body) throw new Error(`Stock stream returned ${response.status}`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  const cancelReader = () => { void reader.cancel(); };
  signal.addEventListener('abort', cancelReader, { once: true });
  try {
    while (!signal.aborted) {
      const chunk = await reader.read();
      if (chunk.done) return;
      const parsed = parseStockFrames(decoder.decode(chunk.value, { stream: true }), buffer);
      buffer = parsed.remainder;
      for (const frame of parsed.events) {
        if (frame.event === 'resync.required') onResync();
        if (frame.event !== 'stock.changed') continue;
        try {
          const event = JSON.parse(frame.data) as Partial<StockChangedEvent>;
          if (event.date && Number.isInteger(event.menuItemId) && Number.isInteger(event.remainingStock) && Number.isInteger(event.stockVersion)) {
            onStock(event as StockChangedEvent);
          }
        } catch { /* Ignore malformed advisory events and let the menu revalidate. */ }
      }
    }
  } finally {
    signal.removeEventListener('abort', cancelReader);
    await reader.cancel().catch(() => undefined);
  }
}

export async function startStockStream(
  date: string,
  signal: AbortSignal,
  onStock: (event: StockChangedEvent) => void,
  onReconnect: () => void,
): Promise<void> {
  let attempt = 0;
  while (!signal.aborted) {
    try {
      await connectStockStream(date, signal, onStock, onReconnect);
      if (signal.aborted) return;
      attempt += 1;
      onReconnect();
    } catch {
      if (signal.aborted) return;
      attempt += 1;
      if (attempt > 1) onReconnect();
    }
    const delay = Math.min(30_000, 500 * (2 ** Math.min(attempt - 1, 6)));
    await new Promise<void>((resolve) => {
      const finish = () => {
        window.clearTimeout(timer);
        signal.removeEventListener('abort', finish);
        resolve();
      };
      const timer = window.setTimeout(finish, delay);
      if (signal.aborted) finish();
      else signal.addEventListener('abort', finish, { once: true });
    });
  }
}
