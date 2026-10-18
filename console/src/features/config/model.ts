import type { ConfigField, Overrides } from '../../api/config';

/** `{retrieval: {"rrf-k": 60}}` → `{"retrieval.rrf-k": 60}`, matching the schema's dotted keys. */
export function flatten(overrides: Overrides | null | undefined): Record<string, unknown> {
  const flat: Record<string, unknown> = {};
  for (const [section, values] of Object.entries(overrides ?? {})) {
    for (const [key, value] of Object.entries(values ?? {})) {
      if (value !== null && value !== undefined) flat[`${section}.${key}`] = value;
    }
  }
  return flat;
}

export function unflatten(flat: Record<string, unknown>): Overrides {
  const nested: Overrides = {};
  for (const [dotted, value] of Object.entries(flat)) {
    const dot = dotted.indexOf('.');
    const section = dotted.slice(0, dot);
    (nested[section] ??= {})[dotted.slice(dot + 1)] = value;
  }
  return nested;
}

/** Keys whose value differs between two flat maps, including keys present in only one. */
export function changedKeys(a: Record<string, unknown>, b: Record<string, unknown>): string[] {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  return [...keys].filter(k => !sameValue(a[k], b[k]));
}

function sameValue(a: unknown, b: unknown): boolean {
  if (a === undefined || b === undefined) return a === b;
  return String(a) === String(b);
}

/** Turns typed text into the value the daemon expects for this field kind, or explains why not. */
export function parseField(field: Pick<ConfigField, 'kind' | 'options' | 'label'>, text: string): { value?: unknown; error?: string } {
  const trimmed = text.trim();
  switch (field.kind) {
    case 'int': {
      if (!/^-?\d+$/.test(trimmed)) return { error: `${field.label} must be a whole number` };
      return { value: Number(trimmed) };
    }
    case 'double':
    case 'weight': {
      const n = Number(trimmed);
      if (trimmed === '' || Number.isNaN(n)) return { error: `${field.label} must be a number` };
      if (field.kind === 'weight' && n < 0) return { error: `${field.label} cannot be negative` };
      return { value: n };
    }
    case 'bool':
      if (trimmed !== 'true' && trimmed !== 'false') return { error: `${field.label} must be true or false` };
      return { value: trimmed === 'true' };
    case 'enum':
      if (!field.options?.includes(trimmed)) return { error: `${field.label} must be one of ${field.options?.join(', ')}` };
      return { value: trimmed };
    default:
      return { value: text };
  }
}

/** Compact summary of a collapsed section: `depth 2 · fanout 20 · seeds 8`. */
export function summarize(fields: ConfigField[], values: Record<string, unknown>, limit = 4): string {
  return fields.slice(0, limit).map(f => {
    const label = f.label.toLowerCase().replace(/\s*\(.*\)$/, '');
    const value = values[f.key];
    if (typeof value === 'boolean') return `${label} ${value ? 'on' : 'off'}`;
    return `${label} ${String(value ?? '—').toLowerCase()}`;
  }).join(' · ');
}
