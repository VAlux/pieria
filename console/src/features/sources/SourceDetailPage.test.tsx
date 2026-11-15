import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Source } from '../../api/sources';
import { SourceDetailPage } from './SourceDetailPage';
import { jira, run, source, teams } from './fixtures';
import { mockApi, renderAt, reply } from '../../test/render';

afterEach(() => vi.restoreAllMocks());

const BASE = '/v1/profiles/p/sources/acme';

function open(current: Source, routes: Record<string, unknown> = {}, url = '/p/p/sources/acme') {
  const calls = mockApi({
    '/v1/harvesters': { harvesters: [jira, teams] },
    [BASE]: (_url: URL, init?: RequestInit) => (init?.method === 'DELETE' ? null : current),
    [`${BASE}/runs`]: { runs: [current.lastRun].filter(Boolean) },
    [`${BASE}/secrets`]: null,
    ...routes
  });
  const view = renderAt('/p/:profile/sources/:sourceId', url, <SourceDetailPage />);
  return { calls, view };
}

const body = (call: { init?: RequestInit } | undefined) => JSON.parse(String(call?.init?.body));

describe('SourceDetailPage', () => {
  it('editingSendsOnlyTheChanges', async () => {
    const { calls } = open(source());
    const jql = await screen.findByLabelText('Which issues');

    await userEvent.clear(jql);
    await userEvent.type(jql, 'project = B');
    await userEvent.click(screen.getByRole('button', { name: 'Save settings' }));

    await waitFor(() => expect(calls.some(c => c.init?.method === 'PATCH')).toBe(true));
    expect(body(calls.find(c => c.init?.method === 'PATCH'))).toEqual({ config: { jql: 'project = B' } });
  });

  it('saveIsDisabledUntilSomethingChanges', async () => {
    open(source());

    expect(await screen.findByRole('button', { name: 'Save settings' })).toBeDisabled();
  });

  it('replacingASecretSendsItOnceAndForgetsIt', async () => {
    const { calls } = open(source());
    const credentials = await screen.findByRole('region', { name: 'Credentials' });
    expect(within(credentials).getByText('Set')).toBeInTheDocument();

    await userEvent.click(within(credentials).getByRole('button', { name: 'Replace' }));
    await userEvent.type(screen.getByLabelText('API token'), 'n3w');
    await userEvent.click(screen.getByRole('button', { name: 'Save credential' }));

    await waitFor(() => expect(calls.some(c => c.init?.method === 'PUT')).toBe(true));
    expect(body(calls.find(c => c.init?.method === 'PUT'))).toEqual({ secrets: { token: 'n3w' } });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(document.body.textContent).not.toContain('n3w');
    for (const input of document.querySelectorAll('input')) expect(input.value).not.toBe('n3w');
  });

  it('runningDisablesRunAndRemove', async () => {
    open(source({ running: true }));

    expect(await screen.findByRole('button', { name: 'Run now' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Dry run' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Remove' })).toBeDisabled();
  });

  it('conflictKeepsTheEdit', async () => {
    const current = source();
    open(current, {
      [BASE]: (_url: URL, init?: RequestInit) => (init?.method === 'PATCH'
        ? reply(409, { error: 'conflict', message: 'source acme is running; try again when it finishes' }) : current)
    });
    const jql = await screen.findByLabelText('Which issues');

    await userEvent.clear(jql);
    await userEvent.type(jql, 'project = B');
    await userEvent.click(screen.getByRole('button', { name: 'Save settings' }));

    expect(await screen.findByText('source acme is running; try again when it finishes')).toBeInTheDocument();
    expect(screen.getByLabelText('Which issues')).toHaveValue('project = B');
  });

  it('dryRunShowsTheEstimate', async () => {
    let polls = 0;
    open(source(), {
      [`${BASE}/run`]: { taskId: 't1' },
      '/v1/tasks/t1': () => (polls++ === 0
        ? { status: 'RUNNING', result: null, errorKind: null, errorMessage: null }
        : { status: 'SUCCEEDED', result: { itemsSeen: 12, itemsChanged: 3, chars: 4000, estimatedChunks: 2 }, errorKind: null, errorMessage: null })
    });

    await userEvent.click(await screen.findByRole('button', { name: 'Dry run' }));

    expect(await screen.findByText('12 items, 3 changed, 4,000 characters · about 2 chunks to extract', {}, { timeout: 3000 }))
      .toBeInTheDocument();
  });

  it('heldBackSweepCanBeAllowed', async () => {
    const { calls } = open(source({ sweepBlockedCount: 40 }), { [`${BASE}/accept-sweep`]: source({ sweepBlockedCount: 40, acceptNextSweep: true }) });

    expect(await screen.findByText(/would delete 40 items/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Allow these deletions' }));

    await waitFor(() => expect(calls.some(c => c.url.pathname === `${BASE}/accept-sweep` && c.init?.method === 'POST')).toBe(true));
  });

  it('removeCanForgetMemories', async () => {
    const { calls } = open(source());

    await userEvent.click(await screen.findByRole('button', { name: 'Remove' }));
    await userEvent.click(screen.getByLabelText('Also forget the memories this source added'));
    await userEvent.click(screen.getByRole('button', { name: 'Remove source' }));

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/p/p/sources'));
    const remove = calls.find(c => c.init?.method === 'DELETE');
    expect(remove?.url.search).toBe('?forgetMemories=true');
  });

  it('missingHarvesterShowsReadOnlySettings', async () => {
    open(source({ harvester: 'gone' }));

    expect(await screen.findByText("The harvester isn't installed, so these settings can't be edited.")).toBeInTheDocument();
    expect(screen.getByText('project = A')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save settings' })).toBeNull();
  });

  it('needsAuthWithoutSignInPointsToCredentials', async () => {
    open(source({ status: 'needs_auth' }));

    expect(await screen.findByText("The source's credentials were refused. Replace them below, then resume it.")).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign in' })).toBeNull();
  });

  it('secretsQueryOpensTheReplaceDialog', async () => {
    open(source({ status: 'needs_auth' }), {}, '/p/p/sources/acme?secrets=1');

    expect(await screen.findByRole('dialog', { name: 'Replace API token' })).toBeInTheDocument();
  });

  it('runHistoryShowsStderrLiterally', async () => {
    const { view } = open(source({ lastRun: run({ status: 'failed', errorClass: 'fatal', errorMessage: 'it broke', stderrTail: '<b>boom</b>' }) }));

    expect(await screen.findByText('<b>boom</b>')).toBeInTheDocument();
    expect(screen.getByText('fatal: it broke')).toBeInTheDocument();
    expect(view.container.ownerDocument.querySelector('b')).toBeNull();
  });
});
