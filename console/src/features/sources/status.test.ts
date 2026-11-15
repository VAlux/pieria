import { describe, expect, it } from 'vitest';
import { needsAttention, runSummary, statusLabel } from './status';
import { run, source } from './fixtures';

describe('statusLabel', () => {
  it('labelsEachState', () => {
    expect(statusLabel(source({ running: true }), false)).toEqual({ label: 'Running', tone: 'warn' });
    expect(statusLabel(source({ status: 'needs_auth' }), true)).toEqual({ label: 'Needs sign-in', tone: 'fail' });
    expect(statusLabel(source({ status: 'needs_auth' }), false)).toEqual({ label: 'Credentials refused', tone: 'fail' });
    expect(statusLabel(source({ status: 'paused' }), false)).toEqual({ label: 'Paused', tone: 'neutral' });
    expect(statusLabel(source({ lastRun: run({ status: 'failed' }) }), false)).toEqual({ label: 'Failing', tone: 'fail' });
    expect(statusLabel(source(), false)).toEqual({ label: 'Active', tone: 'ok' });
  });
});

describe('needsAttention', () => {
  it('attentionCoversAuthFailuresAndHeldSweeps', () => {
    expect(needsAttention(source({ status: 'needs_auth' }))).toBe(true);
    expect(needsAttention(source({ lastRun: run({ status: 'failed' }) }))).toBe(true);
    expect(needsAttention(source({ sweepBlockedCount: 40 }))).toBe(true);
    expect(needsAttention(source())).toBe(false);
  });
});

describe('runSummary', () => {
  it('runSummaryMentionsDeletionsOnlyWhenThereAreSome', () => {
    expect(runSummary(null)).toBe('Never run');
    expect(runSummary(run())).toBe('Run succeeded · 2 changed of 10 · 1 min ago');
    expect(runSummary(run({ dryRun: true, itemsDeleted: 3 }))).toBe('Dry run succeeded · 2 changed of 10 · 3 deleted · 1 min ago');
  });
});
