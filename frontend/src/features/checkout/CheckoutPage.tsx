import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { apiRequest, ProblemError } from '../../api/client';
import type { Address, MealSlot, Order, OrderQuote, OrderRequest, OrderSubmission, Preference } from '../../api/types';
import { formatCny, moneyToFen } from '../../shared/money';
import { useCartStore } from '../cart/store';
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from './idempotency';

const slots: Array<{ value: MealSlot; label: string }> = [
  { value: 'LUNCH', label: 'Lunch' }, { value: 'DINNER', label: 'Dinner' },
];

function nextBusinessDate(): string {
  const date = new Date(new Date().toLocaleString('en-US', { timeZone: 'Asia/Shanghai' }));
  date.setDate(date.getDate() + 1);
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(date);
}

function displayDate(date: string, slot: MealSlot): string {
  const formatted = new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', month: 'long', day: 'numeric', year: 'numeric' })
    .format(new Date(`${date}T00:00:00Z`));
  return `${formatted} at ${slot === 'LUNCH' ? 'Lunch' : 'Dinner'}`;
}

export function CheckoutPage() {
  const navigate = useNavigate();
  const lines = useCartStore((state) => state.lines);
  const totalFen = useCartStore((state) => state.totalFen());
  const clearCart = useCartStore((state) => state.clear);
  const [deliveryDate, setDeliveryDate] = useState(nextBusinessDate());
  const [mealSlot, setMealSlot] = useState<MealSlot>('LUNCH');
  const [addressId, setAddressId] = useState<number | ''>('');
  const [newAddressText, setNewAddressText] = useState('');
  const [saveAddress, setSaveAddress] = useState(true);
  const [quote, setQuote] = useState<OrderQuote | null>(null);
  const [acceptedSlot, setAcceptedSlot] = useState(false);
  const [confirmation, setConfirmation] = useState<'idle' | 'processing' | 'complete' | 'failed'>('idle');
  const [confirmedOrder, setConfirmedOrder] = useState<Order | null>(null);
  const [message, setMessage] = useState('');
  const addressesQuery = useQuery({ queryKey: ['addresses'], queryFn: () => apiRequest<Address[]>('/app/addresses') });
  const preferencesQuery = useQuery({ queryKey: ['preferences'], queryFn: () => apiRequest<Preference>('/app/preferences') });
  useEffect(() => {
    const defaultAddress = addressesQuery.data?.find((address) => address.isDefault);
    if (addressId === '' && defaultAddress) setAddressId(defaultAddress.id);
  }, [addressesQuery.data, addressId]);

  const orderRequest = useMemo<OrderRequest | null>(() => {
    if (!lines.length) return null;
    const selectedAddress = addressId === '' ? null : Number(addressId);
    const newAddress = selectedAddress === null && newAddressText.trim()
      ? { label: 'Delivery', addressText: newAddressText.trim(), isDefault: false, save: saveAddress }
      : null;
    return {
      deliveryDate, mealSlot, addressId: selectedAddress, newAddress,
      acceptedResolvedDeliveryDate: acceptedSlot ? quote?.resolvedDeliveryDate ?? null : null,
      acceptedResolvedMealSlot: acceptedSlot ? quote?.resolvedMealSlot ?? null : null,
      items: lines.map((line) => ({ dishId: line.dishId, quantity: line.quantity, optionValueIds: line.optionValueIds })),
    };
  }, [lines, addressId, newAddressText, saveAddress, deliveryDate, mealSlot, acceptedSlot, quote]);

  const quoteMutation = useMutation({
    mutationFn: (request: OrderRequest) => apiRequest<OrderQuote>('/app/orders/quote', { method: 'POST', body: JSON.stringify(request) }),
    onSuccess: (nextQuote) => {
      setQuote(nextQuote);
      setAcceptedSlot(false);
      setMessage('');
      if (nextQuote.existingOrder) navigate(`/orders/${nextQuote.existingOrder.id}`);
    },
    onError: () => setMessage('We couldn’t prepare your quote. Review your details and try again.'),
  });

  async function pollSubmission(submissionId: string): Promise<void> {
    for (let attempt = 0; attempt < 30; attempt += 1) {
      const result = await apiRequest<OrderSubmission>(`/app/order-submissions/${encodeURIComponent(submissionId)}`);
      if (result.state === 'COMPLETED' && result.order) {
        setConfirmedOrder(result.order);
        setConfirmation('complete');
        clearIdempotencyKey(window.sessionStorage);
        clearCart();
        return;
      }
      if (result.state === 'FAILED') {
        setConfirmation('failed');
        setMessage(result.failure?.detail || 'Your order could not be confirmed. Your cart is ready to edit.');
        clearIdempotencyKey(window.sessionStorage);
        return;
      }
      await new Promise((resolve) => window.setTimeout(resolve, 150));
    }
    setConfirmation('failed');
    setMessage('Your order is still processing. Retry safely with the same order details.');
  }

  async function placeOrder(): Promise<void> {
    if (!orderRequest) return;
    setMessage('');
    setConfirmation('processing');
    const fingerprint = JSON.stringify(orderRequest);
    const key = getOrCreateIdempotencyKey(window.sessionStorage, fingerprint);
    try {
      const body = await apiRequest<Order | OrderSubmission>('/app/orders', {
        method: 'POST',
        headers: { 'Idempotency-Key': key },
        body: JSON.stringify(orderRequest),
      });
      if ('submissionId' in body) {
        setConfirmation('processing');
        await pollSubmission((body as OrderSubmission).submissionId);
      } else {
        const order = body as Order;
        setConfirmedOrder(order);
        setConfirmation('complete');
        clearIdempotencyKey(window.sessionStorage);
        clearCart();
      }
    } catch (error) {
      setConfirmation('failed');
      setMessage(error instanceof ProblemError ? error.problem.detail : error instanceof Error ? error.message : 'Your order could not be placed. Please try again.');
    }
  }

  if (confirmation === 'complete' && confirmedOrder) {
    return <main className="page-content checkout-page"><section className="confirmation-card" role="status">
      <p className="eyebrow">ORDER CONFIRMED</p><h1>Your order is confirmed.</h1>
      <p>Order {confirmedOrder.orderNumber} is scheduled for {displayDate(confirmedOrder.deliveryDate, confirmedOrder.mealSlot)}.</p>
      <Link className="button button-primary" to={`/orders/${confirmedOrder.id}`}>View your order</Link>
    </section></main>;
  }

  if (!lines.length) return <main className="page-content checkout-page"><section className="empty-state"><h1>Your cart is empty.</h1><p>Add a meal before checking out.</p><Link to="/menu">Browse the menu</Link></section></main>;

  const adjusted = Boolean(quote?.slotAdjusted && (quote.resolvedDeliveryDate !== deliveryDate || quote.resolvedMealSlot !== mealSlot));
  const budgetMax = preferencesQuery.data?.budgetMax ? moneyToFen(preferencesQuery.data.budgetMax) : null;
  const overBudget = quote && budgetMax !== null && moneyToFen(quote.total) > budgetMax;

  return <main className="page-content checkout-page">
    <div className="page-heading compact-heading"><div><p className="eyebrow">A FEW LAST DETAILS</p><h1>Almost time to eat.</h1><p>Your total and delivery details are confirmed before placement.</p></div></div>
    <div className="checkout-layout">
      <section className="checkout-form-card" aria-label="Delivery details">
        <h2>Delivery</h2>
        <label>Saved address
          <select aria-label="Saved address" value={addressId} onChange={(event) => setAddressId(event.target.value ? Number(event.target.value) : '')}>
            <option value="">Enter a new address</option>
            {(addressesQuery.data ?? []).map((address) => <option key={address.id} value={address.id}>{address.label} — {address.addressText}</option>)}
          </select>
        </label>
        {addressId === '' && <>
          <label>Delivery address<input aria-label="Delivery address" value={newAddressText} onChange={(event) => setNewAddressText(event.target.value.slice(0, 200))} maxLength={200} /></label>
          <label className="inline-check"><input type="checkbox" checked={saveAddress} onChange={(event) => setSaveAddress(event.target.checked)} /> Save this address for next time</label>
        </>}
        <label>Delivery date<input aria-label="Delivery date" type="date" value={deliveryDate} onChange={(event) => { setDeliveryDate(event.target.value); setQuote(null); setAcceptedSlot(false); }} /></label>
        <label>Meal slot<select aria-label="Meal slot" value={mealSlot} onChange={(event) => { setMealSlot(event.target.value as MealSlot); setQuote(null); setAcceptedSlot(false); }}>
          {slots.map((slot) => <option value={slot.value} key={slot.value}>{slot.label}</option>)}
        </select></label>
        <button className="button button-secondary" type="button" disabled={quoteMutation.isPending || (!addressId && !newAddressText.trim())} onClick={() => orderRequest && quoteMutation.mutate(orderRequest)}>
          {quoteMutation.isPending ? 'Preparing quote…' : 'Get order quote'}
        </button>
      </section>
      <aside className="order-summary checkout-summary" aria-label="Order summary">
        <p className="eyebrow">YOUR ORDER</p><h2>Order summary</h2>
        {lines.map((line) => <div className="checkout-line" key={line.identity}><span>{line.name} × {line.quantity}</span><span>{formatCny(line.unitPriceFen * line.quantity)}</span></div>)}
        <div className="summary-total"><span>Estimated total</span><strong>{formatCny(quote ? moneyToFen(quote.total) : totalFen)}</strong></div>
        {overBudget && <p className="budget-warning" role="status">This order is above your preferred budget of {formatCny(budgetMax!)}.</p>}
        {quote && <>
          {adjusted && <div className="slot-warning" role="alert"><p>The requested time has changed to {displayDate(quote.resolvedDeliveryDate, quote.resolvedMealSlot)}.</p>
            <label className="inline-check"><input type="checkbox" checked={acceptedSlot} onChange={(event) => setAcceptedSlot(event.target.checked)} /> Accept the adjusted delivery time</label>
          </div>}
          {quote.unavailableItems.length > 0 && <div className="form-error" role="alert"><p>Some items are no longer available. Return to your cart to edit:</p><ul>{quote.unavailableItems.map((item) => <li key={item.dishId}>{item.name}: {item.remaining} available, {item.requested} requested</li>)}</ul><Link to="/cart">Edit your cart</Link></div>}
          {quote.existingOrder && <p role="status">An order already exists for this delivery slot. Opening your order details.</p>}
          <button className="button button-primary checkout-submit" type="button" disabled={confirmation === 'processing' || (adjusted && !acceptedSlot) || quote.unavailableItems.length > 0 || Boolean(quote.existingOrder)} onClick={() => void placeOrder()}>
            {confirmation === 'processing' ? 'Placing order…' : 'Place order'}
          </button>
        </>}
        {confirmation === 'processing' && <p role="status">Confirming your order…</p>}
        {message && <div className="form-error" role="alert">{message}</div>}
        {confirmation === 'failed' && quote && <button className="button button-secondary" onClick={() => void placeOrder()}>Retry order</button>}
      </aside>
    </div>
  </main>;
}
