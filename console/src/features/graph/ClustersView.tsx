import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { errorMessage } from '../../api/client';
import {
  clusterLinks, clusterOriginals, clusterSynthesis, latestClusterRun, listClusters, regenerateCluster, renameCluster,
  startClusterRun, type Cluster
} from '../../api/clusters';
import { qk } from '../../api/queries';
import { dayTime, integer, plural } from '../../lib/format';
import { profileUrl } from '../../lib/routes';
import { Button, IconButton, TextInput, cx } from '../../ui/controls';
import { ErrorBanner, SectionLabel, Skeleton } from '../../ui/display';
import { Dialog, DisabledHint, useToast } from '../../ui/overlay';
import { Link } from 'react-router';
import styles from './graph.module.css';

const HUES = ['#E8B062', '#7DB4E8', '#B9A0EE', '#7CCB9F', '#E89AAE', '#6CC8C8'];

interface Props {
  profile: string;
  inspector: boolean;
  onCloseInspector: () => void;
  onOpenInspector: () => void;
  leftInset: number;
}

export function ClustersView({ profile, inspector, onCloseInspector, onOpenInspector, leftInset }: Props) {
  const client = useQueryClient();
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const clusters = useQuery({ queryKey: qk.clusters(profile, 'any'), queryFn: ({ signal }) => listClusters(profile, undefined, signal) });
  const list = clusters.data ?? [];
  const ids = useMemo(() => list.map(c => c.id), [list]);
  const links = useQuery({ queryKey: qk.clusters(profile, 'links', ids), queryFn: ({ signal }) => clusterLinks(profile, ids, signal), enabled: ids.length > 1, retry: false });
  const run = useQuery({ queryKey: qk.clusters(profile, 'run'), queryFn: ({ signal }) => latestClusterRun(profile, signal), retry: false,
    refetchInterval: q => (q.state.data && !['completed', 'failed', 'cancelled'].includes(q.state.data.state) ? 5_000 : false) });
  const start = useMutation({
    mutationFn: () => startClusterRun(profile),
    onSuccess: () => { toast.show('Clustering run started'); client.invalidateQueries({ queryKey: qk.clusters(profile) }); },
    onError: e => toast.show('Could not start a clustering run', errorMessage(e), 'error')
  });
  const selectedId = params.get('cluster') ?? list[0]?.id ?? null;
  const select = (id: string) => { setParams(p => { const n = new URLSearchParams(p); n.set('cluster', id); return n; }, { replace: true }); onOpenInspector(); };

  if (clusters.isPending) return <div className={styles.emptyCanvas}><Skeleton height={200} width={400} /></div>;
  if (clusters.isError) return <div className={styles.emptyCanvas}><ErrorBanner error={clusters.error} /></div>;
  if (list.length === 0) {
    const r = run.data;
    return (
      <div className={styles.emptyCanvas}>
        <h2>No knowledge clusters yet</h2>
        <p>A clustering run groups related memories into clusters with reviewable claims, drawn here as hulls linked by the entities they share.</p>
        {r && <p className={styles.muted}>Last run: {r.state} · started {dayTime(r.createdAt)}</p>}
        <Button variant="primary" disabled={start.isPending} onClick={() => start.mutate()}>Start clustering run</Button>
      </div>
    );
  }

  const columns = Math.ceil(Math.sqrt(list.length));
  const rows = Math.ceil(list.length / columns);
  const pos = new Map(list.map((c, i) => [c.id, {
    x: ((i % columns) + 0.5) / columns,
    y: (Math.floor(i / columns) + 0.5) / rows,
    hue: HUES[i % HUES.length]
  }]));
  const maxSize = Math.max(...list.map(c => c.distinctOriginalCount), 1);
  const right = inspector ? 340 : 16;

  return (
    <>
      <div className={styles.viewport} style={{ left: leftInset + 16, right, top: 16, bottom: 16 }}>
        <svg aria-hidden="true" viewBox="0 0 1000 1000" preserveAspectRatio="none" className={styles.edges}>
          {(links.data ?? []).map(l => {
            const a = pos.get(l.a); const b = pos.get(l.b);
            if (!a || !b) return null;
            const hot = l.a === selectedId || l.b === selectedId;
            return <line key={`${l.a}-${l.b}`} x1={a.x * 1000} y1={a.y * 1000} x2={b.x * 1000} y2={b.y * 1000}
              stroke={hot ? '#8E8A82' : '#34363C'} strokeWidth={Math.min(1 + l.sharedEntities * 0.35, 4)} strokeDasharray={hot ? undefined : '4 4'} vectorEffect="non-scaling-stroke" />;
          })}
        </svg>
        {(links.data ?? []).map(l => {
          const a = pos.get(l.a); const b = pos.get(l.b);
          if (!a || !b) return null;
          const hot = l.a === selectedId || l.b === selectedId;
          return <span key={`label-${l.a}-${l.b}`} className={cx(styles.linkLabel, hot && styles.linkLabelHot)} title={l.topEntityNames.join(', ')}
            style={{ left: `${((a.x + b.x) / 2) * 100}%`, top: `${((a.y + b.y) / 2) * 100}%` }}>{l.sharedEntities} shared</span>;
        })}
        {list.map(c => {
          const p = pos.get(c.id)!;
          const size = 140 + (c.distinctOriginalCount / maxSize) * 120;
          const on = c.id === selectedId;
          return (
            <button key={c.id} type="button" aria-pressed={on} className={styles.hull} onClick={() => select(c.id)}
              style={{ left: `${p.x * 100}%`, top: `${p.y * 100}%`, width: size, height: size, ['--hue' as string]: p.hue }}>
              <span className={styles.hullTitle}>{c.title}</span>
              <span className={styles.hullMeta}>{plural(c.distinctOriginalCount, 'memory', 'memories')}</span>
              {c.freshness !== 'current' && <span className={styles.hullMeta}>{c.freshness}</span>}
            </button>
          );
        })}
      </div>
      {inspector && selectedId && <ClusterInspector key={selectedId} profile={profile} cluster={list.find(c => c.id === selectedId)!}
        hue={pos.get(selectedId)?.hue ?? HUES[0]} onClose={onCloseInspector} />}
      <div className={styles.bottomBar}>
        <span>{plural(list.length, 'cluster')} · line weight = shared entities</span>
        <span className={styles.grow} />
        {run.data && <span className={styles.muted}>last run {run.data.state} · {dayTime(run.data.updatedAt)}</span>}
        <Button size="sm" disabled={start.isPending} onClick={() => start.mutate()}>Run clustering again</Button>
      </div>
    </>
  );
}

