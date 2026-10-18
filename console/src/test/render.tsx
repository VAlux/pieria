import type { ReactElement } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider, useLocation } from 'react-router';
import { vi } from 'vitest';
import { ToastProvider } from '../ui/overlay';

/** Answers fetches from a path → body table; unknown paths 404. Records every request URL. */
export function mockApi(routes: Record<string, unknown | ((url: URL, init?: RequestInit) => unknown)>) {
  const calls: { url: URL; init?: RequestInit }[] = [];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://console.test');
    calls.push({ url, init });
    const match = Object.keys(routes).sort((a, b) => b.length - a.length).find(path => url.pathname === path || url.pathname.startsWith(`${path}?`));
    if (!match) return new Response(JSON.stringify({ error: 'not_found', message: url.pathname }), { status: 404, headers: { 'Content-Type': 'application/json' } });
    const route = routes[match];
    const body = typeof route === 'function' ? (route as (u: URL, i?: RequestInit) => unknown)(url, init) : route;
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
  return calls;
}

export function Location() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname}{location.search}</output>;
}

/** Renders `element` at `path` (a route pattern) with the app's providers, starting at `url`. */
export function renderAt(path: string, url: string, element: ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([
    { path, element: <>{element}<Location /></> },
    { path: '*', element: <Location /> }
  ], { initialEntries: [url] });
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </QueryClientProvider>
  );
}
