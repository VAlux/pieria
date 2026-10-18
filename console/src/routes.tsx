import { createBrowserRouter, Navigate, useLocation } from 'react-router';
import { useProfiles } from './api/queries';
import { lastProfile } from './lib/storage';
import { legacyTarget, profileUrl } from './lib/routes';
import { AppShell } from './features/shell/AppShell';
import { Placeholder } from './features/shell/Placeholder';
import { ActivityPage } from './features/activity/ActivityPage';
import { MemoriesPage } from './features/memories/MemoriesPage';
import { OverviewPage } from './features/overview/OverviewPage';
import { RecallPage } from './features/recall/RecallPage';
import { TasksPage } from './features/tasks/TasksPage';
import { EmptyState, ErrorBanner, Page, SkeletonRows } from './ui/display';

/** `/console/` picks the last-used profile; legacy `?view=` links land on the matching screen. */
function RootRedirect() {
  const location = useLocation();
  const profiles = useProfiles();
  const legacy = legacyTarget(location.search, lastProfile());
  if (legacy) return <Navigate to={legacy} replace />;
  if (profiles.isPending) return <Page><SkeletonRows rows={3} /></Page>;
  if (profiles.isError) return <Page><ErrorBanner error={profiles.error} /></Page>;
  const names = profiles.data.map(p => p.name);
  const remembered = lastProfile();
  const target = remembered && names.includes(remembered) ? remembered
    : [...profiles.data].sort((a, b) => b.memoryCount - a.memoryCount)[0]?.name;
  if (!target) {
    return (
      <Page>
        <EmptyState title="No profiles yet">
          Profiles are created the first time an agent writes to them. Connect a harness through the
          MCP gateway or ingest a transcript, then come back here.
        </EmptyState>
      </Page>
    );
  }
  return <Navigate to={profileUrl(target)} replace />;
}

export const routes = [
  { path: '/', element: <RootRedirect /> },
  {
    path: '/p/:profile',
    element: <AppShell />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'memories', element: <MemoriesPage /> },
      { path: 'memories/new', element: <MemoriesPage adding /> },
      { path: 'memories/:memoryId', element: <MemoriesPage /> },
      { path: 'recall', element: <RecallPage /> },
      { path: 'activity', element: <ActivityPage /> },
      { path: 'activity/:eventId', element: <ActivityPage /> },
      { path: 'tasks', element: <TasksPage /> },
      { path: 'wiki', element: <Placeholder screen="Wiki" legacyView="wiki" /> },
      { path: 'wiki/:pageId', element: <Placeholder screen="Wiki" legacyView="wiki" /> },
      { path: 'graph', element: <Placeholder screen="Entity graph" legacyView="graph" /> },
      { path: 'config', element: <Placeholder screen="Profile config" legacyView="profile-config" /> },
      { path: 'settings', element: <Placeholder screen="Global config" legacyView="global-config" /> },
      { path: '*', element: <Navigate to="." replace /> }
    ]
  },
  { path: '*', element: <Navigate to="/" replace /> }
];

export const router = createBrowserRouter(routes, { basename: '/console' });
