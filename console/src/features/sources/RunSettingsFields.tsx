import { useId } from 'react';
import { Segmented, TextInput } from '../../ui/controls';
import type { RunSettings } from './runSettings';
import { FormRow, invalidProps } from './SourceFields';

interface Props {
  settings: RunSettings;
  errors: Record<string, string>;
  onChange: (settings: RunSettings) => void;
}

/** Mode, schedule and per-run limits, shared by the add form and the settings editor. */
export function RunSettingsFields({ settings, errors, onChange }: Props) {
  const id = useId();
  const set = (patch: Partial<RunSettings>) => onChange({ ...settings, ...patch });
  return (
    <>
      <FormRow id={`${id}-mode`} label="Mode" labelled={false}
        hint={settings.mode === 'raw' ? 'Only makes the text searchable; no model calls.' : 'Extracts memories from what the source reports.'}>
        <Segmented compact label="Mode" value={settings.mode} onChange={mode => set({ mode })}
          options={[{ value: 'full', label: 'Full' }, { value: 'raw', label: 'Raw' }]} />
      </FormRow>
      <FormRow id={`${id}-interval`} label="Run every" hint="Such as 30m, 6h or 1d." error={errors.interval}>
        <TextInput id={`${id}-interval`} mono value={settings.interval} onChange={e => set({ interval: e.target.value })}
          {...invalidProps(`${id}-interval`, errors.interval)} />
      </FormRow>
      <FormRow id={`${id}-items`} label="Max items per run" hint="Empty for no limit." error={errors.maxItems}>
        <TextInput id={`${id}-items`} mono inputMode="numeric" value={settings.maxItems} onChange={e => set({ maxItems: e.target.value })}
          {...invalidProps(`${id}-items`, errors.maxItems)} />
      </FormRow>
      <FormRow id={`${id}-chars`} label="Max characters per run" hint="Empty for no limit." error={errors.maxChars}>
        <TextInput id={`${id}-chars`} mono inputMode="numeric" value={settings.maxChars} onChange={e => set({ maxChars: e.target.value })}
          {...invalidProps(`${id}-chars`, errors.maxChars)} />
      </FormRow>
    </>
  );
}
