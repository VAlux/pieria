import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { isAbort } from '../../api/client';
import { useStatus } from '../../api/queries';
import { recall, type RecallMode, type RecallResult } from '../../api/recall';
import { dayTime, fromLocalInput, integer, plural, toLocalInput } from '../../lib/format';
import { mod, useHotkey } from '../../lib/hotkeys';
import { profileUrl } from '../../lib/routes';
import { recentQuestions, rememberQuestion } from '../../lib/storage';
import { Button, Segmented } from '../../ui/controls';
import { Card, CardHeader, ErrorBanner, Page, PageHeader, Skeleton, TopicKey, TypeChip } from '../../ui/display';
import { Markdown } from '../../ui/Markdown';
import { MenuItem, Popover, usePopover, useToast } from '../../ui/overlay';
import { useProfile } from '../shell/context';
import styles from './recall.module.css';

const MODES: { value: RecallMode; label: string; hint: string }[] = [
  { value: 'synthesized', label: 'Synthesized', hint: 'Full retrieval + a written answer' },
  { value: 'analyzed', label: 'Analyzed', hint: 'Memories only, with model query analysis' },
  { value: 'evidence', label: 'Evidence', hint: 'Fastest — memories only' }
];

/** Channels in the order the canvas lists them; any the daemon adds later are appended. */
const CHANNELS = ['exact_key', 'fts_memory', 'hyde_vector', 'direct_vector', 'fts_message', 'graph', 'symbol_fts', 'code_graph'];

const channelLabel = (c: string) => c.replace(/_/g, '-');

export function RecallPage() {
  const profile = useProfile();
  const toast = useToast();
  const status = useStatus();
  const [params, setParams] = useSearchParams();
  const [question, setQuestion] = useState(params.get('q') ?? '');
  const [mode, setMode] = useState<RecallMode>((params.get('mode') as RecallMode) || 'synthesized');
  const [limit, setLimit] = useState(10);
  const [at, setAt] = useState<{ asOf: string | null; knownAsOf: string | null }>({ asOf: null, knownAsOf: null });
  const [recent, setRecent] = useState(() => recentQuestions(profile));
  const controller = useRef<AbortController | null>(null);

  const ask = useMutation({
    mutationFn: (q: string) => {
      controller.current?.abort();
      controller.current = new AbortController();
      return recall(profile, { query: q, mode, limit, asOf: at.asOf, knownAsOf: at.knownAsOf }, controller.current.signal);
    },
    onSuccess: (_result, q) => setRecent(rememberQuestion(profile, q))
  });

  useEffect(() => () => controller.current?.abort(), []);
  useEffect(() => { setRecent(recentQuestions(profile)); ask.reset(); }, [profile]);

  const submit = (event?: FormEvent, q = question) => {
    event?.preventDefault();
    const trimmed = q.trim();
    if (!trimmed) return;
    setQuestion(trimmed);
    setParams(p => { const next = new URLSearchParams(p); next.set('q', trimmed); return next; }, { replace: true });
    ask.mutate(trimmed);
  };

  useHotkey('mod+Enter', () => submit());

  const result: RecallResult | undefined = ask.data;
  const hint = MODES.find(m => m.value === mode)!.hint;

  return (
    <Page>
      <PageHeader crumb={<>{profile} / Ask</>} title="Recall" />

      <form className={styles.form} onSubmit={submit}>
        <label htmlFor="recall-q" className={styles.formLabel}>Ask this profile a question</label>
        <textarea id="recall-q" rows={2} value={question} className={styles.question} autoFocus
          placeholder="e.g. How is the console served, and how do users update it?"
          onChange={e => setQuestion(e.target.value)} />
        <div className={styles.controls}>
          <Segmented label="Recall mode" value={mode} onChange={setMode} options={MODES.map(m => ({ value: m.value, label: m.label }))} />
          <LimitPicker limit={limit} onChange={setLimit} />
          <AsOfPicker at={at} onAt={setAt} />
          <span className={styles.grow} />
          <span className={styles.hint}>{hint}</span>
          <Button type="submit" variant="primary" size="lg" disabled={!question.trim() || ask.isPending}>
            {ask.isPending ? 'Asking…' : `Ask ${mod}↵`}
          </Button>
        </div>
      </form>

      {ask.isError && !isAbort(ask.error) && <ErrorBanner error={ask.error} />}

      <div className={styles.layout}>
        <div className={styles.main}>
          {ask.isPending && (
            <Card>
              <Skeleton height={16} width="30%" />
              <Skeleton height={72} />
              <Skeleton height={140} />
            </Card>
          )}
          {!ask.isPending && !result && (
            <Card className={styles.empty}>
              <h2>Ask anything this profile has learned</h2>
              <p>Synthesized answers are written from the memories listed under Evidence. Evidence and Analyzed return only the memories, which is faster and spends no synthesis tokens.</p>
            </Card>
          )}
          {result && !ask.isPending && (
            <>
              {result.response.answer && (
                <Card aria-label="Answer">
                  <CardHeader title="Answer"
                    sub={`synthesized${status.data?.synthesisModel ? ` by ${status.data.synthesisModel}` : ''} from ${plural(result.response.memories.length, 'memory', 'memories')}`}
                    actions={<Button size="sm" icon="copy" onClick={() => navigator.clipboard?.writeText(result.response.answer ?? '').then(() => toast.show('Answer copied'))}>Copy</Button>} />
                  <Markdown source={result.response.answer} variant="answer" />
                  {result.response.temporal?.limitations?.map(l => <p key={l} className={styles.hint}>{l}</p>)}
                </Card>
              )}
              <Evidence result={result} profile={profile} />
            </>
          )}
        </div>

        <aside className={styles.side}>
          <Trace result={ask.isPending ? undefined : result} profile={profile} limit={limit} />
          <Card aria-label="Recent questions" className={styles.recent}>
            <CardHeader title="Recent questions" />
            {recent.length === 0 && <p className={styles.hint}>Questions you ask here are kept in this browser.</p>}
            {recent.map(q => (
              <button key={q} type="button" className={styles.recentItem} onClick={() => { setQuestion(q); submit(undefined, q); }}>{q}</button>
            ))}
            {recent.length > 0 && <span className={styles.hint}>Kept per browser; re-run with one click.</span>}
          </Card>
        </aside>
      </div>
    </Page>
  );
}

