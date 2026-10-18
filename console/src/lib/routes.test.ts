import { describe, expect, it } from 'vitest';
import { legacyTarget, profileUrl, subPath, switchedPath } from './routes';

describe('profile urls', () => {
  it('encodes the profile and joins sub paths', () => {
    expect(profileUrl('a b')).toBe('/p/a%20b');
    expect(profileUrl('pieria', '/memories')).toBe('/p/pieria/memories');
    expect(subPath('/p/pieria/memories/abc')).toBe('memories/abc');
    expect(subPath('/p/pieria')).toBe('');
  });

  it('keeps the section but drops item ids when switching profiles', () => {
    expect(switchedPath('/p/pieria/memories/abc', 'other')).toBe('/p/other/memories');
    expect(switchedPath('/p/pieria/wiki/topic-1', 'other')).toBe('/p/other/wiki');
    expect(switchedPath('/p/pieria', 'other')).toBe('/p/other');
  });
});

describe('legacy links', () => {
  it('maps old views onto console routes', () => {
    expect(legacyTarget('?view=audit&profile=pieria', null)).toBe('/p/pieria/activity');
    expect(legacyTarget('?view=global-config&profile=pieria', null)).toBe('/p/pieria/settings');
    expect(legacyTarget('?view=wiki&profile=pieria&page=topic%3A1', null)).toBe('/p/pieria/wiki/topic%3A1');
    expect(legacyTarget('?view=clusters&profile=p&cluster=c1', null)).toBe('/p/p/graph?view=clusters&cluster=c1');
    expect(legacyTarget('?view=stats', 'last')).toBe('/p/last');
  });

  it('ignores plain visits', () => {
    expect(legacyTarget('', 'last')).toBeNull();
  });
});
