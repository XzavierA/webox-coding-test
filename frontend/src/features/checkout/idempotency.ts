const STORAGE_KEY = 'webox-order-idempotency';

interface StoredKey {
  fingerprint: string;
  key: string;
}

export function getOrCreateIdempotencyKey(
  storage: Storage,
  fingerprint: string,
  createKey: () => string = () => crypto.randomUUID(),
): string {
  try {
    const saved = storage.getItem(STORAGE_KEY);
    if (saved) {
      const parsed = JSON.parse(saved) as Partial<StoredKey>;
      if (parsed.fingerprint === fingerprint && typeof parsed.key === 'string' && parsed.key) return parsed.key;
    }
  } catch {
    // Replace malformed local state with a fresh idempotency key.
  }
  const key = createKey();
  storage.setItem(STORAGE_KEY, JSON.stringify({ fingerprint, key } satisfies StoredKey));
  return key;
}

export function clearIdempotencyKey(storage: Storage): void {
  storage.removeItem(STORAGE_KEY);
}
