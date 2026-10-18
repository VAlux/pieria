import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError, errorMessage } from '../../api/client';
import { qk } from '../../api/queries';
import {
  buildWiki, evaluateWikiPage, wikiDirectory, wikiJob, wikiPage, wikiSearch, wikiStatus,
  type WikiCard, type WikiPage as WikiPageData
} from '../../api/wiki';
import { integer, plural } from '../../lib/format';
import { profileUrl } from '../../lib/routes';
import { Button, LinkButton, SearchField, cx } from '../../ui/controls';
import { Card, EmptyState, ErrorBanner, Page, PageHeader, Pill, SectionLabel, Skeleton, TypeChip } from '../../ui/display';
import { Markdown } from '../../ui/Markdown';
import { Dialog, DisabledHint, useToast } from '../../ui/overlay';
import { useProfile } from '../shell/context';
import styles from './wiki.module.css';

type Freshness = 'fresh' | 'stale' | 'none';

export function freshnessOf(card: Pick<WikiCard, 'generation' | 'freshness'>): Freshness {
  if (card.generation !== 'ready') return 'none';
  return card.freshness === 'current' ? 'fresh' : 'stale';
}

export function WikiPage() {
  const profile = useProfile();
  const navigate = useNavigate();
  const { pageId } = useParams();
  const status = useQuery({ queryKey: qk.wikiStatus(profile), queryFn: ({ signal }) => wikiStatus(profile, signal), refetchInterval: 15_000 });
  const roots = useQuery({ queryKey: qk.wikiDir(profile, null), queryFn: ({ signal }) => wikiDirectory(profile, null, null, signal) });
  const enabled = roots.data?.state.enabled ?? status.data?.state.enabled;

  if (roots.isError) return <Page><PageHeader crumb={<>{profile} / Knowledge</>} title="Wiki" /><ErrorBanner error={roots.error} /></Page>;
  if (enabled === false) {
    return (
      <Page>
        <PageHeader crumb={<>{profile} / Knowledge</>} title="Wiki" />
        <EmptyState title="The wiki is off for this profile"
          actions={<LinkButton variant="primary" to={profileUrl(profile, 'config#section-wiki')}>Enable it in Profile config</LinkButton>}>
          The wiki groups this profile's memories into topic, entity and code pages, and writes an article for each page from
          its evidence. Turn on “Enable profile wiki” to start projecting pages; articles are written when you open a page or build the wiki.
        </EmptyState>
      </Page>
    );
  }

  return (
    <Page className={styles.page}>
      <PageHeader crumb={<>{profile} / Knowledge</>} title="Wiki" actions={<WikiSearch profile={profile} onOpen={id => navigate(profileUrl(profile, `wiki/${encodeURIComponent(id)}`))} />} />
      {status.data && status.data.articlesReady === 0 && <BuildBanner profile={profile} status={status.data} />}
      <div className={styles.layout}>
        <PageTree profile={profile} roots={roots.data?.pages ?? []} loading={roots.isPending} current={pageId ?? null} built={(status.data?.articlesReady ?? 0) > 0} />
        {pageId
          ? <Article key={pageId} profile={profile} pageId={pageId} />
          : <Landing profile={profile} built={(status.data?.articlesReady ?? 0) > 0} unassigned={status.data?.unassignedEvidence ?? 0} />}
      </div>
    </Page>
  );
}