function Evidence({ result, profile }: { result: RecallResult; profile: string }) {
  const { memories } = result.response;
  const sources = new Map((result.response.sources ?? []).map(s => [s.memoryId, s.channels]));
  return (
    <Card flush aria-label="Evidence">
      <CardHeader title="Evidence" sub="ranked after fusion + rerank" />
      {memories.length === 0 && <p className={styles.noEvidence}>No memory matched this question.</p>}
      {memories.map((m, i) => (
        <div key={m.id} id={`evidence-${i + 1}`} className={styles.evidence}>
          <span className={styles.ref}>[{i + 1}]</span>
          <div className={styles.evidenceBody}>
            <Link to={profileUrl(profile, `memories/${encodeURIComponent(m.id)}`)} className={styles.evidenceText}>{m.content}</Link>
            <span className={styles.evidenceMeta}>
              <TypeChip type={m.type} />
              {m.topicKey && <TopicKey>{m.topicKey}</TopicKey>}
              {(sources.get(m.id) ?? []).map(c => <span key={c} className={styles.channel}>{channelLabel(c)}</span>)}
            </span>
          </div>
        </div>
      ))}
    </Card>
  );
}

function Trace({ result, profile, limit }: { result: RecallResult | undefined; profile: string; limit: number }) {
  const sources = result?.response.sources ?? [];
  const contributed = new Map<string, number>();
  sources.forEach(s => s.channels.forEach(c => contributed.set(c, (contributed.get(c) ?? 0) + 1)));
  const channelNames = [...CHANNELS, ...(result?.response.channels ?? []).map(c => c.channel).filter(c => !CHANNELS.includes(c))];
  const raw = new Map((result?.response.channels ?? []).map(c => [c.channel, c]));
  const max = Math.max(1, ...contributed.values());
  const t = result?.response.timings;
  return (
    <Card aria-label="Retrieval trace" className={styles.trace}>
      <CardHeader title="Retrieval trace" />
      <span className={styles.hint}>Which channels contributed to the top {limit}</span>
      {channelNames.map(name => {
        const hits = contributed.get(name) ?? 0;
        const stat = raw.get(name);
        return (
          <div key={name} className={styles.traceRow} title={stat ? `${stat.hits} raw hits · ${stat.latencyMs} ms${stat.failed ? ' · failed' : ''}` : 'not run'}>
            <span className={stat?.failed ? styles.failed : undefined}>{channelLabel(name)}</span>
            <span className={styles.traceTrack}><span style={{ width: `${(hits / max) * 100}%` }} /></span>
            <span>{result ? hits : '–'}</span>
          </div>
        );
      })}
      <div className={styles.timings}>
        <span>retrieve {t ? integer(t.retrieveMs) : '–'} ms</span>
        <span>rerank {t ? integer(t.rerankMs) : '–'} ms</span>
        <span>synthesize {t ? integer(t.synthesizeMs) : '–'} ms</span>
        {t && <span>total {integer(t.totalMs)} ms</span>}
      </div>
      {result && (
        <Link to={`${profileUrl(profile, 'activity')}?requestId=${encodeURIComponent(result.requestId)}`} className={styles.more}>
          Open this request in Activity →
        </Link>
      )}
    </Card>
  );
}

