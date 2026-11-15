import { useId, useMemo, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { qk } from '../../api/queries';
import {
  acceptSweep, canSignIn, checkSource, listRuns, pauseSource, removeSource, resumeSource, runSource, setSecrets, updateSource,
  type Harvester, type HarvesterField, type Source, type SourceCheck, type UpdateSource
} from '../../api/sources';
import { getTask } from '../../api/tasks';
import { integer } from '../../lib/format';
import { profileUrl } from '../../lib/routes';
import { Button, Checkbox, TextInput } from '../../ui/controls';
import { Badge, Card, CardHeader, ErrorBanner, Page, PageHeader, SkeletonRows } from '../../ui/display';
import { Dialog, DisabledHint, useToast } from '../../ui/overlay';
import { useProfile } from '../shell/context';
import { SIGN_IN_SECRET } from './AddSourcePage';
import { configChanges, formatInterval, initialValues, parseValues, type FieldValues } from './form';
import { harvesterFor, useHarvesters, useSource } from './queries';
import { RunHistory } from './RunHistory';
import { RunSettingsFields } from './RunSettingsFields';
import { parseRunSettings, type RunSettings } from './runSettings';
import { FormRow, SourceFields } from './SourceFields';
import { statusLabel } from './status';
import styles from './sources.module.css';

const RUNNING_REASON = 'A run is in progress.';

/** A button that explains why it is disabled. */
function Guarded({ reason, children }: { reason: string | null; children: ReactNode }) {
  return reason ? <DisabledHint reason={reason}>{children}</DisabledHint> : <>{children}</>;
}

function displayValue(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value);
}

function runSettingsOf(source: Source): RunSettings {
  return {
    mode: source.mode,
    interval: formatInterval(source.intervalSeconds),
    maxItems: source.maxItemsPerRun === null ? '' : String(source.maxItemsPerRun),
    maxChars: source.maxCharsPerRun === null ? '' : String(source.maxCharsPerRun)
  };
}

/** The harvester's settings and the run settings, sending only what changed. */
function SettingsCard({ profile, source, harvester }: { profile: string; source: Source; harvester: Harvester }) {
  const client = useQueryClient();
  const toast = useToast();
  const fields = useMemo(() => harvester.fields.filter(f => f.kind !== 'secret'), [harvester]);
  const [values, setValues] = useState<FieldValues>(() => initialValues(fields, source.config));
  const [run, setRun] = useState<RunSettings>(() => runSettingsOf(source));
  const [resetCursor, setResetCursor] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const baseline = useMemo(() => parseValues(fields, initialValues(fields, source.config)).config, [fields, source.config]);

  const parsed = parseValues(fields, values);
  const settings = parseRunSettings(run);
  const limitErrors: Record<string, string> = {};
  const body: UpdateSource = {};
  const changes = configChanges(fields, baseline, parsed.config);
  if (Object.keys(changes).length > 0) body.config = changes;
  if (settings.mode !== source.mode) body.mode = settings.mode;
  if (settings.intervalSeconds !== undefined && settings.intervalSeconds !== source.intervalSeconds) body.intervalSeconds = settings.intervalSeconds;
  if (settings.maxItemsPerRun !== source.maxItemsPerRun) {
    if (settings.maxItemsPerRun === null) limitErrors.maxItems = 'A limit can’t be removed here; set a larger one instead';
    else body.maxItemsPerRun = settings.maxItemsPerRun;
  }
  if (settings.maxCharsPerRun !== source.maxCharsPerRun) {
    if (settings.maxCharsPerRun === null) limitErrors.maxChars = 'A limit can’t be removed here; set a larger one instead';
    else body.maxCharsPerRun = settings.maxCharsPerRun;
  }
  if (resetCursor) body.resetCursor = true;
  const dirty = Object.keys(body).length > 0 || Object.keys(limitErrors).length > 0;

  const save = useMutation({
    mutationFn: (update: UpdateSource) => updateSource(profile, source.id, update),
    onSuccess: updated => {
      client.setQueryData(qk.source(profile, source.id), updated);
      void client.invalidateQueries({ queryKey: qk.sources(profile), exact: true });
      toast.show('Saved.');
    }
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = { ...parsed.errors, ...settings.errors, ...limitErrors };
    setErrors(found);
    if (Object.keys(found).length === 0) save.mutate(body);
  };

  return (
    <Card>
      <CardHeader title="Settings" sub={harvester.name} />
      <form onSubmit={submit} noValidate className={styles.form}>
        {save.isError && <ErrorBanner error={save.error} />}
        <div>
          <SourceFields fields={fields} values={values} errors={errors} onChange={(key, value) => setValues(v => ({ ...v, [key]: value }))} />
          <RunSettingsFields settings={run} errors={errors} onChange={setRun} />
          <Checkbox checked={resetCursor} onChange={setResetCursor}>
            Reset cursor: the next run starts over from the beginning.
          </Checkbox>
        </div>
        <div className={styles.formActions}>
          <Button type="submit" variant="primary" disabled={!dirty || save.isPending}>Save settings</Button>
        </div>
      </form>
    </Card>
  );
}

function ReplaceSecretDialog({ profile, source, field, onClose }: { profile: string; source: Source; field: HarvesterField; onClose: () => void }) {
  const client = useQueryClient();
  const toast = useToast();
  const id = useId();
  const [value, setValue] = useState('');
  const save = useMutation({
    mutationFn: (secret: string) => setSecrets(profile, source.id, { [field.key]: secret }),
    onSettled: () => setValue(''),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: qk.source(profile, source.id) });
      toast.show('Credential replaced.');
      onClose();
    }
  });
  return (
    <Dialog title={`Replace ${field.label}`} onClose={onClose} footer={(
      <>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="primary" disabled={value.length === 0 || save.isPending} onClick={() => save.mutate(value)}>Save credential</Button>
      </>
    )}>
      {save.isError && <ErrorBanner error={save.error} />}
      <FormRow id={id} label={field.label} hint="The value is sent to the daemon once and never shown again.">
        <TextInput id={id} type="password" autoComplete="new-password" value={value} onChange={e => setValue(e.target.value)} />
      </FormRow>
    </Dialog>
  );
}

