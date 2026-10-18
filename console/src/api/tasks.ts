import { query, request } from './client';

export type TaskStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export interface TaskLane {
  name: string;
  state: 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED' | string;
  phase: string | null;
  done: number;
  total: number;
  phaseStartedAtEpochMs: number;
}

export interface TaskSummary {
  id: string;
  kind: string;
  profile: string;
  status: TaskStatus;
  lanes: TaskLane[];
  startedAtEpochMs: number;
  errorKind: string | null;
  errorMessage: string | null;
  finishedAtEpochMs?: number;
  durationMs?: number;
  resultSummary?: string | null;
  retryable?: boolean;
}

export async function listTasks(profile?: string | null, signal?: AbortSignal): Promise<TaskSummary[]> {
  const result = await request<{ tasks: TaskSummary[] }>(`/v1/tasks${query({ profile: profile ?? undefined })}`, { signal });
  return result?.tasks ?? [];
}

export function cancelTask(id: string): Promise<unknown> {
  return request(`/v1/tasks/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function retryTask(id: string): Promise<{ taskId: string }> {
  return request<{ taskId: string }>(`/v1/tasks/${encodeURIComponent(id)}/retry`, { method: 'POST', json: {} });
}
