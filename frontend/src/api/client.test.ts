import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { apiRequest, clearAccessToken, ProblemError, setAccessToken } from './client';
import { server } from '../test/server';

describe('API client', () => {
  it('attaches the in-memory bearer access token', async () => {
    setAccessToken('employee-token');
    let authorization = '';
    server.use(http.get('*/api/v1/private', ({ request }) => {
      authorization = request.headers.get('authorization') ?? '';
      return HttpResponse.json({ ok: true });
    }));

    await expect(apiRequest<{ ok: boolean }>('/private')).resolves.toEqual({ ok: true });
    expect(authorization).toBe('Bearer employee-token');
    clearAccessToken();
  });

  it('shares one refresh across concurrent unauthorized requests', async () => {
    setAccessToken('expired-token');
    let refreshCount = 0;
    server.use(
      http.get('*/api/v1/concurrent/:name', ({ request }) => {
        if (request.headers.get('authorization') === 'Bearer fresh-token') {
          return HttpResponse.json({ ok: true });
        }
        return HttpResponse.json({ code: 'AUTH_REQUIRED', detail: 'Authentication required.' }, { status: 401 });
      }),
      http.post('*/api/v1/auth/refresh', () => {
        refreshCount += 1;
        return HttpResponse.json({ accessToken: 'fresh-token', tokenType: 'Bearer', expiresIn: 900 });
      }),
    );

    const results = await Promise.all([
      apiRequest<{ ok: boolean }>('/concurrent/one'),
      apiRequest<{ ok: boolean }>('/concurrent/two'),
    ]);

    expect(results).toEqual([{ ok: true }, { ok: true }]);
    expect(refreshCount).toBe(1);
  });

  it('retries a request only once after refresh', async () => {
    setAccessToken('expired-token');
    let requestCount = 0;
    server.use(
      http.get('*/api/v1/still-unauthorized', () => {
        requestCount += 1;
        return HttpResponse.json({ code: 'AUTH_REQUIRED', detail: 'Authentication required.' }, { status: 401 });
      }),
      http.post('*/api/v1/auth/refresh', () =>
        HttpResponse.json({ accessToken: 'fresh-token', tokenType: 'Bearer', expiresIn: 900 }),
      ),
    );

    await expect(apiRequest('/still-unauthorized')).rejects.toBeInstanceOf(ProblemError);
    expect(requestCount).toBe(2);
  });

  it('parses RFC Problem Details on failed responses', async () => {
    server.use(http.get('*/api/v1/conflict', () => HttpResponse.json({
      type: 'about:blank',
      title: 'Conflict',
      status: 409,
      code: 'ORDER_EXISTS',
      detail: 'An order already exists for this delivery slot.',
      correlationId: 'trace-123',
    }, { status: 409, headers: { 'Content-Type': 'application/problem+json' } })));

    await expect(apiRequest('/conflict')).rejects.toMatchObject({
      problem: {
        status: 409,
        code: 'ORDER_EXISTS',
        detail: 'An order already exists for this delivery slot.',
        correlationId: 'trace-123',
      },
    });
  });
});
