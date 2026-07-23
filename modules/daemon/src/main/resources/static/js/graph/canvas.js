// Canvas rendering, pan/zoom, and pointer interaction.
//
// Every listener is bound to the canvas element itself. The one exception is drag continuation,
// which needs pointer events outside the canvas bounds and is handled with setPointerCapture — so
// it is still routed through the canvas and still dies with the pointer. Nothing is bound to
// `window`, which is what used to let this view hit-test (and pop a tooltip) while the user was
// looking at a completely different console tab.
import { $, el, escapeHtml } from "../util/dom.js";
import { entityTypeColor } from "../util/palette.js";
import { model, node as nodeById } from "./model.js";
import { radius, pin, unpin, start } from "./layout.js";

// Distance the pointer may travel between down and up and still count as a click, not a drag.
const CLICK_SLOP = 4;

const view = { x: 0, y: 0, k: 1 };

let canvas = null;
let ctx = null;
let dpr = 1;
let hoverCard = null;
let handlers = { onSelect: function () {}, onExpand: function () {} };

let drag = null;      // { node, pointerId } while dragging a node
let pan = null;       // { startX, startY, originX, originY } while panning
let moved = false;

export function init(canvasEl, hoverCardEl, callbacks) {
  canvas = canvasEl;
  ctx = canvas.getContext("2d");
  hoverCard = hoverCardEl;
  handlers = Object.assign(handlers, callbacks || {});
  bind();
  resize();
}

export function resize() {
  if (!canvas) return;
  dpr = Math.max(1, window.devicePixelRatio || 1);
  canvas.width = Math.floor(canvas.clientWidth * dpr);
  canvas.height = Math.floor(canvas.clientHeight * dpr);
}

// Reset transient interaction state. Called when the tab is left, so nothing survives to reappear
// over an unrelated view.
export function clearInteraction() {
  drag = null;
  pan = null;
  model.hoverId = null;
  hideHoverCard();
  if (canvas) canvas.style.cursor = "grab";
}

export function draw() {
  if (!ctx || !canvas) return;
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;

  recomputeDrawnDegrees();

  ctx.save();
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  ctx.translate(view.x, view.y);
  ctx.scale(view.k, view.k);

  drawLinks();
  drawNodes();

  ctx.restore();
}

function drawLinks() {
  const hovered = model.hoverId;
  ctx.lineWidth = 1 / view.k;
  model.links.forEach(function (l) {
    if (l.source.x === undefined || l.target.x === undefined) return;
    const hot = hovered && (l.source.id === hovered || l.target.id === hovered);
    ctx.strokeStyle = hot ? "rgba(230,237,243,0.75)" : "rgba(139,152,165,0.25)";
    ctx.beginPath();
    ctx.moveTo(l.source.x, l.source.y);
    ctx.lineTo(l.target.x, l.target.y);
    ctx.stroke();
    drawArrow(l, hot);
  });

  // Relation labels only for the hovered node's own edges — drawing every label turns any
  // interesting graph into unreadable soup.
  if (!hovered || view.k < 0.5) return;
  ctx.font = (10 / view.k) + "px -apple-system, system-ui, sans-serif";
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  model.links.forEach(function (l) {
    if (l.source.id !== hovered && l.target.id !== hovered) return;
    if (l.source.x === undefined || l.target.x === undefined) return;
    const mx = (l.source.x + l.target.x) / 2;
    const my = (l.source.y + l.target.y) / 2;
    const label = l.relation || "";
    if (!label) return;
    const pad = 4 / view.k;
    const width = ctx.measureText(label).width + pad * 2;
    ctx.fillStyle = "rgba(22,27,34,0.92)";
    ctx.fillRect(mx - width / 2, my - 7 / view.k, width, 14 / view.k);
    ctx.fillStyle = "#58a6ff";
    ctx.fillText(label, mx, my);
  });
}

function drawArrow(link, hot) {
  const s = link.source;
  const t = link.target;
  const dx = t.x - s.x;
  const dy = t.y - s.y;
  const d = Math.sqrt(dx * dx + dy * dy) || 1;
  const ux = dx / d;
  const uy = dy / d;
  const tipX = t.x - ux * radius(t);
  const tipY = t.y - uy * radius(t);
  const size = 5 / view.k;
  const angle = Math.atan2(uy, ux);
  ctx.fillStyle = hot ? "rgba(230,237,243,0.85)" : "rgba(139,152,165,0.38)";
  ctx.beginPath();
  ctx.moveTo(tipX, tipY);
  ctx.lineTo(tipX - size * Math.cos(angle - 0.4), tipY - size * Math.sin(angle - 0.4));
  ctx.lineTo(tipX - size * Math.cos(angle + 0.4), tipY - size * Math.sin(angle + 0.4));
  ctx.closePath();
  ctx.fill();
}

