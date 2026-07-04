// Shared, mutable state for the force-directed memory graph. All graph modules import `g`
// and mutate it in place, so the whole view shares one model / view-transform / interaction state.
export const g = {
  nodes: [], links: [], nodeById: {},
  view: { x: 0, y: 0, k: 1 },     // pan (x, y) + zoom (k)
  alpha: 0, running: false,       // simulation energy + raf-loop guard
  hoverNode: null, hoverLink: null, dragNode: null,
  panning: false, panStart: null, moved: false,
  searchTerm: "",
  typeColors: {}, typeOrder: [],  // stable color allocation per entity type
  canvas: null, ctx: null, dpr: 1,
  loadedProfile: null             // which profile the current layout belongs to
};

// Stable color per entity type, allocated on first sight.
const PALETTE = [
  "#58a6ff", "#3fb950", "#d29922", "#bc8cff", "#f778ba",
  "#39c5cf", "#ff7b72", "#a5d6ff", "#e3b341", "#7ee787"
];

export function colorFor(type) {
  const key = type || "unknown";
  if (!(key in g.typeColors)) {
    g.typeColors[key] = PALETTE[g.typeOrder.length % PALETTE.length];
    g.typeOrder.push(key);
  }
  return g.typeColors[key];
}

export function resetColors() {
  g.typeColors = {};
  g.typeOrder = [];
}

// Screen (canvas) coordinates → world coordinates under the current pan/zoom.
export function toWorld(sx, sy) {
  return { x: (sx - g.view.x) / g.view.k, y: (sy - g.view.y) / g.view.k };
}
