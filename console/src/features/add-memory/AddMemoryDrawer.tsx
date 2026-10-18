import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { errorMessage } from '../../api/client';
import { rememberMemory, searchTopicKeys } from '../../api/memories';
import { qk, useSessions } from '../../api/queries';
import { recall } from '../../api/recall';
import { dayTime, fromLocalInput, shortId, toLocalInput } from '../../lib/format';
import { mod, useHotkey } from '../../lib/hotkeys';
import { profileUrl } from '../../lib/routes';
import { Button, IconButton, Kbd, Select, TextArea, TextInput, cx } from '../../ui/controls';
import { ErrorBanner, TopicKey } from '../../ui/display';
import { Icon } from '../../ui/icons';
import { Drawer, useToast } from '../../ui/overlay';
import { useProfile } from '../shell/context';
import { NOW_STEP, MAX_STEP, PRESETS, rangeLabels, rangeToValidity, stepDate } from './range';
import styles from './add-memory.module.css';

type Kind = 'fact' | 'instruction' | 'event' | 'task';

const KINDS: { id: Kind; label: string; color: string; hint: string; placeholder: string; tip: string }[] = [
  { id: 'fact', label: 'Fact', color: 'var(--fact)', hint: 'Something true about the project. Replaces older facts with the same key.',
    placeholder: 'e.g. The daemon listens on port 8077 by default.', tip: 'One self-contained statement reads best in recall answers.' },
  { id: 'instruction', label: 'Instruction', color: 'var(--instruction)', hint: 'How agents should work here. Replaces older ones with the same key.',
    placeholder: 'e.g. Always run ./gradlew test before committing.', tip: 'Write it as a rule an agent can follow without extra context.' },
  { id: 'event', label: 'Event', color: 'var(--event)', hint: 'Something that happened. Kept as history, never replaced.',
    placeholder: 'e.g. Migrated the Activity view to React.', tip: 'Say what happened; the date comes from “When is it true?”.' },
  { id: 'task', label: 'Task', color: 'var(--task)', hint: 'Something still to do. Shows up in recall until done.',
    placeholder: 'e.g. Migrate the Graph view to React.', tip: 'Start with a verb so it reads as an action.' }
];

function useDebounced<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);
  return debounced;
}

interface PayloadField { key: string; value: string }

function payloadJson(fields: PayloadField[], raw: string, rawMode: boolean): { json: string | null; error: string | null } {
  if (rawMode) {
    if (!raw.trim()) return { json: null, error: null };
    try {
      const parsed: unknown = JSON.parse(raw);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return { json: null, error: 'Payload must be a JSON object.' };
      return { json: JSON.stringify(parsed), error: null };
    } catch {
      return { json: null, error: 'Payload is not valid JSON.' };
    }
  }
  const entries = fields.filter(f => f.key.trim());
  return { json: entries.length ? JSON.stringify(Object.fromEntries(entries.map(f => [f.key.trim(), f.value]))) : null, error: null };
}

