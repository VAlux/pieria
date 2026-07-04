import { $, el, api, addRow, escapeHtml } from "../util/dom.js";
import { typeColor } from "../util/palette.js";
import { fmtDate, fmtInt } from "../util/format.js";
import { state } from "./state.js";
import { renderBanner } from "./router.js";

export function loadStats() {
  const body = $("statsBody");
  renderBanner(body, "Loading statistics…");
  fetch(api(state.profile, "/stats"), { headers: { Accept: "application/json" } })
    .then(function (r) { if (!r.ok) throw new Error("Request failed (" + r.status + ")."); return r.json(); })
    .then(function (s) { renderStats(s); })
    .catch(function (e) { renderBanner(body, e.message, true); });
}

function renderStats(s) {
  const body = $("statsBody");
  body.innerHTML = "";

  // headline tiles
  const tiles = el("div", "tiles");
  tiles.appendChild(tile(fmtInt(s.totalActive), "Active memories"));
  tiles.appendChild(tile(fmtInt(s.superseded), "Superseded"));
  tiles.appendChild(tile(fmtInt(s.sessions), "Sessions"));
  if (s.vectorizationBacklog != null) tiles.appendChild(tile(fmtInt(s.vectorizationBacklog), "Vector backlog"));
  body.appendChild(tiles);

  const grid = el("div", "stat-grid");

  // by-type bars
  const byType = s.byType || {};
  const typePanel = el("div", "panel");
  typePanel.appendChild(el("h2", "section", "By type"));
  let maxVal = 0;
  Object.keys(byType).forEach(function (k) { maxVal = Math.max(maxVal, byType[k]); });
  const order = ["fact", "event", "instruction", "task"];
  const keys = Object.keys(byType).sort(function (a, b) {
    const ia = order.indexOf(a), ib = order.indexOf(b);
    return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
  });
  if (!keys.length) typePanel.appendChild(el("p", "muted", "No memories."));
  keys.forEach(function (k) {
    const row = el("div", "bar-row");
    row.appendChild(el("span", "name", k));
    const track = el("div", "bar-track");
    const fill = el("div", "bar-fill");
    fill.style.width = (maxVal ? (byType[k] / maxVal * 100) : 0) + "%";
    fill.style.background = typeColor(k);
    track.appendChild(fill);
    row.appendChild(track);
    row.appendChild(el("span", "cnt", fmtInt(byType[k])));
    typePanel.appendChild(row);
  });
  // date range
  const range = el("p", "muted small");
  range.style.marginTop = "12px";
  range.textContent = "Range: " + fmtDate(s.firstMemoryAt) + "  →  " + fmtDate(s.lastMemoryAt)
    + "   ·   profile created " + fmtDate(s.createdAt);
  typePanel.appendChild(range);
  grid.appendChild(typePanel);

  // impact
  const imp = s.impact || {};
  const impPanel = el("div", "panel");
  impPanel.appendChild(el("h2", "section", "Impact"));
  const dl = el("dl", "rows");
  addRow(dl, "Recalls served", fmtInt(imp.recalls));
  addRow(dl, "Tokens saved (evidence)", fmtInt(imp.tokensSavedEvidence));
  addRow(dl, "Tokens saved (naive)", fmtInt(imp.tokensSavedNaive));
  addRow(dl, "Tokens ingested", fmtInt(imp.tokensIngested));
  addRow(dl, "Tokens stored", fmtInt(imp.tokensStored));
  if (imp.contextWindowTokens) {
    addRow(dl, "≈ context windows saved", (imp.tokensSavedEvidence / imp.contextWindowTokens).toFixed(1)
      + " × " + fmtInt(imp.contextWindowTokens));
  }
  if (imp.pricePerMillionTokens > 0) {
    addRow(dl, "≈ cost saved", "$" + (imp.tokensSavedEvidence / 1e6 * imp.pricePerMillionTokens).toFixed(2));
  }
  impPanel.appendChild(dl);
  grid.appendChild(impPanel);
  body.appendChild(grid);

  // spend
  if (s.spend && s.spend.tiers && s.spend.tiers.length) {
    const sp = s.spend;
    const spendPanel = el("div", "panel");
    spendPanel.style.marginTop = "16px";
    spendPanel.appendChild(el("h2", "section", "Inference spend"));
    const table = el("table", "data");
    const showCost = !!sp.costAvailable;
    const thead = "<tr><th>Tier</th><th>Calls</th><th>Prompt</th><th>Completion</th>" + (showCost ? "<th>Cost</th>" : "") + "</tr>";
    const rowsHtml = sp.tiers.map(function (t) {
      return "<tr><td>" + escapeHtml(t.tier) + "</td><td>" + fmtInt(t.calls) + "</td><td>"
        + fmtInt(t.promptTokens) + "</td><td>" + fmtInt(t.completionTokens) + "</td>"
        + (showCost ? "<td>$" + t.costUsd.toFixed(2) + "</td>" : "") + "</tr>";
    }).join("");
    const totalRow = "<tr><td><b>Total</b></td><td></td><td><b>" + fmtInt(sp.totalPromptTokens)
      + "</b></td><td><b>" + fmtInt(sp.totalCompletionTokens) + "</b></td>"
      + (showCost ? "<td><b>$" + sp.totalCostUsd.toFixed(2) + "</b></td>" : "") + "</tr>";
    table.innerHTML = thead + rowsHtml + totalRow;
    spendPanel.appendChild(table);
    body.appendChild(spendPanel);
  }
}

function tile(num, lbl) {
  const t = el("div", "tile");
  t.appendChild(el("div", "num", num));
  t.appendChild(el("div", "lbl", lbl));
  return t;
}
