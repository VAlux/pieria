import { useMemo, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { useProfiles } from '../../api/queries';
import { avatarFor } from '../../lib/avatar';
import { integer } from '../../lib/format';
import { mod } from '../../lib/hotkeys';
import { switchedPath } from '../../lib/routes';
import { dismiss, pinnedProfiles, recentProfiles, togglePinned } from '../../lib/storage';
import { Kbd } from '../../ui/controls';
import { ErrorBanner, SkeletonRows } from '../../ui/display';
import { Icon } from '../../ui/icons';
import { Dialog } from '../../ui/overlay';
import { useProfile } from '../shell/context';
import { buildSwitcher } from './switcher';
import styles from './switcher.module.css';

export function ProfileSwitcher({ onClose }: { onClose: () => void }) {
  const current = useProfile();
  const location = useLocation();
  const navigate = useNavigate();
  const profiles = useProfiles();
  const input = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const [pinned, setPinned] = useState(pinnedProfiles);
  const [showHidden, setShowHidden] = useState(false);
  const [recent] = useState(recentProfiles);

  const model = useMemo(
    () => buildSwitcher(profiles.data ?? [], query, pinned, recent, current, showHidden),
    [profiles.data, query, pinned, recent, current, showHidden]
  );

  const hrefFor = (name: string) => switchedPath(location.pathname, name);
  const choose = (name: string, newTab = false) => {
    dismiss('switcherTip');
    if (newTab) {
      window.open(`/console${hrefFor(name)}`, '_blank', 'noopener');
      return;
    }
    onClose();
    if (name !== current) navigate(hrefFor(name));
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    const total = model.order.length;
    if (event.key === 'ArrowDown') { event.preventDefault(); setActive(i => Math.min(i + 1, total - 1)); }
    else if (event.key === 'ArrowUp') { event.preventDefault(); setActive(i => Math.max(i - 1, 0)); }
    else if (event.key === 'Enter' && model.order[active]) {
      event.preventDefault();
      choose(model.order[active], event.metaKey || event.ctrlKey);
    }
  };

  let index = 0;
  const activeName = model.order[active];

  return (
    <Dialog title="Switch profile" onClose={onClose} hideTitle placement="anchoredLeft" initialFocus={input}>
      <label className={styles.searchRow}>
        <Icon name="search" strokeWidth={2} />
        <span className="sr-only">Search profiles</span>
        <input ref={input} type="search" role="combobox" aria-expanded="true" aria-controls="switcher-list"
          aria-activedescendant={activeName ? `switcher-${activeName}` : undefined}
          value={query} placeholder={`Search ${profiles.data?.length ?? ''} profiles…`}
          onChange={e => { setQuery(e.target.value); setActive(0); }} onKeyDown={onKeyDown} />
        <Kbd>{mod}P</Kbd>
      </label>

      <div id="switcher-list" role="listbox" aria-label="Profiles" className={styles.list}>
        {profiles.isPending && <SkeletonRows rows={4} height={32} />}
        {profiles.isError && <ErrorBanner error={profiles.error} />}
        {model.groups.map(group => (
          <div key={group.label} role="group" aria-label={group.label} className={styles.group}>
            <div className={styles.groupHead}>
              <span>{group.label}</span>
              <span className={styles.groupCount}>{group.count}</span>
            </div>
            {group.rows.map(row => {
              const position = index++;
              const isActive = position === active;
              const isPinned = pinned.includes(row.name);
              const avatar = avatarFor(row.name);
              return (
                <div key={`${group.label}-${row.name}`} id={`switcher-${row.name}`} role="option" aria-selected={isActive}
                  className={styles.row} onMouseEnter={() => setActive(position)}>
                  <a href={`/console${hrefFor(row.name)}`} className={styles.rowLink} tabIndex={-1}
                    onClick={event => {
                      if (event.metaKey || event.ctrlKey || event.shiftKey) return;
                      event.preventDefault();
                      choose(row.name);
                    }}>
                    <span aria-hidden="true" className={styles.avatar} style={{ background: avatar.background, color: avatar.color }}>{avatar.initials}</span>
                    <span className={styles.name}>
                      {row.pre}{row.hit && <mark>{row.hit}</mark>}{row.post}
                    </span>
                    {row.name === current && <span className={styles.current}>current</span>}
                    <span className={styles.count}>{integer(row.count)}</span>
                  </a>
                  <button type="button" className={styles.pin} aria-pressed={isPinned} tabIndex={-1}
                    aria-label={`${isPinned ? 'Unpin' : 'Pin'} ${row.name}`} title={isPinned ? 'Unpin' : 'Pin'}
                    onClick={() => setPinned(togglePinned(row.name))}>
                    <Icon name="star" size={14} fill={isPinned ? 'currentColor' : 'none'} />
                  </button>
                </div>
              );
            })}
          </div>
        ))}
        {model.noMatch && (
          <div className={styles.noMatch}>
            <span>No profile matches “{query}”.</span>
            <span>Profiles are created the first time an agent writes to them.</span>
          </div>
        )}
        {!query && model.hiddenCount > 0 && (
          <button type="button" className={styles.hiddenToggle} onClick={() => setShowHidden(s => !s)}>
            {showHidden ? 'Hide empty & scratch profiles' : `Show ${model.hiddenCount} empty & scratch profiles`}
          </button>
        )}
      </div>

      <div className={styles.footer}>
        <span><Kbd>↑↓</Kbd> move</span>
        <span><Kbd>↵</Kbd> switch</span>
        <span><Kbd>{mod}↵</Kbd> new tab</span>
        <span><Kbd>{mod}⇧P</Kbd> back to last</span>
        <span className={styles.grow} />
        <span><Kbd>esc</Kbd> close</span>
      </div>
    </Dialog>
  );
}
