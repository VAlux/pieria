import { describe, expect, it } from 'vitest';
import type { ProfileSummary } from '../../api/types';
import { buildTree, canMove, flatten, subtreeOf, validParents } from './tree';

const p = (name: string, parent: string | null = null, memoryCount = 1): ProfileSummary =>
  ({ name, parent, memoryCount, createdAt: null });

const profiles = [p('omega'), p('alpha'), p('gamma', 'alpha', 2), p('beta', 'alpha'), p('delta', 'gamma', 4)];

describe('profile tree', () => {
  it('nestsChildrenUnderTheirParentSortedByName', () => {
    const rows = flatten(buildTree(profiles)).map(n => `${'  '.repeat(n.depth)}${n.profile.name}`);
    expect(rows).toEqual(['alpha', '  beta', '  gamma', '    delta', 'omega']);
  });

  it('sumsTheSubtreeCount', () => {
    const [alpha] = buildTree(profiles);
    expect(alpha.subtreeCount).toBe(1 + 1 + 2 + 4);
  });

  it('showsAProfileWithAMissingParentAtTopLevel', () => {
    expect(flatten(buildTree([p('orphan', 'gone')])).map(n => n.depth)).toEqual([0]);
  });

  it('neverLoopsOnACycle', () => {
    expect(flatten(buildTree([p('a', 'b'), p('b', 'a'), p('c')])).map(n => n.profile.name)).toEqual(['c']);
  });

  it('subtreeIncludesEveryDescendant', () => {
    expect([...subtreeOf(profiles, 'alpha')].sort()).toEqual(['alpha', 'beta', 'delta', 'gamma']);
  });

  it('offersOnlyParentsOutsideTheSubtree', () => {
    expect(validParents(profiles, 'gamma')).toEqual(['alpha', 'beta', 'omega']);
  });

  it('refusesCyclesAndNoOpMoves', () => {
    expect(canMove(profiles, 'alpha', 'delta')).toBe(false);
    expect(canMove(profiles, 'gamma', 'gamma')).toBe(false);
    expect(canMove(profiles, 'gamma', 'alpha')).toBe(false);
    expect(canMove(profiles, 'gamma', 'omega')).toBe(true);
    expect(canMove(profiles, 'gamma', null)).toBe(true);
    expect(canMove(profiles, 'omega', null)).toBe(false);
  });
});