function BuildBanner({ profile, status }: { profile: string; status: NonNullable<Awaited<ReturnType<typeof wikiStatus>>> }) {
  const client = useQueryClient();
  const toast = useToast();
  const [confirming, setConfirming] = useState(false);
  const build = useMutation({
    mutationFn: () => buildWiki(profile, 'missing'),
    onSuccess: () => {
      toast.show('Wiki build started', 'Articles are written one at a time; follow it in Background tasks.');
      client.invalidateQueries({ queryKey: ['tasks'] });
    },
    onError: e => toast.show('Could not start the build', errorMessage(e), 'error')
  });
  const corpus = status.state.corpusWatermark;
  const pct = corpus > 0 ? Math.round((status.state.projectionWatermark / corpus) * 100) : 0;
  return (
    <section aria-label="Wiki build status" className={styles.banner}>
      <div className={styles.bannerHead}>
        <div className={styles.bannerText}>
          <strong>The wiki is enabled for this profile but hasn't been built yet</strong>
          <span>
            Building writes an article for each of the {plural(status.pagesTotal, 'topic and entity page')} from its evidence —
            one synthesis-model call per article, processed one at a time.
          </span>
        </div>
        <Button size="lg" variant="primary" disabled={build.isPending || build.isSuccess || status.pagesTotal === 0} onClick={() => setConfirming(true)}>
          {build.isSuccess ? 'Build started' : 'Build wiki now'}
        </Button>
        {confirming && (
          <Dialog title="Build the whole wiki?" onClose={() => setConfirming(false)}
            footer={<><Button onClick={() => setConfirming(false)}>Cancel</Button>
              <Button variant="primary" onClick={() => { setConfirming(false); build.mutate(); }}>Write {integer(status.articlesAbsent)} articles</Button></>}>
            <p className={styles.dialogCopy}>
              This queues {plural(status.articlesAbsent, 'article')}, each one synthesis-model call. The daemon writes them one at a
              time, so a wiki this size takes a long time and spends tokens on every page. You can cancel it from Background tasks;
              articles already written are kept.
            </p>
            <p className={styles.dialogCopy}>To write just the pages you read, open a page and choose “Write article” instead.</p>
          </Dialog>
        )}
      </div>
      <div className={styles.progress}>
        <div role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-label="Evidence projected" className={styles.progressTrack}>
          <span style={{ width: `${pct}%` }} />
        </div>
        <div className={styles.progressMeta}>
          <span>Evidence projected <span className="mono">{integer(status.state.projectionWatermark)} / {integer(corpus)} · {pct}%</span></span>
          <span className={styles.grow} />
          <span>{status.projectionLag > 0 ? 'The build waits for projection to finish, then writes articles · runs as a background task' : 'Runs as a background task · uses the synthesis model'}</span>
        </div>
      </div>
    </section>
  );
}

function PageTree({ profile, roots, loading, current, built }: { profile: string; roots: WikiCard[]; loading: boolean; current: string | null; built: boolean }) {
  return (
    <nav aria-label="Wiki pages" className={styles.tree}>
      {loading && <Skeleton height={160} />}
      {roots.map(root => <Directory key={root.id} profile={profile} root={root} current={current} built={built} />)}
      <div className={styles.legend}>
        <span><span aria-hidden="true" className={cx(styles.dot, styles.fresh)} />article fresh</span>
        <span><span aria-hidden="true" className={cx(styles.dot, styles.stale)} />stale — new evidence since</span>
        <span><span aria-hidden="true" className={cx(styles.dot, styles.none)} />not written yet</span>
      </div>
    </nav>
  );
}

function Directory({ profile, root, current, built }: { profile: string; root: WikiCard; current: string | null; built: boolean }) {
  const unassigned = root.title.toLowerCase().startsWith('unassigned');
  const children = useQuery({
    queryKey: qk.wikiDir(profile, root.id),
    queryFn: ({ signal }) => wikiDirectory(profile, root.id, null, signal),
    enabled: !unassigned
  });
  const pages = children.data?.pages ?? [];
  const [expanded, setExpanded] = useState(false);
  const shown = expanded ? pages : pages.slice(0, 12);
  return (
    <div className={styles.group}>
      <div className={styles.groupHead}>
        <span>{root.title}</span>
        <span className="mono">{unassigned ? integer(root.evidenceCount) : children.data ? integer(pages.length) + (children.data.nextCursor ? '+' : '') : '–'}</span>
      </div>
      {unassigned ? (
        <Link to={profileUrl(profile, `wiki/${encodeURIComponent(root.id)}`)} className={cx(styles.pageLink, current === root.id && styles.pageOn)}>
          <span className={styles.grow}>{plural(root.evidenceCount, 'memory', 'memories')} fit no page yet</span>
        </Link>
      ) : (
        <>
          {children.isSuccess && pages.length === 0 && <span className={styles.emptyGroup}>{built ? 'No pages' : 'Appears after projection'}</span>}
          {shown.map(page => {
            if (page.kind === 'directory') return <SubDirectory key={page.id} profile={profile} dir={page} current={current} />;
            const f = freshnessOf(page);
            return (
              <Link key={page.id} to={profileUrl(profile, `wiki/${encodeURIComponent(page.id)}`)} className={cx(styles.pageLink, current === page.id && styles.pageOn)}>
                <span className={styles.grow}>{page.title}</span>
                <span aria-label={f === 'fresh' ? 'article fresh' : f === 'stale' ? 'article stale' : 'no article yet'} className={cx(styles.dot, styles[f])} />
              </Link>
            );
          })}
          {pages.length > 12 && (
            <button type="button" className={styles.more} onClick={() => setExpanded(e => !e)}>{expanded ? 'Show fewer' : `Show all ${pages.length}`}</button>
          )}
        </>
      )}
    </div>
  );
}

