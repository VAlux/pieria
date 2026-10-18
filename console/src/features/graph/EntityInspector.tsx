import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { graphEntity, typeColor } from '../../api/graph';
import { qk } from '../../api/queries';
import { integer } from '../../lib/format';
import { profileUrl } from '../../lib/routes';
import { Button, IconButton, LinkButton } from '../../ui/controls';
import { ErrorBanner, SectionLabel, Skeleton } from '../../ui/display';
import styles from './graph.module.css';

interface Props {
  profile: string;
  entityId: string;
  isFocus: boolean;
  onClose: () => void;
  onFocus: (id: string) => void;
  onExpand: () => void;
}

export function EntityInspector({ profile, entityId, isFocus, onClose, onFocus, onExpand }: Props) {
  const entity = useQuery({ queryKey: qk.graph(profile, 'entity', entityId), queryFn: ({ signal }) => graphEntity(profile, entityId, signal) });
  const data = entity.data;
  const totals = data?.relationTotals ?? [];
  const sample = (relation: string, direction: string) => (data?.relations ?? [])
    .filter(r => r.relation === relation && r.direction === direction)
    .slice(0, 2)
    .map(r => r.otherName);
  const totalRelations = totals.reduce((n, t) => n + t.count, 0) || data?.entity.degree || 0;

  return (
    <aside aria-label="Entity inspector" className={styles.inspector}>
      {entity.isPending && <><Skeleton height={20} width="70%" /><Skeleton height={80} /></>}
      {entity.isError && <ErrorBanner error={entity.error} />}
      {data && (
        <>
          <div className={styles.inspectorHead}>
            <span className={styles.typeChip} style={{ color: typeColor(data.entity.type) }}>{data.entity.type}</span>
            <h2>{data.entity.name}</h2>
            <IconButton icon="close" label="Close inspector" bare onClick={onClose} />
          </div>
          <span className={styles.muted}>
            {integer(totalRelations)} relations{isFocus ? ' · the focus of this view' : ' · double-click a node to centre on it'}
          </span>
          <div className={styles.actions}>
            {!isFocus && <Button size="sm" variant="primary" onClick={() => onFocus(entityId)}>Center here</Button>}
            <Button size="sm" onClick={onExpand}>{isFocus ? 'One hop further' : 'Expand'}</Button>
            <LinkButton size="sm" to={`${profileUrl(profile, 'memories')}?q=${encodeURIComponent(data.entity.name)}`}>Source memories</LinkButton>
          </div>
          <div className={styles.inspectorSection}>
            <SectionLabel>Relations</SectionLabel>
            {totals.length === 0 && <span className={styles.muted}>No active relations.</span>}
            {totals.map(t => (
              <div key={`${t.relation}-${t.direction}`} className={styles.relationRow}>
                <span className={styles.verb}>{t.direction === 'in' ? `← ${t.relation}` : t.relation}</span>
                <span className={styles.what}>{sample(t.relation, t.direction).join(', ') || '—'}</span>
                <span className="mono">{integer(t.count)}</span>
              </div>
            ))}
          </div>
          {data.memories.length > 0 && (
            <div className={styles.inspectorSection}>
              <SectionLabel>Mentioned in</SectionLabel>
              {data.memories.slice(0, 4).map(m => (
                <Link key={m.id} to={profileUrl(profile, `memories/${encodeURIComponent(m.id)}`)} className={styles.memoryLink}>{m.content}</Link>
              ))}
            </div>
          )}
        </>
      )}
    </aside>
  );
}
