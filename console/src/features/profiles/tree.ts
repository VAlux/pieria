import type { ProfileSummary } from '../../api/types';

export interface TreeNode {
  profile: ProfileSummary;
  depth: number;
  children: TreeNode[];
  /** Memories here and in every profile below: what a recall on this profile reads. */
  subtreeCount: number;
}

const byName = (a: ProfileSummary, b: ProfileSummary) => a.name.localeCompare(b.name);

/**
 * The profiles as a forest, siblings by name. A profile whose parent is missing from the list is
 * shown at top level rather than dropped, and a cycle (which the daemon refuses) cannot loop.
 */
export function buildTree(profiles: ProfileSummary[]): TreeNode[] {
  const names = new Set(profiles.map(p => p.name));
  const children = new Map<string, ProfileSummary[]>();
  const roots: ProfileSummary[] = [];
  for (const p of profiles) {
    if (p.parent && names.has(p.parent) && p.parent !== p.name) {
      children.set(p.parent, [...(children.get(p.parent) ?? []), p]);
    } else {
      roots.push(p);
    }
  }
  const seen = new Set<string>();
  const node = (profile: ProfileSummary, depth: number): TreeNode | null => {
    if (seen.has(profile.name)) return null;
    seen.add(profile.name);
    const kids = [...(children.get(profile.name) ?? [])].sort(byName)
      .map(child => node(child, depth + 1)).filter((n): n is TreeNode => n !== null);
    return { profile, depth, children: kids, subtreeCount: profile.memoryCount + kids.reduce((sum, k) => sum + k.subtreeCount, 0) };
  };
  return [...roots].sort(byName).map(root => node(root, 0)).filter((n): n is TreeNode => n !== null);
}

/** Depth-first rows, in display order. */
export function flatten(nodes: TreeNode[]): TreeNode[] {
  return nodes.flatMap(n => [n, ...flatten(n.children)]);
}

/** `name` and every profile below it. */
export function subtreeOf(profiles: ProfileSummary[], name: string): Set<string> {
  const out = new Set<string>([name]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const p of profiles) {
      if (p.parent && out.has(p.parent) && !out.has(p.name)) { out.add(p.name); grew = true; }
    }
  }
  return out;
}

/** Profiles `name` may move under: anything outside its own subtree. */
export function validParents(profiles: ProfileSummary[], name: string): string[] {
  const blocked = subtreeOf(profiles, name);
  return profiles.map(p => p.name).filter(n => !blocked.has(n)).sort((a, b) => a.localeCompare(b));
}

/** Whether moving `name` under `parent` (null = top level) is allowed and would change anything. */
export function canMove(profiles: ProfileSummary[], name: string, parent: string | null): boolean {
  const current = profiles.find(p => p.name === name);
  if (!current || (current.parent ?? null) === parent) return false;
  return parent === null || !subtreeOf(profiles, name).has(parent);
}
