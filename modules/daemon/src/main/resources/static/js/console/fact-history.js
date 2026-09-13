import { $, el, api, apiFetch, icon } from "../util/dom.js";
import { fmtDate, isoInstant, localDateTimeValue } from "../util/format.js";
import { markdown } from "../util/markdown.js";
import { toast } from "./toast.js";
import { state } from "./state.js";

const SVG_NS = "http://www.w3.org/2000/svg";
const COLORS = ["#58a6ff", "#d29922", "#bc8cff", "#3fb950", "#f778ba", "#39c5cf"];
let anchorMemory = null;
let selectedRevisionId = null;
let requestSequence = 0;
let listScrollTop = 0;

export function hasFactHistory(memory) {
  if (!memory) return false;
  return memory.type === "fact" || memory.type === "instruction"
    || (memory.type === "event" && String(memory.topicKey || "").startsWith("trace:outcome:"));
}

export function openFactHistory(memory) {
  if (!hasFactHistory(memory)) return;
  anchorMemory = memory;
  selectedRevisionId = null;
  const main = document.querySelector("main");
  listScrollTop = main ? main.scrollTop : 0;
  $("drawer").classList.remove("open");
  $("drawerBackdrop").classList.remove("open");
  $("memoryListView").hidden = true;
  $("memoryHistoryView").hidden = false;
  if (main) main.scrollTop = 0;
  loadHistory(null, null, null);
}

export function closeFactHistory(reload) {
  requestSequence++;
  anchorMemory = null;
  selectedRevisionId = null;
  $("memoryHistoryView").hidden = true;
  $("memoryHistoryView").innerHTML = "";
  $("memoryListView").hidden = false;
  const main = document.querySelector("main");
  if (main) main.scrollTop = listScrollTop;
  if (reload) document.dispatchEvent(new CustomEvent("pieria:reload-memories"));
}

export function resetFactHistory() {
  if (!$("memoryHistoryView").hidden) closeFactHistory(false);
}

function loadHistory(asOf, knownAsOf, preferredRevision) {
  if (!anchorMemory) return;
  const seq = ++requestSequence;
  const host = $("memoryHistoryView");
  host.innerHTML = '<div class="panel"><span class="spinner"></span> Loading fact history…</div>';
  const params = new URLSearchParams();
  if (asOf) params.set("asOf", asOf);
  if (knownAsOf) params.set("knownAsOf", knownAsOf);
  const suffix = params.size ? "?" + params.toString() : "";
  apiFetch(api(state.profile, "/memories/" + encodeURIComponent(anchorMemory.id) + "/history" + suffix), {
    headers: { Accept: "application/json" }
  }).then(async function (response) {
    if (!response.ok) throw new Error(await responseError(response, "History failed"));
    return response.json();
  }).then(function (data) {
    if (seq !== requestSequence || !anchorMemory) return;
    selectedRevisionId = preferredRevision
      || (data.selectedRevision && data.selectedRevision.revisionId)
      || currentRevision(data.revisions || [])?.revisionId
      || (data.revisions || [])[0]?.revisionId
      || null;
    renderHistory(data);
  }).catch(function (error) {
    if (seq !== requestSequence) return;
    host.innerHTML = "";
    const back = el("button", "history-back", "Back to memories");
    back.addEventListener("click", function () { closeFactHistory(false); });
    host.appendChild(back);
    host.appendChild(el("div", "banner err", error.message));
  });
}

