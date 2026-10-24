import { createContext, useContext, useEffect, useState } from 'react';
import type { PropsWithChildren } from 'react';
import { apiRequest, bootstrapRefresh, clearAccessToken, setAccessToken } from '../../api/client';
import type { LoginRequest, RegisterRequest, TokenResponse, User } from '../../api/types';
import { useCartStore } from '../cart/store';

export interface AuthContextValue {
  user: User | null;
  ready: boolean;
  login: (credentials: LoginRequest) => Promise<User>;
  register: (details: RegisterRequest) => Promise<User>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: PropsWithChildren) {
  const [user, setUser] = useState<User | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let active = true;
    void (async () => {
      if (await bootstrapRefresh()) {
        try {
          const actor = await apiRequest<User>('/auth/me');
          if (active) {
            setUser(actor);
            useCartStore.getState().switchUser(actor.id);
          }
        } catch {
          clearAccessToken();
        }
      }
      if (active) setReady(true);
    })();
    return () => { active = false; };
  }, []);

  async function establishSession(endpoint: '/auth/login' | '/auth/register', body: LoginRequest | RegisterRequest) {
    const response = await apiRequest<TokenResponse>(endpoint, {
      method: 'POST',
      body: JSON.stringify(body),
      credentials: 'include',
    });
    setAccessToken(response.accessToken);
    setUser(response.user);
    useCartStore.getState().switchUser(response.user.id);
    setReady(true);
    return response.user;
  }

  async function login(credentials: LoginRequest) {
    return establishSession('/auth/login', credentials);
  }

  async function register(details: RegisterRequest) {
    return establishSession('/auth/register', details);
  }

  async function logout() {
    try {
      await apiRequest<void>('/auth/logout', { method: 'POST', credentials: 'include' });
    } catch {
      // Clear local access even if the cookie revocation endpoint is unavailable.
    } finally {
      clearAccessToken();
      setUser(null);
      useCartStore.getState().logout();
    }
  }

  return <AuthContext.Provider value={{ user, ready, login, register, logout }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}
