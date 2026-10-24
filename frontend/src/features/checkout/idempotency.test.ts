import { describe, expect, it } from 'vitest';
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from './idempotency';

function memoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() { return values.size; },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => [...values.keys()][index] ?? null,
    removeItem: (key) => values.delete(key),
    setItem: (key, value) => values.set(key, String(value)),
  };
}

describe('order idempotency key lifecycle', () => {
  it('reuses the same UUID for an unchanged request retry', () => {
    const storage = memoryStorage();
    let sequence = 0;
    const createKey = () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`;

    const first = getOrCreateIdempotencyKey(storage, '{"total":2250}', createKey);
    const retry = getOrCreateIdempotencyKey(storage, '{"total":2250}', createKey);
    expect(retry).toBe(first);
    expect(sequence).toBe(1);
  });

  it('creates a new key when the quoted request changes and clears after a definitive result', () => {
    const storage = memoryStorage();
    let sequence = 0;
    const createKey = () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`;
    const first = getOrCreateIdempotencyKey(storage, 'first', createKey);
    const second = getOrCreateIdempotencyKey(storage, 'changed', createKey);
    expect(second).not.toBe(first);
    clearIdempotencyKey(storage);
    expect(getOrCreateIdempotencyKey(storage, 'changed', createKey)).not.toBe(second);
  });
});