export function AddMemoryDrawer({ onClose }: { onClose: () => void }) {
  const profile = useProfile();
  const navigate = useNavigate();
  const client = useQueryClient();
  const toast = useToast();
  const contentRef = useRef<HTMLTextAreaElement>(null);

  const [kind, setKind] = useState<Kind>('fact');
  const [content, setContent] = useState('');
  const [key, setKey] = useState('');
  const [from, setFrom] = useState(NOW_STEP);
  const [to, setTo] = useState(MAX_STEP);
  const [exact, setExact] = useState(false);
  const [exactFrom, setExactFrom] = useState(toLocalInput(new Date()));
  const [exactTo, setExactTo] = useState('');
  const [advanced, setAdvanced] = useState(false);
  const [session, setSession] = useState('');
  const [fields, setFields] = useState<PayloadField[]>([{ key: 'source', value: 'manual' }]);
  const [rawMode, setRawMode] = useState(false);
  const [raw, setRaw] = useState('{\n  "source": "manual"\n}');
  const [suggested, setSuggested] = useState<string[]>([]);

  const def = KINDS.find(k => k.id === kind)!;
  const keyed = kind === 'fact' || kind === 'instruction';
  const trimmedKey = key.trim();
  const debouncedKey = useDebounced(trimmedKey, 250);
  const debouncedContent = useDebounced(content.trim(), 700);

  const keys = useQuery({
    queryKey: qk.topicKeys(profile, debouncedKey, keyed ? kind : undefined),
    queryFn: ({ signal }) => searchTopicKeys(profile, debouncedKey, keyed ? kind : undefined, signal),
    staleTime: 30_000
  });
  const similar = useQuery({
    queryKey: ['profile', profile, 'similar', debouncedContent],
    queryFn: ({ signal }) => recall(profile, { query: debouncedContent, mode: 'evidence', limit: 5 }, signal),
    enabled: debouncedContent.length >= 20,
    staleTime: 60_000,
    retry: false
  });
  const sessions = useSessions(profile);

  const exactMatch = keys.data?.find(k => k.topicKey === trimmedKey && k.type === kind);
  const supersedes = keyed && Boolean(trimmedKey) && Boolean(exactMatch);
  const keyChips = suggested.length
    ? suggested
    : (keys.data ?? []).map(k => k.topicKey).filter(k => k !== trimmedKey).filter((k, i, all) => all.indexOf(k) === i).slice(0, 4);
  const closest = similar.data?.response.memories.find(m => m.topicKey && m.topicKey !== trimmedKey && !m.topicKey.startsWith('trace:') && !m.topicKey.startsWith('code:'));
  const payload = payloadJson(fields, raw, rawMode);
  const empty = !content.trim();

  const validity = exact
    ? { validFrom: fromLocalInput(exactFrom), validTo: fromLocalInput(exactTo) }
    : rangeToValidity(from, to);
  const exactInvalid = exact && validity.validFrom && validity.validTo && Date.parse(validity.validFrom) >= Date.parse(validity.validTo);
  const labels = rangeLabels(from, to);

  const store = useMutation({
    mutationFn: () => rememberMemory(profile, {
      type: kind,
      content: content.trim(),
      topicKey: trimmedKey || null,
      sessionId: session || null,
      payload: payload.json,
      validFrom: validity.validFrom,
      validTo: validity.validTo
    }),
    onSuccess: (memory, _vars) => {
      client.invalidateQueries({ queryKey: qk.profile(profile) });
      client.invalidateQueries({ queryKey: qk.profiles });
      toast.show(supersedes ? 'Stored and replaced' : `Stored ${kind}`, 'Agents can recall it right away.');
      if (another.current) {
        another.current = false;
        setContent('');
        setKey('');
        setSuggested([]);
        contentRef.current?.focus();
        return;
      }
      navigate(profileUrl(profile, `memories/${encodeURIComponent(memory.id)}`));
    }
  });
  const another = useRef(false);
  const canStore = !empty && !payload.error && !exactInvalid && !store.isPending;
  const submit = (addAnother = false) => {
    if (!canStore) return;
    another.current = addAnother;
    store.mutate();
  };
  useHotkey('mod+Enter', () => submit(false), { allowInInputs: true });

  const suggestFromText = () => {
    const found = (similar.data?.response.memories ?? [])
      .map(m => m.topicKey)
      .filter((k): k is string => Boolean(k) && !k!.startsWith('trace:') && !k!.startsWith('code:'));
    setSuggested([...new Set(found)].slice(0, 4));
  };

  return (
    <Drawer title="Add a memory" onClose={onClose} initialFocus={contentRef}>
      <div className={styles.head}>
        <div className={styles.headText}>
          <h1>Add a memory</h1>
          <span>to <strong>{profile}</strong> · recallable by agents right after it's stored</span>
        </div>
        <IconButton icon="close" label="Close" onClick={onClose} />
      </div>

      <div className={styles.body}>
        <fieldset className={styles.fieldset}>
          <legend>What kind of memory is it?</legend>
          <div className={styles.kinds}>
            {KINDS.map(k => (
              <button key={k.id} type="button" aria-pressed={k.id === kind} className={styles.kind}
                style={{ ['--kind' as string]: k.color }} onClick={() => setKind(k.id)}>
                <span className={styles.kindTitle}><span aria-hidden="true" className={styles.kindDot} />{k.label}</span>
                <span className={styles.kindHint}>{k.hint}</span>
              </button>
            ))}
          </div>
        </fieldset>

        <div className={styles.group}>
          <div className={styles.labelRow}>
            <label htmlFor="am-content">The memory</label>
            <span className="mono">{content.length} chars</span>
          </div>
          <TextArea id="am-content" ref={contentRef} rows={4} value={content} placeholder={def.placeholder}
            className={styles.content} onChange={e => setContent(e.target.value)} />
          <span className={styles.help}>{def.tip}</span>
        </div>

        <div className={styles.group}>
          <div className={styles.labelRow}>
            <label htmlFor="am-key">Topic key</label>
            <span className={styles.help}>{keyed ? 'Same key = newer version of the same fact' : `Optional for ${kind}s — used for grouping only`}</span>
            <Button size="sm" disabled={!similar.data} onClick={suggestFromText}
              title={similar.data ? 'Use the keys of the most similar existing memories' : 'Type a sentence or two first'}>
              Suggest from text
            </Button>
          </div>
          <TextInput id="am-key" mono value={key} placeholder="e.g. web.console.serving" autoComplete="off"
            onChange={e => { setKey(e.target.value); setSuggested([]); }} />
          {keyChips.length > 0 && (
            <div className={styles.chips}>
              <span className={styles.help}>{suggested.length ? 'From similar memories:' : 'Existing keys:'}</span>
              {keyChips.map(k => <button key={k} type="button" className={styles.chip} onClick={() => setKey(k)}>{k}</button>)}
            </div>
          )}
          {supersedes && exactMatch && (
            <div className={styles.replaces}>
              <div className={styles.replacesHead}><Icon name="replace" strokeWidth={2} /><span>This replaces the current memory for this key</span></div>
              <div className={styles.compare}>
                <span>Now</span><span className={styles.old}>{exactMatch.content}</span>
                <span>New</span><span>{empty ? '—' : content}</span>
              </div>
              <span className={styles.help}>The old version stays in its validity history, ending when the new one starts.</span>
            </div>
          )}
        </div>

        {closest && !supersedes && !empty && (
          <div className={styles.similar}>
            <Icon name="similar" strokeWidth={2} />
            <div>
              <strong>Similar memory under another key</strong>
              <p>{closest.content}</p>
              <span className={styles.similarMeta}>
                <TopicKey>{closest.topicKey}</TopicKey>
                <span>closest match in recall</span>
                <button type="button" className={styles.link} onClick={() => setKey(closest.topicKey!)}>Use this key</button>
                <a href={`/console${profileUrl(profile, `memories/${encodeURIComponent(closest.id)}`)}`} target="_blank" rel="noreferrer">Open</a>
              </span>
            </div>
          </div>
        )}

        <fieldset className={styles.fieldset}>
          <legend>When is it true?</legend>
          {!exact ? (
            <>
              <div className={styles.rangeSummary}>
                <span><span className={styles.help}>From</span> <b>{labels.from}</b> <span className={styles.help}>until</span> <b>{labels.to}</b></span>
                <span className={styles.help}>{labels.span}</span>
              </div>
              <DualRange from={from} to={to} onFrom={setFrom} onTo={setTo} fromLabel={labels.from} toLabel={labels.to} />
              <div className={styles.presets}>
                {PRESETS.map(p => (
                  <button key={p.label} type="button" aria-pressed={from === p.from && to === p.to} className={styles.preset}
                    onClick={() => { setFrom(p.from); setTo(p.to); }}>{p.label}</button>
                ))}
                <span className={styles.grow} />
                <button type="button" className={styles.link} onClick={() => setExact(true)}>Exact dates…</button>
              </div>
            </>
          ) : (
            <>
              <div className={styles.exact}>
                <label><span>True from</span><TextInput type="datetime-local" step={60} value={exactFrom} onChange={e => setExactFrom(e.target.value)} /></label>
                <label><span>True until <span className={styles.help}>· empty = still true</span></span><TextInput type="datetime-local" step={60} value={exactTo} onChange={e => setExactTo(e.target.value)} /></label>
              </div>
              {exactInvalid && <span role="alert" className={styles.error}>“True from” must come before “true until”.</span>}
              <div><button type="button" className={styles.link} onClick={() => setExact(false)}>Use the slider</button></div>
            </>
          )}
        </fieldset>

        <div className={styles.more}>
          <button type="button" aria-expanded={advanced} className={styles.moreToggle} onClick={() => setAdvanced(a => !a)}>
            <span aria-hidden="true" className={cx(styles.caret, advanced && styles.caretOpen)}>›</span>
            <b>More options</b>
            <span className={styles.help}>session, structured payload</span>
          </button>
          {advanced && (
            <div className={styles.moreBody}>
              <label className={styles.stack}>
                <span>Link to a session <span className={styles.help}>· optional</span></span>
                <Select mono value={session} onChange={e => setSession(e.target.value)}>
                  <option value="">None — added manually</option>
                  {(sessions.data ?? []).slice(0, 100).map(s => (
                    <option key={s.sessionId} value={s.sessionId}>{shortId(s.sessionId)} · {dayTime(s.lastSeenAt)}{s.harness ? ` · ${s.harness}` : ''}</option>
                  ))}
                </Select>
              </label>
              <div className={styles.stack}>
                <div className={styles.labelRow}>
                  <span>Payload</span>
                  <span className={styles.grow} />
                  <button type="button" className={styles.link} onClick={() => {
                    if (!rawMode) setRaw(JSON.stringify(Object.fromEntries(fields.filter(f => f.key.trim()).map(f => [f.key.trim(), f.value])), null, 2));
                    setRawMode(m => !m);
                  }}>{rawMode ? 'Edit as fields' : 'Edit as JSON'}</button>
                </div>
                {rawMode ? (
                  <TextArea rows={5} className="mono" value={raw} onChange={e => setRaw(e.target.value)} aria-label="Payload JSON" />
                ) : (
                  <div className={styles.fields}>
                    {fields.map((field, i) => (
                      <div key={i} className={styles.fieldRow}>
                        <TextInput mono aria-label="Payload key" value={field.key} className={styles.payloadKey}
                          onChange={e => setFields(f => f.map((x, j) => (j === i ? { ...x, key: e.target.value } : x)))} />
                        <TextInput mono aria-label="Payload value" value={field.value}
                          onChange={e => setFields(f => f.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))} />
                        <IconButton icon="close" label="Remove field" bare onClick={() => setFields(f => f.filter((_, j) => j !== i))} />
                      </div>
                    ))}
                    <button type="button" className={styles.addField} onClick={() => setFields(f => [...f, { key: '', value: '' }])}>+ Add field</button>
                  </div>
                )}
                {payload.error && <span role="alert" className={styles.error}>{payload.error}</span>}
              </div>
            </div>
          )}
        </div>
        {store.error && <ErrorBanner>{errorMessage(store.error)}</ErrorBanner>}
      </div>

      <div className={styles.foot}>
        <span className={styles.footHint}><Kbd>{mod}↵</Kbd> to store · <Kbd>Esc</Kbd> to close</span>
        <Button size="lg" onClick={onClose}>Cancel</Button>
        <Button size="lg" variant="accent" disabled={!canStore} onClick={() => submit(true)}>Store &amp; add another</Button>
        <Button size="lg" variant="primary" disabled={!canStore} onClick={() => submit(false)}>
          {supersedes ? 'Store & replace' : `Store ${def.label.toLowerCase()}`}
        </Button>
      </div>
    </Drawer>
  );
}

