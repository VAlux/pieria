/** URL helpers. Every console route is profile-scoped: /p/:profile/... under the /console basename. */

export function profileRoot(profile: string): string {
  return `/p/${encodeURIComponent(profile)}`;
}

export function profileUrl(profile: string, sub = ''): string {
  const clean = sub.replace(/^\/+/, '');
  return clean ? `${profileRoot(profile)}/${clean}` : profileRoot(profile);
}

/** Path below the profile segment, e.g. "memories/abc" for /p/pieria/memories/abc. */
export function subPath(pathname: string): string {
  const match = /^\/p\/[^/]+\/?(.*)$/.exec(pathname);
  return match ? match[1] : '';
}

/**
 * Keeps the user on the same kind of screen when switching profiles; drops item ids that only
 * make sense inside the old profile (a memory, a wiki page, an audit event).
 */
export function switchedPath(pathname: string, profile: string): string {
  const section = subPath(pathname).split('/')[0] ?? '';
  const keep = ['memories', 'recall', 'activity', 'tasks', 'wiki', 'graph', 'config', 'settings'];
  return profileUrl(profile, keep.includes(section) ? section : '');
}

const LEGACY_VIEWS: Record<string, string> = {
  memories: 'memories',
  add: 'memories/new',
  recall: 'recall',
  stats: '',
  audit: 'activity',
  graph: 'graph',
  wiki: 'wiki',
  clusters: 'graph?view=clusters',
  'profile-config': 'config',
  'global-config': 'settings'
};

/** Maps the legacy console's `?view=&profile=&page=&cluster=` links onto console routes. */
export function legacyTarget(search: string, fallbackProfile: string | null): string | null {
  const params = new URLSearchParams(search);
  const view = params.get('view');
  const profile = params.get('profile') || fallbackProfile;
  if (!view && !params.get('profile')) return null;
  if (!profile) return null;
  let sub = view && view in LEGACY_VIEWS ? LEGACY_VIEWS[view] : '';
  const page = params.get('page');
  if (view === 'wiki' && page) sub = `wiki/${encodeURIComponent(page)}`;
  const cluster = params.get('cluster');
  if (view === 'clusters' && cluster) sub += `&cluster=${encodeURIComponent(cluster)}`;
  return profileUrl(profile, sub);
}
