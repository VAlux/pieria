import { useMemo, useState } from 'react';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { auditEvent, auditFacets, listAudit, type AuditEventDetail, type AuditEventSummary, type AuditQuery } from '../../api/audit';
import { errorMessage } from '../../api/client';
import { qk } from '../../api/queries';
import { listTasks, retryTask } from '../../api/tasks';
import { clock, dayTime, duration, integer } from '../../lib/format';
import { profileUrl } from '../../lib/routes';
import { Button, Checkbox, SearchField, Segmented, cx } from '../../ui/controls';
import { Badge, Card, EmptyInline, ErrorBanner, Page, PageHeader, SkeletonRows } from '../../ui/display';
import { DisabledHint, MenuItem, Popover, usePopover, useToast } from '../../ui/overlay';
import { clientLabel, statusBadge } from './labels';
import { useProfile } from '../shell/context';
import styles from './activity.module.css';

const RANGES = [
  { id: '1h', label: 'Last hour', ms: 3_600_000 },
  { id: '24h', label: 'Last 24 h', ms: 86_400_000 },
  { id: '7d', label: 'Last 7 days', ms: 7 * 86_400_000 },
  { id: '30d', label: 'Last 30 days', ms: 30 * 86_400_000 },
  { id: 'all', label: 'All time', ms: 0 }
];

type Outcome = 'all' | 'success' | 'failure';

function useFilters() {
  const [params, setParams] = useSearchParams();
  const get = (key: string) => params.get(key) ?? '';
  const set = (changes: Record<string, string | null>) => setParams(p => {
    const next = new URLSearchParams(p);
    Object.entries(changes).forEach(([k, v]) => (v ? next.set(k, v) : next.delete(k)));
    return next;
  }, { replace: true });
  return { get, set, params };
}

