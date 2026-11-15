import { profilePath, query, request } from './client';

/** One setting a harvester asks for. `kind` matches the manifest's field kinds. */
export interface HarvesterField {
  key: string;
  kind: 'string' | 'secret' | 'int' | 'bool' | 'enum' | 'list';
  label: string;
  hint: string | null;
  required: boolean;
  options: string[];
  defaultValue: unknown;
}

export interface Harvester {
  id: string;
  name: string;
  version: string;
  capabilities: string[];
  network: string[];
  fields: HarvesterField[];
  bundled: boolean | null;
}

export interface SourceRun {
  id: string;
  dryRun: boolean;
  status: 'running' | 'succeeded' | 'failed' | 'cancelled' | 'interrupted';
  startedAt: string;
  finishedAt: string | null;
  itemsSeen: number;
  itemsChanged: number;
  itemsDeleted: number;
  memoriesStored: number;
  chars: number;
  errorClass: string | null;
  errorMessage: string | null;
  stderrTail: string | null;
}

/** A harvest source. Secrets are listed by name only; the daemon never returns their values. */
export interface Source {
  id: string;
  profile: string;
  harvester: string;
  config: Record<string, unknown>;
  secretNames: string[];
  mode: 'full' | 'raw';
  intervalSeconds: number;
  maxItemsPerRun: number | null;
  maxCharsPerRun: number | null;
  status: 'active' | 'paused' | 'needs_auth';
  nextRunAt: string | null;
  consecutiveFailures: number;
  sweepBlockedCount: number | null;
  acceptNextSweep: boolean;
  running: boolean;
  warnings: string[];
  lastRun: SourceRun | null;
}

export interface SourceCheck {
  ok: boolean;
  errorClass: string | null;
  message: string | null;
}

export interface SourceAuthorization {
  status: 'pending' | 'authorized' | 'failed';
  verificationUri: string | null;
  userCode: string | null;
  message: string | null;
  expiresAt: string | null;
  errorClass: string | null;
  error: string | null;
}

export interface CreateSource {
  id: string;
  harvester: string;
  config: Record<string, unknown>;
  secrets: Record<string, string>;
  mode: 'full' | 'raw';
  intervalSeconds: number | null;
  maxItemsPerRun: number | null;
  maxCharsPerRun: number | null;
  start: boolean;
}

/** PATCH body; absent fields keep their values, and a config key set to null is cleared. */
export interface UpdateSource {
  config?: Record<string, unknown>;
  mode?: 'full' | 'raw';
  intervalSeconds?: number;
  maxItemsPerRun?: number;
  maxCharsPerRun?: number;
  resetCursor?: boolean;
}

const sources = (profile: string) => profilePath(profile, '/sources');
const source = (profile: string, id: string, suffix = '') => `${sources(profile)}/${encodeURIComponent(id)}${suffix}`;

export async function listHarvesters(signal?: AbortSignal): Promise<Harvester[]> {
  const result = await request<{ harvesters: Harvester[] } | null>('/v1/harvesters', { signal });
  return result?.harvesters ?? [];
}

export async function listSources(profile: string, signal?: AbortSignal): Promise<Source[]> {
  const result = await request<{ sources: Source[] } | null>(sources(profile), { signal });
  return result?.sources ?? [];
}

export function getSource(profile: string, id: string, signal?: AbortSignal): Promise<Source> {
  return request<Source>(source(profile, id), { signal });
}

export function createSource(profile: string, body: CreateSource): Promise<Source> {
  return request<Source>(sources(profile), { method: 'POST', json: body });
}

export function updateSource(profile: string, id: string, body: UpdateSource): Promise<Source> {
  return request<Source>(source(profile, id), { method: 'PATCH', json: body });
}

export async function removeSource(profile: string, id: string, forgetMemories: boolean): Promise<void> {
  await request(source(profile, id, query({ forgetMemories })), { method: 'DELETE' });
}

export function checkSource(profile: string, id: string): Promise<SourceCheck> {
  return request<SourceCheck>(source(profile, id, '/check'), { method: 'POST', json: {} });
}

/** Starts a run and returns its task id. */
export async function runSource(profile: string, id: string, dryRun: boolean): Promise<string> {
  const result = await request<{ taskId: string }>(source(profile, id, '/run'), { method: 'POST', json: { dryRun } });
  return result.taskId;
}

export async function listRuns(profile: string, id: string, limit = 20, signal?: AbortSignal): Promise<SourceRun[]> {
  const result = await request<{ runs: SourceRun[] } | null>(source(profile, id, `/runs${query({ limit })}`), { signal });
  return result?.runs ?? [];
}

export function pauseSource(profile: string, id: string): Promise<Source> {
  return request<Source>(source(profile, id, '/pause'), { method: 'POST', json: {} });
}

export function resumeSource(profile: string, id: string): Promise<Source> {
  return request<Source>(source(profile, id, '/resume'), { method: 'POST', json: {} });
}

export function acceptSweep(profile: string, id: string): Promise<Source> {
  return request<Source>(source(profile, id, '/accept-sweep'), { method: 'POST', json: {} });
}

export async function setSecrets(profile: string, id: string, secrets: Record<string, string>): Promise<void> {
  await request(source(profile, id, '/secrets'), { method: 'PUT', json: { secrets } });
}

export function startAuthorization(profile: string, id: string): Promise<SourceAuthorization> {
  return request<SourceAuthorization>(source(profile, id, '/authorize'), { method: 'POST', json: {} });
}

export function authorizationStatus(profile: string, id: string, signal?: AbortSignal): Promise<SourceAuthorization> {
  return request<SourceAuthorization>(source(profile, id, '/authorize'), { signal });
}

/** Whether the harvester signs in with a device code, rather than taking a token. */
export function canSignIn(harvester: Harvester | undefined): boolean {
  return harvester?.capabilities.includes('oauth-device-code') ?? false;
}
