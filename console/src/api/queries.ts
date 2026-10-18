import { keepPreviousData, QueryClient, useQuery } from '@tanstack/react-query';
import { ApiError } from './client';
import { getMemory, listSessions, memoryHistory, searchMemories, type MemorySearchParams } from './memories';
import { daemonHealth, daemonStatus, listProfiles, profileStats } from './profiles';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      refetchOnWindowFocus: true,
      retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2
    }
  }
});

/** Query keys: everything profile-scoped starts with ['profile', name] so a switch invalidates cleanly. */
export const qk = {
  profiles: ['profiles'] as const,
  status: ['status'] as const,
  health: ['health'] as const,
  profile: (p: string) => ['profile', p] as const,
  stats: (p: string) => ['profile', p, 'stats'] as const,
  memories: (p: string, params: unknown) => ['profile', p, 'memories', params] as const,
  memory: (p: string, id: string) => ['profile', p, 'memory', id] as const,
  history: (p: string, id: string, at: unknown) => ['profile', p, 'memory', id, 'history', at] as const,
  sessions: (p: string) => ['profile', p, 'sessions'] as const,
  topicKeys: (p: string, q: string, type?: string) => ['profile', p, 'topic-keys', q, type] as const,
  audit: (scope: string | null, params: unknown) => ['audit', scope, params] as const,
  auditFacets: (scope: string | null, params: unknown) => ['audit', scope, 'facets', params] as const,
  auditEvent: (id: string) => ['audit', 'event', id] as const,
  tasks: (profile?: string | null) => ['tasks', profile ?? null] as const,
  configSchema: ['config', 'schema'] as const,
  globalConfig: ['config', 'global'] as const,
  profileConfig: (p: string) => ['profile', p, 'config'] as const,
  wikiStatus: (p: string) => ['profile', p, 'wiki', 'status'] as const,
  wikiDir: (p: string, parent: string | null) => ['profile', p, 'wiki', 'dir', parent] as const,
  wikiPage: (p: string, id: string) => ['profile', p, 'wiki', 'page', id] as const,
  wikiSearch: (p: string, q: string) => ['profile', p, 'wiki', 'search', q] as const,
  graph: (p: string, ...rest: unknown[]) => ['profile', p, 'graph', ...rest] as const,
  clusters: (p: string, ...rest: unknown[]) => ['profile', p, 'clusters', ...rest] as const
};

export function useProfiles() {
  return useQuery({ queryKey: qk.profiles, queryFn: ({ signal }) => listProfiles(signal), staleTime: 30_000 });
}

export function useStatus() {
  return useQuery({ queryKey: qk.status, queryFn: ({ signal }) => daemonStatus(signal), refetchInterval: 30_000, retry: false });
}

export function useHealth() {
  return useQuery({ queryKey: qk.health, queryFn: ({ signal }) => daemonHealth(signal), refetchInterval: 30_000, retry: false });
}

export function useStats(profile: string) {
  return useQuery({ queryKey: qk.stats(profile), queryFn: ({ signal }) => profileStats(profile, signal), enabled: Boolean(profile) });
}

export function useMemorySearch(profile: string, params: MemorySearchParams) {
  return useQuery({
    queryKey: qk.memories(profile, params),
    queryFn: ({ signal }) => searchMemories(profile, params, signal),
    placeholderData: keepPreviousData,
    enabled: Boolean(profile)
  });
}

export function useMemory(profile: string, id: string | null | undefined) {
  return useQuery({
    queryKey: qk.memory(profile, id ?? ''),
    queryFn: ({ signal }) => getMemory(profile, id!, signal),
    enabled: Boolean(profile && id)
  });
}

export function useMemoryHistory(profile: string, id: string | null | undefined, at: { asOf?: string | null; knownAsOf?: string | null }) {
  return useQuery({
    queryKey: qk.history(profile, id ?? '', at),
    queryFn: ({ signal }) => memoryHistory(profile, id!, at, signal),
    enabled: Boolean(profile && id),
    retry: false
  });
}

export function useSessions(profile: string) {
  return useQuery({ queryKey: qk.sessions(profile), queryFn: ({ signal }) => listSessions(profile, undefined, signal), enabled: Boolean(profile), staleTime: 60_000 });
}