function SubDirectory({ profile, dir, current }: { profile: string; dir: WikiCard; current: string | null }) {
  const [open, setOpen] = useState(false);
  const children = useQuery({ queryKey: qk.wikiDir(profile, dir.id), queryFn: ({ signal }) => wikiDirectory(profile, dir.id, null, signal), enabled: open });
  return (
    <>
      <button type="button" aria-expanded={open} className={styles.subdir} onClick={() => setOpen(o => !o)}>
        <span aria-hidden="true" className={cx(styles.caret, open && styles.caretOpen)}>›</span>
        <span className={styles.grow}>{dir.title}</span>
        <span className="mono">{integer(dir.evidenceCount)}</span>
      </button>
      {open && (
        <div className={styles.nested}>
          {children.isPending && <Skeleton height={24} />}
          {(children.data?.pages ?? []).map(page => page.kind === 'directory'
            ? <SubDirectory key={page.id} profile={profile} dir={page} current={current} />
            : (
              <Link key={page.id} to={profileUrl(profile, `wiki/${encodeURIComponent(page.id)}`)} className={cx(styles.pageLink, current === page.id && styles.pageOn)}>
                <span className={styles.grow}>{page.title}</span>
                <span className={cx(styles.dot, styles[freshnessOf(page)])} />
              </Link>
            ))}
          {children.data?.nextCursor && <span className={styles.emptyGroup}>More pages: use search</span>}
        </div>
      )}
    </>
  );
}

function Landing({ profile, built, unassigned }: { profile: string; built: boolean; unassigned: number }) {
  return (
    <article className={styles.article}>
      <div className={styles.prose}>
        <h2 className={styles.emptyTitle}>{built ? 'Pick a page' : 'No articles yet'}</h2>
        <p>
          {built
            ? 'Choose a topic or entity on the left. Each article is written from its evidence, with every claim linked to the memory it came from.'
            : 'Once built, each page gets an article written from its evidence, with every claim linked to the memory it came from. Articles are marked stale when new evidence arrives and can be rewritten on demand.'}
        </p>
        {unassigned > 0 && <p>{plural(unassigned, 'memory', 'memories')} are waiting under <strong>Unassigned evidence</strong>.</p>}
        <div><LinkButton to={profileUrl(profile, 'memories')}>Browse memories</LinkButton></div>
      </div>
    </article>
  );
}

