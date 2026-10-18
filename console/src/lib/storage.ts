/** Per-browser conveniences. Storage can be unavailable (private mode), so every access is guarded. */

const PREFIX = 'pieria.console.';

export function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    return raw === null ? fallback : (JSON.parse(raw) as T);
  } catch {
    return fallback;
  }
}

export function writeJson(key: string, value: unknown): void {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(value));
  } catch {
    // Storage full or disabled: the feature simply does not persist.
  }
}

export const keys = {
  pinned: 'pinnedProfiles',
  recent: 'recentProfiles',
  lastProfile: 'lastProfile',
  recentQuestions: (profile: string) => `recentQuestions.${profile}`,
  dismissed: (tip: string) => `dismissed.${tip}`
};

export function pinnedProfiles(): string[] {
  return readJson<string[]>(keys.pinned, []);
}

export function togglePinned(name: string): string[] {
  const current = pinnedProfiles();
  const next = current.includes(name) ? current.filter(n => n !== name) : [...current, name];
  writeJson(keys.pinned, next);
  return next;
}

export function recentProfiles(): string[] {
  return readJson<string[]>(keys.recent, []);
}

/** Records a visit; the most recent profile comes first and the list is capped. */
export function rememberProfile(name: string): void {
  const previous = recentProfiles().filter(n => n !== name);
  writeJson(keys.recent, [name, ...previous].slice(0, 8));
  writeJson(keys.lastProfile, name);
}

export function lastProfile(): string | null {
  return readJson<string | null>(keys.lastProfile, null);
}

/** The profile visited before the current one, for ⌘⇧P. */
export function previousProfile(current: string): string | null {
  return recentProfiles().find(n => n !== current) ?? null;
}

export function recentQuestions(profile: string): string[] {
  return readJson<string[]>(keys.recentQuestions(profile), []);
}

export function rememberQuestion(profile: string, question: string): string[] {
  const next = [question, ...recentQuestions(profile).filter(q => q !== question)].slice(0, 8);
  writeJson(keys.recentQuestions(profile), next);
  return next;
}

export function isDismissed(tip: string): boolean {
  return readJson<boolean>(keys.dismissed(tip), false);
}

export function dismiss(tip: string): void {
  writeJson(keys.dismissed(tip), true);
}
