import { profilePath, query, request } from './client';

export interface Cluster {
  id: string;
  profileId: string;
  revision: number;
  title: string;
  scope: string | null;
  state: string;
  directCount: number;
  distinctOriginalCount: number;
  freshness: string;
}

export interface ClusterMember {
  kind: 'memory' | 'cluster';
  id: string;
}

export interface ClusterDetail {
  cluster: Cluster;
  members: ClusterMember[];
  parents: string[];
}

export interface ClusterClaim {
  text: string;
  supports: { memoryId: string; assertionRevisionId: string }[];
  covers: string[];
}

export interface ClusterSynthesis {
  id: string;
  clusterId: string;
  structureRevision: number;
  body: string | null;
  status: string;
  claims: ClusterClaim[];
}

export interface ClusterRun {
  id: string;
  state: string;
  trigger: string;
  createdAt: string;
  updatedAt: string;
}

export interface ClusterLink {
  a: string;
  b: string;
  sharedEntities: number;
  topEntityNames: string[];
}

export interface ClusterEvidence {
  memoryId: string;
  assertionRevisionId: string;
  content: string;
  sessionId: string | null;
}

export function listClusters(profile: string, q?: string, signal?: AbortSignal): Promise<Cluster[]> {
  return request<Cluster[]>(profilePath(profile, `/clusters${query({ q, limit: 60 })}`), { signal });
}

export function clusterDetail(profile: string, id: string, signal?: AbortSignal): Promise<ClusterDetail> {
  return request<ClusterDetail>(profilePath(profile, `/clusters/${encodeURIComponent(id)}`), { signal });
}

export function clusterSynthesis(profile: string, id: string, signal?: AbortSignal): Promise<ClusterSynthesis | null> {
  return request<ClusterSynthesis | null>(profilePath(profile, `/clusters/${encodeURIComponent(id)}/synthesis`), { signal });
}

export function clusterOriginals(profile: string, id: string, signal?: AbortSignal): Promise<ClusterEvidence[]> {
  return request<ClusterEvidence[]>(profilePath(profile, `/clusters/${encodeURIComponent(id)}/originals${query({ limit: 50 })}`), { signal });
}

export async function clusterLinks(profile: string, ids: string[], signal?: AbortSignal): Promise<ClusterLink[]> {
  if (ids.length < 2) return [];
  const result = await request<{ links: ClusterLink[] }>(profilePath(profile, `/clusters/links${query({ ids: ids.join(','), minShared: 1, limit: 200 })}`), { signal });
  return result?.links ?? [];
}

export function renameCluster(profile: string, cluster: Cluster, title: string): Promise<Cluster> {
  return request<Cluster>(profilePath(profile, `/clusters/${encodeURIComponent(cluster.id)}/rename`), {
    method: 'POST', json: { expectedRevision: cluster.revision, title, scope: cluster.scope }
  });
}

export function regenerateCluster(profile: string, cluster: Cluster): Promise<unknown> {
  return request(profilePath(profile, `/clusters/${encodeURIComponent(cluster.id)}/regenerate`), {
    method: 'POST', json: { expectedRevision: cluster.revision }
  });
}

export function dissolveCluster(profile: string, cluster: Cluster): Promise<Cluster> {
  return request<Cluster>(profilePath(profile, `/clusters/${encodeURIComponent(cluster.id)}/dissolve`), {
    method: 'POST', json: { expectedRevision: cluster.revision }
  });
}

export async function latestClusterRun(profile: string, signal?: AbortSignal): Promise<ClusterRun | null> {
  const runs = await request<ClusterRun[]>(profilePath(profile, `/clustering/runs${query({ limit: 1 })}`), { signal });
  return runs?.[0] ?? null;
}

export function startClusterRun(profile: string): Promise<ClusterRun> {
  return request<ClusterRun>(profilePath(profile, '/clustering/runs'), { method: 'POST' });
}
