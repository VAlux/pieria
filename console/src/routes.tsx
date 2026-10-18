import { createBrowserRouter, Navigate, useLocation } from 'react-router';
import { useProfiles } from './api/queries';
import { lastProfile } from './lib/storage';
import { legacyTarget, profileUrl } from './lib/routes';
import { AppShell } from './features/shell/AppShell';
import { MemoriesPage } from './features/memories/MemoriesPage';
import { OverviewPage } from './features/overview/OverviewPage';
import { RecallPage } from './features/recall/RecallPage';
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

// Screens with heavier dependencies (d3, the config schema editors) load on first visit.
const activity = () => import('./features/activity/ActivityPage').then(m => ({ Component: m.ActivityPage }));
const wiki = () => import('./features/wiki/WikiPage').then(m => ({ Component: m.WikiPage }));

export const routes = [
  { path: '/', element: <RootRedirect /> },
  {
    path: '/p/:profile',
    element: <AppShell />,
    hydrateFallbackElement: <Page><SkeletonRows rows={4} /></Page>,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'memories', element: <MemoriesPage /> },
      { path: 'memories/new', element: <MemoriesPage adding /> },
      { path: 'memories/:memoryId', element: <MemoriesPage /> },
      { path: 'recall', element: <RecallPage /> },
      { path: 'activity', lazy: activity },
      { path: 'activity/:eventId', lazy: activity },
      { path: 'tasks', lazy: () => import('./features/tasks/TasksPage').then(m => ({ Component: m.TasksPage })) },
      { path: 'wiki', lazy: wiki },
      { path: 'wiki/:pageId', lazy: wiki },
      { path: 'graph', lazy: () => import('./features/graph/GraphPage').then(m => ({ Component: m.GraphPage })) },
      { path: 'config', lazy: () => import('./features/config/ProfileConfigPage').then(m => ({ Component: m.ProfileConfigPage })) },
      { path: 'settings', lazy: () => import('./features/config/GlobalConfigPage').then(m => ({ Component: m.GlobalConfigPage })) },
      { path: '*', element: <Navigate to="." replace /> }
    ]
  },
  { path: '*', element: <Navigate to="/" replace /> }
];

export const router = createBrowserRouter(routes, { basename: '/console' });
