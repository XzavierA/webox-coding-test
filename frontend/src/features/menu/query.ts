import { useEffect, useState } from 'react';
import type { Category } from '../../api/types';

export interface MenuQueryInput {
  date: string;
  query: string;
  categories: Category[];
  recommended: boolean;
  page: number;
  size: number;
}

export function buildMenuQuery(input: MenuQueryInput): URLSearchParams {
  const params = new URLSearchParams();
  params.set('date', input.date);
  const query = input.query.trim().slice(0, 50);
  if (query) params.set('q', query);
  if (input.categories.length) params.set('categories', input.categories.join(','));
  if (input.recommended) params.set('recommended', 'true');
  params.set('page', String(input.page));
  params.set('size', String(input.size));
  return params;
}

export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}
