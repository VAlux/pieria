import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ProfileSwitcher } from './ProfileSwitcher';
import { mockApi, renderAt } from '../../test/render';

const profiles = {
  profiles: [
    { name: 'pieria', createdAt: null, memoryCount: 4940 },
    { name: 'codemie', createdAt: null, memoryCount: 1097 },
    { name: 'codemie-dev', createdAt: null, memoryCount: 21066 },
    { name: 'scratch-1', createdAt: null, memoryCount: 3 }
  ]
};

describe('ProfileSwitcher', () => {
  it('filters, moves with the arrow keys and switches while keeping the section', async () => {
    mockApi({ '/v1/profiles': profiles });
    const onClose = vi.fn();
    renderAt('/p/:profile/*', '/p/pieria/memories/abc', <ProfileSwitcher onClose={onClose} />);

    const input = await screen.findByRole('combobox');
    await waitFor(() => expect(screen.getAllByRole('option').length).toBeGreaterThan(0));
    expect(screen.queryByText('scratch-1')).not.toBeInTheDocument();
    expect(screen.getByText('Show 1 empty & scratch profiles')).toBeInTheDocument();

    await userEvent.type(input, 'code');
    expect(screen.getAllByRole('option').map(o => o.textContent)).toEqual([
      expect.stringContaining('codemie-dev'), expect.stringContaining('codemie')
    ]);
    await userEvent.keyboard('{ArrowDown}{Enter}');

    expect(onClose).toHaveBeenCalled();
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/p/codemie/memories'));
  });

  it('pins a profile into the Pinned group', async () => {
    mockApi({ '/v1/profiles': profiles });
    renderAt('/p/:profile/*', '/p/pieria', <ProfileSwitcher onClose={() => undefined} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Pin codemie' }));
    expect(screen.getByRole('group', { name: 'Pinned' })).toHaveTextContent('codemie');
  });
});
