import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { AdminLayout } from '../layouts/AdminLayout';
import { EmployeeLayout } from '../layouts/EmployeeLayout';
const CartPage = lazy(() => import('../features/cart/CartPage').then((module) => ({ default: module.CartPage })));
const CheckoutPage = lazy(() => import('../features/checkout/CheckoutPage').then((module) => ({ default: module.CheckoutPage })));
const LoginPage = lazy(() => import('../features/auth/LoginPage').then((module) => ({ default: module.LoginPage })));
const RegisterPage = lazy(() => import('../features/auth/RegisterPage').then((module) => ({ default: module.RegisterPage })));
import { RequireRole } from '../features/auth/RequireRole';
const MenuPage = lazy(() => import('../features/menu/MenuPage').then((module) => ({ default: module.MenuPage })));
const DishDetailPage = lazy(() => import('../features/menu/DishDetailPage').then((module) => ({ default: module.DishDetailPage })));
const PreferencesPage = lazy(() => import('../features/preferences/PreferencesPage').then((module) => ({ default: module.PreferencesPage })));
const OrderDetailPage = lazy(() => import('../features/orders/OrderDetailPage').then((module) => ({ default: module.OrderDetailPage })));
const OrdersPage = lazy(() => import('../features/orders/OrdersPage').then((module) => ({ default: module.OrdersPage })));
const CatalogPage = lazy(() => import('../features/admin/catalog/CatalogPage').then((module) => ({ default: module.CatalogPage })));
const DailyMenuPage = lazy(() => import('../features/admin/menus/DailyMenuPage').then((module) => ({ default: module.DailyMenuPage })));
const AdminOrdersPage = lazy(() => import('../features/admin/orders/AdminOrdersPage').then((module) => ({ default: module.AdminOrdersPage })));
const AdminOrderDetailPage = lazy(() => import('../features/admin/orders/AdminOrderDetailPage').then((module) => ({ default: module.AdminOrderDetailPage })));
const DashboardPage = lazy(() => import('../features/admin/dashboard/DashboardPage').then((module) => ({ default: module.DashboardPage })));

export function AppRoutes() {
  return <Suspense fallback={<main className="session-loading" aria-busy="true">Loading page…</main>}><Routes>
    <Route path="/" element={<Navigate to="/menu" replace />} />
    <Route path="/login" element={<LoginPage />} />
    <Route path="/register" element={<RegisterPage />} />
    <Route element={<RequireRole role="EMPLOYEE"><EmployeeLayout /></RequireRole>}>
      <Route path="/menu" element={<MenuPage />} />
      <Route path="/dishes/:dishId" element={<DishDetailPage />} />
      <Route path="/cart" element={<CartPage />} />
      <Route path="/checkout" element={<CheckoutPage />} />
      <Route path="/orders" element={<OrdersPage />} />
      <Route path="/orders/:orderId" element={<OrderDetailPage />} />
      <Route path="/settings" element={<PreferencesPage />} />
    </Route>
    <Route path="/console" element={<RequireRole role="ADMIN"><AdminLayout /></RequireRole>}>
      <Route index element={<Navigate to="dashboard" replace />} />
      <Route path="dashboard" element={<DashboardPage />} />
      <Route path="dishes" element={<CatalogPage />} />
      <Route path="menus" element={<DailyMenuPage />} />
      <Route path="orders" element={<AdminOrdersPage />} />
      <Route path="orders/:orderId" element={<AdminOrderDetailPage />} />
    </Route>
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes></Suspense>;
}
