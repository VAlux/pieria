import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { errorMessage } from '../../api/client';
import { configSchema, profileConfig, saveProfileConfig, type ConfigField } from '../../api/config';
import { qk } from '../../api/queries';
import { integer, plural } from '../../lib/format';
import { profileUrl } from '../../lib/routes';
import { Button, SearchField, Segmented, cx } from '../../ui/controls';
import { Card, ErrorBanner, Page, PageHeader, PageIntro, SkeletonRows } from '../../ui/display';
import { useToast } from '../../ui/overlay';
import { useProfile } from '../shell/context';
import { FieldControl, FieldRow } from './fields';
import { changedKeys, flatten, summarize, unflatten } from './model';
import styles from './config.module.css';

const SECTIONS: { id: string; title: string; note?: string; open: boolean }[] = [
  { id: 'channels', title: 'Retrieval channels', note: 'Weights feed reciprocal-rank fusion. 0 disables a channel.', open: true },
  { id: 'fusion', title: 'Fusion & limits', open: true },
  { id: 'wiki', title: 'Wiki', open: true },
  { id: 'graph', title: 'Graph traversal', open: false },
  { id: 'code-graph', title: 'Code graph', open: false },
  { id: 'rerank', title: 'Reranking', open: false },
  { id: 'ingestion', title: 'Ingestion', open: false }
];

const TONES = ['#E8B062', '#7DB4E8', '#B9A0EE', '#8FBCE9', '#C9C5BD', '#7CCB9F', '#A9CDF2', '#9FD9BB'];

