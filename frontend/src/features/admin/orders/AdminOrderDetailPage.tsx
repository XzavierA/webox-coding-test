import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { apiRequest, ProblemError } from '../../../api/client';
import type { Order, OrderTransitionRequest } from '../../../api/types';
import { formatCny, moneyToFen } from '../../../shared/money';

type TargetStatus = OrderTransitionRequest['targetStatus'];
const availableActions: Record<Order['status'], TargetStatus[]> = {
  PENDING: ['CONFIRMED', 'CANCELLED'], CONFIRMED: ['COMPLETED', 'CANCELLED'], COMPLETED: [], CANCELLED: [],
};
const labels: Record<TargetStatus, string> = { CONFIRMED: 'Confirm order', COMPLETED: 'Complete order', CANCELLED: 'Cancel order' };

export function AdminOrderDetailPage() {
  const { orderId = '' } = useParams();
  const queryClient = useQueryClient();
  const queryKey = ['admin-order', orderId] as const;
  const orderQuery = useQuery({ queryKey, queryFn: () => apiRequest<Order>(`/admin/orders/${encodeURIComponent(orderId)}`), enabled: /^\d+$/.test(orderId) });
  const transitionMutation = useMutation({
    mutationFn: (targetStatus: TargetStatus) => apiRequest<Order>(`/admin/orders/${encodeURIComponent(orderId)}/transitions`, {
      method: 'POST', body: JSON.stringify({ targetStatus, expectedVersion: orderQuery.data!.version } satisfies OrderTransitionRequest),
    }),
    onSuccess: async (updated) => {
      queryClient.setQueryData(queryKey, updated);
      await queryClient.invalidateQueries({ queryKey: ['admin-orders'] });
    },
    onError: async () => { await queryClient.invalidateQueries({ queryKey }); },
  });
  const order = orderQuery.data;
  if (orderQuery.isPending) return <main className="page-content"><div className="menu-skeleton" aria-label="Loading order" aria-busy="true" /></main>;
  if (orderQuery.isError || !order) return <main className="page-content"><div className="empty-state" role="alert"><h1>Order unavailable</h1><Link to="/console/orders">Back to orders</Link></div></main>;
  const conflict = transitionMutation.error instanceof ProblemError && transitionMutation.error.problem.status === 409;
  return <main className="page-content admin-order-detail-page">
    <Link className="back-link" to="/console/orders">← Back to all orders</Link>
    <section className="page-heading compact-heading"><div><p className="eyebrow">{order.deliveryDate} · {order.mealSlot.toLowerCase()}</p><h1>{order.orderNumber}</h1><p>{order.userDisplayName} · {order.deliveryAddress}</p></div><span className={`status-pill status-${order.status.toLowerCase()}`}>{order.status[0] + order.status.slice(1).toLowerCase()}</span></section>
    {transitionMutation.isSuccess && <p className="success-note" role="status">Order marked {transitionMutation.data.status === 'COMPLETED' ? 'complete' : transitionMutation.data.status.toLowerCase()}.</p>}
    {transitionMutation.isError && <p className="form-error" role="alert">{conflict ? 'Another administrator updated this order. The latest order details have been loaded.' : 'This order could not be updated.'}</p>}
    <div className="order-detail-layout"><section className="order-detail-card"><h2>Order items</h2>
      {order.items.map((item) => <article className="order-detail-line" key={item.id}><div><h3>{item.dishName} × {item.quantity}</h3>{item.options.length > 0 && <p>{item.options.map((option) => `${option.groupName}: ${option.valueName}`).join(' · ')}</p>}</div><strong>{formatCny(moneyToFen(item.subtotal))}</strong></article>)}
      <div className="summary-total"><span>Total</span><strong>{formatCny(moneyToFen(order.total))}</strong></div>
      {availableActions[order.status].length > 0 && <div className="admin-transition-actions">{availableActions[order.status].map((targetStatus) => <button className={`button ${targetStatus === 'CANCELLED' ? 'button-secondary danger-action' : 'button-primary'}`} key={targetStatus} disabled={transitionMutation.isPending} onClick={() => transitionMutation.mutate(targetStatus)}>{transitionMutation.isPending ? 'Updating…' : labels[targetStatus]}</button>)}</div>}
    </section><aside className="order-detail-card"><h2>Status history</h2><ol className="status-history">{order.statusHistory.map((entry, index) => <li key={`${entry.createdAt}-${index}`}><strong>{entry.toStatus.toLowerCase()}</strong><span>{entry.actorDisplayName} · {new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(entry.createdAt))}</span>{entry.reason && <p>{entry.reason}</p>}</li>)}</ol></aside></div>
  </main>;
}
