import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { listAudit } from '../../api/audit';
import { errorMessage } from '../../api/client';
import { listClusters } from '../../api/clusters';
import { exportProfile } from '../../api/profiles';
import { qk, useMemorySearch, useStats, useStatus } from '../../api/queries';
import { listTasks, retryTask } from '../../api/tasks';
import { buildWiki, wikiDirectory, wikiStatus } from '../../api/wiki';
import { clock, compact, dateShort, dayTime, duration, integer, plural, relativeTime } from '../../lib/format';
import { profileUrl } from '../../lib/routes';
import { Button, LinkButton } from '../../ui/controls';
import { Badge, Card, CardHeader, Dot, ErrorBanner, Kpi, Page, PageHeader, Skeleton, TYPE_COLORS } from '../../ui/display';
import { Icon } from '../../ui/icons';
import { DisabledHint, useToast } from '../../ui/overlay';
import { clientLabel, statusBadge } from '../activity/labels';
import { useProfile } from '../shell/context';
import styles from './overview.module.css';

const MIX_TYPES = ['fact', 'event', 'instruction', 'task'] as const;
const DAY = 86_400_000;

export function OverviewPage() {
  const profile = useProfile();
  const toast = useToast();
  const stats = useStats(profile);
  const status = useStatus();
  const recent = useMemorySearch(profile, { limit: 40, groupToolCalls: true });
  const activity = useQuery({
    queryKey: qk.audit(profile, { limit: 6 }),
    queryFn: ({ signal }) => listAudit(profile, { limit: 6 }, signal),
    refetchInterval: 30_000
  });

  const s = stats.data;
  const learned = (recent.data?.items ?? [])
    .flatMap(item => (item.memory && (item.memory.type === 'fact' || item.memory.type === 'instruction') ? [item.memory] : []))
    .slice(0, 5);
  const mixTotal = s ? MIX_TYPES.reduce((n, t) => n + (s.byType[t] ?? 0), 0) : 0;
  const models = status.data ? [status.data.extractionModel, status.data.synthesisModel, status.data.embeddingModel].filter(Boolean).join(' · ') : null;
  const windows = s && s.impact.contextWindowTokens > 0 ? s.impact.tokensSaved / s.impact.contextWindowTokens : null;

  const doExport = () => exportProfile(profile).catch(e => toast.show('Export failed', errorMessage(e), 'error'));

  return (
    <Page>
      <PageHeader large crumb={<>Profiles / <strong>{profile}</strong></>} title={profile}
        meta={(
          <div className={styles.meta}>
            {s && <span>Created {dateShort(s.createdAt)}</span>}
            {s && <span>{plural(s.sessions, 'session')}</span>}
            {s?.lastMemoryAt && <span>Last ingest {relativeTime(s.lastMemoryAt)}</span>}
            {models && <span className="mono">{models}</span>}
          </div>
        )}
        actions={(
          <>
            <Button size="lg" icon="download" onClick={doExport}>Export NDJSON</Button>
            <LinkButton size="lg" to={profileUrl(profile, 'recall')}>Ask this profile</LinkButton>
            <LinkButton size="lg" variant="primary" to={profileUrl(profile, 'memories/new')}>+ Add memory</LinkButton>
          </>
        )} />

      {stats.isError && <ErrorBanner error={stats.error} />}

      <section aria-label="Key figures" className={styles.kpis}>
        {s ? (
          <>
            <Kpi label="Active memories" value={integer(s.totalActive)} note={`+ ${integer(s.superseded)} superseded`} />
            <Kpi label="Recalls served" value={integer(s.impact.recalls)} note="all clients" />
            <Kpi label="Est. tokens saved" value={compact(s.impact.tokensSaved)} note="vs. re-reading source" />
            <Kpi label="Tokens ingested" value={compact(s.impact.tokensIngested)} note={`${compact(s.impact.tokensStored)} stored`} />
            <Kpi label="Vector backlog" value={integer(s.vectorizationBacklog ?? 0)}
              valueColor={(s.vectorizationBacklog ?? 0) === 0 ? 'var(--ok)' : 'var(--accent)'}
              note={(s.vectorizationBacklog ?? 0) === 0 ? 'outbox drained' : 'waiting to be embedded'} />
          </>
        ) : Array.from({ length: 5 }, (_, i) => <Skeleton key={i} height={96} />)}
      </section>

      <div className={styles.pair}>
        <Card>
          <CardHeader title="Memory mix" sub="active, by type" actions={<Link to={profileUrl(profile, 'memories')} className={styles.more}>Browse →</Link>} />
          {s && (
            <>
              <div role="img" className={styles.mixBar}
                aria-label={MIX_TYPES.map(t => `${t} ${mixTotal ? Math.round(((s.byType[t] ?? 0) / mixTotal) * 100) : 0}%`).join(', ')}>
                {MIX_TYPES.map(t => (s.byType[t] ?? 0) > 0 && (
                  <span key={t} style={{ width: `${((s.byType[t] ?? 0) / mixTotal) * 100}%`, background: TYPE_COLORS[t] }} />
                ))}
              </div>
              <div className={styles.legend}>
                {MIX_TYPES.map(t => (
                  <div key={t}><Dot color={TYPE_COLORS[t]} /><span>{t}</span><span className="mono">{integer(s.byType[t] ?? 0)}</span></div>
                ))}
              </div>
              <div className={styles.saved}>
                <span className={styles.label}>Est. tokens saved</span>
                <span className={styles.savedValue}>{integer(s.impact.tokensSaved)}</span>
                <span className={styles.note}>
                  vs. re-reading the source{windows !== null && <> · ≈ {windows.toFixed(1)} × {compact(s.impact.contextWindowTokens)} context windows</>}
                </span>
              </div>
              <div className={styles.spend}>
                <span className={styles.label}>Inference spend</span>
                <div className={styles.tableWrap}>
                  <table>
                    <thead>
                      <tr><th scope="col">tier</th><th scope="col">calls</th><th scope="col">prompt</th><th scope="col">completion</th>{s.spend.costAvailable && <th scope="col">cost</th>}</tr>
                    </thead>
                    <tbody>
                      {s.spend.tiers.map(tier => (
                        <tr key={tier.tier}>
                          <td>{tier.tier.toLowerCase()}</td><td>{integer(tier.calls)}</td><td>{integer(tier.promptTokens)}</td>
                          <td>{integer(tier.completionTokens)}</td>{s.spend.costAvailable && <td>${tier.costUsd.toFixed(2)}</td>}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </>
          )}
          {!s && <Skeleton height={220} />}
        </Card>

        <NeedsAttention profile={profile} factCount={s?.byType.fact ?? 0} memoryCount={s?.totalActive ?? 0} />
      </div>

      <div className={styles.pair}>
        <Card className={styles.listCard}>
          <CardHeader title="Recently learned" sub="facts & instructions, newest first"
            actions={<Link to={profileUrl(profile, 'memories')} className={styles.more}>All →</Link>} />
          {recent.isPending && <Skeleton height={200} />}
          {recent.isSuccess && learned.length === 0 && <p className={styles.note}>Nothing learned yet.</p>}
          {learned.map(m => (
            <Link key={m.id} to={profileUrl(profile, `memories/${encodeURIComponent(m.id)}`)} className={styles.learned}>
              <span className={styles.learnedText}>{m.content}</span>
              <span className={styles.learnedMeta}>
                {m.topicKey && <span className={styles.key}>{m.topicKey}</span>}
                <span>{relativeTime(m.createdAt)}</span>
              </span>
            </Link>
          ))}
        </Card>

        <Card className={styles.listCard}>
          <CardHeader title="Recent activity" sub="this profile"
            actions={<Link to={profileUrl(profile, 'activity')} className={styles.more}>Open log →</Link>} />
          {activity.isPending && <Skeleton height={200} />}
          {activity.isError && <ErrorBanner error={activity.error} />}
          {activity.isSuccess && activity.data.events.length === 0 && <p className={styles.note}>No requests recorded yet.</p>}
          {activity.data?.events.map(event => {
            const badge = statusBadge(event);
            return (
              <Link key={event.id} to={profileUrl(profile, `activity/${encodeURIComponent(event.id)}`)} className={styles.activityRow}>
                <span className="mono" title={dayTime(event.completedAt)}>{clock(event.completedAt)}</span>
                <span className={styles.activityOp}>
                  <span className="mono">{event.operation}</span>
                  <span>{clientLabel(event)}</span>
                </span>
                <Badge tone={badge.tone}>{badge.label}</Badge>
                <span className={styles.activityDur}>{duration(event.durationMs)}</span>
              </Link>
            );
          })}
        </Card>
      </div>
    </Page>
  );
}

function NeedsAttention({ profile, factCount, memoryCount }: { profile: string; factCount: number; memoryCount: number }) {
  const client = useQueryClient();
  const toast = useToast();
  const since = new Date(Date.now() - DAY).toISOString();
  const failures = useQuery({
    queryKey: qk.audit(profile, { outcome: 'failure', from: 'day' }),
    queryFn: ({ signal }) => listAudit(profile, { outcome: 'failure', from: since, limit: 3 }, signal),
    refetchInterval: 60_000
  });
  const tasks = useQuery({ queryKey: qk.tasks(profile), queryFn: ({ signal }) => listTasks(profile, signal), refetchInterval: 15_000 });
  const wiki = useQuery({
    queryKey: qk.wikiStatus(profile),
    queryFn: async ({ signal }) => {
      try {
        return await wikiStatus(profile, signal);
      } catch {
        const dir = await wikiDirectory(profile, null, null, signal);
        return { state: dir.state, projectionLag: dir.state.corpusWatermark - dir.state.projectionWatermark, unassignedEvidence: 0, pagesTotal: 0,
          articlesReady: 0, articlesAbsent: 0, articlesStale: 0, jobsQueued: 0, jobsGenerating: 0, jobsFailed: 0, fallback: true };
      }
    },
    retry: false
  });
  const clusters = useQuery({ queryKey: qk.clusters(profile, 'any'), queryFn: ({ signal }) => listClusters(profile, undefined, signal), retry: false });

  const retry = useMutation({
    mutationFn: (taskId: string) => retryTask(taskId),
    onSuccess: () => {
      toast.show('Task restarted', 'Follow it in Background tasks.');
      client.invalidateQueries({ queryKey: ['tasks'] });
    },
    onError: e => toast.show('Could not retry', errorMessage(e), 'error')
  });
  const build = useMutation({
    mutationFn: () => buildWiki(profile, 'missing'),
    onSuccess: () => {
      toast.show('Wiki build started', 'Articles are written one at a time; progress is in Background tasks.');
      client.invalidateQueries({ queryKey: ['tasks'] });
    },
    onError: e => toast.show('Could not start the wiki build', errorMessage(e), 'error')
  });

  const failed = failures.data?.events ?? [];
  const w = wiki.data;
  const wikiNeeds = w && w.state.enabled && (!w.state.initialized || w.articlesReady === 0 || w.articlesStale > 0);
  const noClusters = clusters.isSuccess && clusters.data.length === 0 && memoryCount > 0;
  const count = failed.length + (wikiNeeds ? 1 : 0) + (noClusters ? 1 : 0);
  const pct = w && w.state.corpusWatermark > 0 ? Math.round((w.state.projectionWatermark / w.state.corpusWatermark) * 100) : 0;

  return (
    <Card>
      <CardHeader title="Needs attention" sub={failures.isPending ? 'checking…' : plural(count, 'item')} />
      {!failures.isPending && count === 0 && <p className={styles.note}>All clear: no failures in the last day, and the wiki and clusters are up to date.</p>}

      {failed.map(event => {
        const task = event.taskId ? tasks.data?.find(t => t.id === event.taskId) : undefined;
        const retryButton = (
          <Button size="sm" variant="danger" disabled={!task?.retryable || retry.isPending} onClick={() => task && retry.mutate(task.id)}>Retry</Button>
        );
        return (
          <div key={event.id} className={`${styles.alert} ${styles.alertFail}`}>
            <Icon name="alert" size={18} strokeWidth={2} className={styles.alertIcon} />
            <div className={styles.alertBody}>
              <strong>{event.eventType === 'task_terminal' ? 'Background task failed' : `${event.operation} failed`}</strong>
              <span>{event.operation} · {clientLabel(event)} · {dayTime(event.completedAt)}</span>
              {event.errorMessage && <code>{event.errorMessage}</code>}
            </div>
            <div className={styles.alertActions}>
              <Link to={profileUrl(profile, `activity/${encodeURIComponent(event.id)}`)}>Inspect →</Link>
              {event.taskId && (task?.retryable ? retryButton
                : <DisabledHint reason="The task's input is only kept for ten minutes after it ends; re-run it from the client.">{retryButton}</DisabledHint>)}
            </div>
          </div>
        );
      })}

      {wikiNeeds && w && (
        <div className={`${styles.alert} ${styles.alertWarn}`}>
          <Icon name="book" size={18} strokeWidth={2} className={styles.alertIcon} />
          <div className={styles.alertBody}>
            <strong>{w.articlesReady === 0 ? 'Wiki has not been built' : `${plural(w.articlesStale, 'wiki article')} out of date`}</strong>
            <span>
              {w.unassignedEvidence > 0 && <>{plural(w.unassignedEvidence, 'piece')} of evidence {w.unassignedEvidence === 1 ? 'is' : 'are'} unassigned. </>}
              Projection is at revision {integer(w.state.projectionWatermark)} of {integer(w.state.corpusWatermark)} ({pct}%).
            </span>
          </div>
          {'fallback' in w
            ? <DisabledHint reason="This daemon cannot build the whole wiki yet; open a page to write its article."><Button size="sm" variant="accent" disabled>Build wiki</Button></DisabledHint>
            : <Button size="sm" variant="accent" disabled={build.isPending} onClick={() => build.mutate()}>Build wiki</Button>}
        </div>
      )}

      {noClusters && (
        <div className={styles.alert}>
          <Icon name="clusters" size={18} strokeWidth={2} className={styles.alertIcon} />
          <div className={styles.alertBody}>
            <strong>No knowledge clusters yet</strong>
            <span>A clustering run groups the {integer(factCount)} facts into reviewable claims, shown as a layer in the Entity graph.</span>
          </div>
          <LinkButton size="sm" to={`${profileUrl(profile, 'graph')}?view=clusters`}>Open in graph</LinkButton>
        </div>
      )}
    </Card>
  );
}