export function ProfileConfigPage() {
  const profile = useProfile();
  const client = useQueryClient();
  const toast = useToast();
  const schema = useQuery({ queryKey: qk.configSchema, queryFn: ({ signal }) => configSchema(signal), staleTime: Infinity });
  const detail = useQuery({ queryKey: qk.profileConfig(profile), queryFn: ({ signal }) => profileConfig(profile, signal) });
  const fields = useMemo(() => (schema.data ?? []).filter(f => f.scope === 'profile'), [schema.data]);
  const saved = useMemo(() => flatten(detail.data?.overrides), [detail.data]);
  const globals = useMemo(() => flatten(detail.data?.global), [detail.data]);
  const [draft, setDraft] = useState<Record<string, unknown>>({});
  const [filter, setFilter] = useState('');
  const [onlyOverridden, setOnlyOverridden] = useState(false);
  const [open, setOpen] = useState<Record<string, boolean>>(() => Object.fromEntries(SECTIONS.map(s => [s.id, s.open])));

  useEffect(() => { setDraft(saved); }, [saved]);

  const dirty = changedKeys(draft, saved);
  const apply = useMutation({
    mutationFn: () => saveProfileConfig(profile, unflatten(draft)),
    onSuccess: () => {
      toast.show('Profile config applied', 'The next recall and ingest use it; no restart needed.');
      client.invalidateQueries({ queryKey: qk.profileConfig(profile) });
    }
  });

  const set = (key: string, value: unknown) => setDraft(d => {
    const next = { ...d };
    if (value === undefined || String(value) === String(globals[key])) delete next[key];
    else next[key] = value;
    return next;
  });

  const exportOverrides = () => {
    const blob = new Blob([JSON.stringify(detail.data?.overrides ?? {}, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${profile}-config-overrides.json`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  };

  const q = filter.trim().toLowerCase();
  const visible = (f: ConfigField) => (!q || f.label.toLowerCase().includes(q) || f.key.toLowerCase().includes(q)) && (!onlyOverridden || f.key in draft);
  const overrideCount = Object.keys(draft).length;

  return (
    <Page>
      <PageHeader crumb={<>{profile} / Settings</>} title="Profile config"
        meta={(
          <PageIntro>
            Overrides for <strong>{profile}</strong> only. Anything left alone inherits{' '}
            <Link to={profileUrl(profile, 'settings')}>Global config</Link>. Every setting here applies live — no restart.
          </PageIntro>
        )}
        actions={(
          <>
            <span className={styles.countPill}><b>{integer(overrideCount)}</b> overridden · {integer(Math.max(0, fields.length - overrideCount))} inherited</span>
            <Button icon="download" onClick={exportOverrides} disabled={!detail.data}>Export overrides</Button>
          </>
        )} />

      <Card className={styles.toolbar}>
        <SearchField label="Filter settings" value={filter} onChange={setFilter} placeholder="Filter by label or key, e.g. rerank, rrf-k…" className={styles.grow} />
        <Segmented label="Show" value={onlyOverridden ? 'over' : 'all'} onChange={v => setOnlyOverridden(v === 'over')}
          options={[{ value: 'all', label: `All ${fields.length}` }, { value: 'over', label: 'Overridden only' }]} />
      </Card>

      {(schema.isError || detail.isError) && <ErrorBanner error={schema.error ?? detail.error} />}
      {(schema.isPending || detail.isPending) && <SkeletonRows rows={6} height={60} />}

      {detail.data && schema.data && (
        <div className={styles.layout}>
          <nav aria-label="Config sections" className={styles.sectionNav}>
            {SECTIONS.map(section => {
              const sectionFields = fields.filter(f => f.section === section.id);
              const overridden = sectionFields.filter(f => f.key in draft).length;
              return (
                <a key={section.id} href={`#section-${section.id}`} className={styles.sectionLink}
                  onClick={() => setOpen(o => ({ ...o, [section.id]: true }))}>
                  <span>{section.title}</span>
                  <span className={cx(styles.sectionCount, overridden > 0 && styles.sectionCountOn)}>{overridden}/{sectionFields.length}</span>
                </a>
              );
            })}
          </nav>

          <div className={styles.sections}>
            {SECTIONS.map(section => {
              const sectionFields = fields.filter(f => f.section === section.id);
              const shown = sectionFields.filter(visible);
              if (shown.length === 0) return null;
              const overridden = sectionFields.filter(f => f.key in draft).length;
              const expanded = open[section.id] || Boolean(q) || onlyOverridden;
              const effective = (key: string) => (key in draft ? draft[key] : globals[key]);
              const status = (f: ConfigField) => (f.key in draft
                ? <><span className={styles.overriddenNote}>global {String(globals[f.key])}</span>
                  <Button size="sm" variant="accent" onClick={() => set(f.key, undefined)}>Reset</Button></>
                : <span className={styles.inheritedNote}>inherited</span>);
              if (!expanded) {
                return (
                  <button key={section.id} id={`section-${section.id}`} type="button" aria-expanded={false} className={styles.collapsed}
                    onClick={() => setOpen(o => ({ ...o, [section.id]: true }))}>
                    <span aria-hidden="true" className={styles.caret}>›</span>
                    <span className={styles.collapsedTitle}>{section.title}</span>
                    <span className={styles.collapsedSummary}>{summarize(sectionFields, Object.fromEntries(sectionFields.map(f => [f.key, effective(f.key)])))}</span>
                    <span className={styles.grow} />
                    <span className={overridden ? styles.overriddenNote : styles.inheritedNote}>{overridden ? plural(overridden, 'override') : 'all inherited'}</span>
                  </button>
                );
              }
              if (section.id === 'channels') {
                return <ChannelsCard key={section.id} fields={shown} draft={draft} globals={globals} set={set} status={status} note={section.note} />;
              }
              const lead = section.id === 'wiki' ? shown.find(f => f.key === 'wiki.enabled') : undefined;
              const rest = shown.filter(f => f !== lead);
              return (
                <Card key={section.id} flush id={`section-${section.id}`} aria-labelledby={`h-${section.id}`} className={styles.sectionCard}>
                  <div className={styles.sectionHead}>
                    <h2 id={`h-${section.id}`}>{section.title}</h2>
                    <span>{plural(sectionFields.length, 'field')} · {overridden ? `${overridden} overridden` : 'all inherited'}</span>
                    <span className={styles.grow} />
                    {!section.open && <Button size="sm" onClick={() => setOpen(o => ({ ...o, [section.id]: false }))}>Collapse</Button>}
                  </div>
                  {lead && (
                    <div className={styles.leadRow}>
                      <FieldRow field={lead} control={<FieldControl field={lead} value={effective(lead.key)} inherited={!(lead.key in draft)} onChange={v => set(lead.key, v)} />} status={status(lead)} />
                    </div>
                  )}
                  <div className={styles.fieldGrid}>
                    {rest.map(f => (
                      <FieldRow key={f.key} field={f} status={status(f)}
                        control={<FieldControl field={f} value={effective(f.key)} inherited={!(f.key in draft)} onChange={v => set(f.key, v)} />} />
                    ))}
                  </div>
                  <span className={styles.cardNote}>Inherited values show in grey; typing a value creates an override.</span>
                </Card>
              );
            })}
          </div>
        </div>
      )}

      {dirty.length > 0 && (
        <div role="status" className={styles.dirtyBar}>
          <strong>{plural(dirty.length, 'unsaved change')}</strong>
          <span>Applied live to the next recall — no restart.</span>
          {apply.error && <span className={styles.fieldError}>{errorMessage(apply.error)}</span>}
          <span className={styles.grow} />
          <Button size="lg" onClick={() => setDraft(saved)}>Discard</Button>
          <Button size="lg" variant="primary" disabled={apply.isPending} onClick={() => apply.mutate()}>Apply</Button>
        </div>
      )}
    </Page>
  );
}

