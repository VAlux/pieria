import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Health, Memory, Profile, profilePath, request } from './api';
import './style.css';

type View = 'memories' | 'add' | 'recall' | 'stats' | 'audit' | 'graph' | 'wiki' | 'clusters' | 'profile-config' | 'global-config';
const views: { id: View; label: string }[] = [
  { id: 'memories', label: 'Memories' }, { id: 'add', label: 'Add' },
  { id: 'recall', label: 'Recall' }, { id: 'stats', label: 'Statistics' },
  { id: 'audit', label: 'Audit' }, { id: 'graph', label: 'Graph' },
  { id: 'wiki', label: 'Wiki' }, { id: 'clusters', label: 'Clusters' }
];

function initialView(): View {
  const candidate = new URLSearchParams(location.search).get('view');
  return views.some(v => v.id === candidate) ? candidate as View : 'memories';
}

function App() {
  const [view, setView] = useState<View>(initialView);
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [profile, setProfile] = useState(new URLSearchParams(location.search).get('profile') || '');
  const [health, setHealth] = useState<Health | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    let live = true;
    request<{ profiles: Profile[] }>('/v1/profiles').then(result => {
      if (!live) return;
      const sorted = [...(result.profiles || [])].sort((a, b) => a.name.localeCompare(b.name));
      setProfiles(sorted);
      setProfile(current => sorted.some(p => p.name === current) ? current : (sorted[0]?.name || ''));
    }).catch(e => { if (live) setError(String(e.message)); });
    request<Health>('/pieria-health').then(result => { if (live) setHealth(result); })
      .catch(() => { if (live) setHealth({ status: 'unavailable' }); });
    return () => { live = false; };
  }, []);

  useEffect(() => {
    const url = new URL(location.href);
    url.searchParams.set('view', view);
    if (profile) url.searchParams.set('profile', profile);
    else url.searchParams.delete('profile');
    history.replaceState(null, '', url);
  }, [view, profile]);

  return <>
    <header className="topbar">
      <a className="brand" href="/console/">Pieria <span>Console</span></a>
      <strong className="current-profile">{profile || 'No profile'}</strong>
      <a className="legacy" href="/index.html">Current console</a>
    </header>
    <div className="shell">
      <aside className="sidebar">
        <div className="profile-section">
          <h2>Profiles</h2>
          {error && <p role="alert" className="error">{error}</p>}
          {profiles.length === 0 && !error && <p className="muted">No profiles yet.</p>}
          {profiles.length > 0 && <select className="mobile-profile-select" aria-label="Select profile"
            value={profile} onChange={e => setProfile(e.target.value)}>
            {profiles.map(p => <option key={p.name} value={p.name}>{p.name} ({p.memoryCount})</option>)}
          </select>}
          <nav aria-label="Profiles" className="profiles">
            {profiles.map(p => <button key={p.name} type="button" className={p.name === profile ? 'selected' : ''}
              onClick={() => setProfile(p.name)} aria-current={p.name === profile ? 'page' : undefined}>
              <span>{p.name}</span><small>{p.memoryCount}</small>
            </button>)}
          </nav>
        </div>
        <div className="daemon-status">
          <h2>Daemon</h2>
          <p><span className={`status-dot ${health?.status === 'up' ? 'up' : ''}`} />
            {health?.status || 'Checking…'}</p>
          <button type="button" onClick={() => setView('global-config')}>Configuration</button>
        </div>
      </aside>
      <main>
        <nav className="tabs" aria-label="Console views">
          {views.map(v => <button key={v.id} type="button" className={v.id === view ? 'selected' : ''}
            onClick={() => setView(v.id)}>{v.label}</button>)}
          <button type="button" className={view === 'profile-config' ? 'selected' : ''}
            disabled={!profile} onClick={() => setView('profile-config')}>Profile config</button>
        </nav>
        {view === 'memories' ? <Memories key={profile} profile={profile} /> :
          <LegacyView view={view} profile={profile} />}
      </main>
    </div>
  </>;
}

