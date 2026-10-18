import { useEffect, useMemo, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router';
import { useProfiles } from '../../api/queries';
import { mod, useHotkey } from '../../lib/hotkeys';
import { dismiss, isDismissed, previousProfile, rememberProfile } from '../../lib/storage';
import { switchedPath } from '../../lib/routes';
import { ErrorBanner, Page, PageHeader } from '../../ui/display';
import { Button, IconButton, Kbd } from '../../ui/controls';
import { ProfileSwitcher } from '../profile-switcher/ProfileSwitcher';
import { CommandPalette } from './CommandPalette';
import { ShellContext, useProfile } from './context';
import { Sidebar } from './Sidebar';
import { CompatibilityBanner } from './CompatibilityBanner';
import styles from './shell.module.css';

export function AppShell() {
  const profile = useProfile();
  const location = useLocation();
  const navigate = useNavigate();
  const profiles = useProfiles();
  const [switcher, setSwitcher] = useState(false);
  const [palette, setPalette] = useState(false);

  useEffect(() => { if (profile) rememberProfile(profile); }, [profile]);

  useHotkey('mod+p', () => { setPalette(false); setSwitcher(true); });
  useHotkey('mod+k', () => { setSwitcher(false); setPalette(true); });
  useHotkey('mod+shift+p', () => {
    const previous = previousProfile(profile);
    if (previous) navigate(switchedPath(location.pathname, previous));
  });

  const api = useMemo(() => ({ openSwitcher: () => setSwitcher(true), openPalette: () => setPalette(true) }), []);
  const unknown = profiles.isSuccess && !profiles.data.some(p => p.name === profile);

  return (
    <ShellContext.Provider value={api}>
      <div className={styles.shell}>
        <div className={styles.sidebarSlot}>
          <Sidebar />
        </div>
        <div className={styles.mainSlot}>
          <CompatibilityBanner />
          {unknown ? (
            <Page>
              <PageHeader crumb="Profiles" title={profile} />
              <ErrorBanner>
                There is no profile called “{profile}”. Profiles are created the first time an agent writes to them.
              </ErrorBanner>
              <div><Button variant="primary" onClick={() => setSwitcher(true)}>Choose a profile</Button></div>
            </Page>
          ) : <Outlet />}
        </div>
      </div>
      {switcher && <ProfileSwitcher onClose={() => setSwitcher(false)} />}
      {palette && <CommandPalette onClose={() => setPalette(false)} />}
      {!switcher && <SwitcherTip />}
    </ShellContext.Provider>
  );
}

/** One-time hint for the profile switcher shortcut. */
function SwitcherTip() {
  const [visible, setVisible] = useState(() => !isDismissed('switcherTip'));
  if (!visible) return null;
  const close = () => { dismiss('switcherTip'); setVisible(false); };
  return (
    <div role="status" className={styles.tip}>
      <Kbd className={styles.tipKey}>{mod}P</Kbd>
      <span className={styles.tipText}>
        <strong>Switch profiles from anywhere</strong>
        <span>Press ⌘P (Ctrl+P on Windows/Linux) on any screen. ⌘⇧P jumps back to the last profile.</span>
      </span>
      <IconButton icon="close" label="Dismiss tip" bare onClick={close} />
    </div>
  );
}