function drawNodes() {
  model.nodes.forEach(function (n) {
    if (n.x === undefined) return;
    const r = radius(n);
    const selected = n.id === model.selectedId;
    const focused = n.id === model.focusId;
    const hovered = n.id === model.hoverId;

    ctx.beginPath();
    ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
    ctx.fillStyle = entityTypeColor(n.type);
    ctx.fill();

    if (selected || focused || hovered) {
      ctx.lineWidth = (selected ? 2.5 : 1.5) / view.k;
      ctx.strokeStyle = selected ? "#e6edf3" : focused ? "#58a6ff" : "rgba(230,237,243,0.6)";
      ctx.stroke();
    }

    // A node with more edges than are drawn has more to show: ring it so "expandable" is visible
    // without hovering.
    if (drawnDegree(n) < n.degree) {
      ctx.beginPath();
      ctx.arc(n.x, n.y, r + 3 / view.k, 0, Math.PI * 2);
      ctx.lineWidth = 1 / view.k;
      ctx.strokeStyle = "rgba(139,152,165,0.45)";
      ctx.setLineDash([2 / view.k, 2 / view.k]);
      ctx.stroke();
      ctx.setLineDash([]);
    }

    if (labelVisible(n)) {
      ctx.fillStyle = hovered || selected ? "#e6edf3" : "rgba(230,237,243,0.75)";
      ctx.font = (11 / view.k) + "px -apple-system, system-ui, sans-serif";
      ctx.textAlign = "left";
      ctx.textBaseline = "middle";
      ctx.fillText(n.name, n.x + r + 4 / view.k, n.y);
    }
  });
}

// How many of each node's edges are actually on screen. Recomputed once per frame in O(links);
// doing it per node inside the draw loop would be O(nodes x links) sixty times a second.
let drawnDegrees = new Map();

// The handful of biggest hubs, which keep their labels even when zoomed out — without them a
// 300-node overview is an anonymous blob, and with all 300 labels it is unreadable soup.
let landmarks = new Set();
let landmarkSignature = "";

const LANDMARK_COUNT = 12;

function recomputeDrawnDegrees() {
  drawnDegrees = new Map();
  model.links.forEach(function (l) {
    drawnDegrees.set(l.source.id, (drawnDegrees.get(l.source.id) || 0) + 1);
    drawnDegrees.set(l.target.id, (drawnDegrees.get(l.target.id) || 0) + 1);
  });

  // Only recompute the landmark set when the node set itself changed — it does not depend on the
  // layout, so recomputing it every frame would be a per-frame sort for nothing.
  const signature = model.nodes.length + ":" + (model.nodes.length ? model.nodes[0].id : "");
  if (signature === landmarkSignature) return;
  landmarkSignature = signature;
  landmarks = new Set(model.nodes.slice()
    .sort(function (a, b) { return b.degree - a.degree; })
    .slice(0, LANDMARK_COUNT)
    .map(function (n) { return n.id; }));
}

function drawnDegree(n) {
  return drawnDegrees.get(n.id) || 0;
}

// Labels are the difference between a readable graph and a wall of overlapping text. Show them
// where there is room — a small graph, or zoomed in — and otherwise only for the nodes the user is
// pointing at plus a few landmark hubs to orient by.
function labelVisible(n) {
  if (n.id === model.hoverId || n.id === model.selectedId || n.id === model.focusId) return true;
  if (model.nodes.length <= 40) return true;
  if (view.k >= 1.2) return true;
  return landmarks.has(n.id);
}

// ---- view transform --------------------------------------------------------------------------

export function fit() {
  if (!canvas || !model.nodes.length) return;
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  let any = false;
  model.nodes.forEach(function (n) {
    if (n.x === undefined) return;
    any = true;
    const r = radius(n);
    minX = Math.min(minX, n.x - r);
    minY = Math.min(minY, n.y - r);
    maxX = Math.max(maxX, n.x + r);
    maxY = Math.max(maxY, n.y + r);
  });
  if (!any) return;

  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  const pad = 60;
  const gw = Math.max(1, maxX - minX);
  const gh = Math.max(1, maxY - minY);
  view.k = Math.max(0.1, Math.min((w - pad * 2) / gw, (h - pad * 2) / gh, 2.5));
  view.x = w / 2 - ((minX + maxX) / 2) * view.k;
  view.y = h / 2 - ((minY + maxY) / 2) * view.k;
  draw();
}

// Centre on one node without changing zoom.
export function centerOn(id) {
  const n = nodeById(id);
  if (!n || n.x === undefined || !canvas) return;
  view.x = canvas.clientWidth / 2 - n.x * view.k;
  view.y = canvas.clientHeight / 2 - n.y * view.k;
  draw();
}

function toWorld(sx, sy) {
  return { x: (sx - view.x) / view.k, y: (sy - view.y) / view.k };
}

function localPoint(e) {
  const rect = canvas.getBoundingClientRect();
  return { x: e.clientX - rect.left, y: e.clientY - rect.top };
}

function nodeAt(sx, sy) {
  const w = toWorld(sx, sy);
  for (let i = model.nodes.length - 1; i >= 0; i--) {
    const n = model.nodes[i];
    if (n.x === undefined) continue;
    const dx = n.x - w.x;
    const dy = n.y - w.y;
    const hit = radius(n) + 3 / view.k;
    if (dx * dx + dy * dy <= hit * hit) return n;
  }
  return null;
}

