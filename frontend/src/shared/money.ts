export function moneyToFen(value: string): number {
  if (!/^\d{1,8}\.\d{2}$/.test(value)) {
    throw new Error('Money must use two decimal places');
  }
  const [whole = '0', fraction = '00'] = value.split('.');
  return Number(whole) * 100 + Number(fraction);
}

export function formatCny(fen: number): string {
  if (!Number.isSafeInteger(fen)) throw new Error('Money must be a safe integer number of fen');
  const sign = fen < 0 ? '-' : '';
  const absolute = Math.abs(fen);
  return `${sign}¥${Math.floor(absolute / 100)}.${String(absolute % 100).padStart(2, '0')}`;
}
