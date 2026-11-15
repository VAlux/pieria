import { useState } from 'react';
import type { DragEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { errorMessage } from '../../api/client';
import { deleteProfile, setProfileParent } from '../../api/profiles';
import { qk, useProfiles } from '../../api/queries';
import type { ProfileSummary } from '../../api/types';
import { integer, plural } from '../../lib/format';
import { profileUrl } from '../../lib/routes';
import { Button, cx, IconButton, Select } from '../../ui/controls';
import { Card, EmptyState, ErrorBanner, Page, PageHeader, PageIntro, SkeletonRows } from '../../ui/display';
import { Icon } from '../../ui/icons';
import { Dialog, MenuItem, Popover, usePopover, useToast } from '../../ui/overlay';
import { useProfile } from '../shell/context';
import { CreateProfileDialog } from './CreateProfileDialog';
import { buildTree, canMove, flatten, subtreeOf, validParents, type TreeNode } from './tree';
import styles from './profiles.module.css';

const TOP = '\u0000top';

/** Moves a profile and refreshes everything a move changes: the listing and every cached recall. */
function useMove() {
  const client = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ name, parent }: { name: string; parent: string | null }) => setProfileParent(name, parent),
    onSuccess: moved => {
      toast.show('Profile moved', moved.parent ? `${moved.name} now sits under ${moved.parent}.` : `${moved.name} is now at top level.`);
      client.invalidateQueries({ queryKey: qk.profiles });
      client.invalidateQueries({ queryKey: ['profile'] });
    },
    onError: error => toast.show('Could not move the profile', errorMessage(error), 'error')
  });
}

export function ProfilesPage() {
  const current = useProfile();
  const profiles = useProfiles();
  const move = useMove();
  const [params, setParams] = useSearchParams();
  const [creating, setCreating] = useState(() => params.get('new') === '1');
  const closeCreate = () => {
    setCreating(false);
    if (params.has('new')) setParams(p => { p.delete('new'); return p; }, { replace: true });
  };
  const [moving, setMoving] = useState<ProfileSummary | null>(null);
  const [deleting, setDeleting] = useState<ProfileSummary | null>(null);
  const [dragging, setDragging] = useState<string | null>(null);
  const [over, setOver] = useState<string | null>(null);
  const navigate = useNavigate();

  const list = profiles.data ?? [];
  const rows = flatten(buildTree(list));

  const target = (parent: string | null) => ({
    onDragOver: (event: DragEvent) => {
      if (!dragging || !canMove(list, dragging, parent)) return;
      event.preventDefault();
      if (event.dataTransfer) event.dataTransfer.dropEffect = 'move';
      setOver(parent ?? TOP);
    },
    onDragLeave: () => setOver(o => (o === (parent ?? TOP) ? null : o)),
    onDrop: (event: DragEvent) => {
      event.preventDefault();
      const name = dragging;
      setDragging(null);
      setOver(null);
      if (name && canMove(list, name, parent)) move.mutate({ name, parent });
    }
  });

  return (
    <Page label="Profiles">
      <PageHeader crumb="Daemon" title="Profiles"
        actions={<Button variant="primary" icon="plus" onClick={() => setCreating(true)}>New profile</Button>} />
      <PageIntro>
        Drag a profile onto another to put it inside. Agents keep writing to the profile they always wrote to; a
        recall on a profile also reads every profile below it.
      </PageIntro>

      {profiles.isPending && <SkeletonRows rows={4} />}
      {profiles.isError && <ErrorBanner error={profiles.error} />}
      {profiles.isSuccess && rows.length === 0 && (
        <EmptyState title="No profiles yet" actions={<Button variant="primary" onClick={() => setCreating(true)}>New profile</Button>}>
          Profiles are also created the first time an agent writes to them.
        </EmptyState>
      )}

      {rows.length > 0 && (
        <Card flush aria-label="Profile tree">
          <div className={cx(styles.topZone, dragging && styles.topZoneVisible, over === TOP && styles.dropOver)}
            {...target(null)} aria-hidden={!dragging}>
            Drop here to move to top level
          </div>
          <ul className={styles.tree} role="tree" aria-label="Profiles">
            {rows.map(node => (
              <TreeRow key={node.profile.name} node={node} current={current}
                dragging={dragging === node.profile.name}
                dropOver={over === node.profile.name}
                dropTarget={target(node.profile.name)}
                onDragStart={() => setDragging(node.profile.name)}
                onDragEnd={() => { setDragging(null); setOver(null); }}
                onMove={() => setMoving(node.profile)}
                onDelete={() => setDeleting(node.profile)} />
            ))}
          </ul>
        </Card>
      )}

      {creating && <CreateProfileDialog onClose={closeCreate} />}
      {moving && <MoveDialog profile={moving} profiles={list} onClose={() => setMoving(null)}
        onMove={parent => { move.mutate({ name: moving.name, parent }); setMoving(null); }} />}
      {deleting && <DeleteDialog profile={deleting} profiles={list} onClose={() => setDeleting(null)}
        onDeleted={() => {
          if (deleting.name !== current) return;
          const next = list.find(p => p.name !== deleting.name);
          navigate(next ? profileUrl(next.name, 'profiles') : '/', { replace: true });
        }} />}
    </Page>
  );
}

