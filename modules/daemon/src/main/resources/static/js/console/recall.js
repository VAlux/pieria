import { $, el, api, apiFetch, icon } from "../util/dom.js";
import { channelColor, tint } from "../util/palette.js";
import { state } from "./state.js";
import { renderBanner } from "./router.js";
import { memoryRow } from "./memories.js";

// The channel result cap. A channel that returns exactly this many had more to give, so the hit
// count is reporting the limit rather than a distribution — worth marking.
const CHANNEL_LIMIT = 10;

export function submitRecall() {
  const query = $("recallQuery").value.trim();
  if (!query) return;
  const limit = parseInt($("recallLimit").value, 10) || 10;
  const mode = $("recallMode").value;
  const out = $("recallResult");
  const btn = $("recallBtn");
  btn.disabled = true;
  const loading = mode === "synthesized" ? "Recalling… this can take a while." : "Recalling…";
  out.innerHTML = '<div class="panel"><span class="spinner"></span> ' + loading + "</div>";
  // Wall clock is measured here because RecallDebug itemises only the channels; the model stages
  // (query analysis, HyDE, synthesis) are the bulk of the time and are not broken out server-side.
  const startedAt = performance.now();
  apiFetch(api(state.profile, "/recall"), {
    method: "POST", headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ query: query, limit: limit, mode: mode, debug: true })
  })
    .then(function (r) {
      if (r.status === 204) return null;
      if (!r.ok) throw new Error("Recall failed (" + r.status + ")");
      return r.json();
    })
    .then(function (data) {
      out.innerHTML = "";
      if (!data) { renderBanner(out, "No relevant memories found."); return; }
      renderRecall(out, data, (performance.now() - startedAt) / 1000);
    })
    .catch(function (e) { renderBanner(out, e.message, true); })
    .finally(function () { btn.disabled = false; });
}

function renderRecall(out, data, elapsedSec) {
  const mems = data.memories || [];

  if (data.answer) {
    const panel = el("div", "panel");
    panel.appendChild(el("h2", "section", "Answer"));
    panel.appendChild(el("div", "answer", data.answer));
    if (data.debug) panel.appendChild(explainToggle(data.debug, mems, elapsedSec));
    out.appendChild(panel);
  }

  const wrap = el("div");
  wrap.style.marginTop = "16px";
  wrap.appendChild(el("h2", "section", "Supporting memories (" + mems.length + ")"));
  const listEl = el("div", "mem-list");
  if (!mems.length) listEl.appendChild(el("div", "banner", "None."));
  mems.forEach(function (m) { listEl.appendChild(memoryRow(m, false)); });
  wrap.appendChild(listEl);
  out.appendChild(wrap);

  // Modes below SYNTHESIZED return a null answer, so the toggle has no answer panel to hang off.
  if (data.debug && !data.answer) out.appendChild(explainToggle(data.debug, mems, elapsedSec));
}

/** The disclosure plus the panel it reveals. */
function explainToggle(debug, mems, elapsedSec) {
  const frag = document.createDocumentFragment();
  const channels = debug.channels || [];
  const answered = channels.filter(function (c) { return !c.failed && c.hits > 0; }).length;

  const toggle = el("button", "explain-toggle");
  toggle.type = "button";
  toggle.setAttribute("aria-expanded", "false");
  toggle.appendChild(icon("chevronRight", 14));
  toggle.appendChild(el("span", "explain-summary",
    mems.length + " memories · " + answered + " of " + channels.length + " channels · "
    + elapsedSec.toFixed(2) + "s"));
  toggle.appendChild(el("span", "spacer"));
  const action = el("span", "explain-action", "Why this answer?");
  toggle.appendChild(action);

  const panel = explainPanel(debug, mems, elapsedSec);
  panel.hidden = true;
  toggle.setAttribute("aria-controls", "explainPanel");
  panel.id = "explainPanel";

  toggle.addEventListener("click", function () {
    const open = panel.hidden;
    panel.hidden = !open;
    toggle.setAttribute("aria-expanded", open ? "true" : "false");
    action.textContent = open ? "Hide retrieval detail" : "Why this answer?";
  });

  frag.appendChild(toggle);
  frag.appendChild(panel);
  return frag;
}

function explainPanel(debug, mems, elapsedSec) {
  const host = el("div", "explain");
  const channels = debug.channels || [];

  host.appendChild(stagePanel(channels, elapsedSec));
  host.appendChild(channelPanel(channels));
  host.appendChild(candidatePanel(debug.candidates || [], mems));
  host.appendChild(temporalPanel(debug.temporalFacts || []));
  return host;
}

