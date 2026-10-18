import { useState } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import type { FactHistory, Memory } from '../../api/types';
import { dayTime, fromLocalInput, plural, toLocalInput } from '../../lib/format';
import { Button, cx } from '../../ui/controls';
import { ErrorBanner, Pill, SectionLabel, Skeleton, type Tone } from '../../ui/display';
import { Popover, usePopover } from '../../ui/overlay';
import { buildTrack, validityStatus, type Interval, type Version, type VersionState } from './validity';
import styles from './memories.module.css';

const STATE_TONE: Record<VersionState, Tone> = {
  current: 'ok',
  superseded: 'neutral',
  scheduled: 'info',
  corrected: 'neutral',
  invalidated: 'fail'
};

const STATUS_TONE = { 'valid now': 'ok', ended: 'neutral', scheduled: 'info', 'not valid': 'fail' } as const;

function interval(span: Interval, openLabel: string): string {
  const from = span.from === null ? 'always' : dayTime(span.from);
  return `${from} → ${span.to === null ? openLabel : dayTime(span.to)}`;
}

interface Props {
  memory: Memory;
  versions: Version[];
  history: UseQueryResult<FactHistory>;
  at: { asOf: string | null; knownAsOf: string | null };
  onAt: (at: { asOf: string | null; knownAsOf: string | null }) => void;
}

export function ValiditySection({ memory, versions, history, at, onAt }: Props) {
  const ownIndex = Math.max(0, versions.findIndex(v => v.own && v.state !== 'corrected'));
  const [picked, setPicked] = useState<number | null>(null);
  const selected = Math.min(picked ?? ownIndex, Math.max(0, versions.length - 1));
  const now = at.asOf ? Date.parse(at.asOf) : Date.now();
  const status = validityStatus(versions, memory, now);
  const track = buildTrack(versions, now);
  const chosen = versions[selected];

  return (
    <section aria-labelledby="validity-heading" className={styles.validity}>
      <div className={styles.validityHead}>
        <SectionLabel as="h3" id="validity-heading" className={styles.grow}>
          Validity · {plural(versions.length, 'version')}
        </SectionLabel>
        <Pill tone={STATUS_TONE[status]}>{status}</Pill>
        <AsOfPicker at={at} onAt={onAt} />
      </div>

      {history.isError && versions.length === 0 && <ErrorBanner error={history.error} />}
      {history.isPending && versions.length === 0 && <Skeleton height={90} />}

      {versions.length > 0 && (
        <>
          <div className={styles.trackBox}>
            <div role="img" aria-label={`Validity timeline with ${plural(versions.length, 'version')}`} className={styles.track}
              style={{ height: track.rows * 22 - 8 }}>
              {track.bars.map(bar => (
                <button key={bar.key} type="button" tabIndex={-1} aria-hidden="true"
                  className={cx(styles.bar, styles[`bar_${bar.state}`], bar.versionIndex === selected && styles.barSelected)}
                  style={{ top: bar.row * 22, left: `${bar.left}%`, width: `max(calc(${bar.width}% - 10px), 8px)` }}
                  onClick={() => setPicked(bar.versionIndex)}>
                  <span className={styles.barStart} />
                  {bar.open && <span className={styles.barOpen}>→</span>}
                </button>
              ))}
            </div>
            <div className={styles.axis}>
              <span>{dayTime(track.start)}</span><span>world time</span><span>{at.asOf ? dayTime(now) : 'now'}</span>
            </div>
          </div>

          <div className={styles.versions}>
            {versions.map((version, index) => (
              <button key={version.revision.revisionId} type="button" aria-pressed={index === selected}
                className={styles.version} onClick={() => setPicked(index)}>
                <span className={styles.versionHead}>
                  <span className={styles.versionBadge} data-tone={STATE_TONE[version.state]}>{version.state}</span>
                  {!version.own && <span className={styles.muted}>other memory</span>}
                  <span className={styles.grow} />
                  <span className={styles.rev}>rev {version.rev}</span>
                </span>
                <span className={cx(styles.versionText, version.state !== 'current' && version.state !== 'scheduled' && styles.struck)}>
                  {version.revision.content}
                </span>
                <span className={styles.versionTimes}>
                  <span>True</span><span className="mono">{interval(version.valid, 'still true')}</span>
                  <span>Known</span><span className="mono">{interval(version.known, 'present')}</span>
                </span>
              </button>
            ))}
          </div>
          <span className={styles.note}>
            “True” is when it held in the world; “Known” is when Pieria learned it.
            {chosen?.revision.statedAt && <> Stated {dayTime(chosen.revision.statedAt)}</>}
            {chosen?.revision.sourceOccurrence && <> in source <span className="mono">{chosen.revision.sourceOccurrence.slice(0, 24)}</span></>}.
          </span>
        </>
      )}
    </section>
  );
}

function AsOfPicker({ at, onAt }: Pick<Props, 'at' | 'onAt'>) {
  const popover = usePopover();
  const [world, setWorld] = useState(toLocalInput(at.asOf));
  const [known, setKnown] = useState(toLocalInput(at.knownAsOf));
  const [error, setError] = useState('');
  const label = at.asOf || at.knownAsOf ? `As of ${dayTime(at.asOf ?? at.knownAsOf)}` : 'As of now';
  const apply = () => {
    const asOf = fromLocalInput(world);
    const knownAsOf = fromLocalInput(known);
    if (knownAsOf && Date.parse(knownAsOf) > Date.now()) { setError('“Known as of” cannot be in the future.'); return; }
    setError('');
    onAt({ asOf, knownAsOf });
    popover.close();
  };
  return (
    <>
      <Button ref={popover.anchor} size="sm" variant="field" aria-expanded={popover.open} aria-haspopup="dialog" onClick={popover.toggle}>{label} ▾</Button>
      <Popover anchor={popover.anchor} open={popover.open} onClose={popover.close} label="Validity as of" role="dialog" align="end">
        <div className={styles.asOf}>
          <label>
            <span>World time — what was true at</span>
            <input type="datetime-local" step={60} value={world} onChange={e => setWorld(e.target.value)} />
          </label>
          <label>
            <span>Known as of — what Pieria knew at</span>
            <input type="datetime-local" step={60} value={known} onChange={e => setKnown(e.target.value)} />
          </label>
          {error && <span role="alert" className={styles.fieldError}>{error}</span>}
          <div className={styles.asOfActions}>
            <Button size="sm" onClick={() => { setWorld(''); setKnown(''); onAt({ asOf: null, knownAsOf: null }); popover.close(); }}>Now</Button>
            <Button size="sm" variant="primary" onClick={apply}>Apply</Button>
          </div>
        </div>
      </Popover>
    </>
  );
}