function RemoveDialog({ profile, source, onClose }: { profile: string; source: Source; onClose: () => void }) {
  const client = useQueryClient();
  const navigate = useNavigate();
  const [forget, setForget] = useState(false);
  const remove = useMutation({
    mutationFn: () => removeSource(profile, source.id, forget),
    onSuccess: () => {
      client.removeQueries({ queryKey: qk.source(profile, source.id) });
      void client.invalidateQueries({ queryKey: qk.sources(profile) });
      navigate(profileUrl(profile, 'sources'));
    }
  });
  return (
    <Dialog title={`Remove ${source.id}?`} onClose={onClose} footer={(
      <>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="danger" disabled={remove.isPending} onClick={() => remove.mutate()}>Remove source</Button>
      </>
    )}>
      {remove.isError && <ErrorBanner error={remove.error} />}
      <p>The source stops running and its settings and credentials are deleted.</p>
      <Checkbox checked={forget} onChange={setForget}>Also forget the memories this source added</Checkbox>
    </Dialog>
  );
}

function DryRunResult({ taskId }: { taskId: string }) {
  const task = useQuery({
    queryKey: ['tasks', 'status', taskId],
    queryFn: ({ signal }) => getTask(taskId, signal),
    refetchInterval: query => (query.state.data && query.state.data.status !== 'RUNNING' ? false : 1_000)
  });
  const data = task.data;
  if (task.isError) return <ErrorBanner error={task.error} />;
  if (!data || data.status === 'RUNNING') return <p className={styles.estimate}>Dry run in progress…</p>;
  if (data.status !== 'SUCCEEDED') return <ErrorBanner>{data.errorMessage ?? `The dry run ${data.status.toLowerCase()}.`}</ErrorBanner>;
  const r = data.result ?? {};
  const n = (key: string) => integer(Number(r[key] ?? 0));
  return (
    <p className={styles.estimate}>
      {`${n('itemsSeen')} items, ${n('itemsChanged')} changed, ${n('chars')} characters · about ${n('estimatedChunks')} chunks to extract`}
    </p>
  );
}

