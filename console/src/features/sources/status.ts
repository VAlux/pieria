import type { Source, SourceRun } from '../../api/sources';
import { integer, relativeTime } from '../../lib/format';
import type { Tone } from '../../ui/display';

/** The one word a source's row leads with. `signIn` says whether its harvester signs in with a code. */
export function statusLabel(source: Source, signIn: boolean): { label: string; tone: Tone } {
  if (source.running) return { label: 'Running', tone: 'warn' };
  if (source.status === 'needs_auth') return { label: signIn ? 'Needs sign-in' : 'Credentials refused', tone: 'fail' };
  if (source.status === 'paused') return { label: 'Paused', tone: 'neutral' };
  if (source.lastRun?.status === 'failed') return { label: 'Failing', tone: 'fail' };
  return { label: 'Active', tone: 'ok' };
}

/** Whether the source is waiting on the user: a sign-in, a failure, or deletions held back. */
export function needsAttention(source: Source): boolean {
  return source.status === 'needs_auth' || source.lastRun?.status === 'failed' || source.sweepBlockedCount !== null;
}

export function runSummary(run: SourceRun | null): string {
  if (!run) return 'Never run';
  const parts = [`${run.dryRun ? 'Dry run' : 'Run'} ${run.status}`, `${integer(run.itemsChanged)} changed of ${integer(run.itemsSeen)}`];
  if (run.itemsDeleted > 0) parts.push(`${integer(run.itemsDeleted)} deleted`);
  parts.push(relativeTime(run.finishedAt ?? run.startedAt));
  return parts.join(' · ');
}
