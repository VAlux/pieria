import { useMemo } from 'react';
import { typeColor, type GraphLink, type GraphNode } from '../../api/graph';
import { cx } from '../../ui/controls';
import { radialLayout } from './layout';
import { usePanZoom } from './usePanZoom';
import styles from './graph.module.css';

interface Props {
  focusId: string;
  nodes: GraphNode[];
  links: GraphLink[];
  selected: string | null;
  totals?: { relation: string; direction: string; count: number }[];
  onSelect: (id: string) => void;
  onFocus: (id: string) => void;
  leftInset: number;
  rightInset: number;
}

/** The focused entity in the middle, its neighbours around it grouped by relation. */
export function FocusView({ focusId, nodes, links, selected, totals, onSelect, onFocus, leftInset, rightInset }: Props) {
  const layout = useMemo(() => radialLayout(focusId, nodes, links), [focusId, nodes, links]);
  const pos = new Map(layout.placed.map(p => [p.node.id, p]));
  const focus = nodes.find(n => n.id === focusId);
  const pan = usePanZoom();
  const total = (relation: string, drawn: number) =>
    totals?.filter(t => t.relation === relation).reduce((n, t) => n + t.count, 0) || drawn;
  const xy = (id: string) => (id === focusId ? { x: 0.5, y: 0.5 } : pos.get(id) ?? { x: 0.5, y: 0.5 });

  return (
    <div className={styles.viewport} {...pan.handlers} style={{ left: leftInset + 16, right: rightInset + 16 }}>
      <div className={styles.stage} style={pan.style}>
        <svg aria-hidden="true" viewBox="0 0 1000 1000" preserveAspectRatio="none" className={styles.edges}>
          {layout.edges.map(edge => {
            const a = xy(edge.from);
            const b = xy(edge.to);
            const hot = edge.to === selected || edge.from === selected;
            return <line key={`${edge.from}-${edge.to}`} x1={a.x * 1000} y1={a.y * 1000} x2={b.x * 1000} y2={b.y * 1000}
              stroke={hot ? '#E8B062' : edge.color} strokeWidth={hot ? 2 : edge.inner ? 1.2 : 1} vectorEffect="non-scaling-stroke" />;
          })}
        </svg>
        {layout.groups.filter((g, i) => i < 6 && (g.count >= 2 || layout.groups.length <= 4)).map(g => (
          <span key={g.relation} className={styles.groupLabel} style={{ left: `${g.x * 100}%`, top: `${g.y * 100}%` }}>
            {g.relation} · {total(g.relation, g.count)}
          </span>
        ))}
        {layout.placed.map(p => (
          <button key={p.node.id} type="button" aria-pressed={p.node.id === selected} title={`${p.node.name} (${p.node.type}, ${p.node.degree} relations)`}
            className={cx(styles.node, p.parent !== null && styles.nodeOuter)} style={{ left: `${p.x * 100}%`, top: `${p.y * 100}%` }}
            onClick={() => onSelect(p.node.id)} onDoubleClick={() => onFocus(p.node.id)}>
            <span aria-hidden="true" className={styles.dot} style={{ background: typeColor(p.node.type) }} />
            <span>{p.node.name}</span>
          </button>
        ))}
        {focus && (
          <button type="button" aria-pressed={selected === focusId} className={styles.center} onClick={() => onSelect(focusId)}>
            <span className={styles.centerName}>{focus.name}</span>
            <span className={styles.centerMeta}>{focus.type} · {focus.degree} relations</span>
          </button>
        )}
      </div>
    </div>
  );
}
