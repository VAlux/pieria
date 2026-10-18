import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useMemory, useMemoryHistory } from '../../api/queries';
import type { Memory } from '../../api/types';
import { dateTime } from '../../lib/format';
import { profileUrl } from '../../lib/routes';
import { IconButton, LinkButton } from '../../ui/controls';
import { Card, EmptyInline, ErrorBanner, SectionLabel, Skeleton, TopicKey, TypeChip } from '../../ui/display';
import { Markdown } from '../../ui/Markdown';
import { useToast } from '../../ui/overlay';
import { MemoryActions } from './MemoryActions';
import { ValiditySection } from './ValiditySection';
import { buildVersions, editableRevision } from './validity';
import styles from './memories.module.css';

/** Top-level payload fields as display rows; nested values are shown as compact JSON. */
export function payloadRows(payload: string | null | undefined): { key: string; value: string }[] {
  if (!payload) return [];
  try {
    const parsed: unknown = JSON.parse(payload);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return [{ key: 'payload', value: payload }];
    return Object.entries(parsed as Record<string, unknown>).map(([key, value]) => ({
      key,
      value: Array.isArray(value) && value.every(v => typeof v !== 'object')
        ? value.join(', ')
        : typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value)
    }));
  } catch {
    return [{ key: 'payload', value: payload }];
  }
}

interface DetailProps {
  profile: string;
  memoryId: string | null;
  /** The list row, shown while the full record loads. */
  preview: Memory | null;
}

export function MemoryDetail({ profile, memoryId, preview }: DetailProps) {
  const toast = useToast();
  const [params] = useSearchParams();
  const [at, setAt] = useState<{ asOf: string | null; knownAsOf: string | null }>({ asOf: null, knownAsOf: null });
  const detail = useMemory(profile, memoryId);
  const history = useMemoryHistory(profile, memoryId, at);
  const memory = detail.data ?? preview;
  const versions = useMemo(() => {
    if (!memory) return [];
    const now = at.asOf ? Date.parse(at.asOf) : Date.now();
    const known = at.knownAsOf ? Date.parse(at.knownAsOf) : Date.now();
    return buildVersions(history.data, memory, now, known);
  }, [history.data, memory, at]);
  const editable = editableRevision(versions);

  if (!memoryId) {
    return (
      <Card as="aside" aria-label="Memory detail" className={styles.detail}>
        <EmptyInline title="Select a memory to see its details." />
      </Card>
    );
  }
  if (!memory) {
    return (
      <Card as="aside" aria-label="Memory detail" className={styles.detail}>
        {detail.isError ? <ErrorBanner error={detail.error} /> : <><Skeleton height={20} width="60%" /><Skeleton height={60} /><Skeleton height={120} /></>}
      </Card>
    );
  }

  const copy = (text: string, what: string) => {
    navigator.clipboard?.writeText(text).then(() => toast.show(`${what} copied`), () => toast.show(`Could not copy ${what.toLowerCase()}`, undefined, 'error'));
  };
  const sessionParams = new URLSearchParams(params);
  if (memory.sessionId) sessionParams.set('session', memory.sessionId);
  const payload = payloadRows(memory.payload);
  const recallQuery = memory.topicKey ?? memory.content.slice(0, 200);

  return (
    <Card as="aside" aria-label="Memory detail" id="memory-detail" tabIndex={-1} className={styles.detail}>
      <div className={styles.detailHead}>
        <TypeChip type={memory.type} />
        <TopicKey className={styles.detailKey}>{memory.topicKey ?? 'no topic key'}</TopicKey>
        <IconButton icon="copy" label="Copy memory id" onClick={() => copy(memory.id, 'Memory id')} />
      </div>

      <Markdown source={memory.content} className={styles.detailContent} />

      <dl className={styles.facts}>
        <dt>Session</dt>
        <dd className="mono">
          {memory.sessionId
            ? <Link to={{ pathname: profileUrl(profile, 'memories'), search: sessionParams.toString() }}>{memory.sessionId}</Link>
            : <span className={styles.muted}>added manually</span>}
        </dd>
        <dt>Stored</dt>
        <dd className="mono">{dateTime(memory.createdAt)}</dd>
        <dt>Memory id</dt>
        <dd className={`mono ${styles.idValue}`}>{memory.id}</dd>
      </dl>

      {payload.length > 0 && (
        <div className={styles.payloadBlock}>
          <SectionLabel>Payload</SectionLabel>
          <div className={styles.payload}>
            {payload.map(row => (
              <div key={row.key} className={styles.payloadRow}>
                <span className={styles.payloadKey}>{row.key}</span>
                <span className={styles.payloadValue}>{row.value}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      <ValiditySection memory={memory} versions={versions} history={history} at={at} onAt={setAt} />

      <div className={styles.actions}>
        <MemoryActions profile={profile} memory={memory} editable={editable} onCopy={copy}
          recallLink={(
            <LinkButton to={{ pathname: profileUrl(profile, 'recall'), search: `?q=${encodeURIComponent(recallQuery)}` }}>
              Recall with this {memory.topicKey ? 'key' : 'text'}
            </LinkButton>
          )} />
      </div>
    </Card>
  );
}
