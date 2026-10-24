import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { apiRequest, ProblemError } from '../../../api/client';
import type { MealSlot, Order, OrderPage, OrderStatus, OrderTransitionRequest } from '../../../api/types';
import { formatCny, moneyToFen } from '../../../shared/money';

type TargetStatus = OrderTransitionRequest['targetStatus'];
const nextActions: Record<OrderStatus, TargetStatus[]> = {
  PENDING: ['CONFIRMED', 'CANCELLED'], CONFIRMED: ['COMPLETED', 'CANCELLED'], COMPLETED: [], CANCELLED: [],
};
const actionNames: Record<TargetStatus, string> = { CONFIRMED: 'Confirm', COMPLETED: 'Complete', CANCELLED: 'Cancel' };

export function AdminOrdersPage() {
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const [date, setDate] = useState('');
  const [mealSlot, setMealSlot] = useState<MealSlot | ''>('');
  const [status, setStatus] = useState<OrderStatus | ''>('');
  const [page, setPage] = useState(0);
  const [message, setMessage] = useState('');
  const queryKey = ['admin-orders', search, date, mealSlot, status, page] as const;
  const ordersQuery = useQuery({
    queryKey,
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '20' });
      if (search.trim()) params.set('q', search.trim().slice(0, 50));
      if (date) params.set('date', date);
      if (mealSlot) params.set('mealSlot', mealSlot);
      if (status) params.set('status', status);
      return apiRequest<OrderPage>(`/admin/orders?${params}`);
    },
    placeholderData: keepPreviousData,
  });
  const transitionMutation = useMutation({
    mutationFn: ({ order, targetStatus }: { order: Order; targetStatus: TargetStatus }) => apiRequest<Order>(`/admin/orders/${order.id}/transitions`, {
      method: 'POST', body: JSON.stringify({ targetStatus, expectedVersion: order.version } satisfies OrderTransitionRequest),
    }),
    onSuccess: (updated) => {
      queryClient.setQueryData<OrderPage>(queryKey, (current) => current && ({ ...current, items: current.items.map((order) => order.id === updated.id ? updated : order) }));
      queryClient.setQueryData(['admin-order', String(updated.id)], updated);
      setMessage(`${updated.orderNumber} is now ${updated.status.toLowerCase()}.`);
    },
    onError: async (error) => {
      setMessage(error instanceof ProblemError && error.problem.status === 409 ? 'This order changed elsewhere. The latest orders have been loaded.' : 'This order could not be updated.');
      await queryClient.invalidateQueries({ queryKey: ['admin-orders'] });
    },
  });
  const orders = ordersQuery.data?.items ?? [];

  return <main className="page-content admin-orders-page">
    <section className="page-heading compact-heading"><div><p className="eyebrow">ADMIN CONSOLE · FULFILLMENT</p><h1>Orders</h1><p>Keep every meal moving through the right next step.</p></div></section>
    <section className="admin-toolbar" aria-label="Order filters">
      <label>Search<input aria-label="Search orders" maxLength={50} value={search} onChange={(event) => { setSearch(event.target.value.slice(0, 50)); setPage(0); }} placeholder="Order number or employee" /></label>
      <label>Delivery date<input aria-label="Filter by delivery date" type="date" value={date} onChange={(event) => { setDate(event.target.value); setPage(0); }} /></label>
      <label>Meal slot<select aria-label="Filter by meal slot" value={mealSlot} onChange={(event) => { setMealSlot(event.target.value as MealSlot | ''); setPage(0); }}><option value="">All slots</option><option value="LUNCH">Lunch</option><option value="DINNER">Dinner</option></select></label>
      <label>Status<select aria-label="Filter orders by status" value={status} onChange={(event) => { setStatus(event.target.value as OrderStatus | ''); setPage(0); }}><option value="">All statuses</option>{(['PENDING', 'CONFIRMED', 'COMPLETED', 'CANCELLED'] as OrderStatus[]).map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
    </section>
    {message && <p className={message.includes('could not') ? 'form-error' : 'success-note'} role={message.includes('could not') ? 'alert' : 'status'}>{message}</p>}
    {ordersQuery.isPending && <div className="menu-skeleton" aria-label="Loading orders" aria-busy="true" />}
    {ordersQuery.isError && <div className="empty-state" role="alert"><h2>Orders unavailable.</h2><p>We couldn’t load the order queue.</p><button className="button button-secondary" onClick={() => void ordersQuery.refetch()}>Try again</button></div>}
    {ordersQuery.data && <div className="admin-table-wrap"><table className="admin-table"><caption>Order queue</caption><thead><tr><th scope="col">Order</th><th scope="col">Delivery</th><th scope="col">Employee</th><th scope="col">Total</th><th scope="col">Status</th><th scope="col">Actions</th></tr></thead><tbody>
      {orders.map((order) => <tr key={order.id}><td><strong><Link to={`/console/orders/${order.id}`}>{order.orderNumber}</Link></strong><span className="table-subtext">{order.items.length} {order.items.length === 1 ? 'item' : 'items'}</span></td><td>{order.deliveryDate} · {order.mealSlot.toLowerCase()}</td><td>{order.userDisplayName}</td><td>{formatCny(moneyToFen(order.total))}</td><td><span className={`status-pill status-${order.status.toLowerCase()}`}>{order.status[0] + order.status.slice(1).toLowerCase()}</span></td><td className="table-actions">
        {nextActions[order.status].map((targetStatus) => <button className={targetStatus === 'CANCELLED' ? 'text-link danger-action' : 'text-link'} key={targetStatus} aria-label={`${actionNames[targetStatus]} ${order.orderNumber}`} disabled={transitionMutation.isPending} onClick={() => transitionMutation.mutate({ order, targetStatus })}>{actionNames[targetStatus]}</button>)}
      </td></tr>)}
    </tbody></table>{orders.length === 0 && <p className="empty-note">No orders match these filters.</p>}</div>}
    {ordersQuery.data && ordersQuery.data.totalPages > 1 && <nav className="pagination" aria-label="Order pages"><button className="button button-secondary" disabled={page === 0} onClick={() => setPage((value) => value - 1)}>Previous</button><span>Page {ordersQuery.data.page + 1} of {ordersQuery.data.totalPages}</span><button className="button button-secondary" disabled={page + 1 >= ordersQuery.data.totalPages} onClick={() => setPage((value) => value + 1)}>Next</button></nav>}
  </main>;
}
