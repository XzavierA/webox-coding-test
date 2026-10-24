import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { apiRequest } from '../../api/client';
import type { Allergen, Category, Preference, SpiceLevel, TasteIntensity } from '../../api/types';
import { moneyToFen } from '../../shared/money';

const allergens: Array<{ value: Allergen; label: string }> = [
  { value: 'PEANUTS', label: 'Peanuts' }, { value: 'DAIRY', label: 'Dairy' }, { value: 'EGG', label: 'Egg' },
  { value: 'GLUTEN', label: 'Gluten' }, { value: 'SOY', label: 'Soy' }, { value: 'FISH', label: 'Fish' },
  { value: 'SHELLFISH', label: 'Shellfish' },
];
const categories: Array<{ value: Category; label: string }> = [
  { value: 'CHINESE', label: 'Chinese' }, { value: 'WESTERN', label: 'Western' },
  { value: 'JAPANESE', label: 'Japanese' }, { value: 'LIGHT_MEAL', label: 'Light meal' },
  { value: 'KOREAN', label: 'Korean' }, { value: 'SOUTHEAST_ASIAN', label: 'Southeast Asian' },
];
const moneyField = z.string().regex(/^(?:|\d{1,8}\.\d{2})$/, 'Use a whole amount with two decimal places.');
const preferenceSchema = z.object({
  allergens: z.array(z.enum(['PEANUTS', 'DAIRY', 'EGG', 'GLUTEN', 'SOY', 'FISH', 'SHELLFISH'])),
  preferredCategories: z.array(z.enum(['CHINESE', 'WESTERN', 'JAPANESE', 'LIGHT_MEAL', 'KOREAN', 'SOUTHEAST_ASIAN'])),
  spiceLevel: z.union([z.enum(['NONE', 'MILD', 'MEDIUM', 'HOT']), z.literal('')]),
  tasteIntensity: z.union([z.enum(['LIGHT', 'BALANCED', 'RICH']), z.literal('')]),
  budgetMin: moneyField,
  budgetMax: moneyField,
}).refine(({ budgetMin, budgetMax }) => !budgetMin || !budgetMax || moneyToFen(budgetMin) <= moneyToFen(budgetMax), {
  path: ['budgetMax'], message: 'Maximum budget must be at least the minimum budget.',
});
type PreferenceValues = z.infer<typeof preferenceSchema>;

function toFormValues(preferences: Preference): PreferenceValues {
  return {
    allergens: preferences.allergens,
    preferredCategories: preferences.preferredCategories,
    spiceLevel: preferences.spiceLevel ?? '',
    tasteIntensity: preferences.tasteIntensity ?? '',
    budgetMin: preferences.budgetMin ?? '',
    budgetMax: preferences.budgetMax ?? '',
  };
}

function toPreference(values: PreferenceValues): Preference {
  return {
    allergens: [...new Set(values.allergens)],
    preferredCategories: [...new Set(values.preferredCategories)],
    spiceLevel: (values.spiceLevel || null) as SpiceLevel | null,
    tasteIntensity: (values.tasteIntensity || null) as TasteIntensity | null,
    budgetMin: values.budgetMin || null,
    budgetMax: values.budgetMax || null,
  };
}

export function PreferencesPage() {
  const queryClient = useQueryClient();
  const [saved, setSaved] = useState(false);
  const preferenceQuery = useQuery({
    queryKey: ['preferences'],
    queryFn: () => apiRequest<Preference>('/app/preferences'),
  });
  const { register, handleSubmit, reset, formState: { errors } } = useForm<PreferenceValues>({
    resolver: zodResolver(preferenceSchema),
    defaultValues: { allergens: [], preferredCategories: [], spiceLevel: '', tasteIntensity: '', budgetMin: '', budgetMax: '' },
  });
  useEffect(() => {
    if (preferenceQuery.data) reset(toFormValues(preferenceQuery.data));
  }, [preferenceQuery.data, reset]);
  const saveMutation = useMutation({
    mutationFn: (preference: Preference) => apiRequest<Preference>('/app/preferences', {
      method: 'PUT', body: JSON.stringify(preference),
    }),
    onSuccess: async (preference) => {
      reset(toFormValues(preference));
      setSaved(true);
      await queryClient.invalidateQueries({ queryKey: ['preferences'] });
    },
  });
  const submit = handleSubmit((values) => {
    setSaved(false);
    saveMutation.mutate(toPreference(values));
  });

  if (preferenceQuery.isPending) return <main className="page-content"><div className="menu-skeleton" aria-label="Loading preferences" aria-busy="true" /></main>;
  if (preferenceQuery.isError) return <main className="page-content"><div className="empty-state" role="alert"><h1>Preferences unavailable</h1><p>We couldn’t load your preferences.</p></div></main>;

  return (
    <main className="page-content preferences-page">
      <section className="page-heading compact-heading">
        <div><p className="eyebrow">YOUR TABLE, YOUR WAY</p><h1>Make it yours.</h1><p>Tell us what helps make a meal feel right for you.</p></div>
      </section>
      <form className="preferences-form" onSubmit={submit}>
        <section className="preference-section">
          <div><h2>Allergies and avoidances</h2><p>We’ll clearly flag dishes that match your profile.</p></div>
          <div className="preference-choice-grid">
            {allergens.map((allergen) => <label className="preference-choice" key={allergen.value}><input type="checkbox" value={allergen.value} {...register('allergens')} /><span>{allergen.label}</span></label>)}
          </div>
        </section>
        <section className="preference-section">
          <div><h2>Flavors you enjoy</h2><p>Choose as many cuisines as you like.</p></div>
          <div className="preference-choice-grid">
            {categories.map((category) => <label className="preference-choice" key={category.value}><input type="checkbox" value={category.value} {...register('preferredCategories')} /><span>{category.label}</span></label>)}
          </div>
        </section>
        <section className="preference-section preference-fields">
          <label>Preferred spice level
            <select aria-label="Preferred spice level" {...register('spiceLevel')}>
              <option value="">No preference</option><option value="NONE">Not spicy</option><option value="MILD">Mild</option><option value="MEDIUM">Medium</option><option value="HOT">Hot</option>
            </select>
          </label>
          <label>Taste intensity
            <select aria-label="Taste intensity" {...register('tasteIntensity')}>
              <option value="">No preference</option><option value="LIGHT">Light</option><option value="BALANCED">Balanced</option><option value="RICH">Rich</option>
            </select>
          </label>
        </section>
        <section className="preference-section preference-fields">
          <label>Minimum meal budget
            <span className="money-input"><span>¥</span><input aria-label="Minimum meal budget" inputMode="decimal" placeholder="0.00" {...register('budgetMin')} /></span>
            {errors.budgetMin && <span className="field-error">{errors.budgetMin.message}</span>}
          </label>
          <label>Maximum meal budget
            <span className="money-input"><span>¥</span><input aria-label="Maximum meal budget" inputMode="decimal" placeholder="0.00" {...register('budgetMax')} /></span>
            {errors.budgetMax && <span className="field-error">{errors.budgetMax.message}</span>}
          </label>
        </section>
        {saved && <p className="success-note" role="status">Your preferences are saved.</p>}
        {saveMutation.isError && <p className="form-error" role="alert">We couldn’t save your preferences. Please try again.</p>}
        <div className="preference-save-row"><p>Your budget is a guide. It never hides a dish from the menu.</p><button className="button button-primary" disabled={saveMutation.isPending}>{saveMutation.isPending ? 'Saving…' : 'Save preferences'}</button></div>
      </form>
    </main>
  );
}
