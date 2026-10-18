import { profilePath, request } from './client';
import type { Memory } from './types';

export type RecallMode = 'synthesized' | 'analyzed' | 'evidence';

export interface RecallRequest {
  query: string;
  limit?: number;
  mode?: RecallMode;
  debug?: boolean;
  asOf?: string | null;
  knownAsOf?: string | null;
}

export interface RecallTimings {
  retrieveMs: number;
  rerankMs: number;
  synthesizeMs: number;
  totalMs: number;
}

export interface RecallSource {
  memoryId: string;
  rank: number;
  channels: string[];
}

export interface RecallResponse {
  answer: string | null;
  memories: Memory[];
  codeEvidence?: { src: string; srcPath: string; relation: string; dst: string; dstPath: string | null; confidence: string }[];
  debug?: {
    candidates: { id: string; score: number; source: string }[];
    channels: { channel: string; latencyMs: number; hits: number; failed: boolean }[];
  };
  temporal?: { asOf: string | null; knownAsOf: string | null; snapshotSequence: number; limitations: string[] };
  timings?: RecallTimings;
  sources?: RecallSource[];
  synthesisModel?: string | null;
}

export interface RecallResult {
  response: RecallResponse;
  /** The audit request id the daemon recorded this call under. */
  requestId: string;
}

function requestId(): string {
  return `console-${crypto.randomUUID()}`;
}

export async function recall(profile: string, body: RecallRequest, signal?: AbortSignal): Promise<RecallResult> {
  const id = requestId();
  const response = await request<RecallResponse>(profilePath(profile, '/recall'), {
    method: 'POST',
    json: body,
    signal,
    headers: { 'X-Pieria-Request-Id': id }
  });
  return { response, requestId: id };
}
