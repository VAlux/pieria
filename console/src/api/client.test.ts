import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, profilePath, query, request } from './client';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

afterEach(() => vi.restoreAllMocks());

describe('query', () => {
  it('drops empty values and repeats array keys', () => {
    expect(query({ q: 'a b', type: undefined, limit: 50, empty: '', flag: false, types: ['x', 'y'] }))
      .toBe('?q=a+b&limit=50&flag=false&types=x&types=y');
    expect(query({})).toBe('');
  });
});

describe('profilePath', () => {
  it('encodes the profile name', () => {
    expect(profilePath('a/b c', '/memories')).toBe('/v1/profiles/a%2Fb%20c/memories');
  });
});

describe('request', () => {
  it('sends console audit headers and JSON bodies', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ ok: true }));
    await request('/v1/x', { method: 'POST', json: { a: 1 } });
    const [, init] = fetchMock.mock.calls[0];
    const headers = new Headers(init?.headers);
    expect(headers.get('X-Pieria-Client')).toBe('console');
    expect(headers.get('X-Pieria-Channel')).toBe('console');
    expect(headers.get('Content-Type')).toBe('application/json');
    expect(init?.body).toBe('{"a":1}');
  });

  it('returns null for 204', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 204 }));
    expect(await request('/v1/x')).toBeNull();
  });

  it('turns error bodies into ApiError with code and message', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ error: 'not_found', message: 'No such memory' }, 404));
    const error = await request('/v1/x').catch(e => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 404, code: 'not_found', message: 'No such memory' });
  });

  it('keeps the status line when the error body is not JSON', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('boom', { status: 502 }));
    await expect(request('/v1/x')).rejects.toMatchObject({ status: 502, message: 'Request failed (502)' });
  });
});