function renderHistory(data) {
  const host = $("memoryHistoryView");
  host.innerHTML = "";

  const head = el("header", "history-header");
  const back = el("button", "history-back");
  back.type = "button";
  back.appendChild(icon("chevronRight", 14));
  back.appendChild(el("span", null, "Memories"));
  back.addEventListener("click", function () { closeFactHistory(true); });
  head.appendChild(back);
  head.appendChild(el("span", "history-slash", "/"));
  const heading = el("div", "history-heading");
  heading.appendChild(el("h1", null, anchorMemory.topicKey || "Fact history"));
  heading.appendChild(el("p", null, "What was true, and what Pieria knew at the time"));
  head.appendChild(heading);
  host.appendChild(head);

  const controls = el("div", "panel history-controls");
  const asOf = dateControl("historyAsOf", "World time (asOf)", data.temporal.asOf);
  const knownAsOf = dateControl("historyKnownAsOf", "Knowledge cutoff (knownAsOf)", data.temporal.knownAsOf);
  controls.appendChild(asOf.wrap);
  controls.appendChild(knownAsOf.wrap);
  const actions = el("div", "history-control-actions");
  const apply = el("button", "primary", "Apply");
  apply.addEventListener("click", function () { applyTimes(asOf.input, knownAsOf.input); });
  const now = el("button", null, "Now");
  now.addEventListener("click", function () {
    const value = localDateTimeValue(new Date().toISOString());
    asOf.input.value = value;
    knownAsOf.input.value = value;
    applyTimes(asOf.input, knownAsOf.input);
  });
  actions.appendChild(apply);
  actions.appendChild(now);
  controls.appendChild(actions);
  const result = el("div", "history-result");
  result.appendChild(icon(data.selectedRevision ? "clock" : "activity", 16));
  result.appendChild(el("span", "history-result-label", "Selected"));
  result.appendChild(el("strong", null, data.selectedRevision ? data.selectedRevision.content : "No valid assertion"));
  controls.appendChild(result);
  host.appendChild(controls);

  const layout = el("div", "history-layout");
  const chartPanel = el("section", "panel history-chart-panel");
  const chartHead = el("div", "history-section-head");
  chartHead.appendChild(el("h2", "section", "Bi-temporal fact history"));
  chartHead.appendChild(el("span", "history-legend", "● inclusive start   ○ exclusive end   → open-ended"));
  chartPanel.appendChild(chartHead);
  chartPanel.appendChild(renderChart(data));
  layout.appendChild(chartPanel);
  const inspector = el("aside", "panel history-inspector");
  inspector.id = "historyInspector";
  layout.appendChild(inspector);
  host.appendChild(layout);

  const tablePanel = el("section", "panel history-table-panel");
  tablePanel.appendChild(el("h2", "section", "Revision history (" + (data.revisions || []).length + ")"));
  tablePanel.appendChild(renderRevisionTable(data));
  host.appendChild(tablePanel);
  updateSelection(data, selectedRevisionId);
}

function dateControl(id, label, value) {
  const wrap = el("label", "history-date-field");
  wrap.appendChild(el("span", null, label));
  const input = el("input");
  input.id = id;
  input.type = "datetime-local";
  input.step = "60";
  input.value = localDateTimeValue(value);
  wrap.appendChild(input);
  return { wrap: wrap, input: input };
}

function applyTimes(asOfInput, knownInput) {
  const world = isoInstant(asOfInput.value);
  const known = isoInstant(knownInput.value);
  if (!world || !known) {
    toast("Both history times are required", "err");
    return;
  }
  if (new Date(known).getTime() > Date.now()) {
    toast("Knowledge cutoff cannot be in the future", "err");
    return;
  }
  loadHistory(world, known, null);
}

