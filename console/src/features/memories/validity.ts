import type { FactHistory, Memory, Revision, TemporalValidity } from '../../api/types';

export type VersionState = 'current' | 'superseded' | 'scheduled' | 'corrected' | 'invalidated';

export interface Interval {
  from: number | null;
  to: number | null;
}

export interface Version {
  revision: Revision;
  state: VersionState;
  /** World-time spans this revision is actually in force, after supersession and overrides. */
  spans: Interval[];
  /** When it held in the world, summarised over its spans (or its asserted interval if none). */
  valid: Interval;
  /** When Pieria knew it. */
  known: Interval;
  /** 0-based age, oldest = 0, matching the "rev n" label. */
  rev: number;
  /** True when this revision belongs to the memory being inspected. */
  own: boolean;
}

export type ValidityStatus = 'valid now' | 'ended' | 'scheduled' | 'not valid';

const ms = (value: string | null | undefined): number | null => {
  if (!value) return null;
  const t = Date.parse(value);
  return Number.isNaN(t) ? null : t;
};

function covers(span: Interval, at: number): boolean {
  return (span.from === null || span.from <= at) && (span.to === null || at < span.to);
}

/** Segments are bi-temporal: keep only the resolution Pieria held at `knownAt`. */
function knownAt(segment: TemporalValidity, at: number): boolean {
  const from = ms(segment.recordedFrom);
  const to = ms(segment.recordedTo);
  return (from === null || from <= at) && (to === null || at < to);
}

function spansFor(revision: Revision, segments: TemporalValidity[], known: number): Interval[] {
  return segments
    .filter(s => s.revisionId === revision.revisionId && knownAt(s, known))
    .map(s => ({ from: ms(s.validFrom), to: ms(s.validTo) }))
    .sort((a, b) => (a.from ?? -Infinity) - (b.from ?? -Infinity));
}

function summarise(spans: Interval[], revision: Revision): Interval {
  if (spans.length === 0) return { from: ms(revision.validFrom), to: ms(revision.validTo) };
  const froms = spans.map(s => s.from);
  const tos = spans.map(s => s.to);
  return {
    from: froms.includes(null) ? null : Math.min(...(froms as number[])),
    to: tos.includes(null) ? null : Math.max(...(tos as number[]))
  };
}

function stateOf(revision: Revision, spans: Interval[], now: number): VersionState {
  if (revision.recordedTo) return 'corrected';
  if (revision.invalidated) return 'invalidated';
  if (spans.some(span => covers(span, now))) return 'current';
  if (spans.some(span => span.from !== null && span.from > now)) return 'scheduled';
  if (spans.length === 0) {
    const from = ms(revision.validFrom);
    const to = ms(revision.validTo);
    if (from !== null && from > now) return 'scheduled';
    if (to === null || to > now) return 'superseded';
  }
  return 'superseded';
}

/** Versions newest-known first, as the detail panel lists them. */
export function buildVersions(history: FactHistory | null | undefined, memory: Memory, now: number = Date.now(),
                              known: number = now): Version[] {
  const revisions = history?.revisions?.length ? history.revisions : (memory.assertions ?? []);
  const segments = history?.segments?.length ? history.segments : (memory.validity ?? []);
  const oldestFirst = [...revisions].sort((a, b) => (ms(a.recordedFrom) ?? 0) - (ms(b.recordedFrom) ?? 0));
  const versions = oldestFirst.map((revision, index) => {
    const spans = spansFor(revision, segments, known);
    return {
      revision,
      state: stateOf(revision, spans, now),
      spans,
      valid: summarise(spans, revision),
      known: { from: ms(revision.recordedFrom), to: ms(revision.recordedTo) },
      rev: index,
      own: revision.memoryId === memory.id
    };
  });
  return versions.reverse();
}

/** The memory's own standing right now, from the spans of its own revisions. */
export function validityStatus(versions: Version[], memory: Memory, now: number = Date.now()): ValidityStatus {
  const own = versions.filter(v => v.own && v.state !== 'corrected');
  const spans = own.length
    ? own.flatMap(v => v.spans)
    : (memory.validity ?? []).map(s => ({ from: ms(s.validFrom), to: ms(s.validTo) }));
  if (spans.some(span => covers(span, now))) return 'valid now';
  if (spans.some(span => span.from !== null && span.from > now)) return 'scheduled';
  if (spans.length) return 'ended';
  return memory.superseded ? 'ended' : 'not valid';
}

/** The revision a correction or invalidation should build on: this memory's live one. */
export function editableRevision(versions: Version[]): Revision | null {
  const live = versions.find(v => v.own && v.state !== 'corrected' && v.state !== 'invalidated');
  return live?.revision ?? null;
}

export interface TrackBar {
  key: string;
  versionIndex: number;
  left: number;
  width: number;
  open: boolean;
  state: VersionState;
  row: number;
}

export interface Track {
  start: number;
  end: number;
  bars: TrackBar[];
  rows: number;
}

/**
 * Lays versions out on a world-time axis ending at `now`: one row per version, oldest at the top,
 * with a little padding before the earliest start so its start dot is visible.
 */
export function buildTrack(versions: Version[], now: number = Date.now()): Track {
  const starts = versions.flatMap(v => (v.spans.length ? v.spans : [v.valid]).map(s => s.from)).filter((t): t is number => t !== null);
  const ends = versions.flatMap(v => (v.spans.length ? v.spans : [v.valid]).map(s => s.to)).filter((t): t is number => t !== null);
  const earliest = starts.length ? Math.min(...starts) : now - 86_400_000;
  const latest = Math.max(now, ...ends);
  const pad = Math.max((latest - earliest) * 0.06, 3_600_000);
  const start = earliest - pad;
  const end = latest;
  const span = end - start || 1;
  const pct = (t: number) => Math.min(100, Math.max(0, ((t - start) / span) * 100));
  const rows = versions.length;
  const bars: TrackBar[] = [];
  versions.forEach((version, index) => {
    const intervals = version.spans.length ? version.spans : [version.valid];
    intervals.forEach((interval, i) => {
      const left = pct(interval.from ?? start);
      const right = interval.to === null ? 100 : pct(interval.to);
      bars.push({
        key: `${version.revision.revisionId}-${i}`,
        versionIndex: index,
        left,
        width: Math.max(right - left, 1.5),
        open: interval.to === null,
        state: version.state,
        row: rows - 1 - index
      });
    });
  });
  return { start, end, bars, rows };
}
