import { profilePath, query, request } from './client';

export interface AuditEventSummary {
  id: string;
  profile?: string | null;
  eventType: string;
  operation: string;
  requestId: string | null;
  parentRequestId: string | null;
  taskId: string | null;
  sessionId: string | null;
  resourceId: string | null;
  client: string | null;
  harness: string | null;
  channel: string | null;
  startedAt: string;
  completedAt: string;
  durationMs: number;
  httpStatus: number | null;
  outcome: 'success' | 'failure' | 'cancelled' | string;
  errorKind: string | null;
  errorMessage: string | null;
  requestBytes: number;
  requestTruncated: boolean;
  responseBytes: number;
  responseTruncated: boolean;
  responsePreview: string | null;
}

export interface AuditEventDetail extends AuditEventSummary {
  clientVersion: string | null;
  serverVersion: string | null;
  remoteAddress: string | null;
  method: string | null;
  path: string | null;
  queryString: string | null;
  requestMediaType: string | null;
  responseMediaType: string | null;
  metadata: string | null;
  requestBody: string | null;
  requestSha256: string | null;
  responseBody: string | null;
  responseSha256: string | null;
}

export interface AuditQuery {
  q?: string;
  operation?: string;
  client?: string;
  harness?: string;
  channel?: string;
  outcome?: string;
  session?: string;
  taskId?: string;
  requestId?: string;
  from?: string;
  to?: string;
  limit?: number;
  cursor?: string | null;
  profile?: string;
}

export interface AuditListResponse {
  events: AuditEventSummary[];
  nextCursor: string | null;
}

export interface FacetValue {
  value: string;
  count: number;
}

export interface AuditFacets {
  facets: Record<'operation' | 'client' | 'harness' | 'channel' | 'outcome' | 'profile', FacetValue[]>;
}

/** Per-profile feed when `scope` is a profile name; daemon-wide when null. */
function base(scope: string | null): string {
  return scope ? profilePath(scope, '/audit') : '/v1/audit';
}

export function listAudit(scope: string | null, params: AuditQuery, signal?: AbortSignal): Promise<AuditListResponse> {
  return request<AuditListResponse>(`${base(scope)}${query({ ...params })}`, { signal });
}

export function auditFacets(params: AuditQuery, signal?: AbortSignal): Promise<AuditFacets> {
  return request<AuditFacets>(`/v1/audit/facets${query({ ...params, cursor: undefined, limit: undefined })}`, { signal });
}

export function auditEvent(scope: string | null, id: string, signal?: AbortSignal): Promise<AuditEventDetail> {
  return request<AuditEventDetail>(`${base(scope)}/${encodeURIComponent(id)}`, { signal });
}
