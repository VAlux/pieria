import { profilePath, query, request } from './client';
import type {
  FactHistory, Memory, MemorySearchResponse, RememberRequest, Revision, RevisionRequest, SessionSummary, TopicKeyMatch
} from './types';

export interface MemorySearchParams {
  q?: string;
  type?: string;
  session?: string;
  sort?: 'newest' | 'oldest';
  limit?: number;
  cursor?: string | null;
  includeSuperseded?: boolean;
  groupToolCalls?: boolean;
}

export function searchMemories(profile: string, params: MemorySearchParams, signal?: AbortSignal): Promise<MemorySearchResponse> {
  return request<MemorySearchResponse>(profilePath(profile, `/memories/search${query({ ...params })}`), { signal });
}

export function getMemory(profile: string, id: string, signal?: AbortSignal): Promise<Memory> {
  return request<Memory>(profilePath(profile, `/memories/${encodeURIComponent(id)}`), { signal });
}

export function memoryHistory(profile: string, id: string, at: { asOf?: string | null; knownAsOf?: string | null } = {}, signal?: AbortSignal): Promise<FactHistory> {
  return request<FactHistory>(profilePath(profile, `/memories/${encodeURIComponent(id)}/history${query(at)}`), { signal });
}

export function reviseMemory(profile: string, id: string, body: RevisionRequest): Promise<Revision> {
  return request<Revision>(profilePath(profile, `/memories/${encodeURIComponent(id)}/revisions`), { method: 'POST', json: body });
}

export function forgetMemory(profile: string, id: string): Promise<void> {
  return request<void>(profilePath(profile, `/memories/${encodeURIComponent(id)}`), { method: 'DELETE' });
}

export function rememberMemory(profile: string, body: RememberRequest): Promise<Memory> {
  return request<Memory>(profilePath(profile, '/memories'), { method: 'POST', json: body });
}

export async function listSessions(profile: string, q?: string, signal?: AbortSignal): Promise<SessionSummary[]> {
  const result = await request<{ sessions: SessionSummary[] }>(profilePath(profile, `/sessions${query({ q, limit: 200 })}`), { signal });
  return result?.sessions ?? [];
}

export async function searchTopicKeys(profile: string, q: string, type?: string, signal?: AbortSignal): Promise<TopicKeyMatch[]> {
  const result = await request<{ keys: TopicKeyMatch[] }>(profilePath(profile, `/topic-keys${query({ q, type, limit: 8 })}`), { signal });
  return result?.keys ?? [];
}
