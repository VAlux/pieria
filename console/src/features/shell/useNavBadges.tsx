import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { auditFacets } from '../../api/audit';
import { profileConfig } from '../../api/config';
import { listTasks } from '../../api/tasks';
import { wikiStatus } from '../../api/wiki';
import { flatten } from '../config/model';
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
  const tasks = useQuery({
    queryKey: qk.tasks(null),
    queryFn: ({ signal }) => listTasks(null, signal),
    refetchInterval: query => (query.state.data?.some(t => t.status === 'RUNNING') ? 2_000 : 15_000)
  });
  const config = useQuery({ queryKey: qk.profileConfig(profile), queryFn: ({ signal }) => profileConfig(profile, signal), retry: false });
  const overrides = config.data ? Object.keys(flatten(config.data.overrides)).length : 0;
  const wiki = useQuery({ queryKey: qk.wikiStatus(profile), queryFn: ({ signal }) => wikiStatus(profile, signal), retry: false, refetchInterval: 60_000 });
  const w = wiki.data;
  const failed = failures.data?.facets.outcome?.find(f => f.value === 'failure')?.count ?? 0;
  const running = tasks.data?.filter(t => t.status === 'RUNNING').length ?? 0;
  return {
    wiki: w && w.state.enabled
      ? w.articlesReady === 0 ? <span className={styles.badgeWarn}>not built</span>
        : w.articlesStale > 0 ? <span className={styles.badgeNote}>{integer(w.articlesStale)} stale</span> : undefined
      : undefined,
    activity: failed > 0 ? <span className={styles.badgeFail}>{integer(failed)} failed</span> : undefined,
    tasks: running > 0 ? <span className={styles.badgeRunning}>{integer(running)} running</span> : undefined,
    config: overrides > 0 ? <span className={styles.badgeNote}>{integer(overrides)} override{overrides === 1 ? '' : 's'}</span> : undefined
  };
}