// Channels run in parallel, so their contribution to wall clock is the slowest one, not the sum.
function stagePanel(channels, elapsedSec) {
  const panel = el("div", "panel");
  panel.appendChild(el("h2", "section", "Where the time went"));

  let slowest = 0;
  channels.forEach(function (c) { slowest = Math.max(slowest, c.latencyMs || 0); });
  const channelSec = slowest / 1000;
  const restSec = Math.max(0, elapsedSec - channelSec);
  const channelPct = elapsedSec > 0 ? (channelSec / elapsedSec * 100) : 0;

  const total = el("div", "stage-total");
  total.appendChild(el("span", "n", elapsedSec.toFixed(2)));
  total.appendChild(el("span", "u", "seconds end to end"));
  panel.appendChild(total);

  const bar = el("div", "stage-bar");
  const a = el("span", "channels");
  a.style.width = channelPct + "%";
  const b = el("span", "rest");
  b.style.width = (100 - channelPct) + "%";
  bar.appendChild(a);
  bar.appendChild(b);
  panel.appendChild(bar);

  const legend = el("div", "stage-legend");
  legend.appendChild(legendItem("var(--accent)", "channels (parallel)", channelSec.toFixed(2) + "s"));
  legend.appendChild(legendItem("var(--border)", "query analysis + HyDE + synthesis", restSec.toFixed(2) + "s"));
  panel.appendChild(legend);

  panel.appendChild(el("p", "explain-note",
    "RecallDebug itemises only the channels, so the remainder is inferred from this request's own "
    + "timing rather than reported by the daemon."));
  return panel;
}

function legendItem(color, label, value) {
  const item = el("div");
  const swatch = el("span", "stage-swatch");
  swatch.style.background = color;
  item.appendChild(swatch);
  item.appendChild(el("span", null, label));
  item.appendChild(el("span", "v", value));
  return item;
}

function channelPanel(channels) {
  const panel = el("div", "panel");
  panel.appendChild(el("h2", "section", "Channels"));
  panel.appendChild(el("p", "explain-note",
    "Wave 1 fans out from the query; graph and code_graph run second, seeded from wave-1 hits. "
    + "“cap” marks a channel that hit the result limit and had more to give. A failed channel "
    + "reports the configured timeout, not its real elapsed time."));

  const grid = el("div", "channel-grid");
  channels.forEach(function (c) {
    // A channel that reports no hits AND no elapsed time never ran: EVIDENCE mode skips the
    // model analysis, so hyde_vector is absent rather than unproductive. That is a different
    // fact from exact_key running and matching nothing, and the card should not conflate them.
    const skipped = !c.failed && !c.hits && !c.latencyMs;
    const empty = !c.failed && !c.hits && !skipped;
    const card = el("div", "channel-card" + (c.failed ? " failed" : (empty || skipped) ? " empty" : ""));

    const head = el("div", "channel-head");
    const dot = el("span", "channel-dot");
    dot.style.background = c.failed ? "var(--danger)" : empty ? "#4d5763" : channelColor(c.channel);
    head.appendChild(dot);
    head.appendChild(el("span", "channel-name", c.channel));
    card.appendChild(head);

    const stat = el("div", "channel-stat");
    stat.appendChild(el("span", "channel-hits", c.failed || skipped ? "—" : String(c.hits)));
    stat.appendChild(el("span", "channel-hits-label",
      c.failed ? "timed out"
        : skipped ? "not run in this mode"
          : c.hits >= CHANNEL_LIMIT ? "hits · cap" : "hits"));
    const lat = el("span", "channel-latency" + (c.latencyMs >= 500 ? " slow" : ""),
      skipped ? "" : Number(c.latencyMs || 0).toLocaleString() + " ms");
    stat.appendChild(lat);
    card.appendChild(stat);

    grid.appendChild(card);
  });
  panel.appendChild(grid);
  return panel;
}

function candidatePanel(candidates, mems) {
  const panel = el("div", "panel");
  panel.appendChild(el("h2", "section", "Fused candidates — RRF rank order"));
  panel.appendChild(el("p", "explain-note",
    "Channels in contribution order, strongest first. The constant “rrf:” prefix every source "
    + "carries is dropped."));

  candidates.forEach(function (c, i) {
    const row = el("div", "cand-row");
    row.appendChild(el("span", "cand-rank", String(i + 1)));

    const body = el("div");
    const memory = mems.find(function (m) { return m.id === c.id; });
    body.appendChild(el("div", "cand-content", memory ? memory.content : "(not in the returned set)"));
    body.appendChild(el("div", "cand-id", c.id));
    row.appendChild(body);

    row.appendChild(el("span", "cand-score", Number(c.score).toFixed(5)));

    const sources = el("div", "cand-sources");
    sourceChannels(c.source).forEach(function (name) {
      const chip = el("span", "cand-source", name);
      chip.style.color = channelColor(name);
      chip.style.background = tint(channelColor(name), .14);
      sources.appendChild(chip);
    });
    row.appendChild(sources);
    panel.appendChild(row);
  });
  return panel;
}

/** `"rrf:hyde_vector+direct_vector"` → `["hyde_vector", "direct_vector"]`. */
function sourceChannels(source) {
  return String(source || "").replace(/^rrf:/, "").split("+").filter(Boolean);
}

function temporalPanel(facts) {
  const panel = el("div", "panel");
  panel.appendChild(el("h2", "section", "Temporal facts injected into synthesis"));
  if (!facts.length) {
    const empty = el("div", "explain-empty");
    empty.appendChild(icon("clock", 15));
    empty.appendChild(el("span", null,
      "None — nothing in the retrieved set carried a resolvable date reference."));
    panel.appendChild(empty);
  } else {
    facts.forEach(function (f) { panel.appendChild(el("div", "temporal-fact", f)); });
  }
  panel.appendChild(el("p", "explain-note",
    "Computed in Java from each memory's stated_at / occurred_at and handed to the model as fixed "
    + "strings — the model never does the arithmetic. Empty is the common case."));
  return panel;
}
