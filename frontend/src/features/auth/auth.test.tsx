import { http, HttpResponse } from 'msw';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { server } from '../../test/server';
import { AuthProvider, useAuth } from './AuthProvider';
import { LoginPage } from './LoginPage';
import { RequireRole } from './RequireRole';
import { clearAccessToken } from '../../api/client';
import { addCartItem, useCartStore } from '../cart/store';

const employee = { id: 7, email: 'lee@example.com', displayName: 'Lee Chen', role: 'EMPLOYEE' as const };
const admin = { id: 2, email: 'admin@example.com', displayName: 'Morgan Park', role: 'ADMIN' as const };

function LogoutHarness() {
  const { user, logout } = useAuth();
  return (
    <>
      <p>{user?.displayName ?? 'You are signed out.'}</p>
      <button onClick={() => void logout()}>Sign out</button>
    </>
  );
}

function renderGuarded(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<p>Login route</p>} />
          <Route path="/menu" element={<p>Employee menu</p>} />
          <Route path="/console/dashboard" element={<RequireRole role="ADMIN"><p>Admin dashboard</p></RequireRole>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

afterEach(() => {
  cleanup();
  clearAccessToken();
  useCartStore.getState().logout();
});

describe('authentication and role access', () => {
  it('validates login credentials before sending the request', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <AuthProvider>
          <LoginPage />
        </AuthProvider>
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Enter a valid email address.')).toBeInTheDocument();
    expect(screen.getByText('Password is required.')).toBeInTheDocument();
  });

  it('shows generic feedback when login credentials are rejected', async () => {
    server.use(http.post('*/api/v1/auth/login', () => HttpResponse.json({
      type: 'about:blank', title: 'Unauthorized', status: 401,
      code: 'BAD_CREDENTIALS', detail: 'Internal credential lookup failed.', correlationId: 'login-1',
    }, { status: 401, headers: { 'Content-Type': 'application/problem+json' } })));
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <AuthProvider>
          <LoginPage />
        </AuthProvider>
      </MemoryRouter>,
    );
    await user.type(screen.getByLabelText('Email address'), 'lee@example.com');
    await user.type(screen.getByLabelText('Password'), 'Wrong-pass9');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Email or password is incorrect.')).toBeInTheDocument();
    expect(screen.queryByText('Internal credential lookup failed.')).not.toBeInTheDocument();
  });

  it('redirects an employee away from the Admin Console after refresh bootstrap', async () => {
    server.use(
      http.post('*/api/v1/auth/refresh', () => HttpResponse.json({ accessToken: 'employee-token' })),
      http.get('*/api/v1/auth/me', () => HttpResponse.json(employee)),
    );
    renderGuarded('/console/dashboard');

    expect(await screen.findByText('Employee menu')).toBeInTheDocument();
    expect(screen.queryByText('Admin dashboard')).not.toBeInTheDocument();
  });

  it('allows an administrator into the Admin Console after refresh bootstrap', async () => {
    server.use(
      http.post('*/api/v1/auth/refresh', () => HttpResponse.json({ accessToken: 'admin-token' })),
      http.get('*/api/v1/auth/me', () => HttpResponse.json(admin)),
    );
    renderGuarded('/console/dashboard');

    expect(await screen.findByText('Admin dashboard')).toBeInTheDocument();
  });

  it('logs out by revoking the cookie and clearing the memory access token', async () => {
    let logoutAuthorization = 'not-called';
    server.use(
      http.post('*/api/v1/auth/logout', ({ request }) => {
        logoutAuthorization = request.headers.get('authorization') ?? '';
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    server.use(
      http.post('*/api/v1/auth/refresh', () => HttpResponse.json({ accessToken: 'admin-token' })),
      http.get('*/api/v1/auth/me', () => HttpResponse.json(admin)),
    );
    render(<AuthProvider><LogoutHarness /></AuthProvider>);
    await screen.findByText('Morgan Park');
    expect(useCartStore.getState().userId).toBe(2);
    addCartItem({
      dishId: 14, name: 'Herb chicken bowl', imageUrl: '/chicken.jpg', basePriceFen: 1250,
      unitPriceFen: 1250, optionValueIds: [], optionNames: [], allergens: [], quantity: 1,
    });
    await user.click(screen.getByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(logoutAuthorization).toBe('Bearer admin-token'));
    expect(screen.getByText('You are signed out.')).toBeInTheDocument();
    expect(useCartStore.getState().lines).toEqual([]);
    expect(useCartStore.getState().userId).toBeNull();
  });
});
