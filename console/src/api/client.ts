/** Typed same-origin client for the daemon API. Owns URL building, audit headers and errors. */

export class ApiError extends Error {
  readonly status: number;
  readonly code: string | undefined;
  readonly body: unknown;

  constructor(status: number, message: string, code?: string, body?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

export type QueryValue = string | number | boolean | null | undefined | readonly string[];

/** Builds `?a=1&b=x` from defined values; arrays repeat the key. Empty strings are dropped. */
export function query(params: Record<string, QueryValue>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) value.forEach(v => search.append(key, v));
    else search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

export function profilePath(profile: string, suffix = ''): string {
  return `/v1/profiles/${encodeURIComponent(profile)}${suffix}`;
}

export interface RequestOptions extends Omit<RequestInit, 'body'> {
  /** Serialized as JSON with a matching content type. */
  json?: unknown;
  body?: BodyInit | null;
}

function headersFor(init: RequestOptions): Headers {
  const headers = new Headers(init.headers);
  headers.set('X-Pieria-Client', 'console');
  headers.set('X-Pieria-Channel', 'console');
  if (!headers.has('Accept')) headers.set('Accept', 'application/json');
  if (init.json !== undefined) headers.set('Content-Type', 'application/json');
  return headers;
}

async function failure(response: Response): Promise<ApiError> {
  let message = `Request failed (${response.status})`;
  let code: string | undefined;
  let body: unknown;
  try {
    body = await response.json();
    if (body && typeof body === 'object') {
      const b = body as { message?: string; detail?: string; error?: string; code?: string };
      message = b.message || b.detail || message;
      code = b.code || b.error;
    }
  } catch {
    // Keep the status line when the body is not JSON.
  }
  return new ApiError(response.status, message, code, body);
}

export async function send(path: string, init: RequestOptions = {}): Promise<Response> {
  const { json, ...rest } = init;
  const response = await fetch(path, {
    ...rest,
    headers: headersFor(init),
    body: json !== undefined ? JSON.stringify(json) : rest.body
  });
  if (!response.ok) throw await failure(response);
  return response;
}

export async function request<T>(path: string, init: RequestOptions = {}): Promise<T> {
  const response = await send(path, init);
  if (response.status === 204 || response.headers.get('Content-Length') === '0') return null as T;
  const type = response.headers.get('Content-Type') ?? '';
  if (!type.includes('json')) return (await response.text()) as T;
  return (await response.json()) as T;
}

/** Downloads an NDJSON (or any) endpoint as a file without leaving the page. */
export async function download(path: string, filename: string, accept = 'application/x-ndjson'): Promise<void> {
  const response = await send(path, { headers: { Accept: accept } });
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

export function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

export function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}