export function ActivityPage() {
  const profile = useProfile();
  const navigate = useNavigate();
  const { eventId } = useParams();
  const filters = useFilters();
  const [live, setLive] = useState(true);
  const [text, setText] = useState(filters.get('q'));

  const scope = filters.get('scope') === 'all' ? null : profile;
  const outcome = (filters.get('outcome') || 'all') as Outcome;
  const range = RANGES.find(r => r.id === (filters.get('range') || '24h')) ?? RANGES[1];
  // Rounded to the minute so the query key stays stable between renders.
  const from = range.ms ? new Date(Math.floor((Date.now() - range.ms) / 60_000) * 60_000).toISOString() : undefined;
  const base: AuditQuery = {
    q: filters.get('q') || undefined,
    operation: filters.get('operation') || undefined,
    client: filters.get('client') || undefined,
    harness: filters.get('harness') || undefined,
    requestId: filters.get('requestId') || undefined,
    from
  };
  const query: AuditQuery = { ...base, outcome: outcome === 'all' ? undefined : outcome, limit: 50 };

  const feed = useInfiniteQuery({
    queryKey: qk.audit(scope, query),
    queryFn: ({ pageParam, signal }) => listAudit(scope, { ...query, cursor: pageParam }, signal),
    initialPageParam: null as string | null,
    getNextPageParam: last => last.nextCursor,
    refetchInterval: live ? 5_000 : false
  });
  const facets = useQuery({
    queryKey: qk.auditFacets(scope, base),
    queryFn: ({ signal }) => auditFacets({ ...base, profile: scope ?? undefined, from: from ?? '1970-01-01T00:00:00Z' }, signal),
    refetchInterval: live ? 15_000 : false,
    retry: false
  });

  const events = useMemo(() => feed.data?.pages.flatMap(p => p.events) ?? [], [feed.data]);
  const failures = facets.data?.facets.outcome?.find(f => f.value === 'failure')?.count;
  const maxMs = Math.max(1000, ...events.map(e => e.durationMs));
  const pinned = eventId && !events.some(e => e.id === eventId) ? eventId : null;
  const toggle = (id: string) => navigate({
    pathname: profileUrl(profile, eventId === id ? 'activity' : `activity/${encodeURIComponent(id)}`),
    search: filters.params.toString()
  }, { replace: true });

  return (
    <Page>
      <PageHeader crumb={<>{profile} / Operations</>} title="Activity"
        actions={(
          <>
            <Segmented label="Scope" value={scope ? 'profile' : 'all'} onSurface
              options={[{ value: 'profile', label: `${profile} only` }, { value: 'all', label: 'All profiles' }]}
              onChange={v => filters.set({ scope: v === 'all' ? 'all' : null })} />
            <Checkbox checked={live} onChange={setLive}>Live tail</Checkbox>
          </>
        )} />

      <Card className={styles.filters}>
        <SearchField label="Search activity" value={text} onChange={v => { setText(v); filters.set({ q: v.trim() || null }); }}
          placeholder="Search requests, responses, ids, errors…" className={styles.search} />
        <Segmented label="Outcome" value={outcome} onChange={v => filters.set({ outcome: v === 'all' ? null : v })}
          options={[
            { value: 'all', label: 'All' },
            { value: 'success', label: 'Succeeded' },
            { value: 'failure', label: failures ? `Failed · ${integer(failures)}` : 'Failed' }
          ]} />
        <FacetFilter label="Operation" values={facets.data?.facets.operation} value={filters.get('operation')} onChange={v => filters.set({ operation: v })} />
        <FacetFilter label="Client" values={facets.data?.facets.client} value={filters.get('client')} onChange={v => filters.set({ client: v })} />
        <FacetFilter label="Harness" values={facets.data?.facets.harness} value={filters.get('harness')} onChange={v => filters.set({ harness: v })} />
        <RangeFilter value={range.id} onChange={v => filters.set({ range: v === '24h' ? null : v })} />
        {filters.get('requestId') && (
          <Button size="sm" onClick={() => filters.set({ requestId: null })}>Request {filters.get('requestId').slice(0, 18)}… ×</Button>
        )}
      </Card>

      {feed.isError && <ErrorBanner error={feed.error} />}

      <Card flush aria-label="Requests">
        <div className={styles.scroll}>
          <div className={styles.table}>
            <div className={cx(styles.grid, styles.head)}>
              <span /><span>Time</span><span>Operation</span><span>Client</span><span>Outcome</span><span>Duration</span>
            </div>
            {pinned && <Row key={`pinned-${pinned}`} id={pinned} scope={scope} open onToggle={() => toggle(pinned)} maxMs={maxMs} showProfile={!scope} />}
            {feed.isPending && <SkeletonRows rows={8} height={40} />}
            {feed.isSuccess && events.length === 0 && (
              <EmptyInline title="No requests match these filters." detail="Widen the time range, clear the search, or switch to all profiles." />
            )}
            {events.map(event => (
              <Row key={event.id} id={event.id} summary={event} scope={scope} open={event.id === eventId}
                onToggle={() => toggle(event.id)} maxMs={maxMs} showProfile={!scope} />
            ))}
          </div>
        </div>
        <div className={styles.footer}>
          <span>Showing {integer(events.length)} requests · newest first{live ? ' · refreshing every 5 s' : ''}</span>
          <span className={styles.grow} />
          <Button size="sm" disabled={!feed.hasNextPage || feed.isFetchingNextPage} onClick={() => feed.fetchNextPage()}>
            {feed.isFetchingNextPage ? 'Loading…' : feed.hasNextPage ? 'Load older' : 'No older requests'}
          </Button>
        </div>
      </Card>
    </Page>
  );
}

interface RowProps {
  id: string;
  summary?: AuditEventSummary;
  scope: string | null;
  open: boolean;
  onToggle: () => void;
  maxMs: number;
  showProfile: boolean;
}

