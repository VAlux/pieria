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
  /** False for a control that names itself, such as a segmented group, which a <label> can't point at. */
  labelled?: boolean;
  children: ReactNode;
}

/** The id of a row's error message, for the control's aria-describedby. */
export function errorId(id: string): string {
  return `${id}-error`;
}

/** The ARIA attributes that tie a control to its row's error. */
export function invalidProps(id: string, error: string | undefined) {
  return error ? { 'aria-invalid': true, 'aria-describedby': errorId(id) } : {};
}

/** A labelled control with its hint and error, the layout every source form uses. */
export function FormRow({ id, label, hint, required, error, labelled = true, children }: FieldRowProps) {
  return (
    <div className={styles.formRow}>
      <span className={styles.formText}>
        {labelled ? <label htmlFor={id} className={styles.formLabel}>{label}</label> : <span className={styles.formLabel}>{label}</span>}
        {(hint || required) && (
          <span className={styles.formHint}>{required && <span className={styles.required}>Required. </span>}{hint}</span>
        )}
      </span>
      <span className={styles.formControl}>
        {children}
        {error && <span id={errorId(id)} role="alert" className={styles.formError}>{error}</span>}
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
        const error = errors[field.key];
        const aria = invalidProps(id, error);
        // A segmented control can't go back to "nothing chosen", so only a required choice gets one.
        const segmented = field.kind === 'enum' && field.required && field.options.length <= 3;
        let control: ReactNode;
        if (field.kind === 'bool') {
          control = <Switch id={id} checked={value === true} label={field.label} onChange={v => onChange(field.key, v)} {...aria} />;
        } else if (segmented) {
          control = (
            <Segmented compact label={field.label} value={text} onChange={v => onChange(field.key, v)}
              options={field.options.map(o => ({ value: o, label: o }))} />
          );
        } else if (field.kind === 'enum') {
          control = (
            <Select id={id} value={text} onChange={e => onChange(field.key, e.target.value)} {...aria}>
              <option value="">{field.required ? 'Choose…' : '— default —'}</option>
              {field.options.map(o => <option key={o} value={o}>{o}</option>)}
            </Select>
          );
        } else if (field.kind === 'list') {
          control = <TextArea id={id} rows={3} value={text} onChange={e => onChange(field.key, e.target.value)} {...aria} />;
        } else if (field.kind === 'secret') {
          control = (
            <TextInput id={id} type="password" autoComplete="new-password" placeholder={secretPlaceholder ?? ''} value={text}
              onChange={e => onChange(field.key, e.target.value)} {...aria} />
          );
        } else {
          control = (
            <TextInput id={id} mono={field.kind === 'int'} inputMode={field.kind === 'int' ? 'numeric' : undefined} value={text}
              onChange={e => onChange(field.key, e.target.value)} {...aria} />
          );
        }
        const hint = field.kind === 'list' ? [field.hint, 'One per line.'].filter(Boolean).join(' ') : field.hint;
        return (
          <FormRow key={field.key} id={id} label={field.label} hint={hint} required={field.required} error={error} labelled={!segmented}>
            {control}
          </FormRow>
        );
      })}
    </>
  );
}
