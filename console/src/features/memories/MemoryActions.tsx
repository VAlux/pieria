import { useState } from 'react';
import type { ReactNode } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { ApiError, errorMessage } from '../../api/client';
import { forgetMemory, reviseMemory } from '../../api/memories';
import { qk } from '../../api/queries';
import type { Memory, Revision, RevisionRequest } from '../../api/types';
import { fromLocalInput, toLocalInput } from '../../lib/format';
import { profileUrl } from '../../lib/routes';
import { Button, Checkbox, TextArea, TextInput } from '../../ui/controls';
import { ErrorBanner, Spacer } from '../../ui/display';
import { Dialog, DisabledHint, MenuItem, MenuSeparator, Popover, usePopover, useToast } from '../../ui/overlay';
import styles from './memories.module.css';

interface Props {
  profile: string;
  memory: Memory;
  editable: Revision | null;
  onCopy: (text: string, what: string) => void;
  recallLink: ReactNode;
}

type Open = 'correct' | 'end' | 'forget' | null;

export function MemoryActions({ profile, memory, editable, onCopy, recallLink }: Props) {
  const [open, setOpen] = useState<Open>(null);
  const menu = usePopover();
  const noRevision = 'This memory has no live assertion to revise — it was corrected, invalidated or forgotten.';
  const correct = <Button disabled={!editable} onClick={() => setOpen('correct')}>Correct…</Button>;
  const end = <Button disabled={!editable} onClick={() => setOpen('end')}>End validity…</Button>;
  return (
    <>
      {editable ? correct : <DisabledHint reason={noRevision}>{correct}</DisabledHint>}
      {editable ? end : <DisabledHint reason={noRevision}>{end}</DisabledHint>}
      {recallLink}
      <Spacer />
      <Button ref={menu.anchor} aria-label="More actions: raw JSON, copy ids, forget" aria-haspopup="menu" aria-expanded={menu.open} onClick={menu.toggle}>···</Button>
      <Popover anchor={menu.anchor} open={menu.open} onClose={menu.close} label="More actions" align="end">
        <MenuItem onSelect={() => { menu.close(); onCopy(JSON.stringify(memory, null, 2), 'Raw JSON'); }}>Copy raw JSON</MenuItem>
        <MenuItem onSelect={() => { menu.close(); onCopy(memory.id, 'Memory id'); }}>Copy memory id</MenuItem>
        {editable && <MenuItem onSelect={() => { menu.close(); onCopy(editable.assertionId, 'Assertion id'); }}>Copy assertion id</MenuItem>}
        {memory.topicKey && <MenuItem onSelect={() => { menu.close(); onCopy(memory.topicKey!, 'Topic key'); }}>Copy topic key</MenuItem>}
        <MenuSeparator />
        <MenuItem danger onSelect={() => { menu.close(); setOpen('forget'); }}>Forget memory…</MenuItem>
      </Popover>
      {open === 'correct' && editable && <CorrectDialog profile={profile} memory={memory} revision={editable} onClose={() => setOpen(null)} />}
      {open === 'end' && editable && <EndValidityDialog profile={profile} memory={memory} revision={editable} onClose={() => setOpen(null)} />}
      {open === 'forget' && <ForgetDialog profile={profile} memory={memory} onClose={() => setOpen(null)} />}
    </>
  );
}

function useRevise(profile: string, memory: Memory, onDone: () => void) {
  const client = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (body: RevisionRequest) => reviseMemory(profile, memory.id, body),
    onSuccess: () => {
      toast.show('Revision stored', 'The validity history now shows the new version.');
      client.invalidateQueries({ queryKey: qk.memory(profile, memory.id) });
      client.invalidateQueries({ queryKey: ['profile', profile, 'memories'] });
      onDone();
    },
    onError: error => {
      if (error instanceof ApiError && error.status === 409) {
        toast.show('The memory changed meanwhile', 'Its history was reloaded; try again on the latest version.', 'error');
        client.invalidateQueries({ queryKey: qk.memory(profile, memory.id) });
        onDone();
      }
    }
  });
}

