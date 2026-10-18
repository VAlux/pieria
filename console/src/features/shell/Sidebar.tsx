import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Link, useLocation } from 'react-router';
import { useProfiles, useStatus } from '../../api/queries';
import { avatarFor } from '../../lib/avatar';
import { integer, plural } from '../../lib/format';
import { mod } from '../../lib/hotkeys';
import { profileUrl, subPath } from '../../lib/routes';
import { Kbd } from '../../ui/controls';
import { Icon, type IconName } from '../../ui/icons';
import { useProfile, useShell } from './context';
import { useNavBadges } from './useNavBadges';
import styles from './shell.module.css';

interface NavItemProps {
  to: string;
  icon: IconName;
  label: string;
  active: boolean;
  badge?: ReactNode;
}

function NavItem({ to, icon, label, active, badge }: NavItemProps) {
  return (
    <Link to={to} className={styles.item} aria-current={active ? 'page' : undefined}>
      <Icon name={icon} />
      <span>{label}</span>
      {badge}
    </Link>
  );
}

function Group({ label, children }: { label?: string; children: ReactNode }) {
  return (
    <div className={styles.group}>
      {label && <span className={styles.groupLabel}>{label}</span>}
      {children}
    </div>
  );
}

export function Sidebar() {
  const profile = useProfile();
  const { openSwitcher, openPalette } = useShell();
  const location = useLocation();
  const section = subPath(location.pathname).split('/')[0] ?? '';
  const profiles = useProfiles();
  const summary = profiles.data?.find(p => p.name === profile);
  const badges = useNavBadges(profile);
  const avatar = avatarFor(profile);
  const [menuOpen, setMenuOpen] = useState(false);
  useEffect(() => { setMenuOpen(false); }, [location.pathname]);
  const url = (sub: string) => profileUrl(profile, sub);
  const memoryCount = summary?.memoryCount;
  const meta = summary
    ? [plural(summary.memoryCount, 'memory', 'memories'), summary.sessionCount !== undefined ? plural(summary.sessionCount, 'session') : null].filter(Boolean).join(' · ')
    : ' ';

  return (
    <nav aria-label="Console" className={styles.nav} data-open={menuOpen}>
      <div className={styles.brandRow}>
        <Link to={url('')} className={styles.brand}>
          <span className={styles.brandName}>Pieria</span>
          <span className={styles.brandSub}>console</span>
        </Link>
        <button type="button" className={styles.menuButton} aria-expanded={menuOpen} aria-controls="console-menu"
          onClick={() => setMenuOpen(o => !o)}>{menuOpen ? 'Close' : 'Menu'}</button>
      </div>

      <button type="button" className={styles.profileButton} onClick={openSwitcher}
        aria-label={`Switch profile (current: ${profile}), shortcut ${mod}P`} aria-keyshortcuts="Meta+P Control+P"
        title={`Switch profile (${mod}P)`}>
        <span aria-hidden="true" className={styles.avatar} style={{ background: avatar.background, color: avatar.color }}>{avatar.initials}</span>
        <span className={styles.profileText}>
          <span className={styles.profileName}>{profile}</span>
          <span className={styles.profileMeta}>{meta}</span>
        </span>
        <Kbd>{mod}P</Kbd>
      </button>

      <button type="button" className={styles.commandButton} onClick={openPalette} aria-keyshortcuts="Meta+K Control+K">
        <Icon name="search" size={14} strokeWidth={2} />
        <span>Search or run a command</span>
        <Kbd>{mod}K</Kbd>
      </button>

      <div id="console-menu" className={styles.menu}>
      <Group>
        <NavItem to={url('')} icon="overview" label="Overview" active={section === ''} />
      </Group>

      <Group label="Knowledge">
        <NavItem to={url('memories')} icon="memories" label="Memories" active={section === 'memories'}
          badge={memoryCount !== undefined && <span className={styles.count}>{integer(memoryCount)}</span>} />
        <NavItem to={url('wiki')} icon="wiki" label="Wiki" active={section === 'wiki'} badge={badges.wiki} />
        <NavItem to={url('graph')} icon="graph" label="Entity graph" active={section === 'graph'} />
      </Group>

      <Group label="Ask">
        <NavItem to={url('recall')} icon="recall" label="Recall" active={section === 'recall'} />
      </Group>

      <Group label="Operations">
        <NavItem to={url('activity')} icon="activity" label="Activity" active={section === 'activity'} badge={badges.activity} />
        <NavItem to={url('tasks')} icon="tasks" label="Background tasks" active={section === 'tasks'} badge={badges.tasks} />
      </Group>

      <Group label="Settings">
        <NavItem to={url('config')} icon="config" label="Profile config" active={section === 'config'} badge={badges.config} />
        <NavItem to={url('settings')} icon="global" label="Global config" active={section === 'settings'} />
      </Group>

      </div>
      <div className={styles.grow} />
      <DaemonCard />
    </nav>
  );
}

function DaemonCard() {
  const status = useStatus();
  const data = status.data;
  const state = status.isError ? 'down' : data && data.status !== 'up' && data.status !== 'ready' ? 'degraded' : 'up';
  const label = status.isError ? 'Daemon unreachable' : !data ? 'Checking daemon…' : state === 'up' ? 'Daemon ready' : 'Daemon degraded';
  const provider = data?.providerLabel || data?.modelProvider;
  return (
    <div className={styles.daemon} role="status">
      <div className={styles.daemonHead}>
        <span aria-hidden="true" className={`${styles.daemonDot} ${state !== 'up' ? styles[state] : ''}`} />
        <strong>{label}</strong>
        <span className={styles.grow} />
        {data?.port !== undefined && <span className={styles.count}>:{data.port}</span>}
      </div>
      {data && (
        <div className={styles.daemonMeta}>
          <span>{data.backend}</span>
          <span>vector {data.vectorSearch ? 'on' : 'off'}</span>
          {data.vectorizationOutboxDepth !== null && <span>outbox {integer(data.vectorizationOutboxDepth)}</span>}
          {provider && <span>{provider}</span>}
        </div>
      )}
    </div>
  );
}
