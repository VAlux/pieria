import { useMemo } from 'react';
import { forceCenter, forceCollide, forceLink, forceManyBody, forceSimulation, type SimulationLinkDatum, type SimulationNodeDatum } from 'd3-force';
import { typeColor, type GraphOverview } from '../../api/graph';
import { usePanZoom } from './usePanZoom';
import styles from './graph.module.css';

interface SimNode extends SimulationNodeDatum {
  id: string;
  name: string;
  type: string;
  degree: number;
}

const W = 1000;
const H = 700;

/** Force layout of the best-connected slice, settled up front so it renders without animation. */
export function OverviewView({ data, hidden, selected, onSelect, onFocus }: {
  data: GraphOverview;
  hidden: Set<string>;
  selected: string | null;
  onSelect: (id: string) => void;
  onFocus: (id: string) => void;
}) {
  const pan = usePanZoom();
  const { nodes, links } = useMemo(() => {
    const nodes: SimNode[] = data.nodes.filter(n => !hidden.has(n.type)).map(n => ({ ...n }));
    const ids = new Set(nodes.map(n => n.id));
    const links: SimulationLinkDatum<SimNode>[] = data.links
      .filter(l => ids.has(l.source) && ids.has(l.target))
      .map(l => ({ source: l.source, target: l.target }));
    forceSimulation(nodes)
      .force('link', forceLink<SimNode, SimulationLinkDatum<SimNode>>(links).id(d => d.id).distance(60))
      .force('charge', forceManyBody().strength(-90))
      .force('center', forceCenter(W / 2, H / 2))
      .force('collide', forceCollide<SimNode>().radius(d => radius(d) + 4))
      .stop()
      .tick(300);
    return { nodes, links: links as { source: SimNode; target: SimNode }[] };
  }, [data, hidden]);

  const labelled = new Set([...nodes].sort((a, b) => b.degree - a.degree).slice(0, 30).map(n => n.id));
  return (
    <div className={styles.viewport} {...pan.handlers} style={{ left: 16, right: 16 }}>
      <svg viewBox={`0 0 ${W} ${H}`} className={styles.overviewSvg} style={pan.style} role="img" aria-label={`${nodes.length} entities, sized by number of relations`}>
        {links.map((l, i) => (
          <line key={i} x1={l.source.x} y1={l.source.y} x2={l.target.x} y2={l.target.y} stroke="#2E3035" strokeWidth={1} />
        ))}
        {nodes.map(n => (
          <g key={n.id} transform={`translate(${n.x},${n.y})`} className={styles.overviewNode}
            onClick={() => onSelect(n.id)} onDoubleClick={() => onFocus(n.id)}>
            <circle r={radius(n)} fill={typeColor(n.type)} fillOpacity={0.85}
              stroke={n.id === selected ? '#F3EFE7' : '#111214'} strokeWidth={n.id === selected ? 2 : 1} />
            {labelled.has(n.id) && <text y={radius(n) + 12} textAnchor="middle">{n.name}</text>}
            <title>{`${n.name} (${n.type}, ${n.degree} relations) — double-click to focus`}</title>
          </g>
        ))}
      </svg>
    </div>
  );
}

function radius(n: { degree: number }): number {
  return 4 + Math.min(16, Math.sqrt(n.degree) * 1.6);
}
