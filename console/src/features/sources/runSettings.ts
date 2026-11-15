import { parseInterval } from './form';

export interface RunSettings {
  mode: 'full' | 'raw';
  interval: string;
  maxItems: string;
  maxChars: string;
}

export interface ParsedRunSettings {
  mode: 'full' | 'raw';
  intervalSeconds?: number;
  maxItemsPerRun: number | null;
  maxCharsPerRun: number | null;
  errors: Record<string, string>;
}

function limit(text: string, key: string, errors: Record<string, string>): number | null {
  const stripped = text.trim();
  if (stripped === '') return null;
  if (!/^\d+$/.test(stripped) || Number(stripped) < 1) {
    errors[key] = 'Use a positive whole number, or leave it empty for no limit';
    return null;
  }
  return Number(stripped);
}

/** Mode, interval and limits from the form, with an error per field that doesn't parse. */
export function parseRunSettings(settings: RunSettings): ParsedRunSettings {
  const errors: Record<string, string> = {};
  const interval = parseInterval(settings.interval);
  if (interval.error) errors.interval = interval.error;
  return {
    mode: settings.mode,
    intervalSeconds: interval.seconds,
    maxItemsPerRun: limit(settings.maxItems, 'maxItems', errors),
    maxCharsPerRun: limit(settings.maxChars, 'maxChars', errors),
    errors
  };
}