function Row({ id, summary, scope, open, onToggle, maxMs, showProfile }: RowProps) {
  const detail = useQuery({
    queryKey: qk.auditEvent(id),
    queryFn: ({ signal }) => auditEvent(null, id, signal),
    enabled: open || !summary,
    staleTime: Infinity
  });
  const event: AuditEventSummary | AuditEventDetail | undefined = summary ?? detail.data;
  if (!event) return detail.isError ? <ErrorBanner error={detail.error} /> : null;
  const failed = event.outcome === 'failure';
  const badge = statusBadge(event);
  const pct = Math.max(2, Math.round((event.durationMs / maxMs) * 100));
  const barTone = failed ? styles.barFail : event.durationMs > 30_000 ? styles.barSlow : '';
  return (
    <div className={styles.rowWrap}>
      <button type="button" aria-expanded={open} onClick={onToggle} className={cx(styles.grid, styles.row, failed && styles.failed, open && styles.open)}>
        <span aria-hidden="true" className={cx(styles.caret, open && styles.caretOpen)}>›</span>
        <span className="mono" title={dayTime(event.completedAt)}>{dayTime(event.completedAt).slice(0, 6)} {clock(event.completedAt, true)}</span>
        <span className={styles.op}>
          <span className="mono">{event.operation}{showProfile && event.profile ? <span className={styles.profile}> · {event.profile}</span> : null}</span>
          <span className={styles.summary}>{event.errorMessage || event.responsePreview || event.resourceId || ' '}</span>
        </span>
        <span className={styles.client}>{clientLabel(event)}</span>
        <span><Badge tone={badge.tone}>{badge.label}</Badge></span>
        <span className={styles.duration}>
          <span className={styles.track}><span className={barTone} style={{ width: `${pct}%` }} /></span>
          <span className="mono">{duration(event.durationMs)}</span>
        </span>
      </button>
      {open && <Detail event={event} detail={detail.data} loading={detail.isPending} error={detail.error} scope={scope} />}
    </div>
  );
}

function Detail({ event, detail, loading, error }: { event: AuditEventSummary; detail?: AuditEventDetail; loading: boolean; error: unknown; scope: string | null }) {
  const toast = useToast();
  const client = useQueryClient();
  const failed = event.outcome === 'failure';
  const tasks = useQuery({ queryKey: qk.tasks(event.profile ?? null), queryFn: ({ signal }) => listTasks(event.profile, signal), enabled: Boolean(failed && event.taskId) });
  const task = event.taskId ? tasks.data?.find(t => t.id === event.taskId) : undefined;
  const retry = useMutation({
    mutationFn: () => retryTask(event.taskId!),
    onSuccess: () => { toast.show('Task restarted', 'Follow it in Background tasks.'); client.invalidateQueries({ queryKey: ['tasks'] }); },
    onError: e => toast.show('Could not retry', errorMessage(e), 'error')
  });
  if (loading) return <div className={styles.detail}><SkeletonRows rows={2} height={60} /></div>;
  if (error || !detail) return <div className={styles.detail}><ErrorBanner error={error ?? 'Details unavailable'} /></div>;

  const request = [
    detail.method && detail.path ? `${detail.method} ${detail.path}${detail.queryString ? `?${detail.queryString}` : ''}` : detail.operation,
    detail.profile ? `profile: ${detail.profile}` : null,
    detail.harness ? `harness: ${detail.harness}` : null,
    detail.sessionId ? `session: ${detail.sessionId}` : null,
    detail.taskId ? `task: ${detail.taskId}` : null,
    detail.requestId ? `request id: ${detail.requestId}` : null,
    detail.requestBody ? `\n${pretty(detail.requestBody)}` : null,
    detail.requestTruncated ? `\n… truncated (${integer(detail.requestBytes)} bytes)` : null
  ].filter(Boolean).join('\n');
  const response = [
    detail.httpStatus ? `status: ${detail.httpStatus}` : `outcome: ${detail.outcome}`,
    detail.errorKind ? `error: ${detail.errorKind}` : null,
    detail.errorMessage ? `message: ${detail.errorMessage}` : null,
    detail.responseBody ? `\n${pretty(detail.responseBody)}` : null,
    detail.responseTruncated ? `\n… truncated (${integer(detail.responseBytes)} bytes)` : null
  ].filter(Boolean).join('\n');
  const curl = detail.method && detail.path ? toCurl(detail) : null;
  const retryButton = <Button size="sm" variant="danger" disabled={!task?.retryable || retry.isPending} onClick={() => retry.mutate()}>Retry task</Button>;

  return (
    <div className={styles.detail}>
      <div className={styles.pane}>
        <span className={styles.label}>Request</span>
        <pre className={styles.pre}>{request}</pre>
      </div>
      <div className={styles.pane}>
        <span className={styles.label}>Response</span>
        <pre className={cx(styles.pre, failed && styles.preFail)}>{response}</pre>
        <div className={styles.actions}>
          {curl && <Button size="sm" icon="copy" onClick={() => navigator.clipboard?.writeText(curl).then(() => toast.show('cURL command copied'))}>Copy as cURL</Button>}
          {failed && event.taskId && (task?.retryable ? retryButton
            : <DisabledHint reason="A task's input is kept for ten minutes after it ends and is lost on restart; re-run it from the client.">{retryButton}</DisabledHint>)}
          {event.profile && event.resourceId && event.operation.startsWith('memory.') && (
            <Link className={styles.link} to={profileUrl(event.profile, `memories/${encodeURIComponent(event.resourceId)}`)}>Open memory →</Link>
          )}
        </div>
      </div>
    </div>
  );
}

