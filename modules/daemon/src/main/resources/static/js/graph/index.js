// Graph explorer lifecycle: wiring, loading, and the show/hide contract with the console router.
//
// Nothing here draws, lays out, or queries — it connects the modules that do and owns the
// transitions between the explorer's two states: the profile overview (top hubs) and a focused
// neighbourhood.
import { $, el } from "../util/dom.js";
import {
  model, reset, replace, merge, markExpanded, seedPositionsAround, node, typeFilter, noTypesSelected
} from "./model.js";
import { fetchOverview, fetchNeighborhood } from "./api.js";
import * as layout from "./layout.js";
import * as canvas from "./canvas.js";
import * as controls from "./controls.js";
import * as inspector from "./inspector.js";

let initialized = false;
let resizeObserver = null;
let fitWhenSettled = false;   // frame the view once the layout stops moving (see settle())

// Monotonic id for view-replacing loads. Toggling filters quickly fires overlapping requests, and
// a slow earlier one must never land on top of a newer one — on a big profile the unfiltered
// overview takes ~1s while a narrow filter comes back in ~50ms, so out-of-order arrival is the
// normal case, not an edge case.
let loadSeq = 0;

function nextLoad() {
  loadSeq += 1;
  return loadSeq;
}

function isCurrent(seq, profile) {
  return seq === loadSeq && model.profile === profile;
}

function initGraph() {
  if (initialized) return;
  initialized = true;

  layout.init(canvas.draw, onLayoutSettled);
  canvas.init($("graphCanvas"), $("graphHoverCard"), {
    onSelect: selectNode,
    onExpand: expandNode
  });
  controls.init({
    onTypesChanged: reload,
    onDepthChanged: reload,
    onFocus: focusEntity
  });
  inspector.init({
    onFocus: focusEntity,
    onExpand: expandNode
  });

  $("graphFit").addEventListener("click", canvas.fit);
  $("graphReset").addEventListener("click", function () {
    inspector.clear();
    loadOverview();
  });

  window.addEventListener("resize", handleResize);
  if (window.ResizeObserver) {
    resizeObserver = new ResizeObserver(handleResize);
    resizeObserver.observe($("view-graph"));
  }
}

function handleResize() {
  if (!isActive()) return;
  canvas.resize();
  canvas.draw();
}

function isActive() {
  return document.body.classList.contains("view-graph");
}

// ---- router contract ---------------------------------------------------------------------------

export function showGraph(profile, profileChanged) {
  initGraph();
  canvas.resize();

  if (profileChanged || model.profile !== profile) {
    reset(profile);
    // A new profile has its own entity types, so any narrowing from the previous one is meaningless.
    model.activeTypes = null;
    inspector.clear();
    loadOverview();
    return;
  }
  // Returning to a graph we already built: keep it exactly as it was left. Re-fetching or
  // re-simulating here would throw away the layout the user just spent time arranging.
  canvas.draw();
}

// Called by the router when another tab takes over. Stops the simulation and drops every piece of
// transient interaction state, so nothing from this view can surface over another one.
export function hideGraph() {
  if (!initialized) return;
  layout.stop();
  canvas.clearInteraction();
}

// ---- loading -----------------------------------------------------------------------------------

function loadOverview() {
  const profile = model.profile;
  if (!profile) {
    status("No profile selected. Pick one from the side panel.", false);
    return;
  }
  if (emptyTypeSelection()) return;

  const seq = nextLoad();
  busy("Loading graph…");

  fetchOverview(profile, typeFilter(), 300)
    .then(function (data) {
      if (!isCurrent(seq, profile)) return;
      model.totals = { entityCount: data.entityCount, edgeCount: data.edgeCount };
      controls.renderFacets(data.types);
      replace(data, null);

      if (!model.nodes.length) {
        status(data.entityCount === 0
          ? "This profile has no entity graph yet. Memories grow one as they are ingested."
          : "No entities match the current type filter.", false);
        canvas.draw();
        return;
      }
      clearStatus();
      settle();
    })
    .catch(function (e) {
      if (!isCurrent(seq, profile)) return;
      status("Could not load the graph: " + e.message, true);
    });
}

