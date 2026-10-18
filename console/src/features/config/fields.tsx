import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import type { ConfigField } from '../../api/config';
import { Segmented, Select, Switch, TextInput, cx } from '../../ui/controls';
import { parseField } from './model';
import styles from './config.module.css';

interface FieldControlProps {
  field: ConfigField;
  /** What applies now: the override when set, else the inherited/default value. */
  value: unknown;
  /** True when nothing overrides the value (shown greyed). */
  inherited: boolean;
  onChange: (value: unknown) => void;
  disabled?: boolean;
  wide?: boolean;
}

const str = (v: unknown) => (v === null || v === undefined ? '' : String(v));

/** The editor for one schema field; numbers validate as you type and only commit when they parse. */
export function FieldControl({ field, value, inherited, onChange, disabled, wide }: FieldControlProps) {
  const [text, setText] = useState(str(value));
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { setText(str(value)); setError(null); }, [value]);

  if (field.kind === 'bool') {
    return <Switch checked={value === true || value === 'true'} label={field.label} disabled={disabled}
      tone={field.key.endsWith('wiki.enabled') ? 'accent' : 'ok'} onChange={v => onChange(v)} />;
  }
  if (field.kind === 'enum' && field.options) {
    if (field.options.length <= 3) {
      return (
        <Segmented compact label={field.label} value={str(value)} onChange={v => onChange(v)}
          options={field.options.map(o => ({ value: o, label: o.charAt(0) + o.slice(1).toLowerCase(), disabled }))} />
      );
    }
    return (
      <Select value={str(value)} disabled={disabled} onChange={e => onChange(e.target.value)} className={cx(inherited && styles.inheritedText)}>
        {field.options.map(o => <option key={o} value={o}>{o}</option>)}
      </Select>
    );
  }
  const numeric = field.kind === 'int' || field.kind === 'double' || field.kind === 'weight';
  return (
    <span className={styles.inputWrap}>
      <TextInput mono inherited={inherited} disabled={disabled} aria-label={field.label} aria-invalid={Boolean(error)}
        inputMode={numeric ? 'decimal' : undefined} value={text}
        className={cx(numeric ? styles.numberInput : styles.textInput, wide && styles.wideInput)}
        onChange={e => {
          setText(e.target.value);
          const parsed = parseField(field, e.target.value);
          setError(parsed.error ?? null);
          if (!parsed.error) onChange(parsed.value);
        }} />
      {error && <span role="alert" className={styles.fieldError}>{error}</span>}
    </span>
  );
}

interface FieldRowProps {
  field: ConfigField;
  control: ReactNode;
  status?: ReactNode;
  marker?: ReactNode;
}

/** Label, key and hint on the left; the control and its override status on the right. */
export function FieldRow({ field, control, status, marker }: FieldRowProps) {
  return (
    <div className={styles.fieldRow}>
      <span className={styles.fieldText}>
        <span className={styles.fieldLabel}>{marker}{field.label}</span>
        <span className={styles.fieldKey}>{field.key}</span>
        {field.hint && <span className={styles.fieldHint}>{field.hint}</span>}
      </span>
      <span className={styles.fieldControl}>{control}</span>
      {status && <span className={styles.fieldStatus}>{status}</span>}
    </div>
  );
}
