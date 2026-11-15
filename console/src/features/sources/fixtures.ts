import type { Harvester, Source, SourceRun } from '../../api/sources';

/** Daemon-shaped fixtures for the Sources tests. */
export const jira: Harvester = {
  id: 'jira', name: 'Jira', version: '1', capabilities: ['incremental', 'deletes'], network: ['the Jira site at baseUrl'], bundled: true,
  fields: [
    { key: 'baseUrl', kind: 'string', label: 'Base URL', hint: 'For example https://acme.atlassian.net', required: true, options: [], defaultValue: null },
    { key: 'token', kind: 'secret', label: 'API token', hint: null, required: true, options: [], defaultValue: null },
    { key: 'jql', kind: 'string', label: 'Which issues', hint: null, required: true, options: [], defaultValue: null },
    { key: 'sweepHours', kind: 'int', label: 'Hours between deletion checks', hint: null, required: false, options: [], defaultValue: 24 }
  ]
};

export const teams: Harvester = {
  id: 'teams', name: 'Microsoft Teams', version: '1', capabilities: ['incremental', 'deletes', 'oauth-device-code'],
  network: ['login.microsoftonline.com, or loginUrl', 'graph.microsoft.com, or graphUrl'], bundled: true,
  fields: [
    { key: 'clientId', kind: 'string', label: 'Application (client) ID', hint: null, required: true, options: [], defaultValue: null },
    { key: 'includeChats', kind: 'bool', label: 'Read your chats', hint: null, required: false, options: [], defaultValue: true },
    { key: 'channels', kind: 'list', label: 'Channels', hint: null, required: false, options: [], defaultValue: null },
    { key: 'refreshToken', kind: 'secret', label: "Sign-in (set by 'pieria source authorize')", hint: null, required: false, options: [], defaultValue: null }
  ]
};

export function run(overrides: Partial<SourceRun> = {}): SourceRun {
  return {
    id: 'r1', dryRun: false, status: 'succeeded', startedAt: new Date(Date.now() - 120_000).toISOString(),
    finishedAt: new Date(Date.now() - 60_000).toISOString(), itemsSeen: 10, itemsChanged: 2, itemsDeleted: 0, memoriesStored: 5,
    chars: 1000, errorClass: null, errorMessage: null, stderrTail: null, ...overrides
  };
}

export function source(overrides: Partial<Source> = {}): Source {
  return {
    id: 'acme', profile: 'p', harvester: 'jira', config: { baseUrl: 'https://acme.atlassian.net', jql: 'project = A' },
    secretNames: ['token'], mode: 'full', intervalSeconds: 21600, maxItemsPerRun: null, maxCharsPerRun: null, status: 'active',
    nextRunAt: new Date(Date.now() + 3_600_000).toISOString(), consecutiveFailures: 0, sweepBlockedCount: null, acceptNextSweep: false,
    running: false, warnings: [], lastRun: run(), ...overrides
  };
}
