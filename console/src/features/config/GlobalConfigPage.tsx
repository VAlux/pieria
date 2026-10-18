import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { ApiError, errorMessage } from '../../api/client';
import { globalConfig, restartDaemon, saveGlobalConfig, testProvider, type GlobalConfigEntry, type ProviderTier } from '../../api/config';
import { daemonHealth } from '../../api/profiles';
import { qk, useStats, useStatus } from '../../api/queries';
import { compact, duration, integer, plural } from '../../lib/format';
import { profileUrl } from '../../lib/routes';
import { Button, Segmented, TextInput, cx } from '../../ui/controls';
import { Card, ErrorBanner, Page, PageHeader, PageIntro, Pill, SkeletonRows } from '../../ui/display';
import { Icon } from '../../ui/icons';
import { DisabledHint, useToast } from '../../ui/overlay';
import { useProfile } from '../shell/context';
import { FieldControl, FieldRow } from './fields';
import styles from './config.module.css';

const K = {
  type: 'pieria.provider.type',
  baseUrl: 'pieria.provider.base-url',
  apiKey: 'pieria.provider.api-key',
  name: 'pieria.provider.name',
  apiVersion: 'pieria.provider.api-version',
  extraction: 'pieria.model.extraction-model',
  synthesis: 'pieria.model.synthesis-model',
  embedding: 'pieria.model.embedding',
  dimension: 'pieria.model.embedding-dimension'
};

const MODEL_ROWS = [
  { key: K.extraction, tier: 'extraction', label: 'Extraction', hint: 'turns transcripts into memories' },
  { key: K.synthesis, tier: 'synthesis', label: 'Synthesis', hint: 'writes recall answers' },
  { key: K.embedding, tier: 'embedding', label: 'Embedding', hint: 'vectors for search' }
] as const;

const INHERITED_GROUPS = [
  { section: 'rerank', title: 'Reranking', note: 'retrieval defaults · profiles may override any of them' },
  { section: 'wiki', title: 'Wiki', note: 'disabled by default · queue, batch and inference deadlines' },
  { section: 'traces', title: 'Execution traces', note: 'capture, spool, drain thresholds, recipe extraction' }
];

type Draft = Record<string, string | null>;