// Start a fresh view centred on one entity.
function focusEntity(entityId) {
  const profile = model.profile;
  if (emptyTypeSelection()) return;

  const seq = nextLoad();
  busy("Loading neighbourhood…");

  fetchNeighborhood(profile, entityId, model.depth, typeFilter(), 300)
    .then(function (data) {
      if (!isCurrent(seq, profile)) return;
      replace(data, entityId);
      clearStatus();
      settle();
      inspector.show(entityId);
    })
    .catch(function (e) {
      if (!isCurrent(seq, profile)) return;
      status("Could not load that entity: " + e.message, true);
    });
}

// Pull a node's neighbours into the current view without discarding what is already there.
//
// Not sequence-guarded: an expand only ever adds to the view, so two overlapping expands both
// produce a valid result whichever order they land in. A view-replacing load landing in between is
// still safe — its own sequence check discards anything stale, and merge() skips nodes it cannot
// anchor.
function expandNode(entityId) {
  const profile = model.profile;
  const before = model.nodes.length;

  fetchNeighborhood(profile, entityId, 1, typeFilter(), 200)
    .then(function (data) {
      if (model.profile !== profile) return;
      merge(data);
      markExpanded(entityId);
      seedPositionsAround(entityId);
      layout.reheat(0.4);
      const added = model.nodes.length - before;
      footer(added > 0
        ? "Added " + added + (added === 1 ? " entity" : " entities")
        : "Nothing new to add here");
      if (model.selectedId === entityId) inspector.show(entityId);
    })
    .catch(function (e) { status("Could not expand: " + e.message, true); });
}

function selectNode(entityId) {
  inspector.show(entityId);
  canvas.draw();
}

// Lay the new subgraph out, then frame it. Nodes start on a ring rather than scattered, so the
// first ticks pull them apart from a compact seed instead of a random cloud.
function settle() {
  model.nodes.forEach(function (n, i) {
    if (n.x !== undefined) return;
    const angle = (i / Math.max(1, model.nodes.length)) * Math.PI * 2;
    const spread = 30 + Math.sqrt(model.nodes.length) * 12;
    n.x = Math.cos(angle) * spread;
    n.y = Math.sin(angle) * spread;
  });
  fitWhenSettled = true;
  layout.reheat(1);
  // An early rough frame so the user is not watching an off-screen graph while it settles; the
  // authoritative fit happens in onLayoutSettled once positions stop moving.
  setTimeout(function () { if (isActive() && fitWhenSettled) canvas.fit(); }, 400);
  updateFooter();
}

// Final framing, once the force layout has come to rest.
function onLayoutSettled() {
  if (!fitWhenSettled) return;
  fitWhenSettled = false;
  if (isActive()) canvas.fit();
}

// ---- status / footer ---------------------------------------------------------------------------

// Unchecking every type means "show nothing" — a coherent request the server cannot express, since
// an empty types param means "no filter". Handle it here rather than sending a request that would
// come back with everything.
function emptyTypeSelection() {
  if (!noTypesSelected()) return false;
  nextLoad();                       // invalidate anything already in flight
  // Keep the focus so re-checking a type returns to where the user was, rather than the overview.
  replace({ nodes: [], links: [] }, model.focusId);
  canvas.draw();
  status("No entity types selected. Pick at least one to see the graph.", false);
  return true;
}

function busy(message) {
  footer(message);
}

function status(message, isError) {
  const host = $("graphStatus");
  host.innerHTML = "";
  host.appendChild(el("div", "graph-status" + (isError ? " err" : ""), message));
  host.style.display = "block";
  footer("");
}

function clearStatus() {
  const host = $("graphStatus");
  host.style.display = "none";
  host.innerHTML = "";
}

function updateFooter() {
  const focus = model.focusId ? node(model.focusId) : null;
  const parts = [];
  parts.push("showing " + model.nodes.length + " of "
    + model.totals.entityCount.toLocaleString()
    + (model.totals.entityCount === 1 ? " entity" : " entities"));
  parts.push(model.links.length + (model.links.length === 1 ? " relation" : " relations") + " drawn");
  if (focus) {
    parts.push(model.depth + (model.depth === 1 ? " hop" : " hops") + " from “" + focus.name + "”");
  } else {
    parts.push("most-connected first");
  }
  if (model.shown.truncated) parts.push("capped");
  footer(parts.join(" · "));
}

function footer(message) {
  $("graphFooter").textContent = message;
}

function reload() {
  if (model.focusId) focusEntity(model.focusId);
  else loadOverview();
}
