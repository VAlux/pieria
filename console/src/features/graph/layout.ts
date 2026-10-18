import type { GraphLink, GraphNode } from '../../api/graph';

/** Positions are fractions of the stage (0..1). */
export interface Placed {
  node: GraphNode;
  x: number;
  y: number;
  /** Relation that connects a first-ring node to the focus. */
  relation: string;
  /** Neighbour this node hangs off (outer rings). */
  parent: string | null;
}

export interface RadialLayout {
  placed: Placed[];
  groups: { relation: string; count: number; x: number; y: number; color: string }[];
  edges: { from: string; to: string; relation: string; color: string; inner: boolean }[];
}

const GROUP_COLORS = ['#4A6E8C', '#4E7A62', '#6A5A8E', '#8A6A3A', '#5A5650', '#7A4A5A', '#3E6E6E'];

/**
 * Lays a neighbourhood out around its focus: first-hop entities on an ellipse, grouped by the
 * relation that links them to the focus (largest group first), alternating between two radii so
 * labels don't collide; further hops sit just outside the neighbour they hang off.
 */
export function radialLayout(focusId: string, nodes: GraphNode[], links: GraphLink[]): RadialLayout {
  const relationOf = new Map<string, string>();
  const parentOf = new Map<string, string>();
  for (const link of links) {
    if (link.source === focusId && !relationOf.has(link.target)) relationOf.set(link.target, link.relation);
    else if (link.target === focusId && !relationOf.has(link.source)) relationOf.set(link.source, link.relation);
  }
  const first = nodes.filter(n => n.id !== focusId && relationOf.has(n.id));
  const firstIds = new Set(first.map(n => n.id));
  for (const link of links) {
    if (firstIds.has(link.source) && !firstIds.has(link.target) && link.target !== focusId && !parentOf.has(link.target)) parentOf.set(link.target, link.source);
    if (firstIds.has(link.target) && !firstIds.has(link.source) && link.source !== focusId && !parentOf.has(link.source)) parentOf.set(link.source, link.target);
  }

  const byRelation = new Map<string, GraphNode[]>();
  first.forEach(n => {
    const r = relationOf.get(n.id)!;
    byRelation.set(r, [...(byRelation.get(r) ?? []), n]);
  });
  const ordered = [...byRelation.entries()].sort((a, b) => b[1].length - a[1].length || a[0].localeCompare(b[0]));
  const ring = ordered.flatMap(([, list]) => [...list].sort((a, b) => b.degree - a.degree));
  const total = ring.length;
  const color = new Map(ordered.map(([r], i) => [r, GROUP_COLORS[i % GROUP_COLORS.length]]));

  const placed: Placed[] = [];
  const angleOf = new Map<string, number>();
  ring.forEach((node, i) => {
    const angle = (i / Math.max(total, 1)) * Math.PI * 2 - Math.PI / 2;
    const far = total > 10 && i % 2 === 1;
    angleOf.set(node.id, angle);
    placed.push({
      node,
      x: 0.5 + Math.cos(angle) * (far ? 0.46 : 0.33),
      y: 0.5 + Math.sin(angle) * (far ? 0.42 : 0.3),
      relation: relationOf.get(node.id)!,
      parent: null
    });
  });

  const outer = nodes.filter(n => n.id !== focusId && !firstIds.has(n.id));
  const perParent = new Map<string, number>();
  outer.forEach(node => {
    const parent = parentOf.get(node.id) ?? null;
    const base = parent ? angleOf.get(parent) ?? 0 : 0;
    const k = perParent.get(parent ?? '') ?? 0;
    perParent.set(parent ?? '', k + 1);
    const angle = base + (k % 2 === 0 ? 1 : -1) * Math.ceil(k / 2) * 0.09;
    placed.push({ node, x: 0.5 + Math.cos(angle) * 0.49, y: 0.5 + Math.sin(angle) * 0.47, relation: '', parent });
  });

  let cursor = 0;
  const groups = ordered.map(([relation, list]) => {
    const mid = ((cursor + list.length / 2) / Math.max(total, 1)) * Math.PI * 2 - Math.PI / 2;
    cursor += list.length;
    return { relation, count: list.length, x: 0.5 + Math.cos(mid) * 0.17, y: 0.5 + Math.sin(mid) * 0.17, color: color.get(relation)! };
  });

  const edges = [
    ...ring.map(n => ({ from: focusId, to: n.id, relation: relationOf.get(n.id)!, color: color.get(relationOf.get(n.id)!)!, inner: true })),
    ...outer.flatMap(n => (parentOf.get(n.id) ? [{ from: parentOf.get(n.id)!, to: n.id, relation: '', color: '#34363C', inner: false }] : []))
  ];
  return { placed, groups, edges };
}