function LimitPicker({ limit, onChange }: { limit: number; onChange: (n: number) => void }) {
  const popover = usePopover();
  return (
    <>
      <Button ref={popover.anchor} variant="field" aria-haspopup="menu" aria-expanded={popover.open} onClick={popover.toggle}>top {limit} ▾</Button>
      <Popover anchor={popover.anchor} open={popover.open} onClose={popover.close} label="Number of memories">
        {[5, 10, 20, 30].map(n => (
          <MenuItem key={n} checked={n === limit} onSelect={() => { onChange(n); popover.close(); }}>top {n}</MenuItem>
        ))}
      </Popover>
    </>
  );
}

function AsOfPicker({ at, onAt }: { at: { asOf: string | null; knownAsOf: string | null }; onAt: (v: { asOf: string | null; knownAsOf: string | null }) => void }) {
  const popover = usePopover();
  const [world, setWorld] = useState(toLocalInput(at.asOf));
  const [known, setKnown] = useState(toLocalInput(at.knownAsOf));
  const [error, setError] = useState('');
  const label = `as of: ${at.asOf ? dayTime(at.asOf) : 'now'} · known as of: ${at.knownAsOf ? dayTime(at.knownAsOf) : 'now'}`;
  const apply = () => {
    const knownAsOf = fromLocalInput(known);
    if (knownAsOf && Date.parse(knownAsOf) > Date.now()) { setError('“Known as of” cannot be in the future.'); return; }
    setError('');
    onAt({ asOf: fromLocalInput(world), knownAsOf });
    popover.close();
  };
  return (
    <>
      <Button ref={popover.anchor} variant="field" aria-haspopup="dialog" aria-expanded={popover.open} onClick={popover.toggle}>{label}</Button>
      <Popover anchor={popover.anchor} open={popover.open} onClose={popover.close} label="Recall as of" role="dialog">
        <div className={styles.asOf}>
          <label><span>World time — what was true at</span>
            <input type="datetime-local" step={60} value={world} onChange={e => setWorld(e.target.value)} /></label>
          <label><span>Known as of — what Pieria knew at</span>
            <input type="datetime-local" step={60} value={known} onChange={e => setKnown(e.target.value)} /></label>
          {error && <span role="alert" className={styles.failed}>{error}</span>}
          <div className={styles.asOfActions}>
            <Button size="sm" onClick={() => { setWorld(''); setKnown(''); onAt({ asOf: null, knownAsOf: null }); popover.close(); }}>Now</Button>
            <Button size="sm" variant="primary" onClick={apply}>Apply</Button>
          </div>
        </div>
      </Popover>
    </>
  );
}

