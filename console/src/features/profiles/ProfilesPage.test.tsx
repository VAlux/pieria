import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ProfilesPage } from './ProfilesPage';
import { mockApi, renderAt, reply } from '../../test/render';

afterEach(() => vi.restoreAllMocks());

const summary = (name: string, parent: string | null = null, memoryCount = 1) =>
  ({ name, parent, memoryCount, createdAt: '2026-01-01T00:00:00Z' });

const listing = [summary('alpha', null, 3), summary('beta', 'alpha', 2), summary('gamma', 'beta', 5), summary('omega')];

function show(routes: Record<string, unknown> = {}, url = '/p/alpha/profiles') {
  const calls = mockApi({ '/v1/profiles': { profiles: listing }, ...routes });
  renderAt('/p/:profile/profiles', url, <ProfilesPage />);
  return calls;
}

const row = (name: string) => screen.getByRole('link', { name }).closest('li') as HTMLElement;
const bodyOf = (calls: ReturnType<typeof mockApi>, method: string) =>
  JSON.parse(String(calls.find(c => c.init?.method === method)?.init?.body ?? 'null'));

describe('ProfilesPage', () => {
  it('showsProfilesNestedUnderTheirParent', async () => {
    show();

    await screen.findByRole('link', { name: 'gamma' });
    const items = screen.getAllByRole('treeitem');
    expect(items.map(i => [within(i).getByRole('link').textContent, i.getAttribute('aria-level')])).toEqual([
      ['alpha', '1'], ['beta', '2'], ['gamma', '3'], ['omega', '1']
    ]);
    expect(within(row('alpha')).getByText(/recall reads 10/)).toBeInTheDocument();
    expect(within(row('omega')).queryByText(/recall reads/)).toBeNull();
  });

  it('dropsAProfileOntoAnotherToMoveIt', async () => {
    const calls = show({ '/v1/profiles/omega': summary('omega', 'gamma') });
    await screen.findByRole('link', { name: 'omega' });

    fireEvent.dragStart(row('omega'));
    fireEvent.dragOver(row('gamma'));
    fireEvent.drop(row('gamma'));

    await waitFor(() => expect(bodyOf(calls, 'PATCH')).toEqual({ parent: 'gamma' }));
    expect(calls.find(c => c.init?.method === 'PATCH')?.url.pathname).toBe('/v1/profiles/omega');
  });

  it('dropsOntoTheTopZoneToMoveToTopLevel', async () => {
    const calls = show({ '/v1/profiles/gamma': summary('gamma') });
    await screen.findByRole('link', { name: 'gamma' });

    fireEvent.dragStart(row('gamma'));
    const zone = screen.getByText('Drop here to move to top level');
    fireEvent.dragOver(zone);
    fireEvent.drop(zone);

    await waitFor(() => expect(bodyOf(calls, 'PATCH')).toEqual({ parent: null }));
  });

  it('ignoresADropOntoItsOwnDescendant', async () => {
    const calls = show();
    await screen.findByRole('link', { name: 'gamma' });

    fireEvent.dragStart(row('alpha'));
    fireEvent.dragOver(row('gamma'));
    fireEvent.drop(row('gamma'));

    expect(calls.some(c => c.init?.method === 'PATCH')).toBe(false);
  });

  it('movesThroughTheMenuOfferingOnlyValidParents', async () => {
    const calls = show({ '/v1/profiles/beta': summary('beta', 'omega') });
    await userEvent.click(await screen.findByRole('button', { name: 'Actions for beta' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Move to…' }));

    const select = screen.getByRole('combobox');
    expect(within(select).getAllByRole('option').map(o => o.textContent)).toEqual(['Top level', 'alpha', 'omega']);
    await userEvent.selectOptions(select, 'omega');
    await userEvent.click(screen.getByRole('button', { name: 'Move' }));

    await waitFor(() => expect(bodyOf(calls, 'PATCH')).toEqual({ parent: 'omega' }));
  });

  it('showsTheDaemonsReasonWhenAMoveIsRefused', async () => {
    show({ '/v1/profiles/omega': reply(409, { error: 'conflict', message: 'Profile omega cannot move there' }) });
    await screen.findByRole('link', { name: 'omega' });

    fireEvent.dragStart(row('omega'));
    fireEvent.dragOver(row('alpha'));
    fireEvent.drop(row('alpha'));

    expect(await screen.findByText('Profile omega cannot move there')).toBeInTheDocument();
  });

  it('createsAProfileUnderAParent', async () => {
    const calls = show({ '/v1/profiles/notes': summary('notes', 'alpha', 0) });
    await userEvent.click(await screen.findByRole('button', { name: 'New profile' }));

    await userEvent.type(screen.getByRole('textbox'), 'notes');
    await userEvent.selectOptions(screen.getByRole('combobox'), 'alpha');
    await userEvent.click(screen.getByRole('button', { name: 'Create profile' }));

    await waitFor(() => expect(bodyOf(calls, 'PUT')).toEqual({ parent: 'alpha' }));
    expect(calls.find(c => c.init?.method === 'PUT')?.url.pathname).toBe('/v1/profiles/notes');
  });

  it('refusesANameAnAgentCouldNotWriteTo', async () => {
    const calls = show();
    await userEvent.click(await screen.findByRole('button', { name: 'New profile' }));

    await userEvent.type(screen.getByRole('textbox'), 'Team Notes');
    await userEvent.click(screen.getByRole('button', { name: 'Create profile' }));

    expect(screen.getByText(/“team-notes”/)).toBeInTheDocument();
    expect(calls.some(c => c.init?.method === 'PUT')).toBe(false);
  });

  it('opensTheCreateDialogFromTheNewLink', async () => {
    show({}, '/p/alpha/profiles?new=1');

    expect(await screen.findByRole('dialog', { name: 'New profile' })).toBeInTheDocument();
  });

  it('deleteSaysWhereTheSubProfilesGo', async () => {
    const calls = show({ '/v1/profiles/beta': () => reply(204, null) });
    await userEvent.click(await screen.findByRole('button', { name: 'Actions for beta' }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Delete…' }));

    const dialog = screen.getByRole('dialog', { name: 'Delete beta?' });
    expect(dialog).toHaveTextContent('1 sub-profile (gamma) will move up to alpha');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete profile' }));

    await waitFor(() => expect(calls.some(c => c.init?.method === 'DELETE' && c.url.pathname === '/v1/profiles/beta')).toBe(true));
  });
});
