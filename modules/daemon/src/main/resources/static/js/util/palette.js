// Stable color per memory type, shared by the list chips, stats bars, and drawer.
const TYPE_COLORS = {
  fact: "#58a6ff", event: "#d29922", instruction: "#bc8cff", task: "#3fb950"
};

export function typeColor(t) { return TYPE_COLORS[t] || "#8b98a5"; }

// Matching 14%-alpha wash for chip backgrounds. The chip used to flood the type colour behind
// near-black text, which made a label the highest-contrast element in a list of a hundred rows.
export function typeTint(t) { return tint(typeColor(t), .14); }

/** `#rrggbb` → `rgba(r, g, b, alpha)`. */
export function tint(hex, alpha) {
  const h = String(hex).replace("#", "");
  const n = parseInt(h.length === 3 ? h.replace(/./g, "$&$&") : h, 16);
  if (isNaN(n)) return "transparent";
  return "rgba(" + ((n >> 16) & 255) + ", " + ((n >> 8) & 255) + ", " + (n & 255) + ", " + alpha + ")";
}

// ---- retrieval channels ----
// Keys are RetrievalChannelType.name().toLowerCase(), the wire form RecallDebug reports. Fixed
// rather than hash-allocated: the set is closed, and a channel keeping one colour across the
// diagnostics cards and the candidate provenance chips is the whole point.
const CHANNEL_COLORS = {
  fts_memory: "#58a6ff",
  exact_key: "#3fb950",
  fts_message: "#39c5cf",
  direct_vector: "#bc8cff",
  hyde_vector: "#f778ba",
  symbol_fts: "#7ee787",
  graph: "#d29922",
  code_graph: "#ff7b72"
};

export function channelColor(channel) { return CHANNEL_COLORS[channel] || "#8b98a5"; }

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
