import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../features/auth/AuthProvider';

export function AdminLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  async function signOut() { await logout(); navigate('/login', { replace: true }); }
  return <div className="admin-shell">
    <aside className="admin-sidebar"><div className="admin-brand"><span className="brand-mark-small">W</span><div><strong>webox<span className="brand-period">.</span></strong><span>Admin Console</span></div></div>
      <nav aria-label="Admin navigation"><p className="sidebar-label">WORKSPACE</p>
        <NavLink to="/console/dashboard"><span aria-hidden="true">▦</span>Overview</NavLink>
        <NavLink to="/console/dishes"><span aria-hidden="true">◈</span>Dishes</NavLink>
        <NavLink to="/console/menus"><span aria-hidden="true">▤</span>Daily menu</NavLink>
        <NavLink to="/console/orders"><span aria-hidden="true">↗</span>Orders</NavLink>
      </nav>
      <div className="admin-user"><span className="avatar admin-avatar">{user?.displayName.slice(0, 1).toUpperCase() ?? 'A'}</span><div><strong>{user?.displayName}</strong><span>Administrator</span></div><button aria-label="Sign out" onClick={() => void signOut()}>↗</button></div>
    </aside>
    <div className="admin-main"><header className="admin-topbar"><span>Operations</span><button onClick={() => void signOut()}>Sign out</button></header><Outlet /></div>
  </div>;
}
