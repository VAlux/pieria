import { g } from "./state.js";
import { draw } from "./render.js";

const REPULSION = 4000, SPRING = 0.02, SPRING_LEN = 70, CENTER = 0.008, DAMP = 0.85;

// One integration step of the force layout: node repulsion, edge springs, centering, damping.
export function tick() {
  const n = g.nodes.length;
  if (!n) return;
  const cx = (g.canvas.clientWidth || 800) / 2, cy = (g.canvas.clientHeight || 600) / 2;
  for (let i = 0; i < n; i++) {
    const a = g.nodes[i];
    for (let j = i + 1; j < n; j++) {
      const b = g.nodes[j];
      const dx = a.x - b.x, dy = a.y - b.y;
      const d2 = dx * dx + dy * dy + 0.01;
      const f = REPULSION / d2;
      const d = Math.sqrt(d2);
      const fx = f * dx / d, fy = f * dy / d;
      a.vx += fx; a.vy += fy; b.vx -= fx; b.vy -= fy;
    }
  }
  for (let l = 0; l < g.links.length; l++) {
    const s = g.links[l].source, t = g.links[l].target;
    const dx2 = t.x - s.x, dy2 = t.y - s.y;
    const dist = Math.sqrt(dx2 * dx2 + dy2 * dy2) + 0.01;
    const force = SPRING * (dist - SPRING_LEN);
    const ux = dx2 / dist, uy = dy2 / dist;
    s.vx += force * ux; s.vy += force * uy;
    t.vx -= force * ux; t.vy -= force * uy;
  }
  for (let m = 0; m < n; m++) {
    const p = g.nodes[m];
    p.vx += (cx - p.x) * CENTER; p.vy += (cy - p.y) * CENTER;
    if (p === g.dragNode) continue;
    p.vx *= DAMP; p.vy *= DAMP;
    p.x += p.vx * g.alpha; p.y += p.vy * g.alpha;
  }
  g.alpha *= 0.995;
}

export function startSim() {
  if (g.running) return;
  g.running = true;
  requestAnimationFrame(frame);
}

function frame() {
  if (g.alpha > 0.02 || g.dragNode) {
    tick();
    draw();
    requestAnimationFrame(frame);
  } else {
    g.running = false;
    draw();
  }
}
