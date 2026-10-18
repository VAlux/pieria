import { describe, expect, it } from 'vitest';
import { buildSwitcher, isNoise } from './switcher';

const profiles = [
  { name: 'pieria', createdAt: null, memoryCount: 4940 },
  { name: 'codemie', createdAt: null, memoryCount: 1097 },
  { name: 'codemie-dev', createdAt: null, memoryCount: 21066 },
  { name: 'scratch-2026-10-12-96ae01', createdAt: null, memoryCount: 10 },
  { name: 'scripts', createdAt: null, memoryCount: 0 },
  { name: 'evaluation-kit', createdAt: null, memoryCount: 1497 }
];

describe('isNoise', () => {
  it('flags empty and machine-made profiles', () => {
    expect(isNoise('scripts', 0)).toBe(true);
    expect(isNoise('scratch-x', 3)).toBe(true);
    expect(isNoise('codemie-test-e602ba', 1)).toBe(true);
    expect(isNoise('2026-10-15-current-time-function', 5)).toBe(true);
    expect(isNoise('pieria', 4940)).toBe(false);
  });
});

describe('buildSwitcher', () => {
  it('groups pinned, recent and the rest, hiding noise', () => {
    const model = buildSwitcher(profiles, '', ['pieria'], ['pieria', 'codemie', 'evaluation-kit'], 'pieria', false);
    expect(model.groups.map(g => g.label)).toEqual(['Pinned', 'Recent', 'All profiles']);
    expect(model.groups[1].rows.map(r => r.name)).toEqual(['codemie', 'evaluation-kit']);
    expect(model.groups[2].rows.map(r => r.name)).toEqual(['codemie-dev']);
    expect(model.hiddenCount).toBe(2);
    expect(model.order[0]).toBe('pieria');
  });

  it('shows hidden profiles on request', () => {
    const model = buildSwitcher(profiles, '', [], [], 'pieria', true);
    expect(model.groups[0].rows).toHaveLength(profiles.length);
  });

  it('ranks prefix matches first, then by size, and splits the highlight', () => {
    const model = buildSwitcher(profiles, 'code', [], [], 'pieria', false);
    expect(model.groups[0].rows.map(r => r.name)).toEqual(['codemie-dev', 'codemie']);
    expect(model.groups[0].rows[0]).toMatchObject({ pre: '', hit: 'code', post: 'mie-dev' });
  });

  it('reports no match', () => {
    expect(buildSwitcher(profiles, 'zzz', [], [], 'pieria', false).noMatch).toBe(true);
  });
});
