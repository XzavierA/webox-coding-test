import { Link } from 'react-router-dom';
import { formatCny } from '../../shared/money';
import { useCartStore } from './store';

export function CartPage() {
  const lines = useCartStore((state) => state.lines);
  const totalFen = useCartStore((state) => state.totalFen());
  const itemCount = useCartStore((state) => state.itemCount());
  const setQuantity = useCartStore((state) => state.setQuantity);
  const removeItem = useCartStore((state) => state.removeItem);

  if (!lines.length) {
    return (
      <main className="page-content cart-page">
        <section className="empty-cart-card">
          <div className="empty-cart-illustration" aria-hidden="true">✳</div>
          <p className="eyebrow">A GOOD THING IS AHEAD</p>
          <h1>Your order is ready when you are.</h1>
          <p>Find a meal you love and it’ll be waiting right here.</p>
          <Link className="button button-primary" to="/menu">Explore the menu</Link>
        </section>
      </main>
    );
  }

  return (
    <main className="page-content cart-page">
      <div className="page-heading compact-heading">
        <div><p className="eyebrow">YOUR SELECTION</p><h1>Your order</h1><p>{itemCount} {itemCount === 1 ? 'item' : 'items'} selected</p></div>
        <Link className="text-link" to="/menu">Add another dish <span aria-hidden="true">↗</span></Link>
      </div>
      <div className="cart-layout">
        <section className="cart-lines" aria-label="Items in your order">
          {lines.map((line) => (
            <article className="cart-line" key={line.identity}>
              <img src={line.imageUrl || '/meal-placeholder.svg'} alt="" />
              <div className="cart-line-content">
                <div className="cart-line-heading"><div><h2>{line.name}</h2><p>{line.optionNames.length ? line.optionNames.join(' · ') : 'Prepared fresh for you'}</p></div>
                  <strong>{formatCny(line.unitPriceFen * line.quantity)}</strong></div>
                <div className="cart-line-actions">
                  <div className="quantity-control" aria-label={`${line.name} quantity`}>
                    <button
                      aria-label={`Decrease ${line.name} quantity`}
                      disabled={line.quantity <= 1}
                      onClick={() => setQuantity(line.identity, line.quantity - 1)}
                    >−</button>
                    <span>{line.quantity}</span>
                    <button
                      aria-label={`Increase ${line.name} quantity`}
                      disabled={itemCount >= 5}
                      onClick={() => setQuantity(line.identity, line.quantity + 1)}
                    >+</button>
                  </div>
                  <button className="remove-link" onClick={() => removeItem(line.identity)}>Remove</button>
                  <button className="sr-only" aria-label={`Remove ${line.name} from order`} onClick={() => removeItem(line.identity)}>Remove {line.name}</button>
                </div>
              </div>
            </article>
          ))}
        </section>
        <aside className="order-summary" aria-label="Order summary">
          <p className="eyebrow">THE TOTAL</p>
          <h2>Order summary</h2>
          <div><span>Items ({itemCount})</span><span>{formatCny(totalFen)}</span></div>
          <div><span>Delivery</span><span>Included</span></div>
          <div className="summary-total"><span>Total</span><strong>{formatCny(totalFen)}</strong></div>
          <Link className="button button-primary checkout-link" to="/checkout">Continue to checkout <span aria-hidden="true">→</span></Link>
          <p className="summary-note">Your total is confirmed before your order is placed.</p>
        </aside>
      </div>
    </main>
  );
}
