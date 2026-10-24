import type { PropsWithChildren } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import type { Role } from '../../api/types';
import { useAuth } from './AuthProvider';

export function RequireRole({ role, children }: PropsWithChildren<{ role: Role }>) {
  const { user, ready } = useAuth();
  const location = useLocation();

  if (!ready) return <main className="session-loading" aria-busy="true">Restoring your session…</main>;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (user.role !== role) {
    return <Navigate to={user.role === 'ADMIN' ? '/console/dashboard' : '/menu'} replace />;
  }
  return children;
}