function checkText(check: SourceCheck): string {
  return check.ok ? 'The check passed.' : `The check failed (${check.errorClass ?? 'error'}): ${check.message ?? ''}`;
}

export function SourceDetailPage() {
  const profile = useProfile();
  const { sourceId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const client = useQueryClient();
  const toast = useToast();
  const query = useSource(profile, sourceId);
  const harvesters = useHarvesters();
  const source = query.data;
  const runs = useQuery({
    queryKey: qk.sourceRuns(profile, sourceId),
    queryFn: ({ signal }) => listRuns(profile, sourceId, 20, signal),
    enabled: Boolean(profile && sourceId),
    refetchInterval: source?.running ? 2_000 : 15_000
  });
  const [secretKey, setSecretKey] = useState<string | null>(null);
  const [removing, setRemoving] = useState(false);
  const [dryRunTask, setDryRunTask] = useState<string | null>(null);
  const [check, setCheck] = useState<SourceCheck | null>(null);

  const refresh = (updated?: Source) => {
    if (updated) client.setQueryData(qk.source(profile, sourceId), updated);
    void client.invalidateQueries({ queryKey: qk.source(profile, sourceId) });
    void client.invalidateQueries({ queryKey: qk.sources(profile), exact: true });
  };
  const action = useMutation({
    mutationFn: async (kind: 'run' | 'dry' | 'pause' | 'resume' | 'check' | 'sweep') => {
      switch (kind) {
        case 'run': await runSource(profile, sourceId, false); toast.show('Run started.'); return undefined;
        case 'dry': setDryRunTask(await runSource(profile, sourceId, true)); return undefined;
        case 'pause': return pauseSource(profile, sourceId);
        case 'resume': return resumeSource(profile, sourceId);
        case 'sweep': return acceptSweep(profile, sourceId);
        case 'check': setCheck(await checkSource(profile, sourceId)); return undefined;
      }
    },
    onSuccess: updated => refresh(updated)
  });

  if (query.isPending) return <Page><SkeletonRows rows={4} /></Page>;
  if (query.isError || !source) return <Page><ErrorBanner error={query.error} /></Page>;

  const harvester = harvesterFor(harvesters.data, source.harvester);
  const signIn = canSignIn(harvester);
  const status = statusLabel(source, signIn);
  const secretFields = (harvester?.fields ?? []).filter(f => f.kind === 'secret' && !(signIn && f.key === SIGN_IN_SECRET));
  const openSecret = secretKey ?? (params.get('secrets') === '1' ? secretFields[0]?.key ?? null : null);
  const secretField = secretFields.find(f => f.key === openSecret);
  const closeSecret = () => {
    setSecretKey(null);
    if (params.has('secrets')) setParams(p => { const next = new URLSearchParams(p); next.delete('secrets'); return next; }, { replace: true });
  };
  const busy = source.running ? RUNNING_REASON : null;
  const signInButton = signIn && (
    <DisabledHint reason="Coming in the next step"><Button disabled>Sign in</Button></DisabledHint>
  );
  const settingsKey = JSON.stringify([source.config, source.mode, source.intervalSeconds, source.maxItemsPerRun, source.maxCharsPerRun]);

  return (
    <Page>
      <PageHeader crumb={<Link to={profileUrl(profile, 'sources')}>Sources</Link>} title={source.id}
        meta={(
          <span className={styles.rowMain}>
            <Badge tone={status.tone} sans>{status.label}</Badge>
            <span className={styles.harvester}>{harvester?.name ?? source.harvester}</span>
            <Badge tone="neutral" sans>{source.mode}</Badge>
          </span>
        )}
        actions={(
          <span className={styles.actions}>
            <Guarded reason={busy}><Button variant="primary" disabled={Boolean(busy) || action.isPending} onClick={() => action.mutate('run')}>Run now</Button></Guarded>
            <Guarded reason={busy}><Button disabled={Boolean(busy) || action.isPending} onClick={() => action.mutate('dry')}>Dry run</Button></Guarded>
            {source.status === 'paused' || source.status === 'needs_auth'
              ? <Button disabled={action.isPending} onClick={() => action.mutate('resume')}>Resume</Button>
              : <Button disabled={action.isPending} onClick={() => action.mutate('pause')}>Pause</Button>}
            <Button disabled={action.isPending || !harvester} onClick={() => action.mutate('check')}>Check</Button>
            {signInButton}
            <Guarded reason={busy}><Button variant="danger" disabled={Boolean(busy)} onClick={() => setRemoving(true)}>Remove</Button></Guarded>
          </span>
        )} />

      <div className={styles.banners}>
        {action.isError && <ErrorBanner error={action.error} />}
        {!harvester && harvesters.data && (
          <div className={`${styles.banner} ${styles.bannerWarn}`}>
            Harvester {source.harvester} isn't installed. Install it again with <code>pieria harvester install</code>, or remove this source.
          </div>
        )}
        {source.status === 'needs_auth' && (signIn ? (
          <div className={`${styles.banner} ${styles.bannerWarn}`}>This source needs you to sign in again.<span className={styles.grow} />{signInButton}</div>
        ) : (
          <div className={`${styles.banner} ${styles.bannerWarn}`}>The source's credentials were refused. Replace them below, then resume it.</div>
        ))}
        {source.sweepBlockedCount !== null && (
          <div className={`${styles.banner} ${styles.bannerWarn}`}>
            {`The last full run would delete ${integer(source.sweepBlockedCount)} items, more than the safety limit allows. Nothing was deleted.`}
            <span className={styles.grow} />
            {source.acceptNextSweep
              ? <span>Allowed; the next full run will delete them.</span>
              : <Button disabled={action.isPending} onClick={() => action.mutate('sweep')}>Allow these deletions</Button>}
          </div>
        )}
        {check && <div className={`${styles.banner} ${check.ok ? '' : styles.bannerWarn}`}>{checkText(check)}</div>}
        {!check && params.get('check') === 'failed' && (
          <div className={`${styles.banner} ${styles.bannerWarn}`}>The check failed when the source was added. Fix the settings or credentials, then use Check.</div>
        )}
        {dryRunTask && <div className={styles.banner}><DryRunResult taskId={dryRunTask} /></div>}
      </div>

      {harvester ? <SettingsCard key={settingsKey} profile={profile} source={source} harvester={harvester} /> : (
        <Card>
          <CardHeader title="Settings" />
          <p className={styles.note}>The harvester isn't installed, so these settings can't be edited.</p>
          <dl className={styles.config}>
            {Object.entries(source.config).map(([key, value]) => (
              <div key={key} style={{ display: 'contents' }}><dt>{key}</dt><dd>{displayValue(value)}</dd></div>
            ))}
          </dl>
        </Card>
      )}

      <CredentialsCard source={source} fields={secretFields} known={Boolean(harvester)} onReplace={setSecretKey} />

      <Card>
        <CardHeader title="Run history" />
        {runs.isError ? <ErrorBanner error={runs.error} /> : <RunHistory runs={runs.data ?? []} />}
      </Card>

      {secretField && <ReplaceSecretDialog profile={profile} source={source} field={secretField} onClose={closeSecret} />}
      {removing && <RemoveDialog profile={profile} source={source} onClose={() => setRemoving(false)} />}
    </Page>
  );
}

function CredentialsCard({ source, fields, known, onReplace }: { source: Source; fields: HarvesterField[]; known: boolean; onReplace: (key: string) => void }) {
  const heading = useId();
  if (known && fields.length === 0) return null;
  return (
    <Card aria-labelledby={heading}>
      <CardHeader id={heading} title="Credentials" sub="Values are never shown" />
      <ul className={styles.list}>
        {known ? fields.map(field => (
          <li key={field.key} className={styles.row}>
            <div className={styles.rowMain}>
              <span>{field.label}</span>
              {source.secretNames.includes(field.key) ? <Badge tone="ok" sans>Set</Badge> : <Badge tone="neutral" sans>Not set</Badge>}
              <span className={styles.grow} />
              <Button size="sm" onClick={() => onReplace(field.key)}>Replace</Button>
            </div>
          </li>
        )) : source.secretNames.map(name => (
          <li key={name} className={styles.row}><div className={styles.rowMain}><span className={styles.mono}>{name}</span><Badge tone="ok" sans>Set</Badge></div></li>
        ))}
      </ul>
    </Card>
  );
}
