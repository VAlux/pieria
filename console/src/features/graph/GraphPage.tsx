import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { graphEntity, graphNeighborhood, graphOverview, graphSearch, typeColor, type GraphNode } from '../../api/graph';
import { qk } from '../../api/queries';
import { integer, plural } from '../../lib/format';
import { Button, Segmented, cx } from '../../ui/controls';
import { ErrorBanner, Page } from '../../ui/display';
import { Icon } from '../../ui/icons';
import { useProfile } from '../shell/context';
import { ClustersView } from './ClustersView';
import { FiltersPanel } from './FiltersPanel';
import { EntityInspector } from './EntityInspector';
import { FocusView } from './FocusView';
import { OverviewView } from './OverviewView';
import styles from './graph.module.css';

type View = 'focus' | 'clusters' | 'overview';

export function GraphPage() {
  const profile = useProfile();
  const [params, setParams] = useSearchParams();
  const view = (params.get('view') as View) || 'focus';
  const depth = Number(params.get('depth') || 1);
  const [filters, setFilters] = useState(false);
  const [inspector, setInspector] = useState(true);
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<string | null>(null);
  const [limit, setLimit] = useState(24);
  const [trail, setTrail] = useState<GraphNode[]>([]);
  const [fitKey, setFitKey] = useState(0);
  const set = (changes: Record<string, string | null>) => setParams(p => {
    const next = new URLSearchParams(p);
    Object.entries(changes).forEach(([k, v]) => (v ? next.set(k, v) : next.delete(k)));
    return next;
  }, { replace: true });

  const overview = useQuery({ queryKey: qk.graph(profile, 'overview'), queryFn: ({ signal }) => graphOverview(profile, 150, undefined, signal) });
  const entityParam = params.get('entity');
  // Without an explicit entity, start from the one named after the profile (usually the project),
  // else from the best-connected entity.
  const named = useQuery({
    queryKey: qk.graph(profile, 'search', profile),
    queryFn: ({ signal }) => graphSearch(profile, profile, signal),
    enabled: !entityParam
  });
  const fallback = overview.data?.nodes.slice().sort((a, b) => b.degree - a.degree)[0]?.id ?? null;
  const focusId = entityParam
    ?? named.data?.find(n => n.name.toLowerCase() === profile.toLowerCase())?.id
    ?? (named.isFetched || named.isError ? fallback : null);

  const neighborhood = useQuery({
    queryKey: qk.graph(profile, 'neighborhood', focusId, depth, limit),
    queryFn: ({ signal }) => graphNeighborhood(profile, focusId!, depth, limit, signal),
    enabled: Boolean(focusId) && view === 'focus',
    placeholderData: previous => previous
  });
  const focusNode = neighborhood.data?.nodes.find(n => n.id === focusId);
  const focusDetail = useQuery({
    queryKey: qk.graph(profile, 'entity', focusId),
    queryFn: ({ signal }) => graphEntity(profile, focusId!, signal),
    enabled: Boolean(focusId)
  });

  useEffect(() => { setSelected(null); setLimit(24); }, [focusId]);
  useEffect(() => {
    if (!focusNode) return;
    setTrail(t => (t[t.length - 1]?.id === focusNode.id ? t : [...t.filter(n => n.id !== focusNode.id), focusNode].slice(-5)));
  }, [focusNode]);

  const focusOn = (id: string) => set({ entity: id, view: 'focus' });
  const visibleNodes = useMemo(
    () => (neighborhood.data?.nodes ?? []).filter(n => n.id === focusId || !hidden.has(n.type)),
    [neighborhood.data, hidden, focusId]
  );
  const typeCounts = useMemo(() => {
    const counts = new Map<string, number>();
    (neighborhood.data?.nodes ?? []).forEach(n => counts.set(n.type, (counts.get(n.type) ?? 0) + 1));
    return [...counts.entries()].sort((a, b) => b[1] - a[1]);
  }, [neighborhood.data]);

  const inspected = view === 'overview' ? selected : selected ?? focusId;
  const drawn = Math.max(0, visibleNodes.length - 1);
  const status = view === 'focus' && neighborhood.data && focusNode
    ? `${integer(drawn)} of ${integer(focusNode.degree)} relations · ${plural(depth, 'hop')} from ${focusNode.name} · ${integer(overview.data?.entityCount ?? 0)} entities in profile`
    : view === 'overview' && overview.data
      ? `${integer(overview.data.nodes.length)} best-connected of ${integer(overview.data.entityCount)} entities · ${integer(overview.data.edgeCount)} relations`
      : '';

  return (
    <Page className={styles.page} label="Entity graph">
      <div className={styles.toolbar}>
        <h1 className={styles.title}>Entity graph</h1>
        <EntitySearch profile={profile} focus={focusNode ?? null} onPick={n => focusOn(n.id)} total={overview.data?.entityCount} />
        <Segmented label="View" value={view} onSurface onChange={v => set({ view: v === 'focus' ? null : v })}
          options={[{ value: 'focus', label: 'Focus' }, { value: 'clusters', label: 'Clusters' }, { value: 'overview', label: 'Overview' }]} />
        {view === 'focus' && (
          <Segmented label="Depth" value={String(depth)} onSurface onChange={v => set({ depth: v === '1' ? null : v })}
            options={[1, 2, 3].map(d => ({ value: String(d), label: `${d} hop${d > 1 ? 's' : ''}` }))} />
        )}
        <Button variant="field" aria-pressed={filters} className={cx(filters && styles.toggleOn)} onClick={() => setFilters(f => !f)}>Filters</Button>
        <Button variant="field" aria-pressed={inspector} className={cx(inspector && styles.toggleOn)} onClick={() => setInspector(i => !i)}>Inspector</Button>
        <Button variant="field" onClick={() => setFitKey(k => k + 1)}>Fit</Button>
      </div>

      {(overview.isError || neighborhood.isError) && <ErrorBanner error={overview.error ?? neighborhood.error} />}

      <section aria-label="Graph canvas" className={styles.canvas}>
        {view === 'focus' && focusId && neighborhood.data && (
          <FocusView key={fitKey} focusId={focusId} nodes={visibleNodes} links={neighborhood.data.links} selected={inspected}
            totals={focusDetail.data?.relationTotals} onSelect={id => { setSelected(id); setInspector(true); }} onFocus={focusOn}
            leftInset={filters ? 254 : 0} rightInset={inspector ? 324 : 0} />
        )}
        {view === 'focus' && !focusId && overview.isSuccess && (
          <div className={styles.emptyCanvas}>
            <h2>No entities yet</h2>
            <p>Entities and relations are extracted from memories as they are ingested. Turn on graph extraction in Profile config, or run reminiscence, to build them.</p>
          </div>
        )}
        {view === 'overview' && overview.data && (
          <OverviewView key={fitKey} data={overview.data} hidden={hidden} selected={inspected}
            onSelect={id => { setSelected(id); setInspector(true); }} onFocus={focusOn} />
        )}
        {view === 'clusters' && (
          <ClustersView profile={profile} inspector={inspector} onCloseInspector={() => setInspector(false)} onOpenInspector={() => setInspector(true)}
            leftInset={filters ? 254 : 0} />
        )}

        {filters && (
          <FiltersPanel profile={profile} typeCounts={typeCounts} allTypes={overview.data?.types ?? []} hidden={hidden}
            onToggle={type => setHidden(h => { const next = new Set(h); if (next.has(type)) next.delete(type); else next.add(type); return next; })}
            onClose={() => setFilters(false)} selectedEntity={focusDetail.data ?? null} />
        )}
        {inspector && view !== 'clusters' && inspected && (
          <EntityInspector profile={profile} entityId={inspected} isFocus={view === 'focus' && inspected === focusId}
            onClose={() => setInspector(false)} onFocus={focusOn}
            onExpand={() => { if (inspected !== focusId) focusOn(inspected); else set({ depth: String(Math.min(3, depth + 1)) }); }} />
        )}

        {view !== 'clusters' && (
          <div className={styles.bottomBar}>
            <span className={styles.trail}>
              {trail.map((n, i) => (
                <span key={n.id}>
                  {i > 0 && <span aria-hidden="true"> › </span>}
                  <button type="button" className={cx(styles.crumb, n.id === focusId && styles.crumbOn)} onClick={() => focusOn(n.id)}>{n.name}</button>
                </span>
              ))}
              {trail.length > 0 && <span className={styles.hint}> · select a node, then Center here to walk further</span>}
            </span>
            <span className={styles.grow} />
            <span>{status}</span>
            {view === 'focus' && neighborhood.data?.truncated && (
              <Button size="sm" onClick={() => setLimit(l => Math.min(400, l + 40))}>Show remaining relations</Button>
            )}
          </div>
        )}
      </section>
    </Page>
  );
}

