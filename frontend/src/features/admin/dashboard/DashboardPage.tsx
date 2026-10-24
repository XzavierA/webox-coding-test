import { useQuery } from '@tanstack/react-query';
import { lazy, Suspense, useState } from 'react';
import { apiRequest } from '../../../api/client';
import type { Dashboard } from '../../../api/types';
import { formatCny, moneyToFen } from '../../../shared/money';
const TrendChart = lazy(() => import('./TrendChart').then((module) => ({ default: module.TrendChart })));

function businessDate(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
}

export function DashboardPage() {
  const date = businessDate();
  const [manualRefresh, setManualRefresh] = useState(0);
  const query = useQuery({
    queryKey: ['admin-dashboard', date, manualRefresh],
    queryFn: () => apiRequest<Dashboard>(`/admin/dashboard?date=${encodeURIComponent(date)}${manualRefresh ? '&refresh=true' : ''}`),
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
  });
  const dashboard = query.data;

  return <main className="page-content admin-dashboard-page">
    <section className="page-heading compact-heading"><div><p className="eyebrow">ADMIN CONSOLE · OVERVIEW</p><h1>Good work, in view.</h1><p>Today’s delivery-date performance at a glance.</p></div>
      <button className="button button-secondary" onClick={() => setManualRefresh((value) => value + 1)} disabled={query.isFetching} aria-label="Refresh dashboard">{query.isFetching ? 'Refreshing…' : 'Refresh'}</button>
    </section>
    {query.isPending && <div className="dashboard-loading" aria-label="Loading dashboard" aria-busy="true"><div className="metric-skeleton" /><div className="metric-skeleton" /><div className="metric-skeleton" /></div>}
    {query.isError && <div className="empty-state" role="alert"><h2>Dashboard unavailable.</h2><p>We couldn’t load the latest delivery report.</p><button className="button button-primary" onClick={() => void query.refetch()}>Try again</button></div>}
    {dashboard && <>
      <div className="dashboard-meta"><span>{new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', dateStyle: 'full' }).format(new Date(`${dashboard.date}T00:00:00Z`))}</span><span>Updated {new Intl.DateTimeFormat('en-US', { timeStyle: 'short' }).format(new Date(dashboard.asOf))}</span></div>
      <section className="metric-grid" aria-label="Daily overview">
        <article className="metric-card"><span>Total orders</span><strong>{dashboard.overview.totalOrders}</strong><small>Including cancelled</small></article>
        <article className="metric-card"><span>Revenue</span><strong>{formatCny(moneyToFen(dashboard.overview.revenue))}</strong><small>Excludes cancelled orders</small></article>
        <article className="metric-card"><span>Completed</span><strong>{dashboard.overview.completed}</strong><small>Delivered successfully</small></article>
        <article className="metric-card"><span>Pending</span><strong>{dashboard.overview.pending}</strong><small>Needs confirmation</small></article>
      </section>
      <section className="dashboard-main-grid">
        <article className="dashboard-card dashboard-trend"><div className="dashboard-card-heading"><div><p className="eyebrow">LAST SEVEN DAYS</p><h2>Orders and revenue</h2></div></div>
          <Suspense fallback={<div className="trend-chart" role="img" aria-label="Loading trend chart" aria-busy="true" />}><TrendChart data={dashboard.trend} /></Suspense>
          <div className="sr-only" role="region" aria-label="Trend chart data"><table aria-label="Seven-day order and revenue trend"><thead><tr><th>Date</th><th>Orders</th><th>Revenue</th></tr></thead><tbody>{dashboard.trend.map((point) => <tr key={point.date}><th scope="row">{point.date}</th><td>{point.orders}</td><td>{formatCny(moneyToFen(point.revenue))}</td></tr>)}</tbody></table></div>
        </article>
        <article className="dashboard-card low-stock-card"><div className="dashboard-card-heading"><div><p className="eyebrow">NEEDS A LOOK</p><h2>Low stock</h2></div><span>{dashboard.lowStock.length}</span></div>
          {dashboard.lowStock.length ? <ul className="low-stock-list">{dashboard.lowStock.map((item) => <li key={item.dishId}><span>{item.name}</span><strong>{item.remainingStock} left</strong></li>)}</ul> : <p className="empty-note">No dishes are running low.</p>}
        </article>
        <article className="dashboard-card"><div className="dashboard-card-heading"><div><p className="eyebrow">TEAM FAVORITES</p><h2>Top dishes</h2></div></div>
          {dashboard.topDishes.length ? <ol className="ranked-list">{dashboard.topDishes.map((item) => <li key={item.dishId}><span>{item.name}</span><strong>{item.quantity}</strong></li>)}</ol> : <p className="empty-note">No orders yet today.</p>}
        </article>
        <article className="dashboard-card"><div className="dashboard-card-heading"><div><p className="eyebrow">MEAL TIMES</p><h2>Delivery split</h2></div></div>
          {dashboard.mealDistribution.length ? <ul className="ranked-list">{dashboard.mealDistribution.map((item) => <li key={item.mealSlot}><span>{item.mealSlot === 'LUNCH' ? 'Lunch' : 'Dinner'}</span><strong>{item.orders}</strong></li>)}</ul> : <p className="empty-note">No delivery activity yet.</p>}
        </article>
      </section>
    </>}
  </main>;
}
