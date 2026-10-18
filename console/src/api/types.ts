/** Mirrors of the daemon DTOs in modules/shared api/{request,response}. Instants are ISO strings. */

export type MemoryType = 'fact' | 'event' | 'instruction' | 'task' | 'cluster';
export const MEMORY_TYPES = ['fact', 'event', 'instruction', 'task'] as const;

export interface ProfileSummary {
  name: string;
  createdAt: string | null;
  memoryCount: number;
  sessionCount?: number;
  lastMemoryAt?: string | null;
}

export interface HealthResponse {
  status: 'up' | 'degraded' | string;
  db?: string;
  modelProvider?: string;
}

export interface StatusResponse {
  status: string;
  databasePath?: string;
  backend: string;
  vectorSearch: boolean;
  modelProvider: string;
  extractionModel: string;
  synthesisModel: string;
  embeddingModel: string;
  vectorizationOutboxDepth: number | null;
  host?: string;
  port?: number;
  providerLabel?: string;
  providerType?: string;
  apiVersion?: number;
  daemonVersion?: string;
  restart?: { supported: boolean; mechanism: string | null };
}

export interface ProfileStats {
  name: string;
  createdAt: string | null;
  totalActive: number;
  byType: Record<string, number>;
  superseded: number;
  sessions: number;
  firstMemoryAt: string | null;
  lastMemoryAt: string | null;
  vectorizationBacklog: number | null;
  impact: {
    recalls: number;
    tokensSaved: number;
    tokensIngested: number;
    tokensStored: number;
    contextWindowTokens: number;
    pricePerMillionTokens: number;
  };
  spend: {
    tiers: { tier: string; calls: number; promptTokens: number; completionTokens: number; costUsd: number }[];
    totalPromptTokens: number;
    totalCompletionTokens: number;
    totalCostUsd: number;
    costAvailable: boolean;
  };
}

export interface TemporalValidity {
  assertionId: string;
  revisionId: string;
  validFrom: string | null;
  validTo: string | null;
  recordedFrom: string | null;
  recordedTo: string | null;
  statedAt: string | null;
  sourceOccurrence: string | null;
  factGroup: string | null;
  sourcePayload: string | null;
}

export interface Revision {
  assertionId: string;
  revisionId: string;
  memoryId: string;
  content: string;
  validFrom: string | null;
  validTo: string | null;
  statedAt: string | null;
  recordedFrom: string | null;
  recordedTo: string | null;
  corrects: string | null;
  sourceOccurrence: string | null;
  invalidated: boolean;
  factGroup: string | null;
  sourcePayload: string | null;
  conflictEvidence: string | null;
}

export interface Memory {
  id: string;
  type: MemoryType;
  content: string;
  topicKey: string | null;
  sessionId: string | null;
  superseded: boolean;
  payload: string | null;
  createdAt: string | null;
  validity?: TemporalValidity[];
  assertions?: Revision[];
}

export interface FactHistory {
  factGroup: string | null;
  selectedRevision: Revision | null;
  selectedValidity: TemporalValidity | null;
  revisions: Revision[];
  segments: TemporalValidity[];
  temporal: { asOf: string | null; knownAsOf: string | null; snapshotSequence: number };
}

export interface ToolCallGroup {
  sessionId: string | null;
  count: number;
  tools: string[];
  firstAt: string | null;
  lastAt: string | null;
}

export interface MemoryListItem {
  kind: 'memory' | 'tool-calls';
  memory?: Memory;
  group?: ToolCallGroup;
}

export interface MemorySearchResponse {
  items: MemoryListItem[];
  nextCursor: string | null;
  typeCounts: Record<string, number>;
  total: number;
}

export interface SessionSummary {
  sessionId: string;
  firstSeenAt: string | null;
  lastSeenAt: string | null;
  memoryCount: number;
  messageCount: number;
  harness: string | null;
}

export interface TopicKeyMatch {
  topicKey: string;
  type: MemoryType;
  memoryId: string;
  content: string;
  updatedAt: string | null;
}

export interface RememberRequest {
  type: string;
  content: string;
  sessionId?: string | null;
  topicKey?: string | null;
  payload?: string | null;
  validFrom?: string | null;
  validTo?: string | null;
  checkConflicts?: boolean;
}

export interface RevisionRequest {
  assertionId: string;
  expectedRevisionId: string;
  content?: string | null;
  validFrom?: string | null;
  validTo?: string | null;
  invalidateAt?: string | null;
  openEnded?: boolean;
}
