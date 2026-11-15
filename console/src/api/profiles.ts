import { download, profilePath, request } from './client';
import type { HealthResponse, ProfileStats, ProfileSummary, StatusResponse } from './types';

export async function listProfiles(signal?: AbortSignal): Promise<ProfileSummary[]> {
  const result = await request<{ profiles: ProfileSummary[] }>('/v1/profiles', { signal });
  return result?.profiles ?? [];
}

/** Creates an empty profile, under `parent` when given. 409 when the name is taken, 404 for an unknown parent. */
export function createProfile(name: string, parent?: string | null): Promise<ProfileSummary> {
  return request<ProfileSummary>(profilePath(name), { method: 'PUT', json: parent ? { parent } : undefined });
}

/** Moves a profile under `parent`, or to top level with null. 409 when that would make a cycle. */
export function setProfileParent(name: string, parent: string | null): Promise<ProfileSummary> {
  return request<ProfileSummary>(profilePath(name), { method: 'PATCH', json: { parent } });
}

/** Deletes a profile and its own memories; its sub-profiles move up to its parent. */
export function deleteProfile(name: string): Promise<void> {
  return request<void>(profilePath(name), { method: 'DELETE' });
}

export function profileStats(profile: string, signal?: AbortSignal): Promise<ProfileStats> {
  return request<ProfileStats>(profilePath(profile, '/stats'), { signal });
}

export function daemonStatus(signal?: AbortSignal): Promise<StatusResponse> {
  return request<StatusResponse>('/pieria-status', { signal });
}

export function daemonHealth(signal?: AbortSignal): Promise<HealthResponse> {
  return request<HealthResponse>('/pieria-health', { signal });
}

export function exportProfile(profile: string): Promise<void> {
  return download(profilePath(profile, '/export'), `${profile}-memories.ndjson`);
}