function Article({ profile, pageId }: { profile: string; pageId: string }) {
  const client = useQueryClient();
  const toast = useToast();
  const page = useQuery({ queryKey: qk.wikiPage(profile, pageId), queryFn: ({ signal }) => wikiPage(profile, pageId, signal) });
  const data = page.data;
  const liveJob = data?.job && ['queued', 'generating'].includes(data.job.status) ? data.job : null;
  const job = useQuery({
    queryKey: ['profile', profile, 'wiki', 'job', liveJob?.id],
    queryFn: ({ signal }) => wikiJob(profile, liveJob!.id, signal),
    enabled: Boolean(liveJob),
    refetchInterval: 3_000
  });
  useEffect(() => {
    if (job.data && !['queued', 'generating'].includes(job.data.status)) {
      client.invalidateQueries({ queryKey: qk.wikiPage(profile, pageId) });
      client.invalidateQueries({ queryKey: ['profile', profile, 'wiki', 'dir'] });
      client.invalidateQueries({ queryKey: qk.wikiStatus(profile) });
    }
  }, [job.data, client, profile, pageId]);

  const evaluate = useMutation({
    // Projection keeps revising pages while it runs; on a stale revision, reload the page and try once more.
    mutationFn: async (intent: 'initial' | 'refresh' | 'retry') => {
      try {
        return await evaluateWikiPage(profile, data!, intent);
      } catch (e) {
        if (!(e instanceof ApiError && e.status === 409)) throw e;
        return evaluateWikiPage(profile, await wikiPage(profile, pageId), intent);
      }
    },
    onSuccess: result => {
      if (result.status === 'evidence-only') toast.show('Not enough evidence', 'An article needs at least two sources for this page.');
      client.invalidateQueries({ queryKey: qk.wikiPage(profile, pageId) });
    },
    onError: e => {
      const code = e instanceof ApiError && e.body && typeof e.body === 'object' ? (e.body as { code?: string }).code : undefined;
      const reasons: Record<string, string> = {
        queue_full: 'The article queue is full; try again shortly.',
        projection_not_ready: 'Pages are still being projected from memories; articles can be written once projection catches up.',
        wiki_disabled: 'The wiki is turned off for this profile.'
      };
      toast.show('Could not queue the article', (code && reasons[code]) || errorMessage(e), 'error');
    }
  });
  const status = useQuery({ queryKey: qk.wikiStatus(profile), queryFn: ({ signal }) => wikiStatus(profile, signal) });
  const projecting = status.data ? !status.data.state.initialized || status.data.projectionLag > 0 : false;
  const projectingHint = status.data
    ? `Pages are still being projected (${integer(status.data.state.projectionWatermark)} of ${integer(status.data.state.corpusWatermark)}); articles can be written once projection catches up.`
    : '';

  const refs = useMemo(() => new Set(data?.evidence.map(e => e.ref) ?? []), [data]);
  const citationHref = useMemo(() => (ref: string) => (refs.has(ref) ? `#source-${ref}` : null), [refs]);

  if (page.isPending) return <article className={styles.article}><Skeleton height={28} width="50%" /><Skeleton height={240} /></article>;
  if (page.isError || !data) return <article className={styles.article}><ErrorBanner error={page.error} /></article>;

  const f = freshnessOf(data.page);
  const isDirectory = data.page.kind === 'directory';
  const writing = Boolean(liveJob) && !(job.data && !['queued', 'generating'].includes(job.data.status));
  const cited = data.body ? data.evidence.filter(e => data.body!.includes(`#source-${e.ref}`)).length : 0;

  return (
    <>
      <article className={styles.article}>
        <div className={styles.prose}>
          <div className={styles.meta}>
            <span>{data.page.kind === 'entity' ? 'Entities' : isDirectory ? 'Directories' : 'Topics'}</span>
            <span aria-hidden="true">›</span>
            <span className={styles.metaTitle}>{data.page.title}</span>
            <span className={styles.grow} />
            {!isDirectory && (f === 'fresh' ? <Pill tone="ok" dot="var(--ok)">fresh</Pill> : f === 'stale' ? <Pill tone="warn" dot="var(--accent)">stale</Pill> : <Pill tone="neutral">no article</Pill>)}
            <span>{plural(data.totalSources, 'source')}</span>
            {!isDirectory && data.body && (
              <Button size="sm" disabled={writing || evaluate.isPending} onClick={() => evaluate.mutate('refresh')}>{writing ? 'Writing…' : 'Regenerate'}</Button>
            )}
          </div>
          <h2 className={styles.articleTitle}>{data.page.title}</h2>
          {data.renderingUpgrade && data.body && <p className={styles.note}>Written with earlier model settings; regenerate to use the current ones.</p>}
          {data.body && <Markdown source={data.body} variant="article" citationHref={citationHref} />}
          {!data.body && !isDirectory && (
            <div className={styles.noArticle}>
              <p>{writing ? 'The article is being written. This page refreshes when it is ready.' : data.job?.status === 'failed' ? `The last attempt failed${data.job.errorCode ? ` (${data.job.errorCode})` : ''}.` : 'No article has been written for this page yet. Its evidence is listed alongside.'}</p>
              {(() => {
                const button = (
                  <Button variant="primary" disabled={writing || evaluate.isPending || projecting}
                    onClick={() => evaluate.mutate(data.job?.status === 'failed' ? 'retry' : 'initial')}>
                    {writing ? 'Writing…' : data.job?.status === 'failed' ? 'Try again' : 'Write article'}
                  </Button>
                );
                return projecting ? <DisabledHint reason={projectingHint}>{button}</DisabledHint> : button;
              })()}
            </div>
          )}
          {isDirectory && <p className={styles.note}>Memories here did not fit any topic or entity page. They stay searchable and recallable as they are.</p>}
          {(data.addedSources > 0 || data.removedSources > 0) && data.body && (
            <p className={styles.note}>Since this article was written: {data.addedSources} new and {data.removedSources} removed sources.</p>
          )}
        </div>
      </article>
      <Sources profile={profile} page={data} cited={cited} />
    </>
  );
}

