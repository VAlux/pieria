import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { qk } from '../../api/queries';
import { authorizationStatus, startAuthorization, type SourceAuthorization } from '../../api/sources';
import { relativeTime } from '../../lib/format';
import { Button } from '../../ui/controls';
import { ErrorBanner } from '../../ui/display';
import { Dialog, useToast } from '../../ui/overlay';
import styles from './sources.module.css';

export const POLL_MS = 1_000;
/** How long past the code's expiry to wait for the daemon to report how the sign-in ended. */
const EXPIRY_GRACE_MS = 60_000;

function expired(state: SourceAuthorization, now: number): boolean {
  return state.status === 'pending' && state.expiresAt !== null && now > Date.parse(state.expiresAt) + EXPIRY_GRACE_MS;
}

/**
 * Starts the harvester's sign-in, shows the code to enter at the provider's page, and waits for the
 * daemon to report the outcome. Closing the dialog stops the polling; the daemon ends the sign-in
 * when the code expires, and a new one replaces it.
 */
export function SignInDialog({ profile, sourceId, onClose }: { profile: string; sourceId: string; onClose: () => void }) {
  const client = useQueryClient();
  const toast = useToast();
  const [attempt, setAttempt] = useState(0);
  const started = useRef(-1);
  const [now, setNow] = useState(Date.now());
  const start = useMutation({ mutationFn: () => startAuthorization(profile, sourceId) });
  const { mutate } = start;

  useEffect(() => {
    if (started.current === attempt) return;
    started.current = attempt;
    mutate();
  }, [attempt, mutate]);

  const pendingStart = start.data?.status === 'pending';
  const status = useQuery({
    queryKey: ['profile', profile, 'sources', sourceId, 'authorize', attempt],
    queryFn: ({ signal }) => authorizationStatus(profile, sourceId, signal),
    enabled: pendingStart && !start.isPending,
    gcTime: 0,
    retry: false,
    refetchInterval: query => (query.state.data && query.state.data.status !== 'pending' ? false : POLL_MS)
  });
  const state = (pendingStart ? status.data : undefined) ?? start.data;
  const waiting = state?.status === 'pending';
  const timedOut = state ? expired(state, now) : false;

  useEffect(() => {
    if (!waiting) return;
    const timer = setInterval(() => setNow(Date.now()), 1_000);
    return () => clearInterval(timer);
  }, [waiting]);

  useEffect(() => {
    if (state?.status === 'authorized') {
      void client.invalidateQueries({ queryKey: qk.source(profile, sourceId) });
      void client.invalidateQueries({ queryKey: qk.sources(profile), exact: true });
    }
  }, [state?.status, client, profile, sourceId]);

  const retry = () => setAttempt(a => a + 1);
  const copy = (code: string) => {
    void navigator.clipboard?.writeText(code).then(() => toast.show('Code copied.'), () => toast.show('Copy failed.', undefined, 'error'));
  };
  const link = state?.verificationUri?.startsWith('https://') ? state.verificationUri : null;

  let body;
  if (start.isError) {
    body = <ErrorBanner error={start.error} />;
  } else if (!state || start.isPending) {
    body = <p>Asking for a sign-in code…</p>;
  } else if (state.status === 'authorized') {
    body = <p className={styles.signedIn}>Signed in.</p>;
  } else if (state.status === 'failed') {
    body = <ErrorBanner>{`Sign-in failed (${state.errorClass ?? 'error'}): ${state.error ?? ''}`}</ErrorBanner>;
  } else if (timedOut) {
    body = <ErrorBanner>The code expired.</ErrorBanner>;
  } else {
    body = (
      <div className={styles.signIn}>
        {state.message && state.message.trim() && <p>{state.message}</p>}
        {state.userCode && (
          <div className={styles.codeRow}>
            <code className={styles.userCode}>{state.userCode}</code>
            <Button size="sm" icon="copy" onClick={() => copy(state.userCode!)}>Copy code</Button>
          </div>
        )}
        {link && <a href={link} target="_blank" rel="noopener noreferrer">Open the sign-in page</a>}
        {state.expiresAt && <p className={styles.note}>{`The code expires ${relativeTime(state.expiresAt, now)}.`}</p>}
        {status.isError && <ErrorBanner error={status.error} />}
      </div>
    );
  }

  const finished = state?.status === 'authorized';
  const canRetry = start.isError || state?.status === 'failed' || timedOut;
  return (
    <Dialog title={`Sign in to ${sourceId}`} onClose={onClose} footer={(
      <>
        {canRetry && <Button onClick={retry}>Try again</Button>}
        <Button variant={finished ? 'primary' : 'secondary'} onClick={onClose}>{finished ? 'Done' : 'Close'}</Button>
      </>
    )}>
      {body}
    </Dialog>
  );
}
