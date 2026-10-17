export interface Profile {
  name: string;
  memoryCount: number;
}

export interface Memory {
  id: string;
  type: string;
  content: string;
  topicKey?: string | null;
  sessionId?: string | null;
  createdAt?: string | null;
  superseded?: boolean;
  payload?: string | null;
}

export interface Health {
  status: string;
  db?: string;
  modelProvider?: string;
}

export function profilePath(name: string, suffix: string): string {
  return `/v1/profiles/${encodeURIComponent(name)}${suffix}`;
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('X-Pieria-Client', 'console');
  headers.set('X-Pieria-Channel', 'console');
  if (!headers.has('Accept')) headers.set('Accept', 'application/json');
  const response = await fetch(path, { ...init, headers });
  if (response.status === 204) return null as T;
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const error = await response.json() as { message?: string; detail?: string };
      message = error.message || error.detail || message;
    } catch { /* Keep the status when the body is not JSON. */ }
    throw new Error(message);
  }
  return response.json() as Promise<T>;
}
