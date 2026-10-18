import { describe, expect, it } from 'vitest';
import { compact, duration, elapsed, integer, relativeTime } from './format';

describe('numbers', () => {
  it('formats counts the way the canvas does', () => {
    expect(integer(4940)).toBe('4,940');
    expect(compact(4940)).toBe('4,940');
    expect(compact(51_644)).toBe('51.6k');
    expect(compact(2_100_000)).toBe('2.10M');
  });
});

describe('durations', () => {
  it('uses ms, then seconds, then minutes', () => {
    expect(duration(142)).toBe('142 ms');
    expect(duration(48_883)).toBe('48.9 s');
    expect(duration(125_000)).toBe('2m 05s');
    expect(duration(null)).toBe('—');
  });

  it('formats running clocks', () => {
    expect(elapsed(41_000)).toBe('00:41');
    expect(elapsed(3_661_000)).toBe('1:01:01');
  });
});

describe('relativeTime', () => {
  const now = Date.parse('2026-10-18T10:00:00Z');
  it('rounds to the largest sensible unit', () => {
    expect(relativeTime('2026-10-18T09:59:30Z', now)).toBe('just now');
    expect(relativeTime('2026-10-18T09:40:00Z', now)).toBe('20 min ago');
    expect(relativeTime('2026-10-17T22:00:00Z', now)).toBe('12 h ago');
    expect(relativeTime('2026-10-15T10:00:00Z', now)).toBe('3 d ago');
    expect(relativeTime(null, now)).toBe('—');
  });
});