function pretty(body: string): string {
  try {
    return JSON.stringify(JSON.parse(body), null, 2);
  } catch {
    return body;
  }
}

const shellQuote = (s: string) => `'${s.replace(/'/g, `'\\''`)}'`;

export function toCurl(detail: Pick<AuditEventDetail, 'method' | 'path' | 'queryString' | 'requestMediaType' | 'requestBody' | 'requestTruncated'>, origin = window.location.origin): string {
  const parts = ['curl', '-X', detail.method ?? 'GET', shellQuote(`${origin}${detail.path}${detail.queryString ? `?${detail.queryString}` : ''}`)];
  if (detail.requestMediaType) parts.push('-H', shellQuote(`Content-Type: ${detail.requestMediaType}`));
  if (detail.requestBody && !detail.requestTruncated) parts.push('--data-raw', shellQuote(detail.requestBody));
  return parts.join(' ');
}

function FacetFilter({ label, values, value, onChange }: { label: string; values?: { value: string; count: number }[]; value: string; onChange: (v: string | null) => void }) {
  const popover = usePopover();
  return (
    <>
      <Button ref={popover.anchor} variant="field" aria-haspopup="menu" aria-expanded={popover.open} onClick={popover.toggle}>
        {label}: {value || 'any'} ▾
      </Button>
      <Popover anchor={popover.anchor} open={popover.open} onClose={popover.close} label={`Filter by ${label.toLowerCase()}`}>
        <MenuItem checked={!value} onSelect={() => { onChange(null); popover.close(); }}>Any {label.toLowerCase()}</MenuItem>
        {(values ?? []).map(v => (
          <MenuItem key={v.value} checked={v.value === value} detail={integer(v.count)} onSelect={() => { onChange(v.value); popover.close(); }}>{v.value}</MenuItem>
        ))}
        {values && values.length === 0 && <MenuItem disabled onSelect={() => undefined}>Nothing recorded in this range</MenuItem>}
        {!values && <MenuItem disabled onSelect={() => undefined}>Loading…</MenuItem>}
      </Popover>
    </>
  );
}

function RangeFilter({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const popover = usePopover();
  const current = RANGES.find(r => r.id === value) ?? RANGES[1];
  return (
    <>
      <Button ref={popover.anchor} variant="field" aria-haspopup="menu" aria-expanded={popover.open} onClick={popover.toggle}>{current.label} ▾</Button>
      <Popover anchor={popover.anchor} open={popover.open} onClose={popover.close} label="Time range" align="end">
        {RANGES.map(r => <MenuItem key={r.id} checked={r.id === value} onSelect={() => { onChange(r.id); popover.close(); }}>{r.label}</MenuItem>)}
      </Popover>
    </>
  );
}