function EntitySearch({ profile, focus, onPick, total }: { profile: string; focus: GraphNode | null; onPick: (n: GraphNode) => void; total?: number }) {
  const [text, setText] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const [debounced, setDebounced] = useState('');
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => { const t = setTimeout(() => setDebounced(text.trim()), 200); return () => clearTimeout(t); }, [text]);
  const results = useQuery({
    queryKey: qk.graph(profile, 'search', debounced),
    queryFn: ({ signal }) => graphSearch(profile, debounced, signal),
    enabled: debounced.length > 0
  });
  const matches = results.data ?? [];
  const pick = (n: GraphNode) => { onPick(n); setText(''); setOpen(false); };
  return (
    <div ref={box} className={styles.search} onBlur={e => { if (!box.current?.contains(e.relatedTarget as Node)) setOpen(false); }}>
      <Icon name="search" size={14} strokeWidth={2} />
      {focus && (
        <span className={styles.focusChip}>{focus.name} <span style={{ color: typeColor(focus.type) }}>{focus.type}</span></span>
      )}
      <input type="search" role="combobox" aria-expanded={open && matches.length > 0} aria-controls="entity-results" aria-label="Find an entity"
        placeholder={`Jump to any of ${total ? integer(total) : ''} entities…`} value={text}
        onChange={e => { setText(e.target.value); setOpen(true); setActive(0); }}
        onKeyDown={e => {
          if (e.key === 'ArrowDown') { e.preventDefault(); setActive(i => Math.min(i + 1, matches.length - 1)); }
          if (e.key === 'ArrowUp') { e.preventDefault(); setActive(i => Math.max(i - 1, 0)); }
          if (e.key === 'Enter' && matches[active]) { e.preventDefault(); pick(matches[active]); }
          if (e.key === 'Escape') setOpen(false);
        }} />
      {open && debounced && (
        <div id="entity-results" role="listbox" className={styles.results}>
          {matches.map((n, i) => (
            <button key={n.id} type="button" role="option" aria-selected={i === active} className={styles.result} onMouseDown={e => e.preventDefault()} onClick={() => pick(n)}>
              <span aria-hidden="true" className={styles.dot} style={{ background: typeColor(n.type) }} />
              <span className={styles.grow}>{n.name}</span>
              <span className={styles.resultMeta}>{n.type} · {n.degree}</span>
            </button>
          ))}
          {results.isSuccess && matches.length === 0 && <span className={styles.noResult}>No entity matches “{debounced}”.</span>}
        </div>
      )}
    </div>
  );
}
