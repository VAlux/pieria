import { describe, expect, it } from 'vitest';
import { radialLayout } from './layout';

const node = (id: string, degree = 1) => ({ id, type: 'concept', name: id, degree, hop: 1 });

describe('radialLayout', () => {
  it('groups first-hop neighbours by relation, largest group first', () => {
    const nodes = [node('f'), node('a'), node('b'), node('c'), node('x')];
    const links = [
      { source: 'f', target: 'a', relation: 'uses', memoryId: 'm' },
      { source: 'f', target: 'b', relation: 'uses', memoryId: 'm' },
      { source: 'c', target: 'f', relation: 'runs', memoryId: 'm' },
      { source: 'a', target: 'x', relation: 'loads', memoryId: 'm' }
    ];
    const layout = radialLayout('f', nodes, links);

    expect(layout.groups.map(g => [g.relation, g.count])).toEqual([['uses', 2], ['runs', 1]]);
    expect(layout.placed.find(p => p.node.id === 'c')?.relation).toBe('runs');
    expect(layout.placed.find(p => p.node.id === 'x')?.parent).toBe('a');
    expect(layout.edges.filter(e => e.inner)).toHaveLength(3);
    for (const p of layout.placed) {
      expect(p.x).toBeGreaterThanOrEqual(0);
      expect(p.x).toBeLessThanOrEqual(1);
    }
  });
});