export function GlobalConfigPage() {
  const profile = useProfile();
  const client = useQueryClient();
  const toast = useToast();
  const status = useStatus();
  const stats = useStats(profile);
  const config = useQuery({ queryKey: qk.globalConfig, queryFn: ({ signal }) => globalConfig(signal) });
  const [draft, setDraft] = useState<Draft>({});
  const [replacingKey, setReplacingKey] = useState(false);
  const [openGroup, setOpenGroup] = useState<string | null>(null);
  const [restarting, setRestarting] = useState(false);

  const entries = config.data?.entries ?? [];
  const byKey = useMemo(() => new Map(entries.map(e => [e.key, e])), [entries]);
  useEffect(() => { setDraft({}); setReplacingKey(false); }, [config.data]);

  /** What the page shows for a key: the unsaved edit, else the file, else the running value. */
  const current = (key: string): string => {
    if (key in draft) return draft[key] ?? '';
    const e = byKey.get(key);
    return e?.['file-value'] ?? e?.value ?? e?.['default-value'] ?? '';
  };
  const edit = (key: string, value: unknown) => setDraft(d => {
    const e = byKey.get(key);
    const original = e?.['file-value'] ?? e?.value ?? e?.['default-value'] ?? '';
    const next = { ...d };
    if (String(value ?? '') === original) delete next[key];
    else next[key] = value === null ? null : String(value);
    return next;
  });

  const pending = entries.filter(e => e['restart-pending']).length;
  const restartSupported = status.data?.restart?.supported ?? false;
  const restartHint = `Restart with \`${config.data?.restartCommand ?? 'pieria daemon restart'}\` — this daemon is not running under launchd or systemd, so it cannot restart itself.`;

  const restart = useMutation({
    mutationFn: async () => {
      setRestarting(true);
      try {
        await restartDaemon(false);
      } catch (e) {
        if (e instanceof ApiError && e.status === 409 && /task/.test(e.message) && confirm(`${e.message}\n\nRestart anyway?`)) await restartDaemon(true);
        else throw e;
      }
      await new Promise(r => setTimeout(r, 1500));
      for (let i = 0; i < 40; i++) {
        try { if ((await daemonHealth()).status) return; } catch { /* still down */ }
        await new Promise(r => setTimeout(r, 1000));
      }
      throw new Error('The daemon did not come back within 40 seconds. Check `pieria daemon status`.');
    },
    onSuccess: () => { toast.show('Daemon restarted', 'Saved settings are now in effect.'); client.invalidateQueries(); },
    onError: e => toast.show('Restart did not complete', errorMessage(e), 'error'),
    onSettled: () => setRestarting(false)
  });

  const save = useMutation({
    mutationFn: (andRestart: boolean) => saveGlobalConfig(draft).then(result => ({ result, andRestart })),
    onSuccess: ({ result, andRestart }) => {
      client.invalidateQueries({ queryKey: qk.globalConfig });
      toast.show('Saved to pieria.properties', `${plural(result['restart-required'].length, 'change')} take effect after a restart.`);
      if (andRestart) restart.mutate();
    }
  });

  const changes = Object.keys(draft).length;
  const sectionEntries = (section: string) => entries.filter(e => e.section === section);
  const field = (e: GlobalConfigEntry) => ({ ...e, scope: 'global' as const });
  const row = (e: GlobalConfigEntry) => (
    <FieldRow key={e.key} field={field(e)}
      marker={e.provenance === 'set' ? <span aria-label="set in file" title="Set in file" className={styles.setDot} /> : null}
      status={e['restart-pending'] && !(e.key in draft) ? <span className={styles.overriddenNote}>restart pending</span> : undefined}
      control={<FieldControl field={field(e)} value={current(e.key)} inherited={e.provenance !== 'set' && !(e.key in draft)} onChange={v => edit(e.key, v)} />} />
  );

  if (config.isPending) return <Page><SkeletonRows rows={6} height={80} /></Page>;
  if (config.isError) return <Page><ErrorBanner error={config.error} /></Page>;

  const dialect = current(K.type) || 'openai';
  const tierStats = (tier: string) => stats.data?.spend.tiers.find(t => t.tier.toLowerCase() === tier);

  return (
    <Page>
      <PageHeader crumb="Daemon / Settings" title="Global config"
        meta={(
          <PageIntro>
            Process-wide settings, bound when the daemon starts. Every profile inherits the retrieval, ingestion and wiki
            defaults here and can override them in <Link to={profileUrl(profile, 'config')}>Profile config</Link>.
          </PageIntro>
        )}
        actions={(
          <span className={styles.writtenTo}>
            <span>Written to</span>
            <code>{config.data.configFile}</code>
          </span>
        )} />

      <div className={styles.legend}>
        <span><span aria-hidden="true" className={styles.legendRestart} />Restart — applies after the daemon restarts</span>
        <span><Icon name="lock" size={12} strokeWidth={2.4} />Locked — fixed when the store was created</span>
        <span><span aria-hidden="true" className={styles.setDot} />Set in file · others are defaults</span>
      </div>

      {pending > 0 && changes === 0 && (
        <div role="status" className={styles.pendingBanner}>
          <strong>{plural(pending, 'saved change')} waiting for a restart.</strong>
          <span className={styles.grow} />
          {restartSupported
            ? <Button variant="primary" disabled={restarting} onClick={() => restart.mutate()}>{restarting ? 'Restarting…' : 'Restart now'}</Button>
            : <code>{config.data.restartCommand}</code>}
        </div>
      )}

      <ProviderCard entries={byKey} current={current} edit={edit} draft={draft} dialect={dialect}
        replacingKey={replacingKey} setReplacingKey={setReplacingKey} tierStats={tierStats} profile={profile} healthProvider={null} />

      <div className={styles.twoUp}>
        <Card className={styles.groupCard}>
          <h2>Throughput</h2>
          {sectionEntries('throughput').map(row)}
        </Card>
        <Card className={styles.groupCard}>
          <h2>Observability &amp; daemon</h2>
          {sectionEntries('observability').map(row)}
          {sectionEntries('daemon').map(row)}
        </Card>
      </div>

      <div className={styles.stack}>
        <span className={styles.sectionLabel}>Defaults every profile inherits</span>
        {INHERITED_GROUPS.map(group => {
          const list = sectionEntries(group.section);
          const isOpen = openGroup === group.section;
          const set = list.filter(e => e.provenance === 'set').length;
          return (
            <div key={group.section} className={styles.stack}>
              <button type="button" aria-expanded={isOpen} className={styles.collapsed} onClick={() => setOpenGroup(isOpen ? null : group.section)}>
                <span aria-hidden="true" className={cx(styles.caret, isOpen && styles.caretOpen)}>›</span>
                <span className={styles.collapsedTitle}>{group.title}</span>
                <span className={styles.collapsedSummary}>{plural(list.length, 'field')} · {group.note}</span>
                <span className={styles.grow} />
                <span className={set ? styles.overriddenNote : styles.inheritedNote}>{set ? `${set} set in file` : 'defaults'}</span>
              </button>
              {isOpen && <Card className={styles.groupCard}>{list.map(row)}</Card>}
            </div>
          );
        })}
      </div>

      <section aria-labelledby="storage-h" className={styles.storage}>
        <div className={styles.storageHead}>
          <Icon name="lock" size={14} strokeWidth={2.2} />
          <h2 id="storage-h">Storage</h2>
          <span>Locked. Changing these means a new store and re-embedding every memory.</span>
        </div>
        <dl>
          {sectionEntries('storage').map(e => (
            <div key={e.key}>
              <dt>{e.label}</dt>
              <dd className="mono">{e.value || e['default-value'] || '—'}{e.key === K.dimension && <span> · width of memories_vec</span>}</dd>
            </div>
          ))}
        </dl>
      </section>

      {changes > 0 && (
        <div role="status" className={styles.dirtyBar}>
          <strong>{plural(changes, 'change')} {changes === 1 ? 'needs' : 'need'} a restart</strong>
          <span>Saved to pieria.properties; the daemon picks them up after a restart.</span>
          {save.error && <span className={styles.fieldError}>{errorMessage(save.error)}</span>}
          <span className={styles.grow} />
          <Button size="lg" onClick={() => { setDraft({}); setReplacingKey(false); }}>Discard</Button>
          <Button size="lg" variant="accent" disabled={save.isPending} onClick={() => save.mutate(false)}>Save only</Button>
          {restartSupported
            ? <Button size="lg" variant="primary" disabled={save.isPending || restarting} onClick={() => save.mutate(true)}>Save &amp; restart</Button>
            : <DisabledHint reason={restartHint}><Button size="lg" variant="primary" disabled>Save &amp; restart</Button></DisabledHint>}
        </div>
      )}
    </Page>
  );
}

