import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { server } from '../test/server';
import { AuthProvider } from '../features/auth/AuthProvider';
import { AppRoutes } from './router';

afterEach(cleanup);

describe('application routes', () => {
  it('keeps Admin Console routes behind the admin role and provides an employee shell', async () => {
    server.use(
      http.post('*/api/v1/auth/refresh', () => HttpResponse.json({ accessToken: 'employee-token' })),
      http.get('*/api/v1/auth/me', () => HttpResponse.json({ id: 7, email: 'lee@example.com', displayName: 'Lee Chen', role: 'EMPLOYEE' })),
      http.get('*/api/v1/app/menu', () => HttpResponse.json({ date: '2026-10-24', page: 0, size: 12, totalItems: 0, totalPages: 0, items: [] })),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={['/console/dashboard']}>
      <AuthProvider><AppRoutes /></AuthProvider>
    </MemoryRouter></QueryClientProvider>);

    expect(await screen.findByRole('link', { name: 'Menu' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Good afternoon, good food.' })).toBeInTheDocument();
    expect(screen.queryByText('Admin Console')).not.toBeInTheDocument();
  });
});
