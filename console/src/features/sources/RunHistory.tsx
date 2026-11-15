import type { SourceRun } from '../../api/sources';
import { dateTime, duration, integer } from '../../lib/format';
import { Badge, type Tone } from '../../ui/display';
import styles from './sources.module.css';

const TONES: Record<SourceRun['status'], Tone> = {
  running: 'warn', succeeded: 'ok', failed: 'fail', cancelled: 'neutral', interrupted: 'neutral'
};

/** The source's recent runs. Errors and stderr come from the harvester, so they are shown as plain text. */
export function RunHistory({ runs }: { runs: SourceRun[] }) {
  if (runs.length === 0) return <p className={styles.note}>No runs yet.</p>;
  return (
    <ul className={styles.list}>
      {runs.map(run => {
        const took = run.finishedAt ? Date.parse(run.finishedAt) - Date.parse(run.startedAt) : null;
        return (
          <li key={run.id} className={styles.row}>
            <div className={styles.rowMain}>
              <span className={styles.mono}>{dateTime(run.startedAt)}</span>
              <span>{run.dryRun ? 'Dry run' : 'Run'}</span>
              <Badge tone={TONES[run.status] ?? 'neutral'} sans>{run.status}</Badge>
            </div>
            <div className={styles.rowMeta}>
              <span>{integer(run.itemsSeen)} seen</span>
              <span>{integer(run.itemsChanged)} changed</span>
              <span>{integer(run.itemsDeleted)} deleted</span>
              <span>{integer(run.memoriesStored)} memories</span>
              {took !== null && <span>took {duration(took)}</span>}
            </div>
            {(run.errorMessage || run.stderrTail) && (
              <details className={styles.details}>
                <summary>Details</summary>
                {run.errorMessage && <p className={styles.errorText}>{`${run.errorClass ?? 'error'}: ${run.errorMessage}`}</p>}
                {run.stderrTail && <pre className={styles.stderr}>{run.stderrTail}</pre>}
              </details>
            )}
          </li>
        );
      })}
    </ul>
  );
}
