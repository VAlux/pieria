import { useMemo, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { useNavigate } from 'react-router';
import { exportProfile } from '../../api/profiles';
import { useProfiles } from '../../api/queries';
import { errorMessage } from '../../api/client';
import { mod } from '../../lib/hotkeys';
import { profileUrl } from '../../lib/routes';
import { Kbd } from '../../ui/controls';
import { Icon, type IconName } from '../../ui/icons';
import { Dialog, useToast } from '../../ui/overlay';
import { useProfile, useShell } from './context';
import styles from '../profile-switcher/switcher.module.css';

interface Command {
  id: string;
  label: string;
  group: 'Go to' | 'Actions' | 'Profiles';
  icon: IconName;
  hint?: string;
  run: () => void;
}

export function CommandPalette({ onClose }: { onClose: () => void }) {
  const profile = useProfile();
  const navigate = useNavigate();
  const { openSwitcher } = useShell();
  const toast = useToast();
  const profiles = useProfiles();
  const input = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);

  const commands = useMemo<Command[]>(() => {
    const go = (sub: string) => () => { onClose(); navigate(profileUrl(profile, sub)); };
    const views: [string, string, IconName][] = [
      ['Overview', '', 'overview'], ['Memories', 'memories', 'memories'], ['Wiki', 'wiki', 'wiki'],
      ['Entity graph', 'graph', 'graph'], ['Recall', 'recall', 'recall'], ['Activity', 'activity', 'activity'],
      ['Background tasks', 'tasks', 'tasks'], ['Profile config', 'config', 'config'], ['Global config', 'settings', 'global']
    ];
    return [
      ...views.map(([label, sub, icon]) => ({ id: `go-${label}`, label, group: 'Go to' as const, icon, run: go(sub) })),
      { id: 'add', label: 'Add a memory', group: 'Actions', icon: 'plus', run: go('memories/new') },
      { id: 'ask', label: 'Ask this profile', group: 'Actions', icon: 'recall', run: go('recall') },
      { id: 'switch', label: 'Switch profile…', group: 'Actions', icon: 'search', hint: `${mod}P`, run: () => { onClose(); openSwitcher(); } },
      {
        id: 'export', label: 'Export memories as NDJSON', group: 'Actions', icon: 'download', run: () => {
          onClose();
          exportProfile(profile).catch(e => toast.show('Export failed', errorMessage(e), 'error'));
        }
      },
      ...(profiles.data ?? []).filter(p => p.name !== profile).map(p => ({
        id: `profile-${p.name}`, label: p.name, group: 'Profiles' as const, icon: 'memories' as IconName,
        run: () => { onClose(); navigate(profileUrl(p.name)); }
      }))
    ];
  }, [profile, profiles.data, navigate, onClose, openSwitcher, toast]);

  const q = query.trim().toLowerCase();
  const visible = commands
    .filter(c => (q ? c.label.toLowerCase().includes(q) : c.group !== 'Profiles'))
    .slice(0, 40);
  const groups = (['Go to', 'Actions', 'Profiles'] as const)
    .map(group => ({ group, items: visible.filter(c => c.group === group) }))
    .filter(g => g.items.length);
  const order = groups.flatMap(g => g.items);

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown') { event.preventDefault(); setActive(i => Math.min(i + 1, order.length - 1)); }
    else if (event.key === 'ArrowUp') { event.preventDefault(); setActive(i => Math.max(i - 1, 0)); }
    else if (event.key === 'Enter' && order[active]) { event.preventDefault(); order[active].run(); }
  };

  let index = 0;
  return (
    <Dialog title="Command palette" onClose={onClose} hideTitle placement="anchoredLeft" initialFocus={input}>
      <label className={styles.searchRow}>
        <Icon name="search" strokeWidth={2} />
        <span className="sr-only">Search commands</span>
        <input ref={input} type="search" role="combobox" aria-expanded="true" aria-controls="palette-list"
          aria-activedescendant={order[active] ? `palette-${order[active].id}` : undefined}
          value={query} placeholder="Search screens, actions and profiles…"
          onChange={e => { setQuery(e.target.value); setActive(0); }} onKeyDown={onKeyDown} />
        <Kbd>{mod}K</Kbd>
      </label>
      <div id="palette-list" role="listbox" aria-label="Commands" className={styles.list}>
        {groups.map(({ group, items }) => (
          <div key={group} role="group" aria-label={group} className={styles.group}>
            <div className={styles.groupHead}><span>{group}</span></div>
            {items.map(command => {
              const position = index++;
              return (
                <div key={command.id} id={`palette-${command.id}`} role="option" aria-selected={position === active}
                  className={styles.row} onMouseEnter={() => setActive(position)}>
                  <button type="button" tabIndex={-1} className={styles.rowLink}
                    style={{ border: 0, background: 'transparent', textAlign: 'left' }} onClick={command.run}>
                    <Icon name={command.icon} />
                    <span className={styles.name}>{command.label}</span>
                    {command.hint && <Kbd>{command.hint}</Kbd>}
                  </button>
                </div>
              );
            })}
          </div>
        ))}
        {order.length === 0 && <div className={styles.noMatch}><span>Nothing matches “{query}”.</span></div>}
      </div>
      <div className={styles.footer}>
        <span><Kbd>↑↓</Kbd> move</span>
        <span><Kbd>↵</Kbd> run</span>
        <span className={styles.grow} />
        <span><Kbd>esc</Kbd> close</span>
      </div>
    </Dialog>
  );
}
