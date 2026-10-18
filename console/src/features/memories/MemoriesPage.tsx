import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router';
import type { MemorySearchParams } from '../../api/memories';
import { useMemorySearch, useSessions } from '../../api/queries';
import { MEMORY_TYPES, type Memory, type MemoryListItem } from '../../api/types';
import { dayTime, integer, plural, relativeTime, shortId } from '../../lib/format';
import { useHotkey } from '../../lib/hotkeys';
import { profileUrl } from '../../lib/routes';
import { Button, Checkbox, Kbd, LinkButton, SearchField, Segmented, cx } from '../../ui/controls';
import { Card, EmptyInline, ErrorBanner, Page, PageHeader, SkeletonRows, TypeChip } from '../../ui/display';
import { MenuHeading, MenuItem, MenuSeparator, Popover, usePopover } from '../../ui/overlay';
import { AddMemoryDrawer } from '../add-memory/AddMemoryDrawer';
import { useProfile } from '../shell/context';
import { MemoryDetail } from './MemoryDetail';
import styles from './memories.module.css';

const PAGE_SIZE = 50;

type TypeFilter = 'all' | (typeof MEMORY_TYPES)[number];

function useDebounced<T>(value: T, delay = 250): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);
  return debounced;
}

export function MemoriesPage({ adding = false }: { adding?: boolean }) {
  const profile = useProfile();
  const navigate = useNavigate();
  const { memoryId } = useParams();
  const [params, setParams] = useSearchParams();
  const searchRef = useRef<HTMLInputElement>(null);

  const q = params.get('q') ?? '';
  const type = (params.get('type') ?? 'all') as TypeFilter;
  const session = params.get('session') ?? '';
  const sort = params.get('sort') === 'oldest' ? 'oldest' : 'newest';
  const grouped = params.get('grouped') !== '0';
  const superseded = params.get('superseded') === '1';

  const [text, setText] = useState(q);
  const debounced = useDebounced(text);
  const [cursors, setCursors] = useState<(string | null)[]>([null]);
  const page = cursors.length - 1;

  const update = (changes: Record<string, string | null>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value === null || value === '') next.delete(key);
      else next.set(key, value);
    }
    setParams(next, { replace: true });
    setCursors([null]);
  };

  useEffect(() => { if (debounced !== q) update({ q: debounced }); }, [debounced]);
  useEffect(() => { setText(q); }, [q]);

  const searchParams: MemorySearchParams = {
    q: q || undefined,
    type: type === 'all' ? undefined : type,
    session: session || undefined,
    sort,
    limit: PAGE_SIZE,
    cursor: cursors[page],
    includeSuperseded: superseded,
    groupToolCalls: grouped
  };
  const search = useMemorySearch(profile, searchParams);
  const items = search.data?.items ?? [];
  const memories = items.flatMap(item => (item.memory ? [item.memory] : []));
  const selectedId = memoryId ?? memories[0]?.id ?? null;
  const selected = memories.find(m => m.id === selectedId) ?? null;

  const open = (id: string, reveal = true) => {
    navigate({ pathname: profileUrl(profile, `memories/${encodeURIComponent(id)}`), search: params.toString() }, { replace: Boolean(memoryId) });
    // When the detail panel sits below the list (narrow screens), bring it into view.
    if (reveal && window.matchMedia('(max-width: 1099px)').matches) {
      setTimeout(() => document.getElementById('memory-detail')?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 50);
    }
  };
  const move = (delta: number) => {
    if (!memories.length) return;
    const index = memories.findIndex(m => m.id === selectedId);
    const next = memories[Math.min(memories.length - 1, Math.max(0, index + delta))];
    if (next) {
      open(next.id, false);
      document.getElementById(`memory-row-${next.id}`)?.scrollIntoView({ block: 'nearest' });
    }
  };
  useHotkey('/', () => searchRef.current?.focus());
  useHotkey('j', () => move(1));
  useHotkey('k', () => move(-1));
  useHotkey('Enter', () => { if (selectedId) document.getElementById('memory-detail')?.focus(); });

  const counts = search.data?.typeCounts ?? {};
  const allCount = Object.values(counts).reduce((sum, n) => sum + n, 0);
  const typeOptions = [
    { value: 'all' as const, label: 'All', count: search.data ? integer(allCount) : undefined },
    ...MEMORY_TYPES.map(t => ({ value: t, label: t, count: search.data ? integer(counts[t] ?? 0) : undefined }))
  ];
  const total = search.data?.total ?? 0;
  // A grouped row stands for every trace outcome it folds, so count those too.
  const shown = items.reduce((n, item) => n + (item.kind === 'tool-calls' ? item.group?.count ?? 0 : 1), 0);
  const noun = type === 'all' ? 'memories' : `${type}s`;

  return (
    <Page className={styles.page}>
      <PageHeader crumb={<>{profile} / Knowledge</>} title="Memories"
        actions={<LinkButton to={{ pathname: profileUrl(profile, 'memories/new'), search: params.toString() }} variant="primary" size="lg">+ Add memory</LinkButton>} />

      <Card className={styles.filters}>
        <div className={styles.filterRow}>
          <SearchField ref={searchRef} label="Search memories" value={text} onChange={setText}
            placeholder="Search content, topic key, session id…" className={styles.search} shortcut={<Kbd>/</Kbd>} />
          <Segmented label="Filter by type" value={type} options={typeOptions} onChange={value => update({ type: value === 'all' ? null : value })} />
        </div>
        <div className={styles.filterRow}>
          <SessionFilter session={session} onChange={value => update({ session: value })} />
          <SortFilter sort={sort} onChange={value => update({ sort: value === 'newest' ? null : value })} />
          <Checkbox checked={grouped} onChange={value => update({ grouped: value ? null : '0' })}>Group tool calls</Checkbox>
          <Checkbox checked={superseded} onChange={value => update({ superseded: value ? '1' : null })}>Superseded</Checkbox>
        </div>
      </Card>

      {search.isError && <ErrorBanner error={search.error} />}

      <div className={styles.layout}>
        <Card flush as="section" aria-label="Memory list" className={styles.list}>
          <div className={styles.listHead}>
            <span>{search.data ? `Showing ${integer(shown)} of ${integer(total)} ${noun}` : 'Loading…'}</span>
            <span className={styles.grow} />
            <span className="mono">j / k to move · enter to open</span>
          </div>
          {search.isPending && <SkeletonRows rows={8} height={52} />}
          {search.isSuccess && items.length === 0 && (
            <EmptyInline title={q || session || type !== 'all' ? 'No memories match these filters.' : 'This profile has no memories yet.'}
              detail={q || session || type !== 'all' ? 'Clear the search or pick another type.' : 'Agents add memories as they work; you can also add one by hand.'} />
          )}
          <div className={cx(search.isFetching && !search.isPending && styles.refreshing)}>
            {items.map(item => item.kind === 'tool-calls'
              ? <ToolCallRow key={`group-${item.group?.sessionId}`} item={item} onExpand={sessionId => update({ session: sessionId, grouped: '0' })} />
              : item.memory && <MemoryRow key={item.memory.id} memory={item.memory} selected={item.memory.id === selectedId} onOpen={open} />)}
          </div>
          <div className={styles.pager}>
            <span>Page {page + 1} · {PAGE_SIZE} per page</span>
            <span className={styles.grow} />
            <Button size="sm" disabled={page === 0} onClick={() => setCursors(c => c.slice(0, -1))}>Previous</Button>
            <Button size="sm" disabled={!search.data?.nextCursor} onClick={() => setCursors(c => [...c, search.data?.nextCursor ?? null])}>Next</Button>
          </div>
        </Card>

        <MemoryDetail key={selectedId ?? 'none'} profile={profile} memoryId={selectedId} preview={selected} />
      </div>

      {adding && <AddMemoryDrawer onClose={() => navigate({ pathname: profileUrl(profile, 'memories'), search: params.toString() })} />}
    </Page>
  );
}

