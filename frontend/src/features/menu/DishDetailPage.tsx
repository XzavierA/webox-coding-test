import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { apiRequest } from '../../api/client';
import type { Allergen, DishDetail, OptionGroup, Preference } from '../../api/types';
import { formatCny, moneyToFen } from '../../shared/money';
import { addCartItem, useCartStore } from '../cart/store';

const allergenNames: Record<Allergen, string> = {
  PEANUTS: 'Peanuts', DAIRY: 'Dairy', EGG: 'Egg', GLUTEN: 'Gluten', SOY: 'Soy', FISH: 'Fish', SHELLFISH: 'Shellfish',
};

function selectionIsValid(group: OptionGroup, selected: number[]): boolean {
  const minimum = group.required ? Math.max(group.minSelections, 1) : group.minSelections;
  return selected.length >= minimum && selected.length <= group.maxSelections;
}

export function DishDetailPage() {
  const { dishId = '' } = useParams();
  const id = Number(dishId);
  const [selected, setSelected] = useState<Record<number, number[]>>({});
  const [validationError, setValidationError] = useState('');
  const [allergenConfirmation, setAllergenConfirmation] = useState<string[] | null>(null);
  const [notice, setNotice] = useState('');
  const [adding, setAdding] = useState(false);
  const addTimer = useRef<number | null>(null);
  const dishQuery = useQuery({
    queryKey: ['dish-detail', id],
    queryFn: () => apiRequest<DishDetail>(`/app/menu/dishes/${id}`),
    enabled: Number.isInteger(id) && id > 0,
  });
  const preferencesQuery = useQuery({
    queryKey: ['preferences'],
    queryFn: () => apiRequest<Preference>('/app/preferences'),
  });
  const dish = dishQuery.data;
  const selectedIds = useMemo(() => Object.values(selected).flat().sort((a, b) => a - b), [selected]);
  const selectedValues = dish?.optionGroups.flatMap((group) => group.values.filter((value) => selected[group.id]?.includes(value.id))) ?? [];
  const unitPriceFen = dish
    ? moneyToFen(dish.basePrice) + selectedValues.reduce((total, value) => total + moneyToFen(value.priceDelta), 0)
    : 0;
  const optionsValid = dish?.optionGroups.every((group) => selectionIsValid(group, selected[group.id] ?? [])) ?? false;
  const servings = useCartStore((state) => state.itemCount());

  useEffect(() => () => {
    if (addTimer.current !== null) window.clearTimeout(addTimer.current);
  }, []);

  function toggleOption(group: OptionGroup, valueId: number) {
    setSelected((current) => {
      const currentIds = current[group.id] ?? [];
      if (group.selectionType === 'SINGLE') return { ...current, [group.id]: [valueId] };
      return {
        ...current,
        [group.id]: currentIds.includes(valueId)
          ? currentIds.filter((idValue) => idValue !== valueId)
          : [...currentIds, valueId],
      };
    });
    setValidationError('');
  }

  function commitAdd() {
    if (!dish) return;
    const optionNames = dish.optionGroups.flatMap((group) => group.values
      .filter((value) => selected[group.id]?.includes(value.id))
      .map((value) => `${group.name}: ${value.name}`));
    const result = addCartItem({
      dishId: dish.dishId,
      name: dish.name,
      imageUrl: dish.imageUrl,
      basePriceFen: moneyToFen(dish.basePrice),
      unitPriceFen,
      optionValueIds: selectedIds,
      optionNames,
      allergens: dish.allergens,
      quantity: 1,
    });
    setAllergenConfirmation(null);
    if (result === 'MAX_ITEMS') {
      setNotice('Maximum 5 items per order.');
      return;
    }
    setNotice('Added to your order.');
    setAdding(true);
    addTimer.current = window.setTimeout(() => {
      addTimer.current = null;
      setAdding(false);
    }, 300);
  }

  function addToOrder() {
    if (!dish || adding) return;
    if (servings >= 5) {
      setNotice('Maximum 5 items per order.');
      return;
    }
    if (!optionsValid) {
      setValidationError('Choose the required options before adding this dish.');
      return;
    }
    const matches = dish.allergens.filter((allergen) => preferencesQuery.data?.allergens.includes(allergen));
    if (matches.length) setAllergenConfirmation(matches.map((allergen) => allergenNames[allergen]));
    else commitAdd();
  }

  if (dishQuery.isPending) return <main className="page-content"><div className="menu-skeleton" aria-label="Loading dish" aria-busy="true" /></main>;
  if (dishQuery.isError || !dish) return <main className="page-content"><div className="empty-state" role="alert"><h1>Dish unavailable</h1><p>This dish could not be loaded for the selected delivery date.</p><Link to="/menu">Back to the menu</Link></div></main>;

  return (
    <main className="page-content dish-detail-page">
      <Link className="back-link" to="/menu">← Back to menu</Link>
      <div className="dish-detail-layout">
        <div className="detail-image"><img src={dish.imageUrl || '/meal-placeholder.svg'} alt={dish.name} /></div>
        <section className="detail-information">
          <p className="eyebrow">{dish.proteinSource} · {dish.category.replaceAll('_', ' ')}</p>
          <h1>{dish.name}</h1>
          <p className="detail-description">{dish.description}</p>
          {dish.allergens.length > 0 && <p className="allergen-note">Contains {dish.allergens.map((allergen) => allergenNames[allergen]).join(', ')}.</p>}
          {dish.optionGroups.map((group) => (
            <fieldset className="option-group" key={group.id}>
              <legend>{group.name}<span>{group.required ? 'Required' : 'Optional'}</span></legend>
              {group.values.map((value) => {
                const checked = selected[group.id]?.includes(value.id) ?? false;
                const Input = group.selectionType === 'SINGLE' ? 'radio' : 'checkbox';
                const atLimit = group.selectionType === 'MULTIPLE' && (selected[group.id]?.length ?? 0) >= group.maxSelections && !checked;
                return (
                  <label className={`option-row${checked ? ' checked' : ''}`} key={value.id}>
                    <input
                      type={Input}
                      name={`option-${group.id}`}
                      checked={checked}
                      disabled={atLimit}
                      onChange={() => toggleOption(group, value.id)}
                    />
                    <span>{value.name}</span>
                    <span className="option-price">{moneyToFen(value.priceDelta) === 0 ? 'Included' : `+${formatCny(moneyToFen(value.priceDelta))}`}</span>
                  </label>
                );
              })}
            </fieldset>
          ))}
          {validationError && <p className="form-error" role="alert">{validationError}</p>}
          {notice && <p className={notice.startsWith('Maximum') ? 'form-error' : 'success-note'} role="status">{notice}</p>}
          <div className="detail-actions">
            <div><span>Current price</span><strong>{formatCny(unitPriceFen)}</strong></div>
            <button className="button button-primary" type="button" disabled={dish.soldOut || !optionsValid || adding || servings >= 5} onClick={addToOrder}>
              {dish.soldOut ? 'Sold out' : servings >= 5 ? 'Maximum 5 items' : adding ? 'Added' : 'Add to order'}
            </button>
          </div>
        </section>
      </div>
      {allergenConfirmation && (
        <div className="dialog-scrim">
          <section className="confirmation-dialog" role="dialog" aria-modal="true" aria-labelledby="allergen-title">
            <span className="dialog-icon" aria-hidden="true">!</span>
            <h2 id="allergen-title">Allergen match</h2>
            <p>This dish contains allergens in your profile: <strong>{allergenConfirmation.join(', ')}</strong>.</p>
            <p>Please confirm that you still want to add it to your order.</p>
            <div className="dialog-actions">
              <button className="button button-secondary" onClick={() => setAllergenConfirmation(null)}>Cancel</button>
              <button className="button button-primary" onClick={commitAdd}>Add anyway</button>
            </div>
          </section>
        </div>
      )}
    </main>
  );
}