function renderChart(data) {
  const container = el("div", "history-chart-scroll");
  const segments = data.segments || [];
  if (!segments.length) {
    container.appendChild(el("div", "banner", "No visible validity segments remain for this group."));
    return container;
  }
  const rows = [...new Set(segments.map(function (segment) { return segment.recordedFrom; }))]
    .sort(function (a, b) { return a.localeCompare(b); });
  const validTimes = [];
  segments.forEach(function (segment) {
    validTimes.push(new Date(segment.validFrom).getTime());
    if (segment.validTo) validTimes.push(new Date(segment.validTo).getTime());
  });
  const asOfMs = new Date(data.temporal.asOf).getTime();
  validTimes.push(asOfMs);
  let min = Math.min.apply(null, validTimes);
  let max = Math.max.apply(null, validTimes);
  const minimumSpan = 24 * 60 * 60 * 1000;
  if (max - min < minimumSpan) max = min + minimumSpan;
  if (segments.some(function (segment) { return !segment.validTo; })) max += Math.max(minimumSpan, (max - min) * .12);

  const left = 138, right = 28, top = 66, rowHeight = 54;
  const width = 980, height = top + rows.length * rowHeight + 24;
  const plotWidth = width - left - right;
  const x = function (millis) { return left + (millis - min) / (max - min) * plotWidth; };
  const svg = svgNode("svg", { viewBox: "0 0 " + width + " " + height, role: "img",
    "aria-label": "Bi-temporal validity chart. Effective time runs horizontally and knowledge snapshots run vertically." });
  svg.appendChild(svgNode("title", {}, "Bi-temporal fact history"));
  svg.appendChild(svgNode("text", { x: left + plotWidth / 2, y: 18, class: "history-axis-title" },
    "EFFECTIVE / WORLD TIME"));
  svg.appendChild(svgNode("text", { x: 18, y: top + rows.length * rowHeight / 2,
    class: "history-axis-title", transform: "rotate(-90 18 " + (top + rows.length * rowHeight / 2) + ")" },
    "KNOWLEDGE AVAILABLE"));

  for (let i = 0; i <= 5; i++) {
    const value = min + (max - min) * i / 5;
    const px = x(value);
    svg.appendChild(svgNode("line", { x1: px, y1: 28, x2: px, y2: height - 16, class: "history-grid-line" }));
    svg.appendChild(svgNode("text", { x: px, y: 45, class: "history-tick", "text-anchor": "middle" }, tickLabel(value, max - min)));
  }

  const revisions = new Map((data.revisions || []).map(function (revision) { return [revision.revisionId, revision]; }));
  const colorByMemory = new Map();
  rows.forEach(function (recordedFrom, rowIndex) {
    const y = top + rowIndex * rowHeight;
    const future = new Date(recordedFrom).getTime() > new Date(data.temporal.knownAsOf).getTime();
    const row = svgNode("g", { class: "history-chart-row" + (future ? " future" : "") });
    row.appendChild(svgNode("line", { x1: left, y1: y + rowHeight, x2: width - right, y2: y + rowHeight, class: "history-row-line" }));
    row.appendChild(svgNode("text", { x: left - 10, y: y + 30, class: "history-row-label", "text-anchor": "end" }, shortRecordedAt(recordedFrom)));
    segments.filter(function (segment) { return segment.recordedFrom === recordedFrom; }).forEach(function (segment) {
      const revision = revisions.get(segment.revisionId);
      const identity = revision ? revision.memoryId : segment.revisionId;
      if (!colorByMemory.has(identity)) colorByMemory.set(identity, COLORS[colorByMemory.size % COLORS.length]);
      const color = colorByMemory.get(identity);
      const x1 = x(new Date(segment.validFrom).getTime());
      const open = !segment.validTo;
      const x2 = open ? width - right - 16 : x(new Date(segment.validTo).getTime());
      const group = svgNode("g", { class: "history-segment", tabindex: "0", role: "button",
        "data-revision-id": segment.revisionId,
        "aria-label": (revision ? revision.content : "Assertion") + ", valid " + worldInterval(segment.validFrom, segment.validTo) });
      const rect = svgNode("rect", { x: x1, y: y + 16, width: Math.max(3, x2 - x1), height: 25,
        rx: 4, fill: color, opacity: future ? .38 : .78 });
      group.appendChild(rect);
      group.appendChild(svgNode("circle", { cx: x1, cy: y + 28.5, r: 5, fill: color }));
      if (open) {
        group.appendChild(svgNode("text", { x: x2 + 5, y: y + 33, class: "history-infinity" }, "→"));
      } else {
        group.appendChild(svgNode("circle", { cx: x2, cy: y + 28.5, r: 5, fill: "var(--panel)", stroke: color, "stroke-width": 2 }));
      }
      if (x2 - x1 > 48) group.appendChild(svgNode("text", { x: x1 + 11, y: y + 33,
        class: "history-bar-label" }, truncate(revision ? revision.content : segment.revisionId, Math.floor((x2 - x1) / 7))));
      const choose = function () { updateSelection(data, segment.revisionId); };
      group.addEventListener("click", choose);
      group.addEventListener("keydown", function (event) {
        if (event.key === "Enter" || event.key === " ") { event.preventDefault(); choose(); }
      });
      row.appendChild(group);
    });
    svg.appendChild(row);
  });

  const crossX = Math.max(left, Math.min(width - right, x(asOfMs)));
  svg.appendChild(svgNode("line", { x1: crossX, y1: 28, x2: crossX, y2: height - 16, class: "history-crosshair" }));
  svg.appendChild(svgNode("text", { x: crossX, y: 60, class: "history-crosshair-label", "text-anchor": "middle" }, shortDate(data.temporal.asOf)));
  const activeRow = rows.findIndex(function (recordedFrom) {
    const rowSegments = segments.filter(function (segment) { return segment.recordedFrom === recordedFrom; });
    const known = new Date(data.temporal.knownAsOf).getTime();
    return new Date(recordedFrom).getTime() <= known && rowSegments.some(function (segment) {
      return !segment.recordedTo || known < new Date(segment.recordedTo).getTime();
    });
  });
  if (activeRow >= 0) {
    const y = top + activeRow * rowHeight + 28.5;
    svg.appendChild(svgNode("line", { x1: left, y1: y, x2: width - right, y2: y, class: "history-crosshair" }));
  }
  container.appendChild(svg);
  return container;
}

