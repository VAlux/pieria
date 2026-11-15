/**
 * Profile names as the CLI and harnesses derive them (ProfileResolver.normalize in modules/shared):
 * lower-case letters, digits and single hyphens, with no leading or trailing hyphen. Names typed in
 * the console follow the same rule so a profile made here matches the one an agent would write to.
 */
export function normalizeProfileName(input: string): string {
  return input.trim().toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/-{2,}/g, '-').replace(/^-+|-+$/g, '');
}

/** Why `name` can't be used as a profile name, or null when it can. */
export function profileNameProblem(name: string): string | null {
  if (!name.trim()) return 'Enter a name.';
  const normalized = normalizeProfileName(name);
  if (!normalized) return 'Use letters, digits and hyphens.';
  if (normalized !== name) return `Use lower-case letters, digits and hyphens, e.g. “${normalized}”.`;
  return null;
}
