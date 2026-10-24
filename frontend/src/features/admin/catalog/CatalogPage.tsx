import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { apiRequest, ProblemError } from '../../../api/client';
import type { AdminDish, AdminDishPage, Allergen, Category, DishWrite, SpiceLevel } from '../../../api/types';
import { formatCny, moneyToFen } from '../../../shared/money';

const categories: Category[] = ['CHINESE', 'WESTERN', 'JAPANESE', 'LIGHT_MEAL', 'KOREAN', 'SOUTHEAST_ASIAN'];
const allergens: Allergen[] = ['PEANUTS', 'DAIRY', 'EGG', 'GLUTEN', 'SOY', 'FISH', 'SHELLFISH'];
const spices: SpiceLevel[] = ['NONE', 'MILD', 'MEDIUM', 'HOT'];
const emptyDish: DishWrite = {
  name: '', description: '', basePrice: '0.00', category: 'CHINESE', proteinSource: '', spiceLevel: 'NONE',
  allergens: [], imageUrl: '', published: false, version: 0, optionGroups: [],
};
const emptyOptions = '[]';

function validOptionGroups(value: unknown): value is DishWrite['optionGroups'] {
  if (!Array.isArray(value)) return false;
  return value.every((group) => group && typeof group.name === 'string' && group.name.trim().length > 0 && group.name.length <= 80 &&
    (group.id === undefined || group.id === null || Number.isInteger(group.id) && group.id > 0) &&
    ['SINGLE', 'MULTIPLE'].includes(group.selectionType) && typeof group.required === 'boolean' &&
    Number.isInteger(group.minSelections) && group.minSelections >= 0 && Number.isInteger(group.maxSelections) && group.maxSelections >= 1 && group.maxSelections <= 20 &&
    group.minSelections <= group.maxSelections && (!group.required || group.minSelections >= 1) &&
    (group.selectionType !== 'SINGLE' || group.maxSelections === 1) && Number.isInteger(group.displayOrder) && group.displayOrder >= 0 &&
    typeof group.active === 'boolean' && Array.isArray(group.values) && group.values.length > 0 &&
    group.values.every((item: unknown) => {
      if (!item || typeof item !== 'object') return false;
      const option = item as Record<string, unknown>;
      return (option.id === undefined || option.id === null || Number.isInteger(option.id) && Number(option.id) > 0) &&
        typeof option.name === 'string' && option.name.trim().length > 0 && option.name.length <= 80 &&
        typeof option.priceDelta === 'string' && /^\d{1,8}\.\d{2}$/.test(option.priceDelta) &&
        Number.isInteger(option.displayOrder) && Number(option.displayOrder) >= 0 && typeof option.active === 'boolean';
    }));
}

