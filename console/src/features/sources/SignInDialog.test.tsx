import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { SourceAuthorization } from '../../api/sources';
import { SignInDialog } from './SignInDialog';
import { mockApi, renderAt } from '../../test/render';

afterEach(() => vi.restoreAllMocks());

const PATH = '/v1/profiles/p/sources/t/authorize';

function pending(overrides: Partial<SourceAuthorization> = {}): SourceAuthorization {
  return {
    status: 'pending', verificationUri: 'https://microsoft.com/devicelogin', userCode: 'ABCD-EFGH',
    message: 'To sign in, open https://microsoft.com/devicelogin and enter ABCD-EFGH.',
    expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(), errorClass: null, error: null, ...overrides
  };
}

function open(started: SourceAuthorization, polled: () => SourceAuthorization) {
  const calls = mockApi({ [PATH]: (_url: URL, init?: RequestInit) => (init?.method === 'POST' ? started : polled()) });
  const view = renderAt('/p/:profile/sources/:sourceId', '/p/p/sources/t', <SignInDialog profile="p" sourceId="t" onClose={() => {}} />);
  const gets = () => calls.filter(c => c.url.pathname === PATH && (c.init?.method ?? 'GET') === 'GET').length;
  const posts = () => calls.filter(c => c.url.pathname === PATH && c.init?.method === 'POST').length;
  return { view, gets, posts };
}

describe('SignInDialog', () => {
  it('showsTheCodeThenSignsIn', async () => {
    open(pending(), () => ({ ...pending(), status: 'authorized' }));

    expect(await screen.findByText('ABCD-EFGH')).toBeInTheDocument();
    expect(screen.getByText('To sign in, open https://microsoft.com/devicelogin and enter ABCD-EFGH.')).toBeInTheDocument();
    const link = screen.getByRole('link', { name: 'Open the sign-in page' });
    expect(link).toHaveAttribute('href', 'https://microsoft.com/devicelogin');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(link).toHaveAttribute('target', '_blank');
    expect(await screen.findByText('Signed in.', {}, { timeout: 3000 })).toBeInTheDocument();
  });

  it('failureOffersRetry', async () => {
    const { posts } = open(pending(), () => ({ ...pending(), status: 'failed', errorClass: 'auth', error: 'declined' }));

    expect(await screen.findByText('Sign-in failed (auth): declined', {}, { timeout: 3000 })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(posts()).toBe(2));
  });

  it('nonHttpsLinkIsNotRendered', async () => {
    open(pending({ verificationUri: 'javascript:alert(1)' }), () => pending());

    expect(await screen.findByText('ABCD-EFGH')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Open the sign-in page' })).toBeNull();
  });

  it('startFailureIsShown', async () => {
    open({ ...pending(), status: 'failed', verificationUri: null, userCode: null, message: null, expiresAt: null, errorClass: 'config', error: 'clientId is wrong' },
      () => pending());

    expect(await screen.findByText('Sign-in failed (config): clientId is wrong')).toBeInTheDocument();
  });

  it('closingStopsPolling', async () => {
    const { view, gets } = open(pending(), () => pending());
    await waitFor(() => expect(gets()).toBeGreaterThan(0), { timeout: 3000 });

    view.unmount();
    const after = gets();
    await new Promise(resolve => setTimeout(resolve, 2_200));

    expect(gets()).toBe(after);
  });
});
