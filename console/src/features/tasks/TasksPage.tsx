import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { errorMessage } from '../../api/client';
import { qk } from '../../api/queries';
import { cancelTask, listTasks, retryTask, type TaskSummary } from '../../api/tasks';
import { clock, duration, elapsed, integer } from '../../lib/format';
import { profileUrl } from '../../lib/routes';
import { Button, Segmented, cx } from '../../ui/controls';
import { Badge, Card, CardHeader, EmptyState, ErrorBanner, Kpi, Page, PageHeader, ProgressLane, SectionLabel, SkeletonRows, type Tone } from '../../ui/display';
import { DisabledHint, useToast } from '../../ui/overlay';
import { useProfile } from '../shell/context';
import styles from './tasks.module.css';

/** Plain-language description of each task kind the daemon runs. */
const KIND_DESCRIPTIONS: Record<string, string> = {
  'ingest-transcript': 'Extracting memories from a harness transcript.',
  ingest: 'Extracting memories from submitted messages.',
  code: 'Indexing source files for symbol search and the code graph.',
  onboard: 'Onboarding documents and sources into memories.',
  'onboard-graph': 'Adding the onboarded memories to the entity graph.',
  reminisce: 'Linking older memories into the entity graph.',
  'wiki-build': 'Writing wiki articles, one page at a time.'
};

/** A running task whose lanes have all yet to start is waiting, not working. */
export function isWaiting(task: TaskSummary): boolean {
  return task.status === 'RUNNING' && task.lanes.length > 0 && task.lanes.every(l => l.state === 'QUEUED' || l.state === 'WAITING');
}

function badgeFor(task: TaskSummary): { label: string; tone: Tone } {
  if (task.status === 'RUNNING') return isWaiting(task) ? { label: 'WAITING', tone: 'info' } : { label: 'RUNNING', tone: 'warn' };
  if (task.status === 'SUCCEEDED') return { label: 'SUCCEEDED', tone: 'ok' };
  if (task.status === 'FAILED') return { label: 'FAILED', tone: 'fail' };
  return { label: 'CANCELLED', tone: 'neutral' };
}

function useNow(active: boolean): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [active]);
  return now;
}

export function TasksPage() {
  const profile = useProfile();
  const [params, setParams] = useSearchParams();
  const scope = params.get('scope') === 'profile' ? profile : null;
  const outcome = params.get('outcome') ?? 'all';
  const [open, setOpen] = useState<string | null>(null);

  const tasks = useQuery({
    queryKey: qk.tasks(scope),
    queryFn: ({ signal }) => listTasks(scope, signal),
    refetchInterval: query => (query.state.data?.some(t => t.status === 'RUNNING') ? 2_000 : 15_000)
  });
  const all = tasks.data ?? [];
  const active = all.filter(t => t.status === 'RUNNING');
  const finished = all.filter(t => t.status !== 'RUNNING')
    .sort((a, b) => (b.finishedAtEpochMs ?? 0) - (a.finishedAtEpochMs ?? 0));
  const recent = finished.filter(t => outcome === 'all' || (outcome === 'failed' ? t.status === 'FAILED' : t.status === 'SUCCEEDED'));
  const running = active.filter(t => !isWaiting(t)).length;
  const waiting = active.length - running;
  const failed = finished.filter(t => t.status === 'FAILED').length;
  const succeeded = finished.filter(t => t.status === 'SUCCEEDED').length;
  const now = useNow(active.length > 0);
  const set = (key: string, value: string | null) => setParams(p => {
    const next = new URLSearchParams(p);
    if (value) next.set(key, value); else next.delete(key);
    return next;
  }, { replace: true });

  return (
    <Page>
      <PageHeader crumb="Operations" title="Background tasks"
        actions={(
          <>
            <Segmented label="Scope" value={scope ? 'profile' : 'all'} onSurface
              options={[{ value: 'all', label: 'All profiles' }, { value: 'profile', label: `${profile} only` }]}
              onChange={v => set('scope', v === 'profile' ? 'profile' : null)} />
            <span className={styles.poll}>
              <span aria-hidden="true" className={cx(styles.pollDot, active.length > 0 && styles.pollLive)} />
              {active.length > 0 ? 'Live · polling every 2 s' : 'Idle · polling every 15 s'}
            </span>
          </>
        )} />

      <section aria-label="Summary" className={styles.kpis}>
        <Kpi label="Running" value={integer(running)} valueColor={running ? 'var(--accent)' : undefined} />
        <Kpi label="Waiting to start" value={integer(waiting)} valueColor={waiting ? 'var(--info-fg)' : undefined}
          note="started, all lanes queued" />
        <Kpi label="Failed · last 10 min" value={integer(failed)} valueColor={failed ? 'var(--fail-fg)' : undefined} />
        <Kpi label="Succeeded · last 10 min" value={integer(succeeded)} valueColor={succeeded ? 'var(--ok-fg)' : undefined} />
      </section>

      {tasks.isError && <ErrorBanner error={tasks.error} />}
      {tasks.isPending && <SkeletonRows rows={3} height={120} />}

      {tasks.isSuccess && active.length === 0 && (
        <EmptyState title="Nothing running"
          actions={<Link to={profileUrl(profile, 'activity')}>See request history in Activity →</Link>}>
          Ingests, code indexing, onboarding and wiki builds show up here while they work, then stay for ten minutes
          after they finish. Polling slows to every 15 s while idle.
        </EmptyState>
      )}

      {active.length > 0 && (
        <section aria-labelledby="run-h" className={styles.stack}>
          <SectionLabel as="h2" id="run-h">Running &amp; waiting</SectionLabel>
          {active.map(task => <ActiveTask key={task.id} task={task} now={now} />)}
        </section>
      )}

      <Card flush aria-labelledby="rec-h">
        <CardHeader id="rec-h" title="Recent" sub="finished in the last ten minutes; older runs are in Activity"
          actions={(
            <Segmented label="Outcome" value={outcome} onChange={v => set('outcome', v === 'all' ? null : v)}
              options={[{ value: 'all', label: 'All' }, { value: 'ok', label: 'Succeeded' }, { value: 'failed', label: 'Failed' }]} />
          )} />
        <div className={styles.scroll}>
          <div className={styles.table}>
            <div className={cx(styles.grid, styles.head)}>
              <span /><span>Task</span><span>Profile</span><span>Outcome</span><span>Finished</span><span>Took</span><span>Result</span>
            </div>
            {tasks.isSuccess && recent.length === 0 && <p className={styles.none}>No finished tasks{outcome !== 'all' ? ' with this outcome' : ''} in the last ten minutes.</p>}
            {recent.map(task => (
              <RecentTask key={task.id} task={task} open={open === task.id} onToggle={() => setOpen(open === task.id ? null : task.id)} />
            ))}
          </div>
        </div>
      </Card>
    </Page>
  );
}

