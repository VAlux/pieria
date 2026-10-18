import { download, profilePath, request } from './client';
import type { HealthResponse, ProfileStats, ProfileSummary, StatusResponse } from './types';

export async function listProfiles(signal?: AbortSignal): Promise<ProfileSummary[]> {
  const result = await request<{ profiles: ProfileSummary[] }>('/v1/profiles', { signal });
  return result?.profiles ?? [];
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
