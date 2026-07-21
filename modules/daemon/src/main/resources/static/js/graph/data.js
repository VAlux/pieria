import { $, el, apiFetch, escapeHtml } from "../util/dom.js";
import { g, colorFor, resetColors } from "./state.js";
import { tick, startSim } from "./simulation.js";
import { fit } from "./interaction.js";

// Banners live inside the graph view container so they sit under the top nav.
function setBanner(html, isErr) {
  clearBanner();
  const host = $("view-graph");
  const b = el("div", "graph-banner" + (isErr ? " err" : ""));
  b.id = "graphBanner";
  b.innerHTML = html;
  host.appendChild(b);
}
function clearBanner() {
  const b = $("graphBanner");
  if (b) b.remove();
}

// Fetch the entity/relation graph for a profile and lay it out.
export function load(profile) {
  if (!profile) {
    setBanner("<h2>No profile selected</h2>Pick a profile above.", false);
    return;
  }
  g.loadedProfile = profile;
  setBanner("Loading <b>" + escapeHtml(profile) + "</b>…", false);
  apiFetch("/v1/profiles/" + encodeURIComponent(profile) + "/graph", { headers: { Accept: "application/json" } })
    .then(function (r) {
      if (r.status === 404) throw new Error("Profile \"" + profile + "\" not found.");
      if (!r.ok) throw new Error("Request failed (" + r.status + ").");
      return r.json();
    })
    .then(function (data) { ingest(data); })
    .catch(function (e) { setBanner("<h2>Could not load graph</h2>" + escapeHtml(e.message), true); });
}

function ingest(data) {
  clearBanner();
  resetColors();
  g.nodeById = {};
  const w = g.canvas.clientWidth || 800, h = g.canvas.clientHeight || 600;
  g.nodes = (data.nodes || []).map(function (n) {
    const node = {
      id: n.id, name: n.name, type: n.type, deg: 0,
      x: w / 2 + (Math.random() - 0.5) * Math.min(w, h) * 0.6,
      y: h / 2 + (Math.random() - 0.5) * Math.min(w, h) * 0.6,
      vx: 0, vy: 0
    };
    g.nodeById[n.id] = node;
    return node;
  });
  g.links = (data.links || []).filter(function (l) {
    return g.nodeById[l.source] && g.nodeById[l.target];
  }).map(function (l) {
    const s = g.nodeById[l.source], t = g.nodeById[l.target];
    s.deg++; t.deg++;
    return { source: s, target: t, relation: l.relation, memory: l.memory, memoryId: l.memoryId };
  });
  g.nodes.forEach(function (n) { n.r = 5 + Math.sqrt(n.deg) * 3; });
  // Assign a color to every type up front so the legend (built below) sees a populated
  // typeOrder; otherwise colors are only allocated lazily during the first draw().
  g.nodes.forEach(function (n) { colorFor(n.type); });

  buildLegend();
  updateStats();
  g.alpha = 1;
  // brief settle before fitting so the layout has structure to frame
  for (let i = 0; i < 120; i++) tick();
  fit();
  startSim();
}

function buildLegend() {
  const rows = $("graphLegendRows");
  rows.innerHTML = "";
  const types = g.typeOrder.slice().sort();
  types.forEach(function (type) {
    const count = g.nodes.reduce(function (acc, n) { return acc + ((n.type || "unknown") === type ? 1 : 0); }, 0);
    const row = el("div", "row");
    row.innerHTML = '<span class="key"><span class="swatch" style="background:' + colorFor(type) + '"></span></span>'
      + escapeHtml(type) + ' <span class="muted">' + count + '</span>';
    rows.appendChild(row);
  });
  $("graphTypeCount").textContent = types.length ? "(" + types.length + ")" : "";
  $("graphLegend").style.display = types.length ? "block" : "none";
}

function updateStats() {
  const box = $("graphStatsBox");
  box.style.display = "block";
  box.innerHTML = "<b>" + g.nodes.length + "</b> entities · <b>" + g.links.length + "</b> relations";
}
