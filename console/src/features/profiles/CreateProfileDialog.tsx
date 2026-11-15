import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createProfile } from '../../api/profiles';
import { qk, useProfiles } from '../../api/queries';
import type { ProfileSummary } from '../../api/types';
import { profileNameProblem } from '../../lib/profileName';
import { Button, Select, TextInput } from '../../ui/controls';
import { ErrorBanner } from '../../ui/display';
import { Dialog, useToast } from '../../ui/overlay';
import styles from './profiles.module.css';

interface Props {
  onClose: () => void;
  onCreated?: (profile: ProfileSummary) => void;
  initialName?: string;
  initialParent?: string | null;
}

/** Name plus an optional parent; the daemon refuses a taken name (409) or an unknown parent (404). */
export function CreateProfileDialog({ onClose, onCreated, initialName = '', initialParent = null }: Props) {
  const client = useQueryClient();
  const toast = useToast();
  const profiles = useProfiles();
  const [name, setName] = useState(initialName);
  const [parent, setParent] = useState(initialParent ?? '');
  const [touched, setTouched] = useState(Boolean(initialName));
  const problem = profileNameProblem(name);
  const taken = profiles.data?.some(p => p.name === name) ?? false;
  const fieldError = touched ? (problem ?? (taken ? 'A profile with this name already exists.' : null)) : null;
  const create = useMutation({
    mutationFn: () => createProfile(name, parent || null),
    onSuccess: created => {
      toast.show('Profile created', parent ? `${created.name} sits under ${parent}.` : `${created.name} is ready for memories.`);
      client.invalidateQueries({ queryKey: qk.profiles });
      if (parent) client.invalidateQueries({ queryKey: qk.profile(parent) });
      onClose();
      onCreated?.(created);
    }
  });
  const submit = () => {
    setTouched(true);
    if (!problem && !taken) create.mutate();
  };
  const parents = [...(profiles.data ?? [])].map(p => p.name).sort((a, b) => a.localeCompare(b));
  return (
    <Dialog title="New profile" onClose={onClose}
      footer={<>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="primary" type="submit" form="create-profile" disabled={create.isPending}>Create profile</Button>
      </>}>
      <form id="create-profile" className={styles.form} onSubmit={event => { event.preventDefault(); submit(); }}>
        <label className={styles.field}>
          <span>Name</span>
          <TextInput mono value={name} autoFocus aria-invalid={Boolean(fieldError)} aria-describedby="create-profile-name-hint"
            onChange={event => setName(event.target.value)} onBlur={() => setTouched(Boolean(name))} />
          <span id="create-profile-name-hint" className={fieldError ? styles.fieldError : styles.fieldHint}>
            {fieldError ?? 'The name an agent writes to, e.g. the repository name.'}
          </span>
        </label>
        <label className={styles.field}>
          <span>Parent</span>
          <Select value={parent} onChange={event => setParent(event.target.value)}>
            <option value="">None — top level</option>
            {parents.map(p => <option key={p} value={p}>{p}</option>)}
          </Select>
          <span className={styles.fieldHint}>A recall on the parent also reads this profile’s memories.</span>
        </label>
      </form>
      {create.error && <ErrorBanner error={create.error} />}
    </Dialog>
  );
}
