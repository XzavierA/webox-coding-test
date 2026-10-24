import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { BrowserRouter } from 'react-router-dom';
import { AuthProvider } from '../features/auth/AuthProvider';
import { AppRoutes } from './router';

export function App() {
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: {
      queries: { staleTime: 15_000, retry: 1, refetchOnWindowFocus: true },
      mutations: { retry: false },
    },
  }));
  return <QueryClientProvider client={queryClient}><BrowserRouter><AuthProvider><AppRoutes /></AuthProvider></BrowserRouter></QueryClientProvider>;
}
