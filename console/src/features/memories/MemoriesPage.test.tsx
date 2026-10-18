import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { MemoriesPage } from './MemoriesPage';
import { Location, mockApi } from '../../test/render';
import { ToastProvider } from '../../ui/overlay';

const now = Date.now();
const iso = (offsetMs: number) => new Date(now + offsetMs).toISOString();
const memory = (id: string, content: string, extra: object = {}) => ({
  id, type: 'fact', content, topicKey: `key.${id}`, sessionId: 's1', superseded: false, payload: '{"source":"test"}', createdAt: iso(-3_600_000), ...extra
});
const revision = (id: string, memoryId: string, content: string, from: number) => ({
  assertionId: `a-${memoryId}`, revisionId: id, memoryId, content, validFrom: iso(from), validTo: null, statedAt: iso(from),
  recordedFrom: iso(from), recordedTo: null, corrects: null, sourceOccurrence: null, invalidated: false, factGroup: 'fact:k',
  sourcePayload: null, conflictEvidence: null
});

function renderMemories(url: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const element = <><MemoriesPage /><Location /></>;
  const router = createMemoryRouter([
    { path: '/p/:profile/memories', element },
    { path: '/p/:profile/memories/:memoryId', element }
  ], { initialEntries: [url] });
  render(<QueryClientProvider client={client}><ToastProvider><RouterProvider router={router} /></ToastProvider></QueryClientProvider>);
}

describe('MemoriesPage', () => {
  it('lists memories with type counts and opens one with its validity history', async () => {
    const calls = mockApi({
      '/v1/profiles/p/memories/search': {
        items: [{ kind: 'memory', memory: memory('m1', 'The daemon serves the console') },
          { kind: 'tool-calls', group: { sessionId: 'session-xyz', count: 3, tools: ['Bash'], firstAt: iso(-7_200_000), lastAt: iso(-7_000_000) } },
          { kind: 'memory', memory: memory('m2', 'Embeddings are fixed width') }],
        nextCursor: null, typeCounts: { fact: 2, event: 3, instruction: 0, task: 0, cluster: 0 }, total: 5
      },
      '/v1/profiles/p/memories/m2': { ...memory('m2', 'Embeddings are fixed width'), validity: [], assertions: [] },
      '/v1/profiles/p/memories/m2/history': {
        factGroup: 'fact:k', selectedRevision: null, selectedValidity: null,
        revisions: [revision('r-old', 'm0', 'Embeddings were variable', -86_400_000), revision('r-new', 'm2', 'Embeddings are fixed width', -3_600_000)],
        segments: [
          { assertionId: 'a', revisionId: 'r-old', validFrom: iso(-86_400_000), validTo: iso(-3_600_000), recordedFrom: iso(-3_600_000), recordedTo: null, statedAt: null, sourceOccurrence: null, factGroup: 'fact:k', sourcePayload: null },
          { assertionId: 'a', revisionId: 'r-new', validFrom: iso(-3_600_000), validTo: null, recordedFrom: iso(-3_600_000), recordedTo: null, statedAt: null, sourceOccurrence: null, factGroup: 'fact:k', sourcePayload: null }
        ],
        temporal: { asOf: iso(0), knownAsOf: iso(0), snapshotSequence: 1 }
      },
      '/v1/profiles/p/sessions': { sessions: [] }
    });
    renderMemories('/p/p/memories');

    const list = await screen.findByRole('region', { name: 'Memory list' });
    expect(await within(list).findByText('The daemon serves the console')).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Filter by type' })).toHaveTextContent('All5');
    expect(screen.getByText(/3 commands \(Bash\) grouped/)).toBeInTheDocument();
    expect(screen.getByText('Showing 5 of 5 memories')).toBeInTheDocument();
    const search = calls.find(c => c.url.pathname.endsWith('/memories/search'))!;
    expect(search.url.searchParams.get('groupToolCalls')).toBe('true');
    expect(new Headers(search.init?.headers).get('X-Pieria-Client')).toBe('console');

    await userEvent.click(within(list).getByText('Embeddings are fixed width'));
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/p/p/memories/m2'));
    const detail = screen.getByRole('complementary', { name: 'Memory detail' });
    await waitFor(() => expect(within(detail).getByText('Validity · 2 versions')).toBeInTheDocument());
    expect(within(detail).getByText('valid now')).toBeInTheDocument();
    expect(within(detail).getByText('superseded')).toBeInTheDocument();
    expect(within(detail).getByText('source')).toBeInTheDocument();
  });

  it('sends filters to the server and resets paging', async () => {
    const calls = mockApi({
      '/v1/profiles/p/memories/search': { items: [], nextCursor: null, typeCounts: {}, total: 0 },
      '/v1/profiles/p/sessions': { sessions: [] }
    });
    renderMemories('/p/p/memories');
    await screen.findByText('No memories match these filters.').catch(() => screen.findByText('This profile has no memories yet.'));

    await userEvent.click(screen.getByRole('button', { name: /^task/ }));
    await waitFor(() => expect(calls.some(c => c.url.searchParams.get('type') === 'task')).toBe(true));
    expect(screen.getByTestId('location').textContent).toBe('/p/p/memories?type=task');
  });
});