interface TreeRowProps {
  node: TreeNode;
  current: string;
  dragging: boolean;
  dropOver: boolean;
  dropTarget: { onDragOver: (e: DragEvent) => void; onDragLeave: () => void; onDrop: (e: DragEvent) => void };
  onDragStart: () => void;
  onDragEnd: () => void;
  onMove: () => void;
  onDelete: () => void;
}

function TreeRow({ node, current, dragging, dropOver, dropTarget, onDragStart, onDragEnd, onMove, onDelete }: TreeRowProps) {
  const menu = usePopover();
  const { profile, depth, children, subtreeCount } = node;
  return (
    <li role="treeitem" aria-level={depth + 1} aria-selected={profile.name === current}
      aria-expanded={children.length > 0 ? true : undefined}
      className={cx(styles.row, dragging && styles.dragging, dropOver && styles.dropOver)}
      style={{ paddingLeft: 12 + depth * 22 }}
      draggable
      onDragStart={event => {
        event.dataTransfer?.setData('text/plain', profile.name);
        if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
        onDragStart();
      }}
      onDragEnd={onDragEnd}
      {...dropTarget}>
      <span className={styles.grip} aria-hidden="true"><Icon name="grip" size={14} /></span>
      {depth > 0 && <span className={styles.branch} aria-hidden="true">└</span>}
      <Link to={profileUrl(profile.name)} className={styles.name} draggable={false}>{profile.name}</Link>
      {profile.name === current && <span className={styles.current}>current</span>}
      <span className={styles.meta}>
        {plural(profile.memoryCount, 'memory', 'memories')}
        {subtreeCount > profile.memoryCount && <> · recall reads {integer(subtreeCount)}</>}
      </span>
      <span className={styles.grow} />
      <IconButton ref={menu.anchor} icon="more" label={`Actions for ${profile.name}`} bare onClick={menu.toggle}
        aria-haspopup="menu" aria-expanded={menu.open} />
      <Popover anchor={menu.anchor} open={menu.open} onClose={menu.close} label={`Actions for ${profile.name}`} align="end">
        <MenuItem onSelect={() => { menu.close(); onMove(); }}>Move to…</MenuItem>
        <MenuItem danger onSelect={() => { menu.close(); onDelete(); }}>Delete…</MenuItem>
      </Popover>
    </li>
  );
}

function MoveDialog({ profile, profiles, onClose, onMove }: {
  profile: ProfileSummary; profiles: ProfileSummary[]; onClose: () => void; onMove: (parent: string | null) => void;
}) {
  const [parent, setParent] = useState(profile.parent ?? '');
  const options = validParents(profiles, profile.name);
  const unchanged = (profile.parent ?? '') === parent;
  return (
    <Dialog title={`Move ${profile.name}`} onClose={onClose}
      footer={<>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="primary" disabled={unchanged} onClick={() => onMove(parent || null)}>Move</Button>
      </>}>
      <label className={styles.field}>
        <span>Put it under</span>
        <Select value={parent} onChange={event => setParent(event.target.value)} autoFocus>
          <option value="">Top level</option>
          {options.map(name => <option key={name} value={name}>{name}</option>)}
        </Select>
        <span className={styles.fieldHint}>Its own sub-profiles move with it and are not offered here.</span>
      </label>
    </Dialog>
  );
}

function DeleteDialog({ profile, profiles, onClose, onDeleted }: {
  profile: ProfileSummary; profiles: ProfileSummary[]; onClose: () => void; onDeleted: () => void;
}) {
  const client = useQueryClient();
  const toast = useToast();
  const children = profiles.filter(p => p.parent === profile.name).map(p => p.name);
  const below = subtreeOf(profiles, profile.name).size - 1;
  const remove = useMutation({
    mutationFn: () => deleteProfile(profile.name),
    onSuccess: () => {
      toast.show('Profile deleted', `${profile.name} and its ${plural(profile.memoryCount, 'memory', 'memories')} are gone.`);
      client.removeQueries({ queryKey: qk.profile(profile.name) });
      client.invalidateQueries({ queryKey: qk.profiles });
      client.invalidateQueries({ queryKey: ['profile'] });
      onClose();
      onDeleted();
    }
  });
  return (
    <Dialog title={`Delete ${profile.name}?`} onClose={onClose}
      footer={<>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="danger" disabled={remove.isPending} onClick={() => remove.mutate()}>Delete profile</Button>
      </>}>
      <p className={styles.copy}>
        This permanently deletes {profile.name} and its {plural(profile.memoryCount, 'memory', 'memories')}. It can’t be undone.
      </p>
      {children.length > 0 && (
        <p className={styles.copy}>
          {plural(children.length, 'sub-profile')} ({children.join(', ')}) will move up to{' '}
          {profile.parent ? <strong>{profile.parent}</strong> : 'top level'}
          {below > children.length && ', keeping their own sub-profiles'}. Their memories are kept.
        </p>
      )}
      {remove.error && <ErrorBanner error={remove.error} />}
    </Dialog>
  );
}
