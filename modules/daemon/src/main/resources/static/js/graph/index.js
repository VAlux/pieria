import { $ } from "../util/dom.js";
import { g } from "./state.js";
import { resize, draw } from "./render.js";
import { bindInteraction, fit } from "./interaction.js";
import { load } from "./data.js";

let initialized = false;
let graphResizeObserver = null;

// One-time setup: bind the canvas, interaction handlers, resize, and toolbar controls.
function initGraph() {
  if (initialized) return;
  initialized = true;

  g.canvas = $("graphCanvas");
  g.ctx = g.canvas.getContext("2d");
  g.dpr = Math.max(1, window.devicePixelRatio || 1);

  bindInteraction();
  window.addEventListener("resize", function () {
    if (document.body.classList.contains("view-graph")) { resize(); draw(); }
  });
  if (window.ResizeObserver) {
    graphResizeObserver = new ResizeObserver(function () {
      if (document.body.classList.contains("view-graph")) { resize(); draw(); }
    });
    graphResizeObserver.observe($("view-graph"));
  }

  $("graphSearch").addEventListener("input", function () {
    g.searchTerm = $("graphSearch").value.trim().toLowerCase();
    draw();
  });
  $("graphReload").addEventListener("click", function () { if (g.loadedProfile) load(g.loadedProfile); });
  $("graphFit").addEventListener("click", fit);
}

// Called by the router whenever the graph tab becomes active. Loads the profile's graph on
// first view / profile change; otherwise just re-fits the existing layout to the (now-visible) canvas.
export function showGraph(profile, profileChanged) {
  initGraph();
  resize();
  if (profileChanged || g.loadedProfile !== profile || !g.nodes.length) {
    load(profile);
  } else {
    fit();
  }
}
