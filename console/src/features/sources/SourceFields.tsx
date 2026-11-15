import { useId } from 'react';
import type { ReactNode } from 'react';
import type { HarvesterField } from '../../api/sources';
import { Segmented, Select, Switch, TextArea, TextInput } from '../../ui/controls';
import type { FieldValues } from './form';
import styles from './sources.module.css';

interface FieldRowProps {
  id: string;
  label: string;
  hint?: ReactNode;
  required?: boolean;
  error?: string;
  children: ReactNode;
}

/** A labelled control with its hint and error, the layout every source form uses. */
export function FormRow({ id, label, hint, required, error, children }: FieldRowProps) {
  return (
    <div className={styles.formRow}>
      <span className={styles.formText}>
        <label htmlFor={id} className={styles.formLabel}>{label}</label>
        {(hint || required) && (
          <span className={styles.formHint}>{required && <span className={styles.required}>Required. </span>}{hint}</span>
        )}
      </span>
      <span className={styles.formControl}>
        {children}
        {error && <span role="alert" className={styles.formError}>{error}</span>}
      </span>
    </div>
  );
}

interface SourceFieldsProps {
  fields: HarvesterField[];
  values: FieldValues;
  errors: Record<string, string>;
  onChange: (key: string, value: string | boolean) => void;
  secretPlaceholder?: string;
}

/** One control per declared harvester field. Secret inputs never autofill and are cleared by the caller after a submit. */
export function SourceFields({ fields, values, errors, onChange, secretPlaceholder }: SourceFieldsProps) {
  const base = useId();
  return (
    <>
      {fields.map(field => {
        const id = `${base}-${field.key}`;
        const value = values[field.key];
        const text = typeof value === 'string' ? value : '';
        let control: ReactNode;
        if (field.kind === 'bool') {
          control = <Switch id={id} checked={value === true} label={field.label} onChange={v => onChange(field.key, v)} />;
        } else if (field.kind === 'enum' && field.options.length <= 3) {
          control = (
            <Segmented compact label={field.label} value={text} onChange={v => onChange(field.key, v)}
              options={field.options.map(o => ({ value: o, label: o }))} />
          );
        } else if (field.kind === 'enum') {
          control = (
            <Select id={id} value={text} onChange={e => onChange(field.key, e.target.value)}>
              <option value="">—</option>
              {field.options.map(o => <option key={o} value={o}>{o}</option>)}
            </Select>
          );
        } else if (field.kind === 'list') {
          control = <TextArea id={id} rows={3} value={text} onChange={e => onChange(field.key, e.target.value)} />;
        } else if (field.kind === 'secret') {
          control = (
            <TextInput id={id} type="password" autoComplete="new-password" placeholder={secretPlaceholder ?? ''} value={text}
              onChange={e => onChange(field.key, e.target.value)} />
          );
        } else {
          control = (
            <TextInput id={id} mono={field.kind === 'int'} inputMode={field.kind === 'int' ? 'numeric' : undefined} value={text}
              onChange={e => onChange(field.key, e.target.value)} />
          );
        }
        const hint = field.kind === 'list' ? [field.hint, 'One per line.'].filter(Boolean).join(' ') : field.hint;
        return (
          <FormRow key={field.key} id={id} label={field.label} hint={hint} required={field.required} error={errors[field.key]}>
            {control}
          </FormRow>
        );
      })}
    </>
  );
}
