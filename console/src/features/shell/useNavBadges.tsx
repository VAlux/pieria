import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { auditFacets } from '../../api/audit';
import { qk } from '../../api/queries';
import { integer } from '../../lib/format';
import styles from './shell.module.css';

export interface NavBadges {
  wiki?: ReactNode;
  activity?: ReactNode;
  tasks?: ReactNode;
  config?: ReactNode;
}

const DAY = 86_400_000;

/** Live counters beside sidebar items; each comes from a cheap, cached query. */
export function useNavBadges(profile: string): NavBadges {
  const failures = useQuery({
    queryKey: qk.auditFacets(profile, { outcome: 'failure', window: 'day' }),
    queryFn: ({ signal }) => auditFacets({ profile, outcome: 'failure', from: new Date(Date.now() - DAY).toISOString() }, signal),
    refetchInterval: 60_000,
    retry: false
  });
  const failed = failures.data?.facets.outcome?.find(f => f.value === 'failure')?.count ?? 0;
  return {
    activity: failed > 0 ? <span className={styles.badgeFail}>{integer(failed)} failed</span> : undefined
  };
}