function Sources({ profile, page, cited }: { profile: string; page: WikiPageData; cited: number }) {
  const title = page.body ? `Sources · ${cited} cited${page.evidence.length - cited > 0 ? `, ${page.evidence.length - cited} uncited` : ''}` : `Evidence · ${page.totalSources}`;
  return (
    <aside aria-label="Article sources" className={styles.sources}>
      <h2>{title}</h2>
      {page.evidence.map(e => (
        <Link key={e.ref} id={`source-${e.ref}`} to={profileUrl(profile, `memories/${encodeURIComponent(e.memoryId)}`)} className={styles.source}>
          <span className={styles.ref}>[{e.ref}]</span>
          <span className={styles.sourceBody}>
            <span className={styles.sourceText}>{e.content}</span>
            <span className={styles.sourceMeta}><TypeChip type={e.type} /> {e.role}</span>
          </span>
        </Link>
      ))}
      {page.omittedSources > 0 && <span className={styles.note}>+{integer(page.omittedSources)} more not used in the article</span>}
      {page.links.length > 0 && (
        <div className={styles.related}>
          <SectionLabel>Related</SectionLabel>
          <div className={styles.chips}>
            {page.links.map(l => <Link key={l.id} to={profileUrl(profile, `wiki/${encodeURIComponent(l.id)}`)} className={styles.chip}>{l.title}</Link>)}
          </div>
        </div>
      )}
    </aside>
  );
}

function WikiSearch({ profile, onOpen }: { profile: string; onOpen: (id: string) => void }) {
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  useEffect(() => { const t = setTimeout(() => setQ(text.trim()), 250); return () => clearTimeout(t); }, [text]);
  const results = useQuery({ queryKey: qk.wikiSearch(profile, q), queryFn: ({ signal }) => wikiSearch(profile, q, signal), enabled: q.length > 1 });
  return (
    <div className={styles.search}>
      <SearchField label="Search the wiki" value={text} onChange={setText} placeholder="Find a topic, entity or source…" onSurface />
      {q.length > 1 && results.data && (
        <Card className={styles.searchResults} as="div">
          {results.data.pages.map(p => (
            <button key={p.id} type="button" className={styles.searchResult} onClick={() => { setText(''); onOpen(p.id); }}>
              <span className={cx(styles.dot, styles[freshnessOf(p)])} />
              <span className={styles.grow}>{p.title}</span>
              <span className="mono">{p.kind}</span>
            </button>
          ))}
          {results.data.unassigned.length > 0 && <SectionLabel>Unassigned evidence</SectionLabel>}
          {results.data.unassigned.slice(0, 5).map(e => (
            <Link key={e.memoryId} to={profileUrl(profile, `memories/${encodeURIComponent(e.memoryId)}`)} className={styles.searchEvidence}>{e.content}</Link>
          ))}
          {results.data.pages.length === 0 && results.data.unassigned.length === 0 && <span className={styles.note}>Nothing matches “{q}”.</span>}
        </Card>
      )}
    </div>
  );
}
