import { $, el } from "../util/dom.js";
import { state } from "./state.js";
import { loadMemories } from "./memories.js";
import { loadStats } from "./stats.js";
import { loadAudit } from "./audit.js";
import { showGraph, hideGraph } from "../graph/index.js";
import { loadProfileConfig, unloadProfileConfig, pendingChangeCount as pendingProfileChanges } from "./config/profile.js";
import { loadGlobalConfig, unloadGlobalConfig, pendingChangeCount as pendingGlobalChanges } from "./config/global.js";

// Reflect the active tab in the nav, the visible section, and the URL, then (re)load its data.
export function setView(view) {
  // A config view is torn down below either by actually navigating away (unload*, below) or by
  // reloading it in place when `view` re-targets the already-active one — clicking the same nav
  // entry twice does not skip loadActiveView(), so it refetches and overwrites the form either way.
  // Confirm before losing edits in both cases; declining aborts the navigation outright.
  if (state.view === "profile-config" || state.view === "global-config") {
    const pending = state.view === "profile-config" ? pendingProfileChanges() : pendingGlobalChanges();
    if (pending > 0) {
      const label = pending === 1 ? "1 unsaved change" : pending + " unsaved changes";
      if (!window.confirm("Discard " + label + " on this page?")) return;
    }
  }

  const leavingGraph = state.view === "graph" && view !== "graph";
  // Both config views hold fetched state; leaving must drop it so a profile switch cannot render
  // the previous profile's overrides against the new profile's name.
  if (state.view === "profile-config" && view !== "profile-config") unloadProfileConfig();
  if (state.view === "global-config" && view !== "global-config") unloadGlobalConfig();

  state.view = view;
  document.querySelectorAll(".nav button").forEach(function (b) {
    b.classList.toggle("active", b.dataset.view === view);
  });
  document.querySelectorAll(".view").forEach(function (s) {
    s.classList.toggle("active", s.id === "view-" + view);
  });
  document.body.classList.toggle("view-graph", view === "graph");
  document.querySelectorAll("#sidePanel button[data-view]").forEach(function (b) {
    b.classList.toggle("active", b.dataset.view === view);
  });
  // Hand the graph its own teardown rather than relying on CSS alone: it owns a running
  // simulation and pointer state that must not keep working behind another tab.
  if (leavingGraph) hideGraph();
  syncUrl();
  loadActiveView(false);
}

export function loadActiveView(profileChanged) {
  // The daemon's own configuration is not profile-scoped, so it must load before the profile
  // guard below — a store with no profiles yet is exactly when an operator needs this page.
  if (state.view === "global-config") {
    loadGlobalConfig();
    return;
  }
  if (!state.profile) return;
  if (state.view === "memories") loadMemories(profileChanged);
  else if (state.view === "stats") loadStats();
  else if (state.view === "audit") loadAudit(profileChanged);
  else if (state.view === "graph") showGraph(state.profile, profileChanged);
  else if (state.view === "profile-config") loadProfileConfig(state.profile);
  // add + recall views are lazy (populated on submit).
}

export function syncUrl() {
  const url = new URL(location);
  if (state.profile) url.searchParams.set("profile", state.profile); else url.searchParams.delete("profile");
  url.searchParams.set("view", state.view);
  history.replaceState(null, "", url);
}

export function renderBanner(container, msg, isErr) {
  container.innerHTML = "";
  container.appendChild(el("div", "banner" + (isErr ? " err" : ""), msg));
}
