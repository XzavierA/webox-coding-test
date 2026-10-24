import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { apiRequest, ProblemError } from '../../api/client';
import type { Order } from '../../api/types';
import { formatCny, moneyToFen } from '../../shared/money';

function readableDate(date: string): string {
  return new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', month: 'long', day: 'numeric', year: 'numeric' }).format(new Date(`${date}T00:00:00Z`));
}

export function OrderDetailPage() {
  const { orderId = '' } = useParams();
  const queryClient = useQueryClient();
  const orderQuery = useQuery({ queryKey: ['order', orderId], queryFn: () => apiRequest<Order>(`/app/orders/${encodeURIComponent(orderId)}`), enabled: /^\d+$/.test(orderId) });
  const cancelMutation = useMutation({
    mutationFn: (version: number) => apiRequest<Order>(`/app/orders/${encodeURIComponent(orderId)}/cancel`, { method: 'POST', body: JSON.stringify({ expectedVersion: version }) }),
    onSuccess: async (order) => {
      queryClient.setQueryData(['order', orderId], order);
      await queryClient.invalidateQueries({ queryKey: ['orders'] });
    },
  });
  const order = orderQuery.data;
  if (orderQuery.isPending) return <main className="page-content"><div className="menu-skeleton" aria-label="Loading order" aria-busy="true" /></main>;
  if (orderQuery.isError || !order) return <main className="page-content"><div className="empty-state" role="alert"><h1>Order unavailable</h1><p>We couldn’t load this order.</p><Link to="/orders">Back to order history</Link></div></main>;
  const error = cancelMutation.error;
  return <main className="page-content order-detail-page">
    <Link className="back-link" to="/orders">← Back to order history</Link>
    <section className="page-heading compact-heading"><div><p className="eyebrow">{readableDate(order.deliveryDate)} · {order.mealSlot === 'LUNCH' ? 'Lunch' : 'Dinner'}</p><h1>{order.orderNumber}</h1><p>Delivering to {order.deliveryAddress}</p></div><span className={`status-pill status-${order.status.toLowerCase()}`}>{order.status.toLowerCase()}</span></section>
    {cancelMutation.isSuccess && <p className="success-note" role="status">Order cancelled.</p>}
    {error && <div className="form-error" role="alert">{error instanceof ProblemError && error.problem.status === 409 ? 'This order changed before it could be cancelled. The latest order details have been loaded.' : 'We couldn’t cancel this order. Please try again.'}</div>}
    <div className="order-detail-layout">
      <section className="order-detail-card"><h2>Items</h2>
        {order.items.map((item) => <article className="order-detail-line" key={item.id}><div><h3>{item.dishName} × {item.quantity}</h3>{item.options.length > 0 && <p>{item.options.map((option) => `${option.groupName}: ${option.valueName}`).join(' · ')}</p>}</div><strong>{formatCny(moneyToFen(item.subtotal))}</strong></article>)}
        <div className="summary-total"><span>Total</span><strong>{formatCny(moneyToFen(order.total))}</strong></div>
      </section>
      <aside className="order-detail-card"><h2>Order updates</h2><ol className="status-history">{order.statusHistory.map((entry, index) => <li key={`${entry.createdAt}-${index}`}><strong>{entry.toStatus.toLowerCase()}</strong><span>{entry.actorDisplayName} · {new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(entry.createdAt))}</span>{entry.reason && <p>{entry.reason}</p>}</li>)}</ol>
        {order.status === 'PENDING' && <button className="button button-secondary danger-action" disabled={cancelMutation.isPending} onClick={() => cancelMutation.mutate(order.version)}>{cancelMutation.isPending ? 'Cancelling…' : 'Cancel order'}</button>}
      </aside>
    </div>
  </main>;
}
