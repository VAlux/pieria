import { forwardRef } from 'react';
import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes, TextareaHTMLAttributes } from 'react';
import { Link, type LinkProps } from 'react-router';
import styles from './controls.module.css';
import { Icon, type IconName } from './icons';

export function cx(...names: (string | false | null | undefined)[]): string {
  return names.filter(Boolean).join(' ');
}

export type ButtonVariant = 'secondary' | 'primary' | 'accent' | 'danger' | 'field' | 'ghost';
export type ButtonSize = 'sm' | 'md' | 'lg';

function buttonClass(variant: ButtonVariant, size: ButtonSize, extra?: string) {
  return cx(styles.button, variant !== 'secondary' && styles[variant], size !== 'md' && styles[size], extra);
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  icon?: IconName;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', icon, className, children, type = 'button', ...rest }, ref) {
  return (
    <button ref={ref} type={type} className={buttonClass(variant, size, className)} {...rest}>
      {icon && <Icon name={icon} size={14} />}
      {children}
    </button>
  );
});

interface LinkButtonProps extends LinkProps {
  variant?: ButtonVariant;
  size?: ButtonSize;
}

export function LinkButton({ variant = 'secondary', size = 'md', className, ...rest }: LinkButtonProps) {
  return <Link className={buttonClass(variant, size, className)} {...rest} />;
}

interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  icon: IconName;
  label: string;
  bare?: boolean;
  size?: number;
}

export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { icon, label, bare, size = 14, className, type = 'button', ...rest }, ref) {
  return (
    <button ref={ref} type={type} aria-label={label} title={label}
      className={cx(styles.button, styles.iconButton, bare && styles.bare, className)} {...rest}>
      <Icon name={icon} size={size} />
    </button>
  );
});

export interface SegmentOption<T extends string> {
  value: T;
  label: ReactNode;
  count?: ReactNode;
  disabled?: boolean;
  title?: string;
}

interface SegmentedProps<T extends string> {
  label: string;
  value: T;
  options: SegmentOption<T>[];
  onChange: (value: T) => void;
  compact?: boolean;
  onSurface?: boolean;
  className?: string;
}

export function Segmented<T extends string>({ label, value, options, onChange, compact, onSurface, className }: SegmentedProps<T>) {
  return (
    <div role="group" aria-label={label} className={cx(styles.segmented, compact && styles.compact, onSurface && styles.onSurface, className)}>
      {options.map(option => (
        <button key={option.value} type="button" className={styles.segment} aria-pressed={option.value === value}
          disabled={option.disabled} title={option.title} onClick={() => onChange(option.value)}>
          <span>{option.label}</span>
          {option.count !== undefined && <span className={styles.segmentCount}>{option.count}</span>}
        </button>
      ))}
    </div>
  );
}

interface SwitchProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'onChange'> {
  checked: boolean;
  label: string;
  onChange: (checked: boolean) => void;
  tone?: 'ok' | 'accent';
}

export function Switch({ checked, label, onChange, tone = 'ok', className, ...rest }: SwitchProps) {
  return (
    <button type="button" role="switch" aria-checked={checked} aria-label={label}
      className={cx(styles.switch, tone === 'accent' && styles.accentSwitch, className)}
      onClick={() => onChange(!checked)} {...rest} />
  );
}

interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'type'> {
  checked: boolean;
  onChange: (checked: boolean) => void;
  children: ReactNode;
}

export function Checkbox({ checked, onChange, children, className, ...rest }: CheckboxProps) {
  return (
    <label className={cx(styles.check, className)}>
      <input type="checkbox" checked={checked} onChange={e => onChange(e.target.checked)} {...rest} />
      {children}
    </label>
  );
}

interface SearchFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange'> {
  label: string;
  value: string;
  onChange: (value: string) => void;
  shortcut?: ReactNode;
  leading?: ReactNode;
  onSurface?: boolean;
}

export const SearchField = forwardRef<HTMLInputElement, SearchFieldProps>(function SearchField(
  { label, value, onChange, shortcut, leading, onSurface, className, ...rest }, ref) {
  return (
    <label className={cx(styles.search, onSurface && styles.onSurface, className)}>
      <Icon name="search" size={14} strokeWidth={2} />
      <span className="sr-only">{label}</span>
      {leading}
      <input ref={ref} type="search" value={value} onChange={e => onChange(e.target.value)} {...rest} />
      {shortcut}
    </label>
  );
});

export function Kbd({ children, className }: { children: ReactNode; className?: string }) {
  return <kbd className={cx(styles.kbd, className)}>{children}</kbd>;
}

interface TextInputProps extends InputHTMLAttributes<HTMLInputElement> {
  mono?: boolean;
  inherited?: boolean;
}

export const TextInput = forwardRef<HTMLInputElement, TextInputProps>(function TextInput(
  { mono, inherited, className, ...rest }, ref) {
  return <input ref={ref} className={cx(styles.input, mono && styles.mono, inherited && styles.inherited, className)} {...rest} />;
});

export const TextArea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(function TextArea(
  { className, ...rest }, ref) {
  return <textarea ref={ref} className={cx(styles.textarea, className)} {...rest} />;
});

interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  mono?: boolean;
}

export function Select({ mono, className, ...rest }: SelectProps) {
  return <select className={cx(styles.select, mono && styles.mono, className)} {...rest} />;
}
