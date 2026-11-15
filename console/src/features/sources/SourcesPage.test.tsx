import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { SourcesPage } from './SourcesPage';
import { jira, source, teams } from './fixtures';
import { mockApi, renderAt } from '../../test/render';

afterEach(() => vi.restoreAllMocks());

function show(sources: unknown[], harvesters = [jira, teams]) {
  mockApi({ '/v1/harvesters': { harvesters }, '/v1/profiles/p/sources': { sources } });
  renderAt('/p/:profile/sources', '/p/p/sources', <SourcesPage />);
}

describe('SourcesPage', () => {
  it('listsSourcesWithStatusAndLastRun', async () => {
    show([source({ id: 'docs' }), source({ id: 'teams-chats', harvester: 'teams', status: 'needs_auth', lastRun: null })]);

    expect(await screen.findByRole('link', { name: 'docs' })).toHaveAttribute('href', '/p/p/sources/docs');
    expect(screen.getByRole('link', { name: 'teams-chats' })).toBeInTheDocument();
    expect(screen.getByText('Needs sign-in')).toBeInTheDocument();
    expect(screen.getByText('Never run')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/p/p/sources/teams-chats?signin=1');
  });

  it('tokenSourceThatNeedsAuthOffersReplace', async () => {
    show([source({ status: 'needs_auth' })]);

    expect(await screen.findByText('Credentials refused')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Replace credentials' })).toHaveAttribute('href', '/p/p/sources/acme?secrets=1');
  });

  it('sourceOnAMissingHarvesterStillRenders', async () => {
    show([source({ harvester: 'gone' })]);

    expect(await screen.findByText('not installed')).toBeInTheDocument();
  });

  it('heldBackDeletionsAreFlagged', async () => {
    show([source({ sweepBlockedCount: 40 })]);

    expect(await screen.findByText('Deletions held back: 40')).toBeInTheDocument();
  });

  it('emptyProfileOffersAdd', async () => {
    show([]);

    expect(await screen.findByText('No sources yet')).toBeInTheDocument();
    for (const link of screen.getAllByRole('link', { name: 'Add source' })) {
      expect(link).toHaveAttribute('href', '/p/p/sources/new');
    }
  });

  it('harvestersAreListedReadOnly', async () => {
    show([]);

    expect(await screen.findByText('Microsoft Teams')).toBeInTheDocument();
    expect(screen.getByText('oauth-device-code')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /install/i })).toBeNull();
    expect(screen.getByText(/pieria harvester install/)).toBeInTheDocument();
  });
});
