import { describe, expect, it, vi } from 'vitest';
import { buildMenuQuery, useDebouncedValue } from './query';
import { act, renderHook } from '@testing-library/react';

describe('menu query controls', () => {
  it('serializes multi-category filters and bounds search to 50 characters', () => {
    const query = buildMenuQuery({
      date: '2026-10-25',
      query: 'a'.repeat(62),
      categories: ['JAPANESE', 'CHINESE'],
      recommended: true,
      page: 2,
      size: 20,
    });

    expect(query.toString()).toBe(`date=2026-10-25&q=${'a'.repeat(50)}&categories=JAPANESE%2CCHINESE&recommended=true&page=2&size=20`);
  });

  it('updates the searched value only after the debounce window', () => {
    vi.useFakeTimers();
    try {
      const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 250), {
        initialProps: { value: '' },
      });
      rerender({ value: 'grain bowl' });
      act(() => vi.advanceTimersByTime(249));
      expect(result.current).toBe('');
      act(() => vi.advanceTimersByTime(1));
      expect(result.current).toBe('grain bowl');
    } finally {
      vi.useRealTimers();
    }
  });
});
