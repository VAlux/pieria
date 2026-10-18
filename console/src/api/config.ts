import { profilePath, request } from './client';

export type ConfigKind = 'weight' | 'int' | 'double' | 'bool' | 'enum' | 'string' | 'secret';
export type ConfigTier = 'live' | 'restart' | 'locked';

export interface ConfigField {
  key: string;
  scope: 'profile' | 'global';
  section: string;
  tier: ConfigTier;
  kind: ConfigKind;
  options?: string[];
  label: string;
  hint?: string;
}

/** Wire form is kebab-case (the daemon's config codec). */
export interface GlobalConfigEntry {
  key: string;
  section: string;
  tier: ConfigTier;
  kind: ConfigKind;
  options: string[];
  label: string;
  hint?: string;
  value?: string | null;
  'file-value'?: string | null;
  provenance: 'set' | 'default';
  'restart-pending': boolean;
  'default-value'?: string | null;
  'secret-set'?: boolean;
}

export interface GlobalConfig {
  entries: GlobalConfigEntry[];
  configFile: string;
  restartCommand: string;
}

export interface ApplyResult {
  written: string[];
  cleared: string[];
  'restart-required': string[];
}

/** Nested sections (`retrieval`, `ingestion`, `wiki`) of kebab-case keys. */
export type Overrides = Record<string, Record<string, unknown>>;

export interface ProfileConfigDetail {
  global: Overrides;
  overrides: Overrides;
  effective: Overrides;
}

export interface ProviderTier {
  tier: 'extraction' | 'synthesis' | 'embedding';
  model: string;
  reachable: boolean;
  latencyMs: number;
  errorKind?: string;
  error?: string;
  embeddingDimensions?: number;
  dimensionMatches?: boolean;
}

export function configSchema(signal?: AbortSignal): Promise<ConfigField[]> {
  return request<ConfigField[]>('/v1/config/schema', { signal });
}

export function globalConfig(signal?: AbortSignal): Promise<GlobalConfig> {
  return request<GlobalConfig>('/v1/config', { signal });
}

export function saveGlobalConfig(values: Record<string, string | null>, acknowledgeDestructive = false): Promise<ApplyResult> {
  return request<ApplyResult>('/v1/config', { method: 'PUT', json: { values, acknowledgeDestructive } });
}

export function profileConfig(profile: string, signal?: AbortSignal): Promise<ProfileConfigDetail> {
  return request<ProfileConfigDetail>(profilePath(profile, '/config/detail'), { signal });
}

export function saveProfileConfig(profile: string, overrides: Overrides): Promise<Overrides> {
  return request<Overrides>(profilePath(profile, '/config'), { method: 'PUT', json: overrides });
}

export function clearProfileConfig(profile: string): Promise<void> {
  return request<void>(profilePath(profile, '/config'), { method: 'DELETE' });
}

export async function testProvider(values: {
  type?: string; baseUrl?: string; apiKey?: string; apiVersion?: string;
  extractionModel?: string; synthesisModel?: string; embeddingModel?: string;
}): Promise<ProviderTier[]> {
  const result = await request<{ tiers: ProviderTier[] }>('/v1/config/provider/test', { method: 'POST', json: values });
  return result.tiers;
}

export function restartDaemon(force = false): Promise<{ mechanism: string; expectedDowntimeMs: number }> {
  return request('/v1/daemon/restart', { method: 'POST', json: { force } });
}
