import { g, colorFor } from "./state.js";

// Match the backing store to the CSS pixel size of the canvas (retina-aware).
export function resize() {
  const c = g.canvas;
  c.width = Math.floor(c.clientWidth * g.dpr);
  c.height = Math.floor(c.clientHeight * g.dpr);
}

export function draw() {
  const ctx = g.ctx, canvas = g.canvas, view = g.view;
  ctx.save();
  ctx.setTransform(g.dpr, 0, 0, g.dpr, 0, 0);
  ctx.clearRect(0, 0, canvas.clientWidth, canvas.clientHeight);
  ctx.translate(view.x, view.y);
  ctx.scale(view.k, view.k);

  // edges
  ctx.lineWidth = 1 / view.k;
  for (let i = 0; i < g.links.length; i++) {
    const s = g.links[i].source, t = g.links[i].target;
    ctx.strokeStyle = g.links[i] === g.hoverLink ? "#e6edf3" : "rgba(139,152,165,0.28)";
    ctx.beginPath();
    ctx.moveTo(s.x, s.y);
    ctx.lineTo(t.x, t.y);
    ctx.stroke();
    drawArrow(s, t, g.links[i] === g.hoverLink);
    if (g.links[i] === g.hoverLink) drawEdgeLabel(g.links[i]);
  }

  // nodes
  for (let j = 0; j < g.nodes.length; j++) {
    const nd = g.nodes[j];
    const dim = g.searchTerm && nd.name.toLowerCase().indexOf(g.searchTerm) < 0;
    ctx.globalAlpha = dim ? 0.18 : 1;
    ctx.beginPath();
    ctx.arc(nd.x, nd.y, nd.r, 0, Math.PI * 2);
    ctx.fillStyle = colorFor(nd.type);
    ctx.fill();
    if (nd === g.hoverNode || nd === g.dragNode) {
      ctx.lineWidth = 2 / view.k;
      ctx.strokeStyle = "#e6edf3";
      ctx.stroke();
    }
    // labels for hubs, hovered node, or when zoomed in
    if (!dim && (nd === g.hoverNode || nd.deg >= 3 || view.k > 1.4)) {
      ctx.globalAlpha = dim ? 0.18 : 1;
      ctx.fillStyle = "#e6edf3";
      ctx.font = (11 / view.k) + "px -apple-system, system-ui, sans-serif";
      ctx.textAlign = "left";
      ctx.textBaseline = "middle";
      ctx.fillText(nd.name, nd.x + nd.r + 3 / view.k, nd.y);
    }
  }
  ctx.globalAlpha = 1;
  ctx.restore();
}

function drawArrow(s, t, hot) {
  const ctx = g.ctx, view = g.view;
  const dx = t.x - s.x, dy = t.y - s.y;
  const d = Math.sqrt(dx * dx + dy * dy) || 1;
  const ux = dx / d, uy = dy / d;
  const tipX = t.x - ux * t.r, tipY = t.y - uy * t.r;
  const size = 6 / view.k;
  const ang = Math.atan2(uy, ux);
  ctx.fillStyle = hot ? "#e6edf3" : "rgba(139,152,165,0.4)";
  ctx.beginPath();
  ctx.moveTo(tipX, tipY);
  ctx.lineTo(tipX - size * Math.cos(ang - 0.4), tipY - size * Math.sin(ang - 0.4));
  ctx.lineTo(tipX - size * Math.cos(ang + 0.4), tipY - size * Math.sin(ang + 0.4));
  ctx.closePath();
  ctx.fill();
}

function drawEdgeLabel(link) {
  const ctx = g.ctx, view = g.view;
  const mx = (link.source.x + link.target.x) / 2;
  const my = (link.source.y + link.target.y) / 2;
  ctx.font = (11 / view.k) + "px -apple-system, system-ui, sans-serif";
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  const label = link.relation || "";
  const w = ctx.measureText(label).width + 8 / view.k;
  ctx.fillStyle = "rgba(28,35,45,0.92)";
  ctx.fillRect(mx - w / 2, my - 8 / view.k, w, 16 / view.k);
  ctx.fillStyle = "#58a6ff";
  ctx.fillText(label, mx, my);
}
