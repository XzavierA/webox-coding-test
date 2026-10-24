import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { apiRequest, ProblemError } from '../../../api/client';
import type { AdminDailyMenu, AdminDishPage, DailyMenuItemWrite } from '../../../api/types';

function businessTomorrow(): string {
  const today = new Date(new Date().toLocaleString('en-US', { timeZone: 'Asia/Shanghai' }));
  today.setDate(today.getDate() + 1);
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(today);
}

export function DailyMenuPage() {
  const queryClient = useQueryClient();
  const [date, setDate] = useState(businessTomorrow());
  const [items, setItems] = useState<DailyMenuItemWrite[]>([]);
  const [dishToAdd, setDishToAdd] = useState('');
  const [message, setMessage] = useState('');
  const menuKey = ['admin-menu', date] as const;
  const menuQuery = useQuery({
    queryKey: menuKey,
    queryFn: async () => {
      try { return await apiRequest<AdminDailyMenu>(`/admin/menus/${encodeURIComponent(date)}`); }
      catch (error) {
        if (error instanceof ProblemError && error.problem.status === 404) return { date, status: 'DRAFT', publicationVersion: 0, items: [] } satisfies AdminDailyMenu;
        throw error;
      }
    },
  });
  const dishesQuery = useQuery({
    queryKey: ['admin-menu-dishes'],
    queryFn: () => apiRequest<AdminDishPage>('/admin/dishes?page=0&size=100&published=true'),
  });
  useEffect(() => {
    if (menuQuery.data) setItems(menuQuery.data.items.map((item) => ({ dishId: item.dishId, initialStock: item.initialStock, visible: item.visible })));
  }, [menuQuery.data]);
  const saveMutation = useMutation({
    mutationFn: () => apiRequest<AdminDailyMenu>(`/admin/menus/${encodeURIComponent(date)}`, {
      method: 'PUT', body: JSON.stringify({ expectedPublicationVersion: menuQuery.data?.publicationVersion ?? 0, items }),
    }),
    onSuccess: async (menu) => {
      queryClient.setQueryData(menuKey, menu);
      setItems(menu.items.map((item) => ({ dishId: item.dishId, initialStock: item.initialStock, visible: item.visible })));
      setMessage('Daily menu saved.');
    },
    onError: async (error) => {
      setMessage(error instanceof ProblemError && error.problem.status === 409 ? 'This menu changed elsewhere. The latest menu has been loaded.' : 'We couldn’t save this daily menu.');
      await queryClient.invalidateQueries({ queryKey: menuKey });
    },
  });
  const publishMutation = useMutation({
    mutationFn: () => apiRequest<AdminDailyMenu>(`/admin/menus/${encodeURIComponent(date)}/publish`, { method: 'POST' }),
    onSuccess: (menu) => { queryClient.setQueryData(menuKey, menu); setMessage('Daily menu published.'); },
    onError: () => setMessage('The menu could not be published. Save a valid menu and try again.'),
  });
  const dishes = dishesQuery.data?.items ?? [];

  function updateItem(dishId: number, update: Partial<DailyMenuItemWrite>) {
    setItems((current) => current.map((item) => item.dishId === dishId ? { ...item, ...update } : item));
  }
  function addDish() {
    const dishId = Number(dishToAdd);
    if (!dishId || items.some((item) => item.dishId === dishId)) return;
    setItems((current) => [...current, { dishId, initialStock: 0, visible: true }]);
    setDishToAdd('');
  }

  return <main className="page-content daily-menu-page">
    <section className="page-heading compact-heading"><div><p className="eyebrow">ADMIN CONSOLE · DAILY MENU</p><h1>Plan a good day.</h1><p>Choose dishes, set stock, and publish the menu for your team.</p></div>
      {menuQuery.data && <span className={`status-pill status-${menuQuery.data.status === 'PUBLISHED' ? 'confirmed' : 'pending'}`}>{menuQuery.data.status === 'PUBLISHED' ? 'Published' : 'Draft'}</span>}
    </section>
    <section className="daily-menu-toolbar"><label>Menu date<input aria-label="Menu date" type="date" value={date} onChange={(event) => { setDate(event.target.value); setMessage(''); }} /></label>
      <div className="add-menu-item"><label>Add a published dish<select aria-label="Add a published dish" value={dishToAdd} onChange={(event) => setDishToAdd(event.target.value)}><option value="">Choose a dish</option>{dishes.filter((dish) => !items.some((item) => item.dishId === dish.id)).map((dish) => <option value={dish.id} key={dish.id}>{dish.name}</option>)}</select></label><button className="button button-secondary" disabled={!dishToAdd} onClick={addDish}>Add dish</button></div>
    </section>
    {message && <p role="status" className={message.includes('couldn’t') || message.includes('could not') ? 'form-error' : 'success-note'}>{message}</p>}
    {menuQuery.isPending && <div className="menu-skeleton" aria-label="Loading daily menu" aria-busy="true" />}
    {menuQuery.isError && <div className="empty-state" role="alert"><h2>Daily menu unavailable.</h2><button className="button button-secondary" onClick={() => void menuQuery.refetch()}>Try again</button></div>}
    {menuQuery.data && <section className="daily-menu-list" aria-label="Menu dishes">
      {items.map((item) => {
        const dish = dishes.find((candidate) => candidate.id === item.dishId);
        const existing = menuQuery.data?.items.find((candidate) => candidate.dishId === item.dishId);
        return <article className="daily-menu-row" key={item.dishId}><div className="catalog-dish-cell"><img src={dish?.imageUrl || '/meal-placeholder.svg'} alt="" /><div><strong>{dish?.name ?? existing?.dishName ?? `Dish ${item.dishId}`}</strong><span>Currently remaining: {existing?.remainingStock ?? 0}</span></div></div>
          <label>Initial stock<input aria-label={`Initial stock for ${dish?.name ?? existing?.dishName ?? `dish ${item.dishId}`}`} type="number" min="0" value={item.initialStock} onChange={(event) => updateItem(item.dishId, { initialStock: Math.max(0, Number(event.target.value)) })} /></label>
          <label className="inline-check"><input type="checkbox" checked={item.visible} onChange={(event) => updateItem(item.dishId, { visible: event.target.checked })} /> Visible</label>
          <button className="text-link danger-action" aria-label={`Remove ${dish?.name ?? existing?.dishName ?? `dish ${item.dishId}`}`} onClick={() => setItems((current) => current.filter((candidate) => candidate.dishId !== item.dishId))}>Remove</button>
        </article>;
      })}
      {items.length === 0 && <div className="empty-state"><h2>No dishes in this menu.</h2><p>Add published dishes to get started.</p></div>}
    </section>}
    {menuQuery.data && <div className="menu-save-actions"><button className="button button-primary" disabled={saveMutation.isPending} onClick={() => saveMutation.mutate()}>{saveMutation.isPending ? 'Saving…' : 'Save menu'}</button><button className="button button-secondary" disabled={publishMutation.isPending || !items.length} onClick={() => publishMutation.mutate()}>{publishMutation.isPending ? 'Publishing…' : menuQuery.data.status === 'PUBLISHED' ? 'Publish updates' : 'Publish menu'}</button></div>}
  </main>;
}