function renderRevisionTable(data) {
  const wrap = el("div", "history-table-scroll");
  const table = el("table", "data history-table");
  const head = el("thead");
  const hr = el("tr");
  ["Revision", "Assertion", "Valid interval", "Recorded interval", "Source", "Status"].forEach(function (label) {
    hr.appendChild(el("th", null, label));
  });
  head.appendChild(hr);
  table.appendChild(head);
  const body = el("tbody");
  (data.revisions || []).forEach(function (revision) {
    const row = el("tr");
    row.tabIndex = 0;
    row.dataset.revisionId = revision.revisionId;
    row.appendChild(el("td", "mono", shortId(revision.revisionId)));
    row.appendChild(el("td", null, revision.content));
    row.appendChild(el("td", "num", worldInterval(revision.validFrom, revision.validTo)));
    row.appendChild(el("td", "num", knowledgeInterval(revision.recordedFrom, revision.recordedTo)));
    row.appendChild(el("td", "mono", revision.sourceOccurrence || "—"));
    const status = revision.invalidated ? "Invalidated" : revision.recordedTo ? "Corrected" : "Current";
    const cell = el("td");
    cell.appendChild(el("span", "history-status " + status.toLowerCase(), status));
    row.appendChild(cell);
    const choose = function () { updateSelection(data, revision.revisionId); };
    row.addEventListener("click", choose);
    row.addEventListener("keydown", function (event) {
      if (event.key === "Enter" || event.key === " ") { event.preventDefault(); choose(); }
    });
    body.appendChild(row);
  });
  table.appendChild(body);
  wrap.appendChild(table);
  return wrap;
}

function updateSelection(data, revisionId) {
  selectedRevisionId = revisionId;
  document.querySelectorAll(".history-segment").forEach(function (node) {
    node.classList.toggle("selected", node.dataset.revisionId === revisionId);
  });
  document.querySelectorAll(".history-table tbody tr").forEach(function (node) {
    node.classList.toggle("selected", node.dataset.revisionId === revisionId);
  });
  renderInspector($("historyInspector"), data,
    (data.revisions || []).find(function (revision) { return revision.revisionId === revisionId; }));
}

