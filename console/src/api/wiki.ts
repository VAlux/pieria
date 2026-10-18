import { profilePath, query, request } from './client';
import type { TemporalValidity } from './types';

export interface WikiState {
  enabled: boolean;
  initialized: boolean;
  corpusWatermark: number;
  projectionWatermark: number;
  revision: number;
}

export type WikiGeneration = 'ready' | 'queued' | 'generating' | 'failed' | 'absent';
export type WikiFreshness = 'current' | 'stale' | 'needs-check' | 'unavailable';

export interface WikiCard {
  id: string;
  title: string;
  kind: 'directory' | 'topic' | 'entity' | string;
  scope: string | null;
  parentId: string | null;
  descriptorRevision: number;
  evidenceCount: number;
  generation: WikiGeneration;
  freshness: WikiFreshness;
  excerpt: string | null;
}

export interface WikiEvidence {
  ref: string;
  memoryId: string;
  content: string;
  type: string;
  role: string;
  validity: TemporalValidity[];
  truncated: boolean;
}

export interface WikiJob {
  id: string;
  pageId: string;
  status: 'queued' | 'generating' | 'published' | 'failed' | 'obsolete' | string;
  attempts: number;
  errorCode: string | null;
  versionId: string | null;
}

export interface WikiPage {
  state: WikiState;
  page: WikiCard;
  body: string | null;
  versionId: string | null;
  evidence: WikiEvidence[];
  links: { id: string; title: string; kind: string }[];
  job: WikiJob | null;
  totalSources: number;
  omittedSources: number;
  addedSources: number;
  removedSources: number;
  renderingUpgrade: boolean;
}

export interface WikiDirectory {
  state: WikiState;
  pages: WikiCard[];
  nextCursor: string | null;
}

export interface WikiSearch {
  state: WikiState;
  pages: WikiCard[];
  unassigned: WikiEvidence[];
  degraded: boolean;
  truncated: boolean;
}

export interface WikiStatus {
  state: WikiState;
  projectionLag: number;
  unassignedEvidence: number;
  pagesTotal: number;
  articlesReady: number;
  articlesAbsent: number;
  articlesStale: number;
  jobsQueued: number;
  jobsGenerating: number;
  jobsFailed: number;
}

export function wikiDirectory(profile: string, parent: string | null, cursor?: string | null, signal?: AbortSignal): Promise<WikiDirectory> {
  return request<WikiDirectory>(profilePath(profile, `/wiki${query({ parent, cursor, limit: 100 })}`), { signal });
}

export function wikiSearch(profile: string, q: string, signal?: AbortSignal): Promise<WikiSearch> {
  return request<WikiSearch>(profilePath(profile, `/wiki/search${query({ q, limit: 30 })}`), { signal });
}

export function wikiPage(profile: string, id: string, signal?: AbortSignal): Promise<WikiPage> {
  return request<WikiPage>(profilePath(profile, `/wiki/pages/${encodeURIComponent(id)}`), { signal });
}

export function evaluateWikiPage(profile: string, page: WikiPage, intent: 'initial' | 'refresh' | 'retry') {
  return request<{ status: string; reason: string | null; job: WikiJob | null; versionId: string | null }>(
    profilePath(profile, `/wiki/pages/${encodeURIComponent(page.page.id)}/evaluate`),
    { method: 'POST', json: { expectedDescriptorRevision: page.page.descriptorRevision, expectedVersionId: page.versionId, intent } }
  );
}

export function wikiJob(profile: string, id: string, signal?: AbortSignal): Promise<WikiJob> {
  return request<WikiJob>(profilePath(profile, `/wiki/jobs/${encodeURIComponent(id)}`), { signal });
}

export function wikiStatus(profile: string, signal?: AbortSignal): Promise<WikiStatus> {
  return request<WikiStatus>(profilePath(profile, '/wiki/status'), { signal });
}

export function buildWiki(profile: string, scope: 'missing' | 'stale'): Promise<{ taskId: string }> {
  return request<{ taskId: string }>(profilePath(profile, '/wiki/build'), { method: 'POST', json: { scope } });
}
