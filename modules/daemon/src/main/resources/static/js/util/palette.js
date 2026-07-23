// Stable color per memory type, shared by the list chips, stats bars, and drawer.
const TYPE_COLORS = {
  fact: "#58a6ff", event: "#d29922", instruction: "#bc8cff", task: "#3fb950"
};

export function typeColor(t) { return TYPE_COLORS[t] || "#8b98a5"; }

// ---- entity types (graph) ----
// Unlike memory types, entity types are open-ended — the extraction model emits whatever fits, so
// a profile can hold "concept", "file", "tool", "state", "data source", … Colors are therefore
// allocated on first sight and cached, keyed by the type name itself rather than by discovery
// order, so a type keeps the same color across reloads and across the legend/canvas boundary.
const ENTITY_PALETTE = [
  "#58a6ff", "#3fb950", "#d29922", "#bc8cff", "#f778ba",
  "#39c5cf", "#ff7b72", "#a5d6ff", "#e3b341", "#7ee787"
];

const entityColors = new Map();

export function entityTypeColor(type) {
  const key = type || "unknown";
  if (!entityColors.has(key)) {
    entityColors.set(key, ENTITY_PALETTE[hash(key) % ENTITY_PALETTE.length]);
  }
  return entityColors.get(key);
}

// FNV-1a. Any stable string hash works; this one is short and has no collisions worth worrying
// about across the handful of types a profile actually holds.
function hash(s) {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return Math.abs(h);
}
