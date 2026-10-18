import { describe, expect, it } from 'vitest';
import type { FactHistory, Memory, Revision, TemporalValidity } from '../../api/types';
import { buildTrack, buildVersions, editableRevision, validityStatus } from './validity';

const revision = (id: string, memoryId: string, validFrom: string, recordedFrom: string, extra: Partial<Revision> = {}): Revision => ({
  assertionId: `a-${memoryId}`, revisionId: id, memoryId, content: `content ${id}`, validFrom, validTo: null,
  statedAt: validFrom, recordedFrom, recordedTo: null, corrects: null, sourceOccurrence: null, invalidated: false,
  factGroup: 'fact:k', sourcePayload: null, conflictEvidence: null, ...extra
});

const segment = (revisionId: string, validFrom: string, validTo: string | null, recordedFrom: string, recordedTo: string | null): TemporalValidity => ({
  assertionId: 'a', revisionId, validFrom, validTo, recordedFrom, recordedTo, statedAt: null, sourceOccurrence: null, factGroup: 'fact:k', sourcePayload: null
});

const memory = { id: 'new', type: 'fact', content: 'new', topicKey: 'k', sessionId: null, superseded: false, payload: null, createdAt: null } as Memory;

// "old" was true from 1 Oct; "new" replaced it on 10 Oct. Before 10 Oct was recorded, old was open-ended.
const history: FactHistory = {
  factGroup: 'fact:k', selectedRevision: null, selectedValidity: null,
  revisions: [revision('r-old', 'old', '2026-10-01T00:00:00Z', '2026-10-01T00:00:00Z'), revision('r-new', 'new', '2026-10-10T00:00:00Z', '2026-10-10T00:00:00Z')],
  segments: [
    segment('r-old', '2026-10-01T00:00:00Z', null, '2026-10-01T00:00:00Z', '2026-10-10T00:00:00Z'),
    segment('r-old', '2026-10-01T00:00:00Z', '2026-10-10T00:00:00Z', '2026-10-10T00:00:00Z', null),
    segment('r-new', '2026-10-10T00:00:00Z', null, '2026-10-10T00:00:00Z', null)
  ],
  temporal: { asOf: null, knownAsOf: null, snapshotSequence: 1 }
};

const now = Date.parse('2026-10-18T00:00:00Z');

describe('buildVersions', () => {
  it('lists newest first and resolves states from the current knowledge only', () => {
    const versions = buildVersions(history, memory, now);
    expect(versions.map(v => [v.revision.revisionId, v.state, v.rev, v.own])).toEqual([
      ['r-new', 'current', 1, true],
      ['r-old', 'superseded', 0, false]
    ]);
    expect(versions[1].valid).toEqual({ from: Date.parse('2026-10-01T00:00:00Z'), to: Date.parse('2026-10-10T00:00:00Z') });
    expect(validityStatus(versions, memory, now)).toBe('valid now');
    expect(editableRevision(versions)?.revisionId).toBe('r-new');
  });

  it('shows what was believed at an earlier knowledge cutoff', () => {
    const before = Date.parse('2026-10-05T00:00:00Z');
    const versions = buildVersions(history, memory, before, before);
    expect(versions.find(v => v.revision.revisionId === 'r-old')?.state).toBe('current');
    expect(versions.find(v => v.revision.revisionId === 'r-new')?.spans).toEqual([]);
  });

  it('marks corrected revisions', () => {
    const corrected = { ...history, revisions: [revision('r-x', 'new', '2026-10-10T00:00:00Z', '2026-10-10T00:00:00Z', { recordedTo: '2026-10-11T00:00:00Z' })], segments: [] };
    expect(buildVersions(corrected, memory, now)[0].state).toBe('corrected');
    expect(editableRevision(buildVersions(corrected, memory, now))).toBeNull();
  });
});

describe('buildTrack', () => {
  it('places the newest version on the bottom row and ends at now', () => {
    const track = buildTrack(buildVersions(history, memory, now), now);
    expect(track.end).toBe(now);
    const newest = track.bars.find(b => b.versionIndex === 0)!;
    expect(newest.row).toBe(1);
    expect(newest.open).toBe(true);
    const oldest = track.bars.find(b => b.versionIndex === 1)!;
    expect(oldest.open).toBe(false);
    expect(oldest.left).toBeGreaterThan(0);
    expect(oldest.left + oldest.width).toBeLessThan(newest.left + 1);
  });
});