function CorrectDialog({ profile, memory, revision, onClose }: { profile: string; memory: Memory; revision: Revision; onClose: () => void }) {
  const [content, setContent] = useState(revision.content);
  const [from, setFrom] = useState(toLocalInput(revision.validFrom));
  const [to, setTo] = useState(toLocalInput(revision.validTo));
  const [openEnded, setOpenEnded] = useState(!revision.validTo);
  const [error, setError] = useState('');
  const revise = useRevise(profile, memory, onClose);
  const submit = () => {
    const validFrom = fromLocalInput(from);
    const validTo = openEnded ? null : fromLocalInput(to);
    if (!content.trim()) return setError('The corrected text is required.');
    if (!validFrom || (!openEnded && !validTo)) return setError('Give both validity times, or mark it open-ended.');
    if (validTo && Date.parse(validFrom) >= Date.parse(validTo)) return setError('“True from” must come before “true until”.');
    setError('');
    revise.mutate({ assertionId: revision.assertionId, expectedRevisionId: revision.revisionId, content: content.trim(), validFrom, validTo, openEnded });
  };
  return (
    <Dialog title="Correct this memory" onClose={onClose}
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="primary" disabled={revise.isPending} onClick={submit}>Save correction</Button></>}>
      <p className={styles.dialogCopy}>
        A correction records what should have been known. The current text stays in the history as a corrected revision.
      </p>
      <label className={styles.field}>
        <span>Corrected text</span>
        <TextArea rows={4} value={content} onChange={e => setContent(e.target.value)} autoFocus />
      </label>
      <div className={styles.fieldRow}>
        <label className={styles.field}>
          <span>True from</span>
          <TextInput type="datetime-local" step={60} value={from} onChange={e => setFrom(e.target.value)} />
        </label>
        <label className={styles.field}>
          <span>True until</span>
          <TextInput type="datetime-local" step={60} value={to} disabled={openEnded} onChange={e => setTo(e.target.value)} />
        </label>
      </div>
      <Checkbox checked={openEnded} onChange={setOpenEnded}>Still true (open-ended)</Checkbox>
      {(error || revise.error) && <ErrorBanner>{error || errorMessage(revise.error)}</ErrorBanner>}
    </Dialog>
  );
}

function EndValidityDialog({ profile, memory, revision, onClose }: { profile: string; memory: Memory; revision: Revision; onClose: () => void }) {
  const [at, setAt] = useState(toLocalInput(new Date()));
  const [error, setError] = useState('');
  const revise = useRevise(profile, memory, onClose);
  const submit = () => {
    const invalidateAt = fromLocalInput(at);
    if (!invalidateAt) return setError('Pick the moment it stopped being true.');
    setError('');
    revise.mutate({ assertionId: revision.assertionId, expectedRevisionId: revision.revisionId, invalidateAt });
  };
  return (
    <Dialog title="End validity" onClose={onClose}
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="danger" disabled={revise.isPending} onClick={submit}>End validity</Button></>}>
      <p className={styles.dialogCopy}>
        The memory stops applying from this moment in world time. Recalls “as of” an earlier moment still see it, and a fact
        it replaced is not brought back.
      </p>
      <label className={styles.field}>
        <span>No longer true from</span>
        <TextInput type="datetime-local" step={60} value={at} onChange={e => setAt(e.target.value)} autoFocus />
      </label>
      {(error || revise.error) && <ErrorBanner>{error || errorMessage(revise.error)}</ErrorBanner>}
    </Dialog>
  );
}

function ForgetDialog({ profile, memory, onClose }: { profile: string; memory: Memory; onClose: () => void }) {
  const client = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const forget = useMutation({
    mutationFn: () => forgetMemory(profile, memory.id),
    onSuccess: () => {
      toast.show('Memory forgotten', 'It no longer appears in recall at any point in time.');
      client.invalidateQueries({ queryKey: qk.profile(profile) });
      client.invalidateQueries({ queryKey: qk.profiles });
      onClose();
      navigate(profileUrl(profile, 'memories'), { replace: true });
    }
  });
  return (
    <Dialog title="Forget this memory?" onClose={onClose}
      footer={<><Button onClick={onClose}>Cancel</Button><Button variant="danger" disabled={forget.isPending} onClick={() => forget.mutate()}>Forget</Button></>}>
      <p className={styles.dialogCopy}>
        Forgetting removes the memory from every recall, including historical ones, and never restores a fact it replaced.
        Use “End validity” instead if it was true until some point.
      </p>
      <blockquote className={styles.quote}>{memory.content}</blockquote>
      {forget.error && <ErrorBanner error={forget.error} />}
    </Dialog>
  );
}