interface ChannelsProps {
  fields: ConfigField[];
  draft: Record<string, unknown>;
  globals: Record<string, unknown>;
  set: (key: string, value: unknown) => void;
  status: (f: ConfigField) => React.ReactNode;
  note?: string;
}

function ChannelsCard({ fields, draft, globals, set, status, note }: ChannelsProps) {
  const weights = fields.filter(f => f.kind === 'weight');
  const others = fields.filter(f => f.kind !== 'weight');
  const value = (key: string) => Number(key in draft ? draft[key] : globals[key]) || 0;
  const total = weights.reduce((n, f) => n + value(f.key), 0);
  return (
    <Card flush id="section-channels" aria-labelledby="h-channels" className={styles.sectionCard}>
      <div className={styles.sectionHead}>
        <h2 id="h-channels">Retrieval channels</h2>
        <span>{note}</span>
        <span className={styles.grow} />
        <span className="mono">total {total}</span>
      </div>
      <div role="img" aria-label="Share of fusion weight by channel" className={styles.shareBar}>
        {weights.map((f, i) => <span key={f.key} title={`${f.label} ${total ? Math.round((value(f.key) / total) * 100) : 0}%`}
          style={{ width: `${total ? (value(f.key) / total) * 100 : 0}%`, background: TONES[i % TONES.length] }} />)}
      </div>
      <div className={styles.scrollX}>
        <div className={styles.channelTable}>
          {weights.map(f => {
            const v = value(f.key);
            const g = Number(globals[f.key]) || 0;
            const overridden = f.key in draft;
            const max = Math.max(5, v, g);
            const share = total ? (v / total) * 100 : 0;
            return (
              <div key={f.key} className={cx(styles.channelRow, overridden && styles.channelOverridden)}>
                <span className={styles.fieldText}>
                  <span className={styles.fieldLabel}>{f.label}</span>
                  <span className={styles.fieldKey}>{f.key}</span>
                </span>
                <span className={styles.slider}>
                  <span className={styles.sliderTrack}>
                    {overridden && <span className={styles.globalTick} style={{ left: `${(g / max) * 100}%` }} title={`global ${g}`} />}
                    <span className={cx(styles.sliderFill, overridden && styles.sliderFillOn)} style={{ width: `${(v / max) * 100}%` }} />
                    <span className={cx(styles.sliderThumb, overridden && styles.sliderThumbOn)} style={{ left: `${(v / max) * 100}%` }} />
                    <input type="range" min={0} max={max} step={0.5} value={v} aria-label={`${f.label} weight`}
                      onChange={e => set(f.key, Number(e.target.value))} />
                  </span>
                  <span className={styles.sliderValue}>{v}</span>
                </span>
                <span className={styles.share}>
                  <span className={styles.shareTrack}><span className={overridden ? styles.shareOn : undefined} style={{ width: `${share}%` }} /></span>
                  <span className="mono">{Math.round(share)}%</span>
                </span>
                <span className={styles.fieldStatus}>{status(f)}</span>
              </div>
            );
          })}
        </div>
      </div>
      {others.map(f => (
        <div key={f.key} className={styles.leadRow}>
          <FieldRow field={f} status={status(f)}
            control={<FieldControl field={f} value={f.key in draft ? draft[f.key] : globals[f.key]} inherited={!(f.key in draft)} onChange={v => set(f.key, v)} />} />
        </div>
      ))}
    </Card>
  );
}