interface ProviderProps {
  entries: Map<string, GlobalConfigEntry>;
  current: (key: string) => string;
  edit: (key: string, value: unknown) => void;
  draft: Draft;
  dialect: string;
  replacingKey: boolean;
  setReplacingKey: (v: boolean) => void;
  tierStats: (tier: string) => { calls: number; promptTokens: number; completionTokens: number } | undefined;
  profile: string;
  healthProvider: string | null;
}

function ProviderCard({ entries, current, edit, draft, dialect, replacingKey, setReplacingKey, tierStats, profile }: ProviderProps) {
  const status = useStatus();
  const [results, setResults] = useState<ProviderTier[] | null>(null);
  const test = useMutation({
    mutationFn: () => testProvider({
      type: current(K.type) || undefined,
      baseUrl: current(K.baseUrl) || undefined,
      apiKey: draft[K.apiKey] || undefined,
      apiVersion: current(K.apiVersion) || undefined,
      extractionModel: current(K.extraction) || undefined,
      synthesisModel: current(K.synthesis) || undefined,
      embeddingModel: current(K.embedding) || undefined
    }),
    onSuccess: setResults
  });
  const reachable = results ? results.every(r => r.reachable) : null;
  const keyEntry = entries.get(K.apiKey);
  const dimension = entries.get(K.dimension)?.value;
  const input = (key: string, props: { type?: string; label: string; suffix?: string }) => (
    <label className={styles.stackField}>
      <span>{props.label}{props.suffix && <span className={styles.muted}> · {props.suffix}</span>}</span>
      <TextInput mono type={props.type ?? 'text'} value={current(key)} onChange={e => edit(key, e.target.value)} />
    </label>
  );
  return (
    <Card className={styles.providerCard} aria-labelledby="provider-h">
      <div className={styles.providerHead}>
        <h2 id="provider-h">Model provider</h2>
        {reachable === true && <Pill tone="ok" dot="var(--ready)">reachable</Pill>}
        {reachable === false && <Pill tone="fail" dot="var(--fail)">{results?.every(r => !r.reachable) ? 'unreachable' : 'partly reachable'}</Pill>}
        {reachable === null && status.data && <span className={styles.muted}>running: {status.data.providerLabel ?? status.data.modelProvider}</span>}
        <span className={styles.grow} />
        <span className={styles.muted}>spends a few tokens per model</span>
        <Button disabled={test.isPending} onClick={() => test.mutate()}>{test.isPending ? 'Testing…' : 'Test connection'}</Button>
      </div>
      {test.error && <ErrorBanner error={test.error} />}
      <div className={styles.providerGrid}>
        <div className={styles.stackField}>
          <span>Dialect</span>
          <Segmented label="Provider dialect" value={dialect} onChange={v => edit(K.type, v)}
            options={[{ value: 'openai', label: 'OpenAI-compatible' }, { value: 'azure', label: 'Azure' }]} />
          <span className={styles.muted}>{dialect === 'azure' ? 'Model names below are Azure deployment names.' : 'Covers Ollama, LM Studio, llama.cpp, vLLM, OpenRouter and OpenAI.'}</span>
        </div>
        {input(K.baseUrl, { label: 'Base URL', suffix: 'without /v1', type: 'url' })}
        <div className={styles.stackField}>
          <span>API key</span>
          {replacingKey ? (
            <TextInput mono type="password" autoComplete="off" autoFocus placeholder="Paste the new key" value={draft[K.apiKey] ?? ''}
              onChange={e => edit(K.apiKey, e.target.value)} />
          ) : (
            <span className={styles.secretRow}>
              <span className={styles.secret}>{keyEntry?.['secret-set'] ? '••••••••••••' : 'not set'}</span>
              <Button onClick={() => setReplacingKey(true)}>Replace</Button>
            </span>
          )}
          <span className={styles.muted}>Write-only: the console never reads the key back.</span>
        </div>
        {input(K.name, { label: 'Label' })}
        {dialect === 'azure' && input(K.apiVersion, { label: 'Azure API version' })}
      </div>

      <div className={styles.models}>
        <span className={styles.sectionLabel}>{dialect === 'azure' ? 'Deployments' : 'Models'}</span>
        {MODEL_ROWS.map(m => {
          const stats = tierStats(m.tier);
          const result = results?.find(r => r.tier === m.tier);
          return (
            <div key={m.key} className={styles.modelRow}>
              <label htmlFor={`model-${m.tier}`} className={styles.fieldText}>
                <span className={styles.fieldLabel}>{m.label}</span>
                <span className={styles.fieldHint}>{m.hint}</span>
              </label>
              <TextInput id={`model-${m.tier}`} mono value={current(m.key)} onChange={e => edit(m.key, e.target.value)} />
              <span className={styles.modelNote}>
                {result
                  ? result.reachable
                    ? <span className={result.dimensionMatches === false ? styles.overriddenNote : styles.okNote}>
                        ok · {duration(result.latencyMs)}{result.embeddingDimensions !== undefined && ` · ${result.embeddingDimensions} dims${result.dimensionMatches === false ? ` ≠ ${dimension}` : ''}`}
                      </span>
                    : <span className={styles.fieldError} title={result.error}>{result.errorKind?.replace(/_/g, ' ')}: {result.error}</span>
                  : m.tier === 'embedding'
                    ? <span className={styles.overriddenNote}>must match the locked dimension{dimension ? ` (${dimension})` : ''}</span>
                    : stats ? <span className="mono">{profile}: {integer(stats.calls)} calls · {compact(stats.promptTokens + stats.completionTokens)} tok</span> : null}
              </span>
            </div>
          );
        })}
      </div>
    </Card>
  );
}