// ---- pointer handling ------------------------------------------------------------------------

function bind() {
  canvas.addEventListener("pointerdown", onPointerDown);
  canvas.addEventListener("pointermove", onPointerMove);
  canvas.addEventListener("pointerup", onPointerUp);
  canvas.addEventListener("pointercancel", onPointerUp);
  canvas.addEventListener("pointerleave", function () {
    if (drag || pan) return;
    model.hoverId = null;
    hideHoverCard();
    draw();
  });
  canvas.addEventListener("dblclick", function (e) {
    const p = localPoint(e);
    const n = nodeAt(p.x, p.y);
    if (n) handlers.onExpand(n.id);
  });
  canvas.addEventListener("wheel", onWheel, { passive: false });
}

function onPointerDown(e) {
  const p = localPoint(e);
  const n = nodeAt(p.x, p.y);
  moved = false;
  // Capture on the canvas: the drag keeps receiving moves outside the element without a single
  // window-level listener, and the capture is released automatically on pointerup/cancel.
  canvas.setPointerCapture(e.pointerId);
  if (n) {
    drag = { node: n, pointerId: e.pointerId };
    const w = toWorld(p.x, p.y);
    pin(n, w.x, w.y);
  } else {
    pan = { startX: e.clientX - view.x, startY: e.clientY - view.y };
    canvas.classList.add("dragging");
  }
}

function onPointerMove(e) {
  const p = localPoint(e);

  if (drag) {
    const w = toWorld(p.x, p.y);
    moved = moved || Math.abs(w.x - drag.node.x) > CLICK_SLOP || Math.abs(w.y - drag.node.y) > CLICK_SLOP;
    pin(drag.node, w.x, w.y);
    hideHoverCard();
    return;
  }

  if (pan) {
    const nx = e.clientX - pan.startX;
    const ny = e.clientY - pan.startY;
    moved = moved || Math.abs(nx - view.x) > CLICK_SLOP || Math.abs(ny - view.y) > CLICK_SLOP;
    view.x = nx;
    view.y = ny;
    hideHoverCard();
    draw();
    return;
  }

  const n = nodeAt(p.x, p.y);
  const previous = model.hoverId;
  model.hoverId = n ? n.id : null;
  if (n) showHoverCard(n, p);
  else hideHoverCard();
  canvas.style.cursor = n ? "pointer" : "grab";
  if (previous !== model.hoverId) draw();
}

function onPointerUp(e) {
  const wasDrag = drag;
  const wasPan = pan;
  if (drag) {
    unpin(drag.node);
    // A press that never moved is a click: select the node.
    if (!moved) handlers.onSelect(drag.node.id);
    start();
  }
  drag = null;
  pan = null;
  canvas.classList.remove("dragging");
  if (canvas.hasPointerCapture && canvas.hasPointerCapture(e.pointerId)) {
    canvas.releasePointerCapture(e.pointerId);
  }
  if (wasDrag || wasPan) draw();
}

function onWheel(e) {
  e.preventDefault();
  const p = localPoint(e);
  const before = toWorld(p.x, p.y);
  const factor = Math.pow(1.0015, -e.deltaY);
  view.k = Math.max(0.1, Math.min(6, view.k * factor));
  view.x = p.x - before.x * view.k;
  view.y = p.y - before.y * view.k;
  hideHoverCard();
  draw();
}

// ---- hover card ------------------------------------------------------------------------------
//
// The card lives inside #view-graph rather than on <body>, so the console's `.view { display:none }`
// takes it away with the tab. Position is relative to the graph view, not the viewport.

function showHoverCard(n, point) {
  if (!hoverCard) return;
  const hidden = n.degree - drawnDegree(n);
  hoverCard.innerHTML = "";
  hoverCard.appendChild(el("div", "hover-title", n.name));
  const meta = el("div", "hover-meta");
  meta.innerHTML = '<span class="hover-type">' + escapeHtml(n.type || "unknown") + "</span> · "
    + n.degree + (n.degree === 1 ? " relation" : " relations")
    + (hidden > 0 ? ' · <span class="hover-more">' + hidden + " not shown</span>" : "");
  hoverCard.appendChild(meta);
  hoverCard.appendChild(el("div", "hover-hint", hidden > 0
    ? "click to inspect · double-click to expand"
    : "click to inspect"));

  hoverCard.style.display = "block";
  const w = hoverCard.offsetWidth;
  const h = hoverCard.offsetHeight;
  let x = point.x + 16;
  let y = point.y + 16;
  if (x + w > canvas.clientWidth - 8) x = point.x - w - 16;
  if (y + h > canvas.clientHeight - 8) y = point.y - h - 16;
  hoverCard.style.left = Math.max(8, x) + "px";
  hoverCard.style.top = Math.max(8, y) + "px";
}

export function hideHoverCard() {
  if (hoverCard) hoverCard.style.display = "none";
}
