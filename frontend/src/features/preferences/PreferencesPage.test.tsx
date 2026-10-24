import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { server } from '../../test/server';
import { PreferencesPage } from './PreferencesPage';

describe('preference editor', () => {
  it('saves allergen, cuisine, spice, and budget preferences through the API contract', async () => {
    let saved: unknown;
    server.use(
      http.get('*/api/v1/app/preferences', () => HttpResponse.json({
        allergens: [], preferredCategories: [], spiceLevel: null, tasteIntensity: null,
        budgetMin: null, budgetMax: null,
      })),
      http.put('*/api/v1/app/preferences', async ({ request }) => {
        saved = await request.json();
        return HttpResponse.json(saved as Record<string, unknown>);
      }),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const user = userEvent.setup();
    render(<QueryClientProvider client={client}><MemoryRouter><PreferencesPage /></MemoryRouter></QueryClientProvider>);

    expect(await screen.findByRole('heading', { name: 'Make it yours.' })).toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Peanuts' }));
    await user.click(screen.getByRole('checkbox', { name: 'Japanese' }));
    await user.selectOptions(screen.getByLabelText('Preferred spice level'), 'MILD');
    await user.type(screen.getByLabelText('Maximum meal budget'), '25.00');
    await user.click(screen.getByRole('button', { name: 'Save preferences' }));

    await waitFor(() => expect(saved).toEqual({
      allergens: ['PEANUTS'], preferredCategories: ['JAPANESE'], spiceLevel: 'MILD', tasteIntensity: null,
      budgetMin: null, budgetMax: '25.00',
    }));
    expect(screen.getByText('Your preferences are saved.')).toBeInTheDocument();
  });
});
