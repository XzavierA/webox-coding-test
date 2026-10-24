import { describe, expect, it } from 'vitest';
import { formatCny, moneyToFen } from './money';

describe('money helpers', () => {
  it('converts an API decimal string into integer fen', () => {
    expect(moneyToFen('22.50')).toBe(2250);
  });

  it('formats fen as CNY with exactly two decimals', () => {
    expect(formatCny(2250)).toBe('¥22.50');
    expect(formatCny(5)).toBe('¥0.05');
  });

  it('rejects decimal strings that do not have exactly two places', () => {
    expect(() => moneyToFen('22.5')).toThrow('Money must use two decimal places');
  });
});
