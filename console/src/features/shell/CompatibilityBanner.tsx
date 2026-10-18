import { useHealth, useStatus } from '../../api/queries';
import { CONSOLE_API_VERSION } from '../../api/version';
import { Icon } from '../../ui/icons';
import styles from './shell.module.css';

/** Warns when the daemon is down or speaks a different API version than this console build. */
export function CompatibilityBanner() {
  const status = useStatus();
  const health = useHealth();
  if (health.isError && status.isError) {
    return (
      <div role="alert" className={styles.banner}>
        <Icon name="alert" strokeWidth={2} />
        The daemon is not responding. Start it with <code>pieria daemon start</code>; this page retries every 30 seconds.
      </div>
    );
  }
  const version = status.data?.apiVersion;
  if (version === undefined || version === CONSOLE_API_VERSION) return null;
  return (
    <div role="alert" className={styles.banner}>
      <Icon name="alert" strokeWidth={2} />
      This console was built for API version {CONSOLE_API_VERSION}, but the daemon reports version {version}.
      Run <code>pieria update</code> so the daemon and console match.
    </div>
  );
}
