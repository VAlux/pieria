import { Link } from 'react-router';
import { canSignIn, type Harvester, type Source } from '../../api/sources';
import { dateTime, integer, relativeTime } from '../../lib/format';
import { profileUrl } from '../../lib/routes';
import { LinkButton } from '../../ui/controls';
import { Badge, Card, CardHeader, EmptyState, ErrorBanner, Page, PageHeader, SkeletonRows } from '../../ui/display';
import { useProfile } from '../shell/context';
import { harvesterFor, useHarvesters, useSources } from './queries';
import { runSummary, statusLabel } from './status';
import styles from './sources.module.css';

function SourceRow({ profile, source, harvester }: { profile: string; source: Source; harvester: Harvester | undefined }) {
  const signIn = canSignIn(harvester);
  const status = statusLabel(source, signIn);
  const detail = profileUrl(profile, `sources/${encodeURIComponent(source.id)}`);
  return (
    <li className={styles.row}>
      <div className={styles.rowMain}>
        <Link to={detail} className={styles.sourceId}>{source.id}</Link>
        <Badge tone={status.tone} sans>{status.label}</Badge>
        {source.mode === 'raw' && <Badge tone="neutral" sans>raw</Badge>}
        <span className={styles.harvester}>
          {harvester ? harvester.name : <>{source.harvester} <span className={styles.missing}>not installed</span></>}
        </span>
      </div>
      <div className={styles.rowMeta}>
        <span>{runSummary(source.lastRun)}</span>
        {source.status === 'active' && source.nextRunAt && (
          <span title={dateTime(source.nextRunAt)}>next {relativeTime(source.nextRunAt)}</span>
        )}
        {source.sweepBlockedCount !== null && (
          <span className={styles.warn}>Deletions held back: {integer(source.sweepBlockedCount)}</span>
        )}
        {source.status === 'needs_auth' && (signIn
          ? <Link to={`${detail}?signin=1`}>Sign in</Link>
          : <Link to={`${detail}?secrets=1`}>Replace credentials</Link>)}
      </div>
    </li>
  );
}

function HarvesterList({ harvesters }: { harvesters: Harvester[] }) {
  return (
    <Card>
      <CardHeader title="Installed harvesters" sub={`${harvesters.length}`} />
      <ul className={styles.list}>
        {harvesters.map(h => (
          <li key={h.id} className={styles.row}>
            <div className={styles.rowMain}>
              <strong>{h.name}</strong>
              <span className={styles.mono}>{h.id} {h.version}</span>
              {h.bundled && <Badge tone="info" sans>bundled</Badge>}
              {h.capabilities.map(c => <Badge key={c} tone="neutral">{c}</Badge>)}
            </div>
            {h.network.length > 0 && <div className={styles.rowMeta}>Talks to {h.network.join('; ')}</div>}
          </li>
        ))}
      </ul>
      <p className={styles.note}>
        Install or remove harvesters with <code>pieria harvester install</code> and <code>pieria harvester remove</code>.
      </p>
    </Card>
  );
}

/** Every source of the profile, and the harvesters they can use. */
export function SourcesPage() {
  const profile = useProfile();
  const sources = useSources(profile);
  const harvesters = useHarvesters();
  const add = <LinkButton to={profileUrl(profile, 'sources/new')} variant="primary">Add source</LinkButton>;
  const sorted = [...(sources.data ?? [])].sort((a, b) => a.id.localeCompare(b.id));

  return (
    <Page>
      <PageHeader crumb="Operations" title="Sources" actions={add} />
      {sources.isError && <ErrorBanner error={sources.error} />}
      {sources.isPending ? <SkeletonRows rows={3} /> : sorted.length === 0 ? (
        <EmptyState title="No sources yet" actions={add}>
          A source brings knowledge in from outside, such as a website, Jira, Confluence or Teams
          {harvesters.data && harvesters.data.length > 0 && <> ({harvesters.data.map(h => h.name).join(', ')} are installed)</>}.
          Memories from a source are labelled as untrusted in recall.
        </EmptyState>
      ) : (
        <Card flush>
          <ul className={styles.list}>
            {sorted.map(s => <SourceRow key={s.id} profile={profile} source={s} harvester={harvesterFor(harvesters.data, s.harvester)} />)}
          </ul>
        </Card>
      )}
      {harvesters.isError && <ErrorBanner error={harvesters.error} />}
      {harvesters.data && <HarvesterList harvesters={harvesters.data} />}
    </Page>
  );
}