function MemoryRow({ memory, selected, onOpen }: { memory: Memory; selected: boolean; onOpen: (id: string) => void }) {
  return (
    <button type="button" id={`memory-row-${memory.id}`} className={styles.row} aria-current={selected ? 'true' : undefined}
      onClick={() => onOpen(memory.id)}>
      <span className={styles.rowMeta}>
        <TypeChip type={memory.type} />
        {memory.topicKey && <span className={styles.rowKey}>{memory.topicKey}</span>}
        {memory.superseded && <span className={styles.superseded}>superseded</span>}
        <span className={styles.grow} />
        <span className={styles.when} title={dayTime(memory.createdAt)}>{relativeTime(memory.createdAt)}</span>
      </span>
      <span className={cx(styles.rowContent, memory.superseded && styles.struck)}>{memory.content}</span>
    </button>
  );
}

function ToolCallRow({ item, onExpand }: { item: MemoryListItem; onExpand: (sessionId: string) => void }) {
  const group = item.group!;
  const tools = group.tools.filter(Boolean).slice(0, 4);
  return (
    <div className={styles.groupRow}>
      <TypeChip type="event" />
      <span className={styles.groupText}>
        Session <span className="mono">{shortId(group.sessionId)}</span> · {plural(group.count, 'command')}
        {tools.length > 0 && <> ({tools.join(', ')})</>} grouped
      </span>
      <span className={styles.when}>{relativeTime(group.lastAt)}</span>
      {group.sessionId && <Button size="sm" onClick={() => onExpand(group.sessionId!)}>Expand</Button>}
    </div>
  );
}

