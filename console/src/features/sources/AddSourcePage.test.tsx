import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AddSourcePage } from './AddSourcePage';
import { jira, source, teams } from './fixtures';
import { mockApi, renderAt, reply } from '../../test/render';

afterEach(() => vi.restoreAllMocks());

const created = source({ status: 'paused', lastRun: null });

function open(harvester: string, routes: Record<string, unknown> = {}) {
  const calls = mockApi({
    '/v1/harvesters': { harvesters: [jira, teams] },
    '/v1/profiles/p/sources': created,
    '/v1/profiles/p/sources/acme/check': { ok: true, errorClass: null, message: null },
    ...routes
  });
  renderAt('/p/:profile/sources/new', `/p/p/sources/new?harvester=${harvester}`, <AddSourcePage />);
  return calls;
}

async function fillJira(id = 'acme') {
  await userEvent.type(await screen.findByLabelText('Source id'), id);
  await userEvent.type(screen.getByLabelText('Base URL'), 'https://acme.atlassian.net');
  await userEvent.type(screen.getByLabelText('Which issues'), 'project = A');
  await userEvent.type(screen.getByLabelText('API token'), 's3cret');
}

const location = () => screen.getByTestId('location').textContent;

describe('AddSourcePage', () => {
  it('requiredFieldsBlockTheSubmit', async () => {
    const calls = open('jira');

    await userEvent.click(await screen.findByRole('button', { name: 'Add source' }));

    expect(await screen.findByText('Base URL is required')).toBeInTheDocument();
    expect(screen.getByText('API token is required')).toBeInTheDocument();
    expect(screen.getByText(/Use lowercase letters/)).toBeInTheDocument();
    expect(calls.some(c => c.init?.method === 'POST')).toBe(false);
  });

  it('addsAJiraSourceAndRunsTheCheck', async () => {
    const calls = open('jira');
    await fillJira();

    await userEvent.click(screen.getByRole('button', { name: 'Add source' }));

    await waitFor(() => expect(location()).toBe('/p/p/sources/acme'));
    const create = calls.find(c => c.url.pathname === '/v1/profiles/p/sources' && c.init?.method === 'POST');
    expect(JSON.parse(String(create?.init?.body))).toEqual({
      id: 'acme', harvester: 'jira', config: { baseUrl: 'https://acme.atlassian.net', jql: 'project = A', sweepHours: 24 },
      secrets: { token: 's3cret' }, mode: 'full', intervalSeconds: 21600, maxItemsPerRun: null, maxCharsPerRun: null, start: false
    });
    expect(calls.some(c => c.url.pathname === '/v1/profiles/p/sources/acme/check' && c.init?.method === 'POST')).toBe(true);
    expect(document.body.textContent).not.toContain('s3cret');
  });

  it('remoteProviderWarningIsShown', async () => {
    open('jira', { '/v1/profiles/p/sources': { ...created, warnings: ['Text from this source will be sent to your configured model provider.'] } });
    await fillJira();

    await userEvent.click(screen.getByRole('button', { name: 'Add source' }));

    expect(await screen.findByText(/sent to your configured model provider/)).toBeInTheDocument();
  });

  it('signInHarvesterSkipsTheCheck', async () => {
    const calls = open('teams', { '/v1/profiles/p/sources': { ...created, id: 't', harvester: 'teams' } });
    await userEvent.type(await screen.findByLabelText('Source id'), 't');
    await userEvent.type(screen.getByLabelText('Application (client) ID'), '11111111-2222-3333-4444-555555555555');

    expect(screen.queryByLabelText(/Sign-in/)).toBeNull();
    expect(screen.getByText("You'll sign in after the source is added.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Add source' }));

    await waitFor(() => expect(location()).toBe('/p/p/sources/t?signin=1'));
    expect(calls.some(c => c.url.pathname.endsWith('/check'))).toBe(false);
  });

  it('failedCheckLandsOnTheSource', async () => {
    open('jira', { '/v1/profiles/p/sources/acme/check': { ok: false, errorClass: 'auth', message: 'denied' } });
    await fillJira();

    await userEvent.click(screen.getByRole('button', { name: 'Add source' }));

    await waitFor(() => expect(location()).toBe('/p/p/sources/acme?check=failed'));
    expect(await screen.findByText(/denied/)).toBeInTheDocument();
  });

  it('conflictKeepsTheForm', async () => {
    open('jira', { '/v1/profiles/p/sources': reply(409, { error: 'conflict', message: 'source already exists: acme' }) });
    await fillJira();

    await userEvent.click(screen.getByRole('button', { name: 'Add source' }));

    expect(await screen.findByText('source already exists: acme')).toBeInTheDocument();
    expect(screen.getByLabelText('Base URL')).toHaveValue('https://acme.atlassian.net');
    expect(screen.getByLabelText('API token')).toHaveValue('');
  });

  it('badIntervalIsRefused', async () => {
    const calls = open('jira');
    await fillJira();
    const interval = screen.getByLabelText('Run every');
    await userEvent.clear(interval);
    await userEvent.type(interval, '30');

    await userEvent.click(screen.getByRole('button', { name: 'Add source' }));

    expect(await screen.findByText('The interval must be at least 60 seconds')).toBeInTheDocument();
    expect(calls.some(c => c.init?.method === 'POST')).toBe(false);
  });
});
