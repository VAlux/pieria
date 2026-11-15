import type { HarvesterField } from '../../api/sources';

/** What the form holds per field: the typed text, a bool for switches. A list is one item per line. */
export type FieldValues = Record<string, string | boolean>;

/** The same rule as the daemon's `MemoryOrigin.SOURCE_ID_REGEX`. */
export const SOURCE_ID_PATTERN = /^[a-z0-9][a-z0-9_-]{0,63}$/;
export const SOURCE_ID_ERROR = 'Use lowercase letters, digits, - and _ (start with a letter or digit)';

const MIN_INTERVAL_SECONDS = 60;

function text(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (Array.isArray(value)) return value.map(String).join('\n');
  return String(value);
}

/** The form's starting values: the stored config, else each field's default. Secrets always start empty. */
export function initialValues(fields: HarvesterField[], config: Record<string, unknown> = {}): FieldValues {
  const values: FieldValues = {};
  for (const field of fields) {
    if (field.kind === 'secret') {
      values[field.key] = '';
      continue;
    }
    const stored = config[field.key];
    const value = stored !== undefined && stored !== null ? stored : field.defaultValue;
    values[field.key] = field.kind === 'bool' ? value === true || value === 'true' : text(value);
  }
  return values;
}

export interface ParsedValues {
  config: Record<string, unknown>;
  secrets: Record<string, string>;
  errors: Record<string, string>;
}

/**
 * Turns form values into the request's config and secrets. Blank fields are left out, so the
 * harvester's own default applies; secrets never go into the config.
 */
export function parseValues(fields: HarvesterField[], values: FieldValues, options: { secretOptional?: boolean } = {}): ParsedValues {
  const config: Record<string, unknown> = {};
  const secrets: Record<string, string> = {};
  const errors: Record<string, string> = {};
  for (const field of fields) {
    const raw = values[field.key];
    if (field.kind === 'bool') {
      config[field.key] = raw === true;
      continue;
    }
    const typed = typeof raw === 'string' ? raw : '';
    const stripped = typed.trim();
    switch (field.kind) {
      case 'secret':
        if (typed.length > 0) secrets[field.key] = typed;
        else if (field.required && !options.secretOptional) errors[field.key] = `${field.label} is required`;
        continue;
      case 'int':
        if (stripped === '') break;
        if (!/^-?\d+$/.test(stripped)) {
          errors[field.key] = `${field.label} must be a whole number`;
          continue;
        }
        config[field.key] = Number(stripped);
        break;
      case 'enum':
        if (stripped === '') break;
        if (!field.options.includes(stripped)) {
          errors[field.key] = `${field.label} must be one of ${field.options.join(', ')}`;
          continue;
        }
        config[field.key] = stripped;
        break;
      case 'list': {
        const items = typed.split(/[\n,]/).map(item => item.trim()).filter(Boolean);
        if (items.length > 0) config[field.key] = items;
        break;
      }
      default:
        if (stripped !== '') config[field.key] = stripped;
    }
    if (field.required && !(field.key in config)) errors[field.key] = `${field.label} is required`;
  }
  return { config, secrets, errors };
}

/** The config keys an edit changed. A removed key becomes null, which the daemon's merge clears. Secrets are never included. */
export function configChanges(fields: HarvesterField[], before: Record<string, unknown>, after: Record<string, unknown>): Record<string, unknown> {
  const secretKeys = new Set(fields.filter(f => f.kind === 'secret').map(f => f.key));
  const changes: Record<string, unknown> = {};
  for (const key of new Set([...Object.keys(before), ...Object.keys(after)])) {
    if (secretKeys.has(key)) continue;
    const next = key in after ? after[key] : null;
    const previous = key in before ? before[key] : null;
    if (JSON.stringify(previous) !== JSON.stringify(next)) changes[key] = next;
  }
  return changes;
}

/** Seconds from `90`, `90s`, `30m`, `6h` or `1d`: what the CLI accepts, plus the `s` that `formatInterval` writes. */
export function parseInterval(value: string): { seconds?: number; error?: string } {
  const match = /^(\d+)([smhd]?)$/.exec(value.trim());
  if (!match) return { error: 'Use a form like 30m, 6h or 1d' };
  const unit = { '': 1, s: 1, m: 60, h: 3_600, d: 86_400 }[match[2] as '' | 's' | 'm' | 'h' | 'd'];
  const seconds = Number(match[1]) * unit;
  if (seconds < MIN_INTERVAL_SECONDS) return { error: 'The interval must be at least 60 seconds' };
  return { seconds };
}

/** The largest whole unit: `1d`, `6h`, `30m`, else seconds. */
export function formatInterval(seconds: number): string {
  if (seconds > 0 && seconds % 86_400 === 0) return `${seconds / 86_400}d`;
  if (seconds > 0 && seconds % 3_600 === 0) return `${seconds / 3_600}h`;
  if (seconds > 0 && seconds % 60 === 0) return `${seconds / 60}m`;
  return `${seconds}s`;
}
