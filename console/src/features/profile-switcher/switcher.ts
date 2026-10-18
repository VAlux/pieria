import type { ProfileSummary } from '../../api/types';

export interface SwitcherRow {
  name: string;
  count: number;
  /** Text split around the first match of the query, for <mark> highlighting. */
  pre: string;
  hit: string;
  post: string;
}

export interface SwitcherGroup {
  label: string;
  count: number;
  rows: SwitcherRow[];
}

export interface SwitcherModel {
  groups: SwitcherGroup[];
  /** Flat order used for keyboard movement. */
  order: string[];
  hiddenCount: number;
  noMatch: boolean;
}

/** Empty profiles and machine-made scratch/test profiles are tucked away by default. */
export function isNoise(name: string, count: number): boolean {
  return count === 0 || /^scratch-|-test-|-task-|^\d{4}-\d{2}-\d{2}-/.test(name);
}

function row(name: string, count: number, query: string): SwitcherRow {
  const at = query ? name.toLowerCase().indexOf(query) : -1;
  if (at < 0) return { name, count, pre: name, hit: '', post: '' };
  return { name, count, pre: name.slice(0, at), hit: name.slice(at, at + query.length), post: name.slice(at + query.length) };
}

export function buildSwitcher(
  profiles: ProfileSummary[],
  rawQuery: string,
  pinned: string[],
  recent: string[],
  current: string,
  showHidden: boolean
): SwitcherModel {
  const query = rawQuery.trim().toLowerCase();
  const counts = new Map(profiles.map(p => [p.name, p.memoryCount]));
  const groups: SwitcherGroup[] = [];
  let hiddenCount = 0;

  if (query) {
    const matches = profiles.filter(p => p.name.toLowerCase().includes(query));
    matches.sort((a, b) => {
      const prefix = Number(!a.name.toLowerCase().startsWith(query)) - Number(!b.name.toLowerCase().startsWith(query));
      return prefix || b.memoryCount - a.memoryCount || a.name.localeCompare(b.name);
    });
    if (matches.length) groups.push({ label: 'Matches', count: matches.length, rows: matches.map(p => row(p.name, p.memoryCount, query)) });
  } else {
    const pins = pinned.filter(n => counts.has(n));
    if (pins.length) groups.push({ label: 'Pinned', count: pins.length, rows: pins.map(n => row(n, counts.get(n) ?? 0, '')) });
    const recents = recent.filter(n => counts.has(n) && !pins.includes(n) && n !== current).slice(0, 3);
    if (recents.length) groups.push({ label: 'Recent', count: recents.length, rows: recents.map(n => row(n, counts.get(n) ?? 0, '')) });
    const rest = profiles
      .filter(p => !pins.includes(p.name) && !recents.includes(p.name))
      .sort((a, b) => a.name.localeCompare(b.name));
    const visible = rest.filter(p => showHidden || !isNoise(p.name, p.memoryCount) || p.name === current);
    hiddenCount = rest.length - rest.filter(p => !isNoise(p.name, p.memoryCount) || p.name === current).length;
    groups.push({ label: 'All profiles', count: profiles.length, rows: visible.map(p => row(p.name, p.memoryCount, '')) });
  }

  return {
    groups,
    order: groups.flatMap(g => g.rows.map(r => r.name)),
    hiddenCount,
    noMatch: Boolean(query) && groups.length === 0
  };
}