export function CatalogPage() {
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState<Category | ''>('');
  const [published, setPublished] = useState<boolean | ''>('');
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<AdminDish | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [form, setForm] = useState<DishWrite>(emptyDish);
  const [optionText, setOptionText] = useState(emptyOptions);
  const [formError, setFormError] = useState('');
  const [notice, setNotice] = useState('');
  const queryKey = ['admin-dishes', search, category, published, page] as const;
  const query = useQuery({
    queryKey,
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '20' });
      if (search.trim()) params.set('q', search.trim().slice(0, 50));
      if (category) params.set('category', category);
      if (published !== '') params.set('published', String(published));
      return apiRequest<AdminDishPage>(`/admin/dishes?${params}`);
    },
  });
  const saveMutation = useMutation({
    mutationFn: (payload: DishWrite) => editing
      ? apiRequest<AdminDish>(`/admin/dishes/${editing.id}`, { method: 'PUT', body: JSON.stringify(payload) })
      : apiRequest<AdminDish>('/admin/dishes', { method: 'POST', body: JSON.stringify(payload) }),
    onSuccess: async () => {
      setNotice(editing ? 'Dish updated.' : 'Dish created.');
      setEditing(null);
      setEditorOpen(false);
      setForm(emptyDish);
      await queryClient.invalidateQueries({ queryKey: ['admin-dishes'] });
    },
    onError: (error) => {
      if (error instanceof ProblemError && error.problem.status === 409) {
        setNotice('This dish changed elsewhere. The latest catalog has been loaded.');
        void queryClient.invalidateQueries({ queryKey: ['admin-dishes'] });
      } else setFormError('We couldn’t save this dish. Check the fields and try again.');
    },
  });
  const publishMutation = useMutation({
    mutationFn: (dish: AdminDish) => apiRequest<AdminDish>(`/admin/dishes/${dish.id}/publication`, {
      method: 'PATCH', body: JSON.stringify({ published: !dish.published, expectedVersion: dish.version }),
    }),
    onSuccess: async (updated) => {
      queryClient.setQueryData<AdminDishPage>(queryKey, (current) => current && ({ ...current, items: current.items.map((item) => item.id === updated.id ? updated : item) }));
      await queryClient.invalidateQueries({ queryKey: ['admin-dishes'] });
      setNotice(updated.published ? 'Published' : 'Unpublished');
    },
    onError: async () => {
      setNotice('This dish changed elsewhere. The latest catalog has been loaded.');
      await queryClient.invalidateQueries({ queryKey: ['admin-dishes'] });
    },
  });

  function beginNew() {
    setEditing(null); setForm(emptyDish); setOptionText(emptyOptions); setFormError(''); setNotice(''); setEditorOpen(true);
  }
  function beginEdit(dish: AdminDish) {
    const write = Object.fromEntries(Object.entries(dish).filter(([key]) => key !== 'id')) as DishWrite;
    setEditing(dish); setForm(write); setOptionText(JSON.stringify(write.optionGroups, null, 2)); setFormError(''); setNotice(''); setEditorOpen(true);
  }
  function setField<K extends keyof DishWrite>(key: K, value: DishWrite[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }
  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFormError('');
    let parsed: unknown;
    try { parsed = JSON.parse(optionText); } catch { setFormError('Enter valid option groups JSON.'); return; }
    if (!validOptionGroups(parsed)) { setFormError('Each option group needs valid selection limits and at least one valid option value.'); return; }
    if (!form.name.trim() || !form.description.trim() || !form.proteinSource.trim() || !form.imageUrl.trim() || !/^\d{1,8}\.\d{2}$/.test(form.basePrice)) {
      setFormError('Complete every required field and use a price with two decimal places.'); return;
    }
    saveMutation.mutate({ ...form, basePrice: form.basePrice, optionGroups: parsed });
  }
  async function uploadImage(file: File | undefined) {
    if (!file) return;
    const body = new FormData(); body.set('file', file);
    try {
      const result = await apiRequest<{ url: string }>('/admin/media/images', { method: 'POST', body });
      setField('imageUrl', result.url);
      setNotice('Image uploaded.');
    } catch { setFormError('Image upload failed. Try another image.'); }
  }

  return <main className="page-content admin-catalog-page">
    <section className="page-heading compact-heading"><div><p className="eyebrow">ADMIN CONSOLE · CATALOG</p><h1>Dishes</h1><p>Keep the catalog accurate and ready for the daily menu.</p></div><button className="button button-primary" onClick={beginNew}>Add dish</button></section>
    <section className="admin-toolbar" aria-label="Catalog filters">
      <label>Search dishes<input aria-label="Search dishes" maxLength={50} value={search} onChange={(event) => { setSearch(event.target.value.slice(0, 50)); setPage(0); }} placeholder="Name or description" /></label>
      <label>Category<select aria-label="Filter by category" value={category} onChange={(event) => { setCategory(event.target.value as Category | ''); setPage(0); }}><option value="">All categories</option>{categories.map((value) => <option key={value} value={value}>{value.replaceAll('_', ' ')}</option>)}</select></label>
      <label>Publication<select aria-label="Filter by publication" value={published === '' ? '' : String(published)} onChange={(event) => { setPublished(event.target.value === '' ? '' : event.target.value === 'true'); setPage(0); }}><option value="">All dishes</option><option value="true">Published</option><option value="false">Draft</option></select></label>
    </section>
    {notice && <p className="success-note" role="status">{notice}</p>}
    {query.isPending && <div className="menu-skeleton" aria-label="Loading catalog" aria-busy="true" />}
    {query.isError && <div className="empty-state" role="alert"><h2>Catalog unavailable.</h2><p>Try loading the catalog again.</p><button className="button button-secondary" onClick={() => void query.refetch()}>Try again</button></div>}
    {query.data && <div className="admin-table-wrap"><table className="admin-table"><caption>Dish catalog</caption><thead><tr><th scope="col">Dish</th><th scope="col">Category</th><th scope="col">Price</th><th scope="col">Status</th><th scope="col">Actions</th></tr></thead><tbody>
      {query.data.items.map((dish) => <tr key={dish.id}><td><div className="catalog-dish-cell"><img src={dish.imageUrl || '/meal-placeholder.svg'} alt="" /><div><strong>{dish.name}</strong><span>{dish.proteinSource}</span></div></div></td><td>{dish.category.replaceAll('_', ' ')}</td><td>{formatCny(moneyToFen(dish.basePrice))}</td><td><span className={`status-pill status-${dish.published ? 'confirmed' : 'pending'}`}>{dish.published ? 'Published' : 'Draft'}</span></td><td className="table-actions"><button className="text-link" onClick={() => beginEdit(dish)}>Edit</button><button className="text-link" disabled={publishMutation.isPending} aria-label={`${dish.published ? 'Unpublish' : 'Publish'} ${dish.name}`} onClick={() => publishMutation.mutate(dish)}>{dish.published ? 'Unpublish' : 'Publish'}</button></td></tr>)}
    </tbody></table>{query.data.items.length === 0 && <p className="empty-note">No dishes match those filters.</p>}</div>}
    {query.data && query.data.totalPages > 1 && <nav className="pagination" aria-label="Catalog pages"><button className="button button-secondary" disabled={page === 0} onClick={() => setPage((value) => value - 1)}>Previous</button><span>Page {query.data.page + 1} of {query.data.totalPages}</span><button className="button button-secondary" disabled={page + 1 >= query.data.totalPages} onClick={() => setPage((value) => value + 1)}>Next</button></nav>}
    {editorOpen && <div className="dialog-scrim"><section className="admin-editor" role="dialog" aria-modal="true" aria-labelledby="dish-editor-title"><button className="dialog-close" aria-label="Close dish editor" onClick={() => { setEditing(null); setForm(emptyDish); setEditorOpen(false); }}>×</button><p className="eyebrow">CATALOG EDITOR</p><h2 id="dish-editor-title">{editing ? `Edit ${editing.name}` : 'Add dish'}</h2>
      <form onSubmit={submit}>
        <label>Dish name<input aria-label="Dish name" maxLength={120} value={form.name} onChange={(event) => setField('name', event.target.value)} /></label>
        <label>Description<textarea aria-label="Description" maxLength={1000} value={form.description} onChange={(event) => setField('description', event.target.value)} /></label>
        <div className="admin-form-grid"><label>Base price<input aria-label="Base price" inputMode="decimal" value={form.basePrice} onChange={(event) => setField('basePrice', event.target.value)} /></label>
          <label>Category<select aria-label="Dish category" value={form.category} onChange={(event) => setField('category', event.target.value as Category)}>{categories.map((value) => <option key={value} value={value}>{value.replaceAll('_', ' ')}</option>)}</select></label>
          <label>Protein source<input aria-label="Protein source" value={form.proteinSource} onChange={(event) => setField('proteinSource', event.target.value)} /></label>
          <label>Spice level<select aria-label="Spice level" value={form.spiceLevel} onChange={(event) => setField('spiceLevel', event.target.value as SpiceLevel)}>{spices.map((value) => <option key={value} value={value}>{value}</option>)}</select></label></div>
        <fieldset className="admin-allergens"><legend>Allergens</legend>{allergens.map((allergen) => <label key={allergen}><input type="checkbox" checked={form.allergens.includes(allergen)} onChange={(event) => setField('allergens', event.target.checked ? [...form.allergens, allergen] : form.allergens.filter((value) => value !== allergen))} />{allergen}</label>)}</fieldset>
        <label>Image URL<input aria-label="Image URL" maxLength={500} value={form.imageUrl} onChange={(event) => setField('imageUrl', event.target.value)} /></label><label>Upload image<input aria-label="Upload image" type="file" accept="image/*" onChange={(event) => void uploadImage(event.target.files?.[0])} /></label>{form.imageUrl && <img className="image-preview" src={form.imageUrl} alt="Dish preview" />}
        <label>Option groups JSON<textarea aria-label="Option groups JSON" rows={7} value={optionText} onChange={(event) => setOptionText(event.target.value)} /></label>
        <label className="inline-check"><input type="checkbox" checked={form.published} onChange={(event) => setField('published', event.target.checked)} /> Publish this dish</label>
        {formError && <p role="alert" className="form-error">{formError}</p>}
        <button className="button button-primary" type="submit" disabled={saveMutation.isPending}>{saveMutation.isPending ? 'Saving…' : 'Save dish'}</button>
      </form>
    </section></div>}
  </main>;
}
