import type { AuditEventSummary } from '../../api/audit';
import type { Tone } from '../../ui/display';

/** The badge the canvas shows: HTTP status for requests, success/failure for task completions. */
export function statusBadge(event: Pick<AuditEventSummary, 'httpStatus' | 'outcome' | 'eventType'>): { label: string; tone: Tone } {
  if (event.outcome === 'failure') return { label: event.eventType === 'task_terminal' ? 'failure' : String(event.httpStatus ?? 'failure'), tone: 'fail' };
  if (event.outcome === 'cancelled') return { label: 'cancelled', tone: 'neutral' };
  if (event.eventType === 'task_terminal') return { label: 'success', tone: 'ok' };
  if (event.httpStatus === 202) return { label: '202', tone: 'info' };
  return { label: String(event.httpStatus ?? event.outcome), tone: 'ok' };
}

export function clientLabel(event: Pick<AuditEventSummary, 'client' | 'harness'>): string {
  return [event.client, event.harness].filter(Boolean).join(' · ') || 'unknown';
}
