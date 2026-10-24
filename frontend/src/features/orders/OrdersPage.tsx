import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { apiRequest } from '../../api/client';
import type { OrderPage, OrderStatus } from '../../api/types';
import { formatCny, moneyToFen } from '../../shared/money';

const statuses: Array<{ value: OrderStatus | ''; label: string }> = [
  { value: '', label: 'All orders' }, { value: 'PENDING', label: 'Pending' },
  { value: 'CONFIRMED', label: 'Confirmed' }, { value: 'COMPLETED', label: 'Completed' }, { value: 'CANCELLED', label: 'Cancelled' },
];

function readableDate(date: string): string {
  return new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', month: 'long', day: 'numeric', year: 'numeric' }).format(new Date(`${date}T00:00:00Z`));
}

export function OrdersPage() {
  const [status, setStatus] = useState<OrderStatus | ''>('');
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: ['orders', status, page],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '20' });
      if (status) params.set('status', status);
      return apiRequest<OrderPage>(`/app/orders?${params}`);
    },
    placeholderData: keepPreviousData,
  });

  return <main className="page-content orders-page">
    <section className="page-heading compact-heading"><div><p className="eyebrow">YOUR MEALS, AT A GLANCE</p><h1>Order history</h1><p>Follow your upcoming deliveries and find past favorites.</p></div>
      <label>Order status<select aria-label="Filter orders by status" value={status} onChange={(event) => { setStatus(event.target.value as OrderStatus | ''); setPage(0); }}>
        {statuses.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
      </select></label>
    </section>
    {query.isPending && <div className="menu-skeleton" aria-label="Loading orders" aria-busy="true" />}
    {query.isError && <div className="empty-state" role="alert"><h2>Orders are unavailable.</h2><p>We couldn’t load your order history.</p><button className="button button-secondary" onClick={() => void query.refetch()}>Try again</button></div>}
    {query.data && query.data.items.length === 0 && <div className="empty-state"><h2>No orders found.</h2><p>Orders will appear here after you place one.</p><Link className="button button-primary" to="/menu">Browse the menu</Link></div>}
    {query.data && query.data.items.length > 0 && <section className="order-history-list" aria-label="Your orders" aria-busy={query.isFetching}>
      {query.data.items.map((order) => <article className="order-history-card" key={order.id}>
        <div><p className="eyebrow">{readableDate(order.deliveryDate)} · {order.mealSlot === 'LUNCH' ? 'Lunch' : 'Dinner'}</p>
          <h2><Link to={`/orders/${order.id}`}>{order.orderNumber}</Link></h2><p>{order.items.length} {order.items.length === 1 ? 'item' : 'items'} · {order.deliveryAddress}</p>
        </div>
        <div className="order-history-meta"><span className={`status-pill status-${order.status.toLowerCase()}`}>{order.status.toLowerCase()}</span><strong>{formatCny(moneyToFen(order.total))}</strong><Link className="text-link" to={`/orders/${order.id}`}>View order <span aria-hidden="true">→</span></Link></div>
      </article>)}
    </section>}
    {query.data && query.data.totalPages > 1 && <nav className="pagination" aria-label="Order pages">
      <button className="button button-secondary" disabled={page === 0} onClick={() => setPage((value) => value - 1)}>Previous</button><span>Page {query.data.page + 1} of {query.data.totalPages}</span>
      <button className="button button-secondary" disabled={page + 1 >= query.data.totalPages} onClick={() => setPage((value) => value + 1)}>Next</button>
    </nav>}
  </main>;
}
