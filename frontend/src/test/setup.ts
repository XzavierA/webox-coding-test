import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, beforeEach } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from './server';

const storageValues = new Map<string, string>();
const testStorage: Storage = {
  get length() { return storageValues.size; },
  clear: () => storageValues.clear(),
  getItem: (key) => storageValues.get(key) ?? null,
  key: (index) => [...storageValues.keys()][index] ?? null,
  removeItem: (key) => storageValues.delete(key),
  setItem: (key, value) => storageValues.set(key, String(value)),
};
Object.defineProperty(window, 'localStorage', { configurable: true, value: testStorage });
Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: testStorage });

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => {
  server.use(
    http.post('*/api/v1/auth/refresh', () => HttpResponse.json({
      type: 'about:blank', title: 'Unauthorized', status: 401,
      code: 'REFRESH_REQUIRED', detail: 'A session could not be restored.', correlationId: 'test-refresh',
    }, { status: 401, headers: { 'Content-Type': 'application/problem+json' } })),
    http.get('*/api/v1/app/menu/stock-stream', () => new HttpResponse('', { headers: { 'Content-Type': 'text/event-stream' } })),
  );
});
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => server.close());