function Memories({ profile }: { profile: string }) {
  const [memories, setMemories] = useState<Memory[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [term, setTerm] = useState('');
  const [type, setType] = useState('');
  const [includeSuperseded, setIncludeSuperseded] = useState(false);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<Memory | null>(null);
  useEffect(() => {
    if (!profile) { setMemories([]); return; }
    const controller = new AbortController();
    setLoading(true); setError('');
    request<{ memories: Memory[] }>(profilePath(profile, `/memories?includeSuperseded=${includeSuperseded}`),
      { signal: controller.signal }).then(result => setMemories(result.memories || []))
      .catch(e => { if (!controller.signal.aborted) setError(String(e.message)); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [profile, includeSuperseded]);
  const shown = useMemo(() => memories.filter(m =>
    (!type || m.type === type) &&
    (!term || `${m.content} ${m.topicKey || ''} ${m.sessionId || ''}`.toLowerCase().includes(term.toLowerCase()))
  ), [memories, term, type]);
  const pageCount = Math.max(1, Math.ceil(shown.length / 100));
  const visible = shown.slice(page * 100, (page + 1) * 100);
  return <section aria-labelledby="memories-title">
    <div className="page-heading"><div><p className="eyebrow">Knowledge</p><h1 id="memories-title">Memories</h1></div>
      <span className="count">{shown.length} shown</span></div>
    {!profile ? <p className="empty">Choose a profile to view its memories.</p> : <>
      <div className="filters">
        <input aria-label="Search memories" placeholder="Search content, topic key, session…"
          value={term} onChange={e => { setTerm(e.target.value); setPage(0); }} />
        <select aria-label="Filter by type" value={type} onChange={e => { setType(e.target.value); setPage(0); }}>
          <option value="">All types</option>{['fact', 'event', 'instruction', 'task'].map(t => <option key={t}>{t}</option>)}
        </select>
        <label><input type="checkbox" checked={includeSuperseded}
          onChange={e => { setIncludeSuperseded(e.target.checked); setPage(0); }} /> Show superseded</label>
      </div>
      {loading && <p role="status" className="empty">Loading memories…</p>}
      {error && <p role="alert" className="error">{error}</p>}
      {!loading && !error && shown.length === 0 && <p className="empty">No matching memories.</p>}
      <div className="memory-list">{visible.map(m => <button type="button" className="memory" key={m.id}
        onClick={() => setSelected(m)}>
        <span className="memory-meta"><b>{m.type}</b>{m.topicKey && <code>{m.topicKey}</code>}
          {m.superseded && <em>superseded</em>}</span>
        <span className="memory-content">{m.content}</span>
        <time>{m.createdAt ? new Date(m.createdAt).toLocaleString() : ''}</time>
      </button>)}</div>
      {shown.length > 100 && <nav className="pager" aria-label="Memory pages">
        <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
        <span>Page {page + 1} of {pageCount}</span>
        <button type="button" disabled={page + 1 >= pageCount} onClick={() => setPage(page + 1)}>Next</button>
      </nav>}
    </>}
    {selected && <div className="dialog-backdrop" onClick={() => setSelected(null)}>
      <section className="detail" role="dialog" aria-modal="true" aria-label="Memory details"
        onClick={event => event.stopPropagation()}>
        <div className="detail-head"><h2>Memory details</h2><button type="button" onClick={() => setSelected(null)}>Close</button></div>
        <dl><dt>Type</dt><dd>{selected.type}</dd><dt>ID</dt><dd><code>{selected.id}</code></dd>
          {selected.topicKey && <><dt>Topic key</dt><dd><code>{selected.topicKey}</code></dd></>}
          {selected.sessionId && <><dt>Session</dt><dd><code>{selected.sessionId}</code></dd></>}</dl>
        <pre>{selected.content}</pre>
      </section></div>}
  </section>;
}

function LegacyView({ view, profile }: { view: View; profile: string }) {
  const oldUrl = `/index.html?${new URLSearchParams({ view, ...(profile ? { profile } : {}) })}`;
  return <section className="coming-soon"><p className="eyebrow">Migration in progress</p>
    <h1>{view.replace('-', ' ')}</h1><p>This view is still available in the current console.</p>
    <a className="action" href={oldUrl}>Open {view.replace('-', ' ')}</a></section>;
}

createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>);