function SessionFilter({ session, onChange }: { session: string; onChange: (value: string | null) => void }) {
  const profile = useProfile();
  const popover = usePopover();
  const sessions = useSessions(profile);
  const [filter, setFilter] = useState('');
  const shown = useMemo(() => (sessions.data ?? [])
    .filter(s => !filter || s.sessionId.toLowerCase().includes(filter.toLowerCase()) || (s.harness ?? '').includes(filter.toLowerCase()))
    .slice(0, 50), [sessions.data, filter]);
  return (
    <>
      <Button ref={popover.anchor} variant="field" onClick={popover.toggle} aria-haspopup="menu" aria-expanded={popover.open}>
        Session: {session ? <span className="mono">{shortId(session)}</span> : 'any'} ▾
      </Button>
      <Popover anchor={popover.anchor} open={popover.open} onClose={popover.close} label="Filter by session">
        <div style={{ padding: 4 }}>
          <SearchField label="Find a session" value={filter} onChange={setFilter} placeholder="Session id or harness…" />
        </div>
        <MenuItem checked={!session} onSelect={() => { onChange(null); popover.close(); }}>Any session</MenuItem>
        <MenuSeparator />
        <MenuHeading>Recent sessions</MenuHeading>
        {sessions.isPending && <MenuItem disabled onSelect={() => undefined}>Loading…</MenuItem>}
        {sessions.isError && <MenuItem disabled onSelect={() => undefined}>Sessions unavailable</MenuItem>}
        {shown.map(s => (
          <MenuItem key={s.sessionId} checked={s.sessionId === session} detail={`${dayTime(s.lastSeenAt)} · ${integer(s.memoryCount)}`}
            onSelect={() => { onChange(s.sessionId); popover.close(); }}>
            <span className="mono">{shortId(s.sessionId)}</span>{s.harness && <span style={{ color: 'var(--text-muted)' }}> · {s.harness}</span>}
          </MenuItem>
        ))}
      </Popover>
    </>
  );
}

function SortFilter({ sort, onChange }: { sort: 'newest' | 'oldest'; onChange: (value: 'newest' | 'oldest') => void }) {
  const popover = usePopover();
  return (
    <>
      <Button ref={popover.anchor} variant="field" onClick={popover.toggle} aria-haspopup="menu" aria-expanded={popover.open}>
        {sort === 'newest' ? 'Newest' : 'Oldest'} ▾
      </Button>
      <Popover anchor={popover.anchor} open={popover.open} onClose={popover.close} label="Sort order">
        <MenuItem checked={sort === 'newest'} onSelect={() => { onChange('newest'); popover.close(); }}>Newest first</MenuItem>
        <MenuItem checked={sort === 'oldest'} onSelect={() => { onChange('oldest'); popover.close(); }}>Oldest first</MenuItem>
      </Popover>
    </>
  );
}
