import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../features/auth/AuthProvider';
import { useCartStore } from '../features/cart/store';

export function EmployeeLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const count = useCartStore((state) => state.itemCount());
  async function signOut() { await logout(); navigate('/login', { replace: true }); }
  return <div className="employee-shell">
    <header className="employee-header"><Link className="brand" to="/menu"><span className="brand-mark-small">W</span><span>webox<span className="brand-period">.</span></span></Link>
      <p className="header-greeting">A brighter break, {user?.displayName.split(' ')[0] ?? 'today'}.</p>
      <div className="header-actions"><Link className="header-cart" to="/cart" aria-label="Your order"><span aria-hidden="true">♧</span>{count > 0 && <span className="cart-count">{count}</span>}</Link><button className="avatar-button" aria-label="Sign out" title="Sign out" onClick={() => void signOut()}>{user?.displayName.slice(0, 1).toUpperCase() ?? 'U'}</button></div>
    </header>
    <div className="employee-body"><Outlet /></div>
    <nav className="employee-nav" aria-label="Main navigation">
      <NavLink to="/menu"><span aria-hidden="true">✳</span>Menu</NavLink>
      <NavLink to="/orders"><span aria-hidden="true">▤</span>Orders</NavLink>
      <NavLink to="/settings"><span aria-hidden="true">◌</span>Settings</NavLink>
      <NavLink to="/cart"><span aria-hidden="true">♧</span>Cart{count > 0 && <span className="nav-count">{count}</span>}</NavLink>
    </nav>
  </div>;
}
