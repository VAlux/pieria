import { useEffect, useId, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router';
import { qk } from '../../api/queries';
import { canSignIn, checkSource, createSource, type Harvester, type HarvesterField } from '../../api/sources';
import { profileUrl } from '../../lib/routes';
import { Button, Checkbox, Select, TextInput } from '../../ui/controls';
import { Card, CardHeader, ErrorBanner, Page, PageHeader, SkeletonRows } from '../../ui/display';
import { useToast } from '../../ui/overlay';
import { useProfile } from '../shell/context';
import { SOURCE_ID_ERROR, SOURCE_ID_PATTERN, initialValues, parseValues, type FieldValues } from './form';
import { useHarvesters } from './queries';
import { RunSettingsFields } from './RunSettingsFields';
import { parseRunSettings, type RunSettings } from './runSettings';
import { FormRow, SourceFields } from './SourceFields';
import styles from './sources.module.css';

/** The secret a device-code harvester fills in itself when the user signs in. */
export const SIGN_IN_SECRET = 'refreshToken';

const DEFAULT_RUN: RunSettings = { mode: 'full', interval: '6h', maxItems: '', maxChars: '' };

/** The fields the form asks for; a harvester that signs in fills its own sign-in secret. */
export function formFields(harvester: Harvester | undefined): HarvesterField[] {
  if (!harvester) return [];
  return canSignIn(harvester) ? harvester.fields.filter(f => f.key !== SIGN_IN_SECRET) : harvester.fields;
}

function clearSecrets(fields: HarvesterField[], values: FieldValues): FieldValues {
  const next = { ...values };
  fields.filter(f => f.kind === 'secret').forEach(f => { next[f.key] = ''; });
  return next;
}

export function AddSourcePage() {
  const profile = useProfile();
  const navigate = useNavigate();
  const client = useQueryClient();
  const toast = useToast();
  const [params] = useSearchParams();
  const harvesters = useHarvesters();
  const formId = useId();
  const [harvesterId, setHarvesterId] = useState(params.get('harvester') ?? '');
  const harvester = harvesters.data?.find(h => h.id === harvesterId);
  const fields = useMemo(() => formFields(harvester), [harvester]);
  const [id, setId] = useState('');
  const [values, setValues] = useState<FieldValues>({});
  const [run, setRun] = useState<RunSettings>(DEFAULT_RUN);
  const [start, setStart] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    if (!harvesterId && harvesters.data?.length) setHarvesterId(harvesters.data[0].id);
  }, [harvesterId, harvesters.data]);
  useEffect(() => { setValues(initialValues(fields)); setErrors({}); }, [fields]);

  const add = useMutation({
    mutationFn: async (body: Parameters<typeof createSource>[1]) => {
      const created = await createSource(profile, body);
      const signIn = canSignIn(harvester) && Object.keys(body.secrets).length === 0;
      const check = signIn ? null : await checkSource(profile, created.id);
      return { created, signIn, check };
    },
    onSettled: () => setValues(current => clearSecrets(fields, current)),
    onSuccess: ({ created, signIn, check }) => {
      void client.invalidateQueries({ queryKey: qk.sources(profile) });
      created.warnings.forEach(warning => toast.show(warning));
      const detail = profileUrl(profile, `sources/${encodeURIComponent(created.id)}`);
      if (signIn) {
        navigate(`${detail}?signin=1`);
      } else if (check && !check.ok) {
        toast.show(`The check failed: ${check.message ?? check.errorClass ?? 'unknown error'}`, undefined, 'error');
        navigate(`${detail}?check=failed`);
      } else {
        navigate(detail);
      }
    }
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!harvester) return;
    const parsed = parseValues(fields, values, { secretOptional: canSignIn(harvester) });
    const settings = parseRunSettings(run);
    const found: Record<string, string> = { ...parsed.errors, ...settings.errors };
    if (!SOURCE_ID_PATTERN.test(id)) found.id = SOURCE_ID_ERROR;
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    add.mutate({
      id, harvester: harvester.id, config: parsed.config, secrets: parsed.secrets, mode: settings.mode,
      intervalSeconds: settings.intervalSeconds ?? null, maxItemsPerRun: settings.maxItemsPerRun, maxCharsPerRun: settings.maxCharsPerRun, start
    });
  };

  return (
    <Page>
      <PageHeader crumb="Sources" title="Add a source" />
      {harvesters.isError && <ErrorBanner error={harvesters.error} />}
      {harvesters.isPending ? <SkeletonRows rows={4} /> : (
        <form onSubmit={submit} noValidate className={styles.form}>
          {add.isError && <ErrorBanner error={add.error} />}
          <Card>
            <CardHeader title="Source" />
            <FormRow id={`${formId}-harvester`} label="Harvester" hint={harvester?.network.length ? `Talks to ${harvester.network.join('; ')}` : undefined}>
              <Select id={`${formId}-harvester`} value={harvesterId} onChange={e => setHarvesterId(e.target.value)}>
                {harvesters.data?.map(h => <option key={h.id} value={h.id}>{h.name}</option>)}
              </Select>
            </FormRow>
            <FormRow id={`${formId}-id`} label="Source id" hint="How this source is named in commands and recall labels." required error={errors.id}>
              <TextInput id={`${formId}-id`} mono value={id} onChange={e => setId(e.target.value)} />
            </FormRow>
          </Card>
          <Card>
            <CardHeader title="Settings" sub={harvester?.name} />
            <SourceFields fields={fields} values={values} errors={errors} onChange={(key, value) => setValues(v => ({ ...v, [key]: value }))} />
            {canSignIn(harvester) && <p className={styles.note}>You'll sign in after the source is added.</p>}
          </Card>
          <Card>
            <CardHeader title="Runs" />
            <RunSettingsFields settings={run} errors={errors} onChange={setRun} />
            <Checkbox checked={start} onChange={setStart}>Start on schedule now</Checkbox>
          </Card>
          <div className={styles.formActions}>
            <Button type="submit" variant="primary" disabled={add.isPending || !harvester}>Add source</Button>
          </div>
        </form>
      )}
    </Page>
  );
}
