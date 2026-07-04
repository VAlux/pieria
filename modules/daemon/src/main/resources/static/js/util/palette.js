// Stable color per memory type, shared by the list chips, stats bars, and drawer.
const TYPE_COLORS = {
  fact: "#58a6ff", event: "#d29922", instruction: "#bc8cff", task: "#3fb950"
};

export function typeColor(t) { return TYPE_COLORS[t] || "#8b98a5"; }
