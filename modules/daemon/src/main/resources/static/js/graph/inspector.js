// The docked inspector: what one entity is, what it connects to, and which memories put it there.
//
// This is the answer to "clicking a node does nothing useful". It stays open while exploring
// rather than sliding over the canvas, because the whole loop is click a node -> read its
// relations -> follow one -> repeat, and a panel that covers the graph fights that.
import { $, el, escapeHtml } from "../util/dom.js";
import { entityTypeColor, typeColor } from "../util/palette.js";
import { relTime } from "../util/format.js";
import { openDrawer } from "../console/drawer.js";
import { model } from "./model.js";
import { fetchEntity } from "./api.js";

let handlers = { onFocus: function () {}, onExpand: function () {} };
let pending = null;   // id of the entity currently being fetched, to drop stale responses

export function init(callbacks) {
  handlers = Object.assign(handlers, callbacks || {});
  $("graphInspectorClose").addEventListener("click", clear);
}

// Deselect. The panel stays docked — it is part of the layout, not a popover — so it falls back to
// telling the user what to do rather than going blank.
export function clear() {
  pending = null;
  model.selectedId = null;
  $("graphInspector").classList.remove("is-open");
  $("graphInspectorTitle").textContent = "";
  $("graphInspectorChip").style.display = "none";
  $("graphInspectorClose").style.display = "none";

  const body = $("graphInspectorBody");
  body.innerHTML = "";
  body.appendChild(el("div", "graph-inspector-status",
    "Click a node to inspect it. Double-click to pull in its neighbours."));
}

export function show(entityId) {
  model.selectedId = entityId;
  pending = entityId;

  const panel = $("graphInspector");
  panel.classList.add("is-open");
  $("graphInspectorClose").style.display = "";
  const body = $("graphInspectorBody");
  body.innerHTML = "";
  body.appendChild(el("div", "graph-inspector-status", "Loading…"));

  fetchEntity(model.profile, entityId)
    .then(function (data) {
      if (pending !== entityId) return;   // the user clicked something else meanwhile
      render(data);
    })
    .catch(function (e) {
      if (pending !== entityId) return;
      body.innerHTML = "";
      body.appendChild(el("div", "graph-inspector-status err", e.message));
    });
}

function render(data) {
  const entity = data.entity || {};
  const relations = data.relations || [];
  const memories = data.memories || [];

  $("graphInspectorTitle").textContent = entity.name || "";
  const chip = $("graphInspectorChip");
  chip.textContent = entity.type || "unknown";
  chip.style.background = entityTypeColor(entity.type);
  chip.style.display = "";

  const body = $("graphInspectorBody");
  body.innerHTML = "";

  body.appendChild(summary(entity, relations));
  body.appendChild(relationSection(entity, relations));
  body.appendChild(memorySection(memories));
}

function summary(entity, relations) {
  const box = el("div", "graph-inspector-summary");
  const drawn = drawnCount(entity.id);
  const parts = [
    entity.degree + (entity.degree === 1 ? " relation" : " relations")
  ];
  if (drawn < entity.degree) parts.push(drawn + " drawn");
  if (relations.length < entity.degree) parts.push("showing first " + relations.length);
  box.appendChild(el("div", "graph-inspector-counts", parts.join(" · ")));

  const actions = el("div", "graph-inspector-actions");

  const expand = el("button", null, drawn < entity.degree ? "Expand here" : "Re-expand");
  expand.title = "Pull this entity's neighbours into the current view";
  expand.addEventListener("click", function () { handlers.onExpand(entity.id); });
  actions.appendChild(expand);

  const focus = el("button", null, "Focus");
  focus.title = "Start a fresh view centred on this entity";
  focus.addEventListener("click", function () { handlers.onFocus(entity.id); });
  actions.appendChild(focus);

  box.appendChild(actions);
  return box;
}

function relationSection(entity, relations) {
  const section = el("section", "graph-inspector-section");
  section.appendChild(el("h4", null, "Relations"));

  if (!relations.length) {
    section.appendChild(el("div", "graph-inspector-status", "No active relations."));
    return section;
  }

  // Group by label so a hub with forty "defines" edges reads as one heading, not forty rows of
  // near-identical text.
  const groups = new Map();
  relations.forEach(function (r) {
    const key = (r.direction === "out" ? "→ " : "← ") + (r.relation || "—");
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(r);
  });

  groups.forEach(function (rows, label) {
    const group = el("div", "graph-relation-group");
    const heading = el("div", "graph-relation-label");
    heading.innerHTML = escapeHtml(label) + ' <span class="muted">' + rows.length + "</span>";
    group.appendChild(heading);

    rows.forEach(function (r) {
      const row = el("button", "graph-relation-row");
      row.type = "button";
      row.title = "Focus " + r.otherName;
      const swatch = el("span", "swatch");
      swatch.style.background = entityTypeColor(r.otherType);
      row.appendChild(swatch);
      row.appendChild(el("span", "graph-relation-name", r.otherName));
      row.appendChild(el("span", "graph-relation-type", r.otherType || "unknown"));
      row.addEventListener("click", function () { handlers.onFocus(r.otherId); });
      group.appendChild(row);
    });

    section.appendChild(group);
  });

  return section;
}

function memorySection(memories) {
  const section = el("section", "graph-inspector-section");
  section.appendChild(el("h4", null, "Memories"));

  if (!memories.length) {
    section.appendChild(el("div", "graph-inspector-status", "No memories reference this entity."));
    return section;
  }

  memories.forEach(function (m) {
    const row = el("div", "graph-memory-row");
    row.title = "Open memory details";
    const chip = el("span", "chip", m.type);
    chip.style.background = typeColor(m.type);
    row.appendChild(chip);

    const bodyText = el("div", "graph-memory-body");
    bodyText.appendChild(el("div", "graph-memory-content", m.content || ""));
    bodyText.appendChild(el("div", "graph-memory-meta", relTime(m.createdAt)));
    row.appendChild(bodyText);

    // The payload is the same MemoryResponse shape the Memories tab uses, so the existing drawer
    // takes it without translation.
    row.addEventListener("click", function () { openDrawer(m); });
    section.appendChild(row);
  });

  return section;
}

function drawnCount(id) {
  let count = 0;
  model.links.forEach(function (l) {
    if (l.source.id === id || l.target.id === id) count++;
  });
  return count;
}