function renderInspector(host, data, revision) {
  host.innerHTML = "";
  host.appendChild(el("h2", "section", "Selected assertion"));
  if (!revision) {
    host.appendChild(el("div", "banner", "No assertion is valid at the selected times."));
    return;
  }
  host.appendChild(markdown(revision.content, { className: "history-inspector-content" }));
  const dl = el("dl", "history-inspector-kv");
  inspectorRow(dl, "Valid", worldInterval(revision.validFrom, revision.validTo));
  inspectorRow(dl, "Recorded", knowledgeInterval(revision.recordedFrom, revision.recordedTo));
  inspectorRow(dl, "Stated", fmtDate(revision.statedAt));
  inspectorRow(dl, "Source", revision.sourceOccurrence || "—");
  inspectorRow(dl, "Assertion", revision.assertionId);
  inspectorRow(dl, "Revision", revision.revisionId);
  if (revision.corrects) inspectorRow(dl, "Corrects", revision.corrects);
  host.appendChild(dl);
  const current = !revision.recordedTo;
  const actions = el("div", "history-inspector-actions");
  const correct = el("button", "primary", "Correct assertion");
  correct.disabled = !current;
  correct.title = current ? "Create a corrected revision" : "Only the current revision can be corrected";
  correct.addEventListener("click", function () { openCorrectionDialog(data, revision); });
  const invalidate = el("button", "danger", "Invalidate assertion");
  invalidate.disabled = !current || revision.invalidated;
  invalidate.addEventListener("click", function () { openInvalidationDialog(data, revision); });
  actions.appendChild(correct);
  actions.appendChild(invalidate);
  host.appendChild(actions);
}

function openCorrectionDialog(data, revision) {
  const dialog = revisionDialog("Correct assertion");
  const content = dialogField(dialog.form, "Content", "textarea", revision.content);
  const from = dialogField(dialog.form, "Valid from", "datetime-local", localDateTimeValue(revision.validFrom));
  const to = dialogField(dialog.form, "Valid to", "datetime-local", localDateTimeValue(revision.validTo));
  const open = el("label", "check revision-open-ended");
  const check = el("input");
  check.type = "checkbox";
  check.checked = !revision.validTo;
  open.appendChild(check);
  open.appendChild(el("span", null, "Open-ended"));
  dialog.form.appendChild(open);
  to.input.disabled = check.checked;
  check.addEventListener("change", function () { to.input.disabled = check.checked; });
  appendDialogActions(dialog, function () {
    const validFrom = isoInstant(from.input.value);
    const validTo = check.checked ? null : isoInstant(to.input.value);
    if (!content.input.value.trim()) return showDialogError(dialog, "Content is required.");
    if (!validFrom || (!check.checked && !validTo)) return showDialogError(dialog, "Valid times are required.");
    if (validTo && new Date(validFrom).getTime() >= new Date(validTo).getTime()) {
      return showDialogError(dialog, "Valid from must precede valid to.");
    }
    postRevision(data, revision, {
      assertionId: revision.assertionId,
      expectedRevisionId: revision.revisionId,
      content: content.input.value.trim(),
      validFrom: validFrom,
      validTo: validTo,
      openEnded: check.checked
    }, dialog);
  });
  document.body.appendChild(dialog.element);
  dialog.element.showModal();
}

function openInvalidationDialog(data, revision) {
  const dialog = revisionDialog("Invalidate assertion");
  dialog.form.appendChild(el("p", "revision-dialog-copy",
    "The assertion will stop applying at this world-time instant. Earlier knowledge snapshots retain the previous belief."));
  const cutoff = dialogField(dialog.form, "Invalidate at", "datetime-local", localDateTimeValue(data.temporal.asOf));
  appendDialogActions(dialog, function () {
    const invalidateAt = isoInstant(cutoff.input.value);
    if (!invalidateAt) return showDialogError(dialog, "An effective instant is required.");
    postRevision(data, revision, {
      assertionId: revision.assertionId,
      expectedRevisionId: revision.revisionId,
      invalidateAt: invalidateAt
    }, dialog);
  }, "Invalidate");
  document.body.appendChild(dialog.element);
  dialog.element.showModal();
}

function revisionDialog(title) {
  const element = el("dialog", "revision-dialog");
  const form = el("form");
  form.method = "dialog";
  const head = el("div", "revision-dialog-head");
  head.appendChild(el("h2", null, title));
  const close = el("button", "icon-only");
  close.type = "button";
  close.setAttribute("aria-label", "Close");
  close.appendChild(icon("close", 15));
  close.addEventListener("click", function () { element.close(); });
  head.appendChild(close);
  form.appendChild(head);
  const error = el("div", "revision-dialog-error");
  error.hidden = true;
  form.appendChild(error);
  element.appendChild(form);
  element.addEventListener("close", function () { element.remove(); });
  return { element: element, form: form, error: error };
}

