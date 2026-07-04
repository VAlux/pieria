import { $, escapeHtml } from "../util/dom.js";
import { g, toWorld } from "./state.js";
import { draw } from "./render.js";
import { startSim } from "./simulation.js";

// ---- hit testing ----
function nodeAt(sx, sy) {
  const w = toWorld(sx, sy);
  for (let i = g.nodes.length - 1; i >= 0; i--) {
    const n = g.nodes[i];
    const dx = n.x - w.x, dy = n.y - w.y;
    if (dx * dx + dy * dy <= (n.r + 3) * (n.r + 3)) return n;
  }
  return null;
}

function linkAt(sx, sy) {
  const w = toWorld(sx, sy);
  let best = null, bestD = 6 / g.view.k;
  for (let i = 0; i < g.links.length; i++) {
    const s = g.links[i].source, t = g.links[i].target;
    const d = distToSeg(w.x, w.y, s.x, s.y, t.x, t.y);
    if (d < bestD) { bestD = d; best = g.links[i]; }
  }
  return best;
}

function distToSeg(px, py, x1, y1, x2, y2) {
  const dx = x2 - x1, dy = y2 - y1;
  const l2 = dx * dx + dy * dy;
  if (l2 === 0) return Math.hypot(px - x1, py - y1);
  let tt = ((px - x1) * dx + (py - y1) * dy) / l2;
  tt = Math.max(0, Math.min(1, tt));
  return Math.hypot(px - (x1 + tt * dx), py - (y1 + tt * dy));
}

// ---- tooltip ----
function showNodeTip(n, cx, cy) {
  const tooltip = $("graphTooltip");
  tooltip.innerHTML = '<div class="t-title">' + escapeHtml(n.name) + '</div>'
    + '<div class="t-type">' + escapeHtml(n.type) + ' · ' + n.deg + ' link' + (n.deg === 1 ? '' : 's') + '</div>';
  placeTip(cx, cy);
}
function showLinkTip(l, cx, cy) {
  const tooltip = $("graphTooltip");
  let html = '<div><b>' + escapeHtml(l.source.name) + '</b> '
    + '<span class="t-rel">' + escapeHtml(l.relation || '—') + '</span> '
    + '<b>' + escapeHtml(l.target.name) + '</b></div>';
  if (l.memory) html += '<div class="t-mem">' + escapeHtml(l.memory) + '</div>';
  tooltip.innerHTML = html;
  placeTip(cx, cy);
}
function placeTip(cx, cy) {
  const tooltip = $("graphTooltip");
  tooltip.style.display = "block";
  const tw = tooltip.offsetWidth, th = tooltip.offsetHeight;
  let x = cx + 14, y = cy + 14;
  if (x + tw > window.innerWidth - 8) x = cx - tw - 14;
  if (y + th > window.innerHeight - 8) y = cy - th - 14;
  tooltip.style.left = x + "px";
  tooltip.style.top = y + "px";
}
function hideTip() { $("graphTooltip").style.display = "none"; }

// Frame all nodes within the viewport with a little padding.
export function fit() {
  if (!g.nodes.length) return;
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  g.nodes.forEach(function (n) {
    minX = Math.min(minX, n.x - n.r); minY = Math.min(minY, n.y - n.r);
    maxX = Math.max(maxX, n.x + n.r); maxY = Math.max(maxY, n.y + n.r);
  });
  const w = g.canvas.clientWidth, h = g.canvas.clientHeight;
  const pad = 60;
  const gw = Math.max(1, maxX - minX), gh = Math.max(1, maxY - minY);
  const k = Math.min((w - pad * 2) / gw, (h - pad * 2) / gh, 2.5);
  g.view.k = k;
  g.view.x = w / 2 - ((minX + maxX) / 2) * k;
  g.view.y = h / 2 - ((minY + maxY) / 2) * k;
  draw();
}

// Attach pan / zoom / drag / hover handlers to the (already-assigned) canvas.
export function bindInteraction() {
  const canvas = g.canvas;

  canvas.addEventListener("mousedown", function (e) {
    const r = canvas.getBoundingClientRect();
    const sx = e.clientX - r.left, sy = e.clientY - r.top;
    const n = nodeAt(sx, sy);
    g.moved = false;
    if (n) {
      g.dragNode = n;
      startSim();
    } else {
      g.panning = true;
      g.panStart = { x: e.clientX - g.view.x, y: e.clientY - g.view.y };
      canvas.classList.add("dragging");
    }
  });

  window.addEventListener("mousemove", function (e) {
    if (!g.canvas) return;
    const r = canvas.getBoundingClientRect();
    const sx = e.clientX - r.left, sy = e.clientY - r.top;
    if (g.dragNode) {
      const w = toWorld(sx, sy);
      g.dragNode.x = w.x; g.dragNode.y = w.y; g.dragNode.vx = 0; g.dragNode.vy = 0;
      g.moved = true;
      draw();
      return;
    }
    if (g.panning) {
      g.view.x = e.clientX - g.panStart.x;
      g.view.y = e.clientY - g.panStart.y;
      g.moved = true;
      draw();
      return;
    }
    // hover
    const n = nodeAt(sx, sy);
    const prevN = g.hoverNode, prevL = g.hoverLink;
    g.hoverNode = n;
    g.hoverLink = n ? null : linkAt(sx, sy);
    if (g.hoverNode) showNodeTip(g.hoverNode, e.clientX, e.clientY);
    else if (g.hoverLink) showLinkTip(g.hoverLink, e.clientX, e.clientY);
    else hideTip();
    canvas.style.cursor = (g.hoverNode || g.hoverLink) ? "pointer" : "grab";
    if (prevN !== g.hoverNode || prevL !== g.hoverLink) draw();
  });

  window.addEventListener("mouseup", function () {
    g.dragNode = null;
    g.panning = false;
    canvas.classList.remove("dragging");
  });

  canvas.addEventListener("wheel", function (e) {
    e.preventDefault();
    const r = canvas.getBoundingClientRect();
    const sx = e.clientX - r.left, sy = e.clientY - r.top;
    const before = toWorld(sx, sy);
    const factor = Math.pow(1.0015, -e.deltaY);
    g.view.k = Math.max(0.15, Math.min(6, g.view.k * factor));
    g.view.x = sx - before.x * g.view.k;
    g.view.y = sy - before.y * g.view.k;
    draw();
  }, { passive: false });
}
