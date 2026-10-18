import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TasksPage, isWaiting } from './TasksPage';
import { mockApi, renderAt } from '../../test/render';

const running = {
  id: 't1', kind: 'code', profile: 'p', status: 'RUNNING', startedAtEpochMs: Date.now() - 41_000, errorKind: null, errorMessage: null,
  lanes: [{ name: 'Parse files', state: 'RUNNING', phase: 'tree-sitter', done: 412, total: 1030, phaseStartedAtEpochMs: 0 }]
};
const failed = {
  id: 't2', kind: 'ingest-transcript', profile: 'p', status: 'FAILED', startedAtEpochMs: Date.now() - 60_000, finishedAtEpochMs: Date.now() - 30_000,
  durationMs: 31_100, errorKind: 'failure', errorMessage: 'Could not open JDBC Connection', retryable: true, resultSummary: null,
  lanes: []
};

describe('TasksPage', () => {
  it('shows running work with lanes and cancels it', async () => {
    const calls = mockApi({ '/v1/tasks': { tasks: [running, failed] }, '/v1/tasks/t1': { status: 'CANCELLED' } });
    renderAt('/p/:profile/tasks', '/p/p/tasks', <TasksPage />);

    expect(await screen.findByText('412 / 1,030 · 40%')).toBeInTheDocument();
    expect(screen.getByText('Could not open JDBC Connection')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(calls.some(c => c.url.pathname === '/v1/tasks/t1' && c.init?.method === 'DELETE')).toBe(true));
  });

  it('treats a task whose lanes have not started as waiting', () => {
    expect(isWaiting({ ...running, lanes: [{ ...running.lanes[0], state: 'QUEUED' }] } as never)).toBe(true);
    expect(isWaiting(running as never)).toBe(false);
  });
});