function dialogField(form, label, type, value) {
  const wrap = el("label", "revision-field");
  wrap.appendChild(el("span", null, label));
  const input = el(type === "textarea" ? "textarea" : "input");
  if (type !== "textarea") { input.type = type; input.step = "60"; }
  input.value = value || "";
  wrap.appendChild(input);
  form.appendChild(wrap);
  return { wrap: wrap, input: input };
}

function appendDialogActions(dialog, submit, submitLabel) {
  const actions = el("div", "revision-dialog-actions");
  const cancel = el("button", null, "Cancel");
  cancel.type = "button";
  cancel.addEventListener("click", function () { dialog.element.close(); });
  const save = el("button", "primary", submitLabel || "Save correction");
  save.type = "button";
  save.addEventListener("click", submit);
  actions.appendChild(cancel);
  actions.appendChild(save);
  dialog.form.appendChild(actions);
  dialog.save = save;
}

function showDialogError(dialog, message) {
  dialog.error.hidden = false;
  dialog.error.textContent = message;
}

function postRevision(data, revision, body, dialog) {
  dialog.save.disabled = true;
  dialog.error.hidden = true;
  apiFetch(api(state.profile, "/memories/" + encodeURIComponent(revision.memoryId) + "/revisions"), {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body)
  }).then(async function (response) {
    if (!response.ok) {
      const error = new Error(await responseError(response, "Revision failed"));
      error.status = response.status;
      throw error;
    }
    return response.json();
  }).then(function (updated) {
    dialog.element.close();
    toast("Assertion revision stored", "ok");
    const asOf = isoInstant($("historyAsOf").value);
    const knownAsOf = isoInstant($("historyKnownAsOf").value);
    loadHistory(asOf, knownAsOf, updated.revisionId);
  }).catch(function (error) {
    if (error.status === 409) {
      dialog.element.close();
      toast("Assertion changed; history reloaded", "err");
      loadHistory(isoInstant($("historyAsOf").value), isoInstant($("historyKnownAsOf").value), null);
      return;
    }
    showDialogError(dialog, error.message);
    dialog.save.disabled = false;
  });
}

async function responseError(response, fallback) {
  try {
    const body = await response.json();
    return body.message || fallback + " (" + response.status + ")";
  } catch (_) {
    return fallback + " (" + response.status + ")";
  }
}

function currentRevision(revisions) {
  return revisions.slice().reverse().find(function (revision) { return !revision.recordedTo; });
}

function inspectorRow(dl, label, value) {
  dl.appendChild(el("dt", null, label));
  dl.appendChild(el("dd", label === "Assertion" || label === "Revision" || label === "Corrects" ? "mono" : null, value || "—"));
}

function worldInterval(from, to) {
  return fmtDate(from) + " – " + (to ? fmtDate(to) : "∞");
}

function knowledgeInterval(from, to) {
  return fmtDate(from) + " – " + (to ? fmtDate(to) : "Present");
}

function shortId(value) {
  const string = String(value || "");
  return string.length > 13 ? string.slice(0, 11) + "…" : string;
}

function shortDate(value) {
  const date = new Date(value);
  return isNaN(date) ? String(value || "") : date.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

function shortRecordedAt(value) {
  const date = new Date(value);
  return isNaN(date) ? String(value || "") : date.toLocaleString(undefined, {
    month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit"
  });
}

function tickLabel(value, span) {
  const date = new Date(value);
  const day = 24 * 60 * 60 * 1000;
  if (span > 730 * day) return date.toLocaleDateString(undefined, { year: "numeric" });
  if (span > 90 * day) return date.toLocaleDateString(undefined, { year: "numeric", month: "short" });
  return date.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

function truncate(value, limit) {
  const text = String(value || "");
  const bounded = Math.max(5, limit);
  return text.length > bounded ? text.slice(0, bounded - 1) + "…" : text;
}

function svgNode(tag, attrs, text) {
  const node = document.createElementNS(SVG_NS, tag);
  Object.keys(attrs || {}).forEach(function (name) { node.setAttribute(name, attrs[name]); });
  if (text != null) node.textContent = text;
  return node;
}
