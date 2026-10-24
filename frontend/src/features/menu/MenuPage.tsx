import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiRequest } from '../../api/client';
import { getAccessToken } from '../../api/client';
import { applyStockChange, startStockStream } from '../../api/sse';
import type { Category, MenuPage as MenuPageResponse } from '../../api/types';
import { formatCny, moneyToFen } from '../../shared/money';
import { buildMenuQuery, useDebouncedValue } from './query';

const categories: Array<{ value: Category; label: string }> = [
  { value: 'CHINESE', label: 'Chinese' },
  { value: 'JAPANESE', label: 'Japanese' },
  { value: 'WESTERN', label: 'Western' },
  { value: 'KOREAN', label: 'Korean' },
  { value: 'SOUTHEAST_ASIAN', label: 'Southeast Asian' },
  { value: 'LIGHT_MEAL', label: 'Light meal' },
];

function businessDate(): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(new Date());
}

export function MenuPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [date, setDate] = useState(businessDate);
  const [searchInput, setSearchInput] = useState('');
  const [selectedCategories, setSelectedCategories] = useState<Category[]>([]);
  const [recommended, setRecommended] = useState(false);
  const [page, setPage] = useState(0);
  const search = useDebouncedValue(searchInput, 250);
  const query = useQuery({
    queryKey: ['menu', date, search, selectedCategories, recommended, page],
    queryFn: ({ signal }) => {
      const params = buildMenuQuery({
        date, query: search, categories: selectedCategories, recommended, page, size: 12,
      });
      return apiRequest<MenuPageResponse>(`/app/menu?${params.toString()}`, { signal });
    },
    placeholderData: keepPreviousData,
    refetchInterval: 30_000,
  });

  useEffect(() => {
    if (!getAccessToken()) return undefined;
    const controller = new AbortController();
    void startStockStream(date, controller.signal, (event) => {
      queryClient.setQueriesData<MenuPageResponse>({ queryKey: ['menu', date] }, (current) => current
        ? { ...current, items: applyStockChange(current.items, date, event) }
        : current);
    }, () => { void queryClient.invalidateQueries({ queryKey: ['menu', date] }); });
    return () => controller.abort();
  }, [date, queryClient]);

  function toggleCategory(category: Category) {
    setSelectedCategories((selected) => selected.includes(category)
      ? selected.filter((item) => item !== category)
      : [...selected, category]);
    setPage(0);
  }

  return (
    <main className="page-content menu-page">
      <section className="page-heading menu-heading">
        <div>
          <p className="eyebrow">YOUR DAILY PICK</p>
          <h1>Good afternoon, good food.</h1>
          <p>Thoughtfully prepared meals for the middle of your day.</p>
        </div>
        <label className="date-picker">Delivery date
          <input aria-label="Delivery date" type="date" value={date} onChange={(event) => { setDate(event.target.value); setPage(0); }} />
        </label>
      </section>

      <section className="menu-controls" aria-label="Menu filters">
        <label className="search-field">
          <span aria-hidden="true">⌕</span>
          <input
            aria-label="Search dishes"
            placeholder="Search today’s menu"
            maxLength={50}
            value={searchInput}
            onChange={(event) => { setSearchInput(event.target.value.slice(0, 50)); setPage(0); }}
          />
          {searchInput && <button type="button" aria-label="Clear search" onClick={() => setSearchInput('')}>×</button>}
        </label>
        <label className="recommend-toggle">
          <input type="checkbox" checked={recommended} onChange={(event) => { setRecommended(event.target.checked); setPage(0); }} />
          <span>Recommended for me</span>
        </label>
      </section>

      <div className="category-list" aria-label="Dish categories">
        {categories.map((category) => (
          <button
            className={`category-chip${selectedCategories.includes(category.value) ? ' selected' : ''}`}
            key={category.value}
            type="button"
            aria-pressed={selectedCategories.includes(category.value)}
            onClick={() => toggleCategory(category.value)}
          >{category.label}</button>
        ))}
      </div>

      <div className="results-caption">
        <h2>{query.data?.totalItems ?? 'Today’s'} <span>meals to make your day</span></h2>
        {query.data && <span>{query.data.totalItems} {query.data.totalItems === 1 ? 'dish' : 'dishes'}</span>}
      </div>

      {query.isPending && <div className="menu-skeletons" aria-label="Loading menu" aria-busy="true">{[1, 2, 3].map((item) => <div className="menu-skeleton" key={item} />)}</div>}
      {query.isError && <div className="empty-state" role="alert"><h2>We couldn’t load the menu.</h2><p>Check your connection and try again.</p><button className="button button-secondary" onClick={() => void query.refetch()}>Try again</button></div>}
      {query.data && query.data.items.length === 0 && <div className="empty-state"><h2>No dishes found.</h2><p>Try a different search or remove a filter.</p></div>}
      {query.data && query.data.items.length > 0 && (
        <div className="dish-grid" aria-busy={query.isFetching}>
          {query.data.items.map((dish) => (
            <article className="dish-card" key={dish.menuItemId}>
              <div className="dish-image-wrap">
                <img src={dish.imageUrl || '/meal-placeholder.svg'} alt={dish.name} loading="lazy" onError={(event) => { event.currentTarget.src = '/meal-placeholder.svg'; }} />
                {dish.lowStock && !dish.soldOut && <span className="stock-badge low-stock">Only {dish.remainingStock} left</span>}
                {dish.soldOut && <span className="stock-badge sold-out">Sold out</span>}
              </div>
              <div className="dish-card-body">
                <div className="dish-meta"><span>{categories.find((item) => item.value === dish.category)?.label}</span><span>{dish.spiceLevel === 'NONE' ? 'Not spicy' : `${dish.spiceLevel.toLowerCase()} spice`}</span></div>
                <h3>{dish.name}</h3>
                <p>{dish.description}</p>
                {recommended && dish.preferenceMatches.length > 0 && <div className="match-tags">{dish.preferenceMatches.map((match) => <span key={match}>✓ {match}</span>)}</div>}
                <div className="dish-card-bottom">
                  <strong>{formatCny(moneyToFen(dish.basePrice))}</strong>
                  <button
                    className="button button-small button-primary"
                    type="button"
                    aria-label={dish.soldOut ? `Unavailable: ${dish.name}` : `Choose meal: ${dish.name}`}
                    disabled={dish.soldOut}
                    onClick={() => navigate(`/dishes/${dish.dishId}`)}
                  >{dish.soldOut ? 'Unavailable' : 'Choose meal'} <span aria-hidden="true">↗</span></button>
                </div>
              </div>
            </article>
          ))}
        </div>
      )}

      {query.data && query.data.totalPages > 1 && (
        <nav className="pagination" aria-label="Menu pages">
          <button className="button button-secondary" disabled={page === 0} onClick={() => setPage((current) => current - 1)}>Previous</button>
          <span>Page {query.data.page + 1} of {query.data.totalPages}</span>
          <button className="button button-secondary" disabled={page + 1 >= query.data.totalPages} onClick={() => setPage((current) => current + 1)}>Next</button>
        </nav>
      )}
    </main>
  );
}
