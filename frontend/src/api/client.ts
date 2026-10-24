import type { ProblemDetails, TokenResponse } from './types';

const API_BASE = '/api/v1';
let accessToken: string | null = null;
let refreshInFlight: Promise<boolean> | null = null;

export class ProblemError extends Error {
  constructor(readonly problem: ProblemDetails) {
    super(problem.detail);
    this.name = 'ProblemError';
  }
}

export function setAccessToken(token: string): void {
  accessToken = token;
}

export function clearAccessToken(): void {
  accessToken = null;
}

export function getAccessToken(): string | null {
  return accessToken;
}

async function performRefresh(): Promise<boolean> {
  try {
    const response = await fetch(`${API_BASE}/auth/refresh`, {
      method: 'POST',
      credentials: 'include',
    });
    if (!response.ok) {
      clearAccessToken();
      return false;
    }
    const tokenResponse = await response.json() as Partial<TokenResponse>;
    if (typeof tokenResponse.accessToken !== 'string' || !tokenResponse.accessToken) {
      clearAccessToken();
      return false;
    }
    setAccessToken(tokenResponse.accessToken);
    return true;
  } catch {
    clearAccessToken();
    return false;
  }
}

function refreshAccessToken(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;
  const pending = performRefresh().finally(() => {
    if (refreshInFlight === pending) refreshInFlight = null;
  });
  refreshInFlight = pending;
  return pending;
}

export function bootstrapRefresh(): Promise<boolean> {
  return refreshAccessToken();
}

function isAuthSessionEndpoint(path: string): boolean {
  return /^\/auth\/(?:login|register|refresh|logout)(?:\?|$)/.test(path);
}

async function parseProblem(response: Response): Promise<ProblemError> {
  let body: Partial<ProblemDetails> | null = null;
  try {
    body = await response.json() as Partial<ProblemDetails>;
  } catch {
    body = null;
  }
  return new ProblemError({
    type: body?.type,
    title: body?.title,
    status: body?.status ?? response.status,
    detail: body?.detail ?? response.statusText ?? 'The request could not be completed.',
    code: body?.code ?? 'REQUEST_FAILED',
    correlationId: body?.correlationId,
    fieldErrors: body?.fieldErrors,
    details: body?.details,
  });
}

async function send<T>(path: string, init: RequestInit, token: string | null): Promise<T> {
  const headers = new Headers(init.headers);
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const requestInit = { ...init, headers };
  if (init.signal) {
    try {
      new Request('http://localhost/', { signal: init.signal });
    } catch {
      // Ignore signals from a different DOM runtime (for example, jsdom with Node fetch).
      requestInit.signal = undefined;
    }
  }
  const response = await fetch(`${API_BASE}${path.startsWith('/') ? path : `/${path}`}`, {
    ...requestInit,
  });
  if (!response.ok) throw await parseProblem(response);
  if (response.status === 204 || response.headers.get('content-length') === '0') return undefined as T;
  return await response.json() as T;
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const originalToken = accessToken;
  try {
    return await send<T>(path, init, originalToken);
  } catch (error) {
    if (!(error instanceof ProblemError) || error.problem.status !== 401 || isAuthSessionEndpoint(path)) {
      throw error;
    }
    if (accessToken === originalToken && !(await refreshAccessToken())) throw error;
    return send<T>(path, init, accessToken);
  }
}