function ActiveTask({ task, now }: { task: TaskSummary; now: number }) {
  const client = useQueryClient();
  const toast = useToast();
  const cancel = useMutation({
    mutationFn: () => cancelTask(task.id),
    onSuccess: () => { toast.show('Cancellation requested', 'The task stops at its next checkpoint.'); client.invalidateQueries({ queryKey: ['tasks'] }); },
    onError: e => toast.show('Could not cancel', errorMessage(e), 'error')
  });
  const badge = badgeFor(task);
  const waiting = isWaiting(task);
  return (
    <article className={cx(styles.card, !waiting && styles.cardRunning)}>
      <div className={styles.cardHead}>
        <span className={styles.kind}>{task.kind}</span>
        <Link to={profileUrl(task.profile, 'tasks')} className={styles.profile}>{task.profile}</Link>
        <Badge tone={badge.tone}>{badge.label}</Badge>
        <span className={styles.grow} />
        <span className="mono">{waiting ? 'waiting ' : ''}{elapsed(now - task.startedAtEpochMs)}</span>
        <Button size="sm" disabled={cancel.isPending} onClick={() => cancel.mutate()}>Cancel</Button>
      </div>
      <span className={styles.desc}>{KIND_DESCRIPTIONS[task.kind] ?? `Running ${task.kind}.`}</span>
      {task.lanes.length > 0 && (
        <div className={styles.lanes}>
          {task.lanes.map(lane => <ProgressLane key={lane.name} name={lane.name} phase={lane.phase} done={lane.done} total={lane.total} state={lane.state} />)}
        </div>
      )}
    </article>
  );
}

function RecentTask({ task, open, onToggle }: { task: TaskSummary; open: boolean; onToggle: () => void }) {
  const client = useQueryClient();
  const toast = useToast();
  const retry = useMutation({
    mutationFn: () => retryTask(task.id),
    onSuccess: () => { toast.show('Task restarted'); client.invalidateQueries({ queryKey: ['tasks'] }); },
    onError: e => toast.show('Could not retry', errorMessage(e), 'error')
  });
  const badge = badgeFor(task);
  const failed = task.status === 'FAILED';
  const result = failed ? task.errorMessage ?? task.errorKind : task.status === 'CANCELLED' ? 'Cancelled' : task.resultSummary ?? 'Done';
  const retryButton = <Button size="sm" variant="primary" disabled={!task.retryable || retry.isPending} onClick={() => retry.mutate()}>Retry</Button>;
  return (
    <div className={styles.rowWrap}>
      <button type="button" aria-expanded={open} onClick={onToggle} className={cx(styles.grid, styles.row, failed && styles.failed, open && styles.open)}>
        <span aria-hidden="true" className={cx(styles.caret, open && styles.caretOpen)}>›</span>
        <span className="mono">{task.kind}</span>
        <span className={cx('mono', styles.profileCell)}>{task.profile}</span>
        <span><Badge tone={badge.tone}>{badge.label}</Badge></span>
        <span className="mono">{task.finishedAtEpochMs ? clock(task.finishedAtEpochMs, true) : '—'}</span>
        <span className="mono">{duration(task.durationMs ?? null)}</span>
        <span className={cx(styles.result, failed && styles.resultFail)}>{result}</span>
      </button>
      {open && (
        <div className={styles.detail}>
          <div className={styles.detailMain}>
            {task.lanes.map(lane => <ProgressLane key={lane.name} name={lane.name} done={lane.done} total={lane.total} state={lane.state} />)}
            {failed && <pre className={styles.error}>{[task.errorKind && `errorKind: ${task.errorKind}`, task.errorMessage && `errorMessage: ${task.errorMessage}`].filter(Boolean).join('\n')}</pre>}
          </div>
          <div className={styles.detailActions}>
            {(failed || task.status === 'CANCELLED') && (task.retryable ? retryButton
              : <DisabledHint reason="This task's input is no longer held, so it cannot be retried here; re-run it from the client.">{retryButton}</DisabledHint>)}
            <Link className={styles.linkButton} to={`${profileUrl(task.profile, 'activity')}?taskId=${encodeURIComponent(task.id)}&range=all`}>Open in Activity</Link>
          </div>
        </div>
      )}
    </div>
  );
}
