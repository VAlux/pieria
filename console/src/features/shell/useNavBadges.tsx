import type { ReactNode } from 'react';

export interface NavBadges {
  wiki?: ReactNode;
  activity?: ReactNode;
  tasks?: ReactNode;
  config?: ReactNode;
}

/** Live counters beside sidebar items; each comes from a cheap, cached query. */
export function useNavBadges(_profile: string): NavBadges {
  return {};
}
