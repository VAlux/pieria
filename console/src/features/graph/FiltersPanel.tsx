import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { errorMessage } from '../../api/client';
import { listClusters, startClusterRun } from '../../api/clusters';
import { typeColor, type GraphEntity } from '../../api/graph';
import { qk } from '../../api/queries';
import { integer } from '../../lib/format';
import { Button, IconButton } from '../../ui/controls';
import { DisabledHint, useToast } from '../../ui/overlay';
import { request, profilePath } from '../../api/client';
import styles from './graph.module.css';

interface Props {
  profile: string;
  typeCounts: [string, number][];
  allTypes: { type: string; count: number }[];
  hidden: Set<string>;
  onToggle: (type: string) => void;
  onClose: () => void;
  selectedEntity: GraphEntity | null;
}

export function FiltersPanel({ profile, typeCounts, allTypes, hidden, onToggle, onClose, selectedEntity }: Props) {
  const client = useQueryClient();
  const toast = useToast();
  const [showAll, setShowAll] = useState(false);
  const [filter, setFilter] = useState('');
  const clusters = useQuery({ queryKey: qk.clusters(profile, 'any'), queryFn: ({ signal }) => listClusters(profile, undefined, signal), retry: false });
  const run = useMutation({
    mutationFn: () => startClusterRun(profile),
    onSuccess: () => { toast.show('Clustering run started', 'It runs in the background; clusters appear here as they are written.'); client.invalidateQueries({ queryKey: qk.clusters(profile) }); },
    onError: e => toast.show('Could not start a clustering run', errorMessage(e), 'error')
  });
  const fromSelection = useMutation({
    mutationFn: () => request(profilePath(profile, '/clusters'), {
      method: 'POST',
      json: { title: selectedEntity!.entity.name, scope: `entity:${selectedEntity!.entity.name}`, members: selectedEntity!.memories.map(m => ({ kind: 'memory', id: m.id })) }
    }),
    onSuccess: () => { toast.show('Cluster created', `From the memories that mention ${selectedEntity?.entity.name}.`); client.invalidateQueries({ queryKey: qk.clusters(profile) }); },
    onError: e => toast.show('Could not create the cluster', errorMessage(e), 'error')
  });

  const rows = showAll
    ? allTypes.filter(t => !filter || t.type.includes(filter.toLowerCase())).map(t => [t.type, t.count] as [string, number])
    : typeCounts;
  const canCreate = Boolean(selectedEntity && selectedEntity.memories.length > 0);
  const createButton = <Button size="sm" disabled={!canCreate || fromSelection.isPending} onClick={() => fromSelection.mutate()}>New from selection</Button>;

  return (
    <aside aria-label="Filters" className={styles.filters}>
      <div className={styles.inspectorHead}>
        <h2 className={styles.filtersTitle}>Entity types <span>{showAll ? 'in profile' : 'in view'}</span></h2>
        <IconButton icon="close" label="Close filters" bare onClick={onClose} />
      </div>
      {showAll && (
        <input className={styles.filterInput} type="search" placeholder="Filter types…" aria-label="Filter entity types" value={filter} onChange={e => setFilter(e.target.value)} />
      )}
      <div className={styles.typeList}>
        {rows.map(([type, count]) => (
          <label key={type} className={styles.typeRow}>
            <input type="checkbox" checked={!hidden.has(type)} onChange={() => onToggle(type)} />
            <span aria-hidden="true" className={styles.dot} style={{ background: typeColor(type) }} />
            <span className={styles.grow}>{type}</span>
            <span className="mono">{integer(count)}</span>
          </label>
        ))}
      </div>
      <button type="button" className={styles.link} onClick={() => setShowAll(s => !s)}>
        {showAll ? 'Only types in view' : `Search all ${allTypes.length} types…`}
      </button>
      <div className={styles.filtersClusters}>
        <div className={styles.inspectorHead}>
          <span className={styles.sectionHeading}>Clusters</span>
          <span className="mono">{clusters.data ? integer(clusters.data.length) : '–'}</span>
        </div>
        <DisabledHint reason="Grouping the focus view by cluster is not available yet; switch to the Clusters view to see them as hulls.">
          <label className={styles.typeRow} style={{ color: 'var(--text-faint)' }}>
            <input type="checkbox" disabled /> Group nodes by cluster
          </label>
        </DisabledHint>
        {clusters.data?.length === 0 && (
          <p className={styles.muted}>No clusters yet. A run groups related memories into reviewable claims, drawn as hulls in the Clusters view.</p>
        )}
        <div className={styles.actions}>
          <Button size="sm" variant="accent" disabled={run.isPending} onClick={() => run.mutate()}>Start clustering run</Button>
          {canCreate ? createButton : <DisabledHint reason="Select an entity that is mentioned in memories first.">{createButton}</DisabledHint>}
        </div>
      </div>
    </aside>
  );
}
