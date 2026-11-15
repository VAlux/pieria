import { afterEach, describe, expect, it, vi } from 'vitest';
import { canSignIn, createSource, getSource, removeSource, runSource, setSecrets, type CreateSource, type Harvester } from './sources';
import { mockApi } from '../test/render';

afterEach(() => vi.restoreAllMocks());

const draft: CreateSource = {
  id: 'acme', harvester: 'jira', config: { baseUrl: 'https://x' }, secrets: { token: 't' }, mode: 'full',
  intervalSeconds: 21600, maxItemsPerRun: null, maxCharsPerRun: null, start: false
};

function headers(init?: RequestInit): Headers {
  return new Headers(init?.headers);
}

describe('sources api', () => {
  it('createPostsTheDraftToTheProfile', async () => {
    const calls = mockApi({ '/v1/profiles/my%20p/sources': { ...draft, status: 'paused' } });

    await createSource('my p', draft);

    const call = calls[0];
    expect(call.url.pathname).toBe('/v1/profiles/my%20p/sources');
    expect(call.init?.method).toBe('POST');
    expect(JSON.parse(String(call.init?.body))).toEqual(draft);
    expect(headers(call.init).get('Content-Type')).toBe('application/json');
    expect(headers(call.init).get('X-Pieria-Client')).toBe('console');
  });

  it('removeSendsForgetMemories', async () => {
    const calls = mockApi({ '/v1/profiles/p/sources/s': null });

    await removeSource('p', 's', true);

    expect(calls[0].init?.method).toBe('DELETE');
    expect(calls[0].url.search).toBe('?forgetMemories=true');
  });

  it('runSendsDryRunAndReturnsTheTask', async () => {
    const calls = mockApi({ '/v1/profiles/p/sources/s/run': { taskId: 't1' } });

    expect(await runSource('p', 's', true)).toBe('t1');
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ dryRun: true });
  });

  it('secretsArePut', async () => {
    const calls = mockApi({ '/v1/profiles/p/sources/s/secrets': null });

    await setSecrets('p', 's', { token: 'x' });

    expect(calls[0].init?.method).toBe('PUT');
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ secrets: { token: 'x' } });
  });

  it('idsAreEncoded', async () => {
    const calls = mockApi({ '/v1/profiles/p/sources/a%2Fb': { id: 'a/b' } });

    await getSource('p', 'a/b');

    expect(calls[0].url.pathname).toBe('/v1/profiles/p/sources/a%2Fb');
  });

  it('canSignInReadsTheCapability', () => {
    const harvester = (capabilities: string[]) => ({ capabilities } as Harvester);
    expect(canSignIn(harvester(['incremental', 'oauth-device-code']))).toBe(true);
    expect(canSignIn(harvester(['incremental']))).toBe(false);
    expect(canSignIn(undefined)).toBe(false);
  });
});