function ClusterInspector({ profile, cluster, hue, onClose }: { profile: string; cluster: Cluster; hue: string; onClose: () => void }) {
  const client = useQueryClient();
  const toast = useToast();
  const [renaming, setRenaming] = useState(false);
  const synthesis = useQuery({ queryKey: qk.clusters(profile, cluster.id, 'synthesis'), queryFn: ({ signal }) => clusterSynthesis(profile, cluster.id, signal), retry: false });
  const originals = useQuery({ queryKey: qk.clusters(profile, cluster.id, 'originals'), queryFn: ({ signal }) => clusterOriginals(profile, cluster.id, signal) });
  const regenerate = useMutation({
    mutationFn: () => regenerateCluster(profile, cluster),
    onSuccess: () => { toast.show('Regenerating claims', 'The synthesis is rewritten in the background.'); client.invalidateQueries({ queryKey: qk.clusters(profile) }); },
    onError: e => toast.show('Could not regenerate', errorMessage(e), 'error')
  });
  const claims = synthesis.data?.claims ?? [];
  return (
    <aside aria-label="Cluster inspector" className={styles.inspector}>
      <div className={styles.inspectorHead}>
        <span aria-hidden="true" className={styles.swatch} style={{ background: hue }} />
        <h2>{cluster.title}</h2>
        <IconButton icon="close" label="Close inspector" bare onClick={onClose} />
      </div>
      <span className={styles.muted}>
        {plural(cluster.distinctOriginalCount, 'memory', 'memories')} · {plural(cluster.directCount, 'direct member')} · {plural(claims.length, 'claim')} · {cluster.freshness}
      </span>
      <div className={styles.inspectorSection}>
        <SectionLabel>Claims</SectionLabel>
        {synthesis.isPending && <Skeleton height={60} />}
        {!synthesis.isPending && claims.length === 0 && <span className={styles.muted}>No claims written yet.</span>}
        {claims.map((claim, i) => (
          <div key={i} className={styles.claim}>
            <span className={styles.claimBadge}>{claim.supports.length ? 'supported' : 'unsupported'}</span>
            <span>
              <span className={styles.claimText}>{claim.text}</span>
              <span className={styles.muted}>{plural(claim.supports.length, 'supporting memory', 'supporting memories')}</span>
            </span>
          </div>
        ))}
      </div>
      <div className={styles.inspectorSection}>
        <SectionLabel>Members</SectionLabel>
        {(originals.data ?? []).slice(0, 5).map(m => (
          <Link key={m.memoryId} to={profileUrl(profile, `memories/${encodeURIComponent(m.memoryId)}`)} className={styles.memoryLink}>{m.content}</Link>
        ))}
        {(originals.data?.length ?? 0) > 5 && <span className={styles.muted}>+{integer((originals.data?.length ?? 0) - 5)} more</span>}
      </div>
      <div className={styles.actions}>
        <Button size="sm" variant="primary" disabled={regenerate.isPending} onClick={() => regenerate.mutate()}>Regenerate claims</Button>
        <Button size="sm" onClick={() => setRenaming(true)}>Rename</Button>
        <DisabledHint reason="Splitting and merging clusters is not available in the console yet.">
          <Button size="sm" disabled>Split / merge…</Button>
        </DisabledHint>
      </div>
      {renaming && <RenameDialog profile={profile} cluster={cluster} onClose={() => setRenaming(false)} />}
    </aside>
  );
}

function RenameDialog({ profile, cluster, onClose }: { profile: string; cluster: Cluster; onClose: () => void }) {
  const client = useQueryClient();
  const [title, setTitle] = useState(cluster.title);
  const rename = useMutation({
    mutationFn: () => renameCluster(profile, cluster, title.trim()),
    onSuccess: () => { client.invalidateQueries({ queryKey: qk.clusters(profile) }); onClose(); }
  });
  return (
    <Dialog title="Rename cluster" onClose={onClose}
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" disabled={!title.trim() || rename.isPending} onClick={() => rename.mutate()}>Rename</Button></>}>
      <TextInput autoFocus value={title} onChange={e => setTitle(e.target.value)} aria-label="Cluster title" />
      {rename.error && <ErrorBanner error={rename.error} />}
    </Dialog>
  );
}