interface DualRangeProps {
  from: number;
  to: number;
  onFrom: (v: number) => void;
  onTo: (v: number) => void;
  fromLabel: string;
  toLabel: string;
}

function DualRange({ from, to, onFrom, onTo, fromLabel, toLabel }: DualRangeProps) {
  const pct = (i: number) => (i / MAX_STEP) * 100;
  const nowPct = pct(NOW_STEP);
  const ticks = useMemo(() => [0, 16, 23, NOW_STEP, 37, MAX_STEP].map(i => ({
    i, label: i === NOW_STEP ? 'now' : i === MAX_STEP ? '∞' : stepDate(i).toLocaleDateString('en-GB', { day: '2-digit', month: 'short' })
  })), []);
  return (
    <>
      <div className={styles.dual}>
        <span aria-hidden="true" className={styles.past} style={{ width: `${nowPct}%` }} />
        <span aria-hidden="true" className={styles.future} style={{ left: `${nowPct}%` }} />
        <span aria-hidden="true" className={cx(styles.band, to === MAX_STEP && styles.bandOpen)} style={{ left: `${pct(from)}%`, width: `${pct(to) - pct(from)}%` }} />
        <span aria-hidden="true" className={styles.nowLine} style={{ left: `${nowPct}%` }} />
        <span aria-hidden="true" className={styles.thumb} style={{ left: `${pct(from)}%` }} />
        <span aria-hidden="true" className={cx(styles.thumb, to === MAX_STEP && styles.thumbOpen)} style={{ left: `${pct(to)}%` }} />
        <input type="range" min={0} max={MAX_STEP} step={1} value={from} aria-label="True from" aria-valuetext={fromLabel}
          onChange={e => onFrom(Math.min(Number(e.target.value), to))} />
        <input type="range" min={0} max={MAX_STEP} step={1} value={to} aria-label="True until" aria-valuetext={toLabel}
          onChange={e => onTo(Math.max(Number(e.target.value), from))} />
      </div>
      <div className={styles.ticks}>
        {ticks.map(t => <span key={t.i} className={t.i === NOW_STEP ? styles.tickNow : undefined} style={{ left: `${pct(t.i)}%` }}>{t.label}</span>)}
      </div>
    </>
  );
}
