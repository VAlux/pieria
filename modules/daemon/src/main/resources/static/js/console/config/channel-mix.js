// The eight retrieval channel weights RRF actually fuses, drawn as one bar.
//
// A weight means nothing on its own — only its share of the total decides what the channel
// contributes, so the bar is the value and the numbers are the detail. A weight of 0 is a
// documented disable, not a small number: the segment disappears and the legend greys out.
import { el } from "../../util/dom.js";

export const CHANNEL_COLORS = {
  "retrieval.weight-exact-key": "#58a6ff",
  "retrieval.weight-fts-memory": "#3fb950",
  "retrieval.weight-hyde-vector": "#bc8cff",
  "retrieval.weight-direct-vector": "#56d4dd",
  "retrieval.weight-fts-message": "#d29922",
  "retrieval.weight-graph": "#ff7b72",
  // The two code-graph fusion weights (RetrievalService.getWeightsForRetrievalChannels) — their
  // own fields stay plain number inputs in the code-graph section; only this legend/bar sees them.
  "retrieval.weight-symbol-fts": "#f778ba",
  "retrieval.weight-code-graph": "#ffa657"
};

export function renderChannelMix(container, weights, opts) {
  const options = opts || {};
  container.innerHTML = "";
  const wrap = el("div", "cfg-mix");

  const head = el("div");
  head.style.display = "flex";
  head.style.alignItems = "baseline";
  head.style.marginBottom = "9px";
  head.appendChild(el("span", "section", "Channel mix"));
  head.appendChild(el("span", "spacer"));

  let total = 0;
  weights.forEach(function (w) { total += Number(w.value) || 0; });
  head.appendChild(el("span", "mono num", "total " + total.toFixed(1)));
  wrap.appendChild(head);

  const bar = el("div", "cfg-mix-bar");
  const divisor = total > 0 ? total : 1;
  weights.forEach(function (w) {
    const value = Number(w.value) || 0;
    if (value <= 0) return;
    const segment = el("span");
    segment.style.width = ((value / divisor) * 100).toFixed(2) + "%";
    segment.style.background = CHANNEL_COLORS[w.key] || "var(--accent)";
    segment.title = w.key + " = " + value;
    bar.appendChild(segment);
  });
  wrap.appendChild(bar);

  const legend = el("div", "cfg-mix-legend");
  let anyDisabled = false;
  weights.forEach(function (w) {
    const value = Number(w.value) || 0;
    const disabled = value <= 0;
    if (disabled) anyDisabled = true;
    const button = el("button", disabled ? "off" : null);
    button.type = "button";
    if (options.focused === w.key) button.classList.add("active");
    const swatch = el("span", "cfg-mix-swatch");
    swatch.style.background = disabled ? "var(--border)" : (CHANNEL_COLORS[w.key] || "var(--accent)");
    button.appendChild(swatch);
    button.appendChild(el("span", "mono", w.label));
    button.appendChild(el("span", "mono num", value.toFixed(1)));
    button.appendChild(el("span", "num",
      total > 0 ? Math.round((value / divisor) * 100) + "%" : "0%"));
    if (options.onFocus) {
      button.addEventListener("click", function () { options.onFocus(w.key); });
    }
    legend.appendChild(button);
  });
  wrap.appendChild(legend);

  if (anyDisabled && options.disabledNote) {
    wrap.appendChild(el("div", "cfg-hint", options.disabledNote));
  }

  container.appendChild(wrap);
  return { total: total, disabled: anyDisabled };
}
