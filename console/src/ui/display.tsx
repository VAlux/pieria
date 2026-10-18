import type { CSSProperties, HTMLAttributes, ReactNode } from 'react';
import { cx } from './controls';
import styles from './display.module.css';
import { Icon } from './icons';
import { integer } from '../lib/format';

export function Page({ children, className, label }: { children: ReactNode; className?: string; label?: string }) {
  return <main className={cx(styles.page, className)} aria-label={label}>{children}</main>;
}

interface PageHeaderProps {
  crumb: ReactNode;
  title: ReactNode;
  large?: boolean;
  meta?: ReactNode;
  actions?: ReactNode;
}

export function PageHeader({ crumb, title, large, meta, actions }: PageHeaderProps) {
  return (
    <header className={styles.pageHeader}>
      <div className={styles.pageHeading}>
        <span className={styles.crumb}>{crumb}</span>
        <h1 className={cx(styles.title, large && styles.titleLarge)}>{title}</h1>
        {meta}
      </div>
      {actions && <div className={styles.pageActions}>{actions}</div>}
    </header>
  );
}

export function PageIntro({ children }: { children: ReactNode }) {
  return <p className={styles.pageIntro}>{children}</p>;
}

interface CardProps extends HTMLAttributes<HTMLElement> {
  flush?: boolean;
  as?: 'section' | 'div' | 'aside' | 'article';
}

export function Card({ flush, as: Tag = 'section', className, ...rest }: CardProps) {
  return <Tag className={cx(styles.card, flush && styles.flush, className)} {...rest} />;
}

interface CardHeaderProps {
  title: ReactNode;
  sub?: ReactNode;
  actions?: ReactNode;
  id?: string;
  level?: 2 | 3;
  className?: string;
}

export function CardHeader({ title, sub, actions, id, level = 2, className }: CardHeaderProps) {
  const Heading = level === 2 ? 'h2' : 'h3';
  return (
    <div className={cx(styles.cardHeader, className)}>
      <Heading id={id} className={styles.cardTitle}>{title}</Heading>
      {sub && <span className={styles.cardSub}>{sub}</span>}
      {actions && <><span className={styles.spacer} />{actions}</>}
    </div>
  );
}

export function SectionLabel({ children, as: Tag = 'span', id, className }: { children: ReactNode; as?: 'span' | 'h2' | 'h3'; id?: string; className?: string }) {
  return <Tag id={id} className={cx(styles.sectionLabel, className)}>{children}</Tag>;
}

export function TypeChip({ type }: { type: string }) {
  const tone = (styles as Record<string, string>)[type] ?? styles.cluster;
  return <span className={cx(styles.chip, tone)}>{type}</span>;
}

export type Tone = 'ok' | 'fail' | 'info' | 'warn' | 'neutral';

export function Badge({ tone, children, sans, title }: { tone: Tone; children: ReactNode; sans?: boolean; title?: string }) {
  return <span className={cx(styles.chip, styles[tone], sans && styles.badgeSans)} title={title}>{children}</span>;
}

export function Pill({ tone, children, dot }: { tone: Tone; children: ReactNode; dot?: string }) {
  return (
    <span className={cx(styles.pill, styles[tone])}>
      {dot && <span aria-hidden="true" className={cx(styles.dot, styles.round)} style={{ width: 6, height: 6, background: dot }} />}
      {children}
    </span>
  );
}

export const TYPE_COLORS: Record<string, string> = {
  fact: 'var(--fact)',
  event: 'var(--event)',
  instruction: 'var(--instruction)',
  task: 'var(--task)',
  cluster: 'var(--text-3)'
};

export function Dot({ color, round, size = 10, style }: { color: string; round?: boolean; size?: number; style?: CSSProperties }) {
  return <span aria-hidden="true" className={cx(styles.dot, round && styles.round)} style={{ width: size, height: size, background: color, ...style }} />;
}

export function TopicKey({ children, className }: { children: ReactNode; className?: string }) {
  return <span className={cx(styles.key, className)}>{children}</span>;
}

export function EmptyState({ title, children, actions }: { title: string; children?: ReactNode; actions?: ReactNode }) {
  return (
    <section className={styles.empty}>
      <h2>{title}</h2>
      {children && <p>{children}</p>}
      {actions}
    </section>
  );
}

export function EmptyInline({ title, detail }: { title: ReactNode; detail?: ReactNode }) {
  return <div className={styles.emptyInline}>{title}{detail && <span>{detail}</span>}</div>;
}

export function ErrorBanner({ error, children }: { error?: unknown; children?: ReactNode }) {
  const message = children ?? (error instanceof Error ? error.message : String(error ?? 'Something went wrong'));
  return (
    <div role="alert" className={styles.error}>
      <Icon name="alert" size={16} strokeWidth={2} />
      <span>{message}</span>
    </div>
  );
}

export function Skeleton({ width = '100%', height = 14, style }: { width?: number | string; height?: number; style?: CSSProperties }) {
  return <span aria-hidden="true" className={styles.skeleton} style={{ width, height, ...style }} />;
}

export function SkeletonRows({ rows = 5, height = 44 }: { rows?: number; height?: number }) {
  return (
    <div role="status" aria-label="Loading" style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: 14 }}>
      {Array.from({ length: rows }, (_, i) => <Skeleton key={i} height={height} />)}
    </div>
  );
}

interface LaneProps {
  name: string;
  phase?: string | null;
  done: number;
  total: number;
  state?: string;
}

/** Progress lane: dashed when waiting, amber while running, green when done. */
export function ProgressLane({ name, phase, done, total, state }: LaneProps) {
  const finished = state === 'COMPLETED' || (total > 0 && done >= total);
  const pending = !finished && (state === 'QUEUED' || state === 'WAITING' || (done === 0 && state !== 'RUNNING'));
  const pct = total > 0 ? Math.min(100, Math.round((done / total) * 100)) : finished ? 100 : 0;
  const count = state === 'FAILED' ? 'failed' : state === 'CANCELLED' ? 'cancelled' : pending ? 'waiting' : finished ? `${integer(total || done)} · done` : total > 0 ? `${integer(done)} / ${integer(total)} · ${pct}%` : `${integer(done)}`;
  return (
    <div className={styles.lane}>
      <span className={styles.laneName}>
        <span>{name}</span>
        {phase && <span className={styles.lanePhase}>{phase}</span>}
      </span>
      <span role="progressbar" aria-label={name} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pending ? 0 : pct}
        className={cx(styles.track, pending && styles.trackPending)}>
        <span className={cx(styles.fill, finished && styles.fillDone)} style={{ width: `${pending ? 0 : pct}%` }} />
      </span>
      <span className={styles.laneCount}>{count}</span>
    </div>
  );
}

export function Kpi({ label, value, note, valueColor }: { label: string; value: ReactNode; note?: ReactNode; valueColor?: string }) {
  return (
    <div className={styles.kpi}>
      <span className={styles.kpiLabel}>{label}</span>
      <span className={styles.kpiValue} style={valueColor ? { color: valueColor } : undefined}>{value}</span>
      {note && <span className={styles.kpiNote}>{note}</span>}
    </div>
  );
}

export function Spacer() {
  return <span className={styles.spacer} />;
}
