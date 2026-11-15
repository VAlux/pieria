import { describe, expect, it } from 'vitest';
import { normalizeProfileName, profileNameProblem } from './profileName';

describe('profile names', () => {
  it('normalizesLikeTheResolver', () => {
    expect(normalizeProfileName('  My Repo_Name!! ')).toBe('my-repo-name');
    expect(normalizeProfileName('--a--b--')).toBe('a-b');
    expect(normalizeProfileName('***')).toBe('');
  });

  it('acceptsOnlyNormalizedNames', () => {
    expect(profileNameProblem('team-notes')).toBeNull();
    expect(profileNameProblem('')).toBe('Enter a name.');
    expect(profileNameProblem('***')).toBe('Use letters, digits and hyphens.');
    expect(profileNameProblem('Team Notes')).toContain('“team-notes”');
  });
});
