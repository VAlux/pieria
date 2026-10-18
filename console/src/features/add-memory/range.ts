/**
 * The "When is it true?" slider works in whole days: 30 days back to 15 days ahead, where step 30
 * is now, step 0 means "earlier than that" and step 45 means open-ended.
 */
export const NOW_STEP = 30;
export const MAX_STEP = 45;
const DAY = 86_400_000;

export const PRESETS = [
  { label: 'From now', from: NOW_STEP, to: MAX_STEP },
  { label: 'Since last week', from: NOW_STEP - 7, to: MAX_STEP },
  { label: 'Today only', from: NOW_STEP, to: NOW_STEP + 1 },
  { label: 'Only in the past', from: NOW_STEP - 14, to: NOW_STEP }
];

function startOfToday(now: Date): number {
  const d = new Date(now);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** Local midnight of the day the step stands for. */
export function stepDate(step: number, now: Date = new Date()): Date {
  return new Date(startOfToday(now) + (step - NOW_STEP) * DAY);
}

function label(step: number, now: Date): string {
  return stepDate(step, now).toLocaleDateString('en-GB', { day: '2-digit', month: 'short' });
}

export function rangeLabels(from: number, to: number, now: Date = new Date()): { from: string; to: string; span: string } {
  const fromLabel = from === 0 ? `before ${label(0, now)}` : from === NOW_STEP ? 'now' : label(from, now);
  const toLabel = to === MAX_STEP ? 'still true' : to === NOW_STEP ? 'now' : label(to, now);
  const days = to - from;
  const span = to === MAX_STEP ? 'open-ended' : days <= 0 ? 'a single moment' : `${days} ${days === 1 ? 'day' : 'days'}`;
  return { from: fromLabel, to: toLabel, span };
}

/**
 * Maps the slider onto the daemon's validity interval. "Now" and "open-ended" are sent as null so
 * the daemon applies its own clock and keeps the fact open; other steps become local midnights.
 */
export function rangeToValidity(from: number, to: number, now: Date = new Date()): { validFrom: string | null; validTo: string | null } {
  const validFrom = from === NOW_STEP ? null : stepDate(from, now).toISOString();
  let validTo: string | null = null;
  if (to === NOW_STEP) validTo = now.toISOString();
  else if (to !== MAX_STEP) validTo = stepDate(to, now).toISOString();
  return { validFrom, validTo };
}
