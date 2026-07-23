// Force layout, wrapping d3-force.
//
// d3-force's own timer is deliberately not used: the simulation is created stopped and stepped by
// hand from a rAF loop this module owns, so (a) nothing runs while the graph tab is hidden, and
// (b) there is never a synchronous warm-up burst before the first paint — the user sees the graph
// immediately and watches it settle.
//
// forceManyBody uses a Barnes-Hut quadtree internally, so repulsion is O(n log n) rather than the
// O(n^2) all-pairs loop this view used to run.
import {
  forceSimulation, forceLink, forceManyBody, forceCenter, forceCollide, forceX, forceY
} from "../vendor/d3-force.js";
import { model } from "./model.js";

let simulation = null;
let running = false;
let onTick = function () {};
let onSettle = function () {};

export function init(tickHandler, settleHandler) {
  onTick = tickHandler;
  onSettle = settleHandler || function () {};
  simulation = forceSimulation()
    .force("charge", forceManyBody().strength(-260).distanceMax(600))
    .force("link", forceLink().id(function (d) { return d.id; }).distance(70).strength(0.35))
    .force("collide", forceCollide().radius(function (d) { return radius(d) + 4; }))
    // Weak pull towards the centre keeps disconnected components from drifting off-screen.
    .force("x", forceX().strength(0.02))
    .force("y", forceY().strength(0.02))
    .stop();
}

// Re-bind the simulation to the current model and give it energy to settle.
export function reheat(alpha) {
  if (!simulation) return;
  simulation.nodes(model.nodes);
  simulation.force("link").links(model.links);
  simulation.force("center", forceCenter(0, 0));
  simulation.alpha(alpha === undefined ? 1 : alpha).alphaTarget(0);
  start();
}

export function start() {
  if (running || !simulation) return;
  running = true;
  requestAnimationFrame(frame);
}

export function stop() {
  running = false;
}

export function isRunning() { return running; }

// Pin a node under the pointer while it is being dragged, and keep the layout warm so the rest of
// the graph reacts to the move.
export function pin(node, x, y) {
  node.fx = x;
  node.fy = y;
  if (simulation) simulation.alphaTarget(0.25);
  start();
}

export function unpin(node) {
  node.fx = null;
  node.fy = null;
  if (simulation) simulation.alphaTarget(0);
}

// Node radius from degree: sqrt so a 500-edge hub is bigger than a 5-edge one without being
// a hundred times bigger.
export function radius(node) {
  return 4 + Math.sqrt(Math.max(0, node.degree || 0)) * 2.2;
}

function frame() {
  if (!running || !simulation) {
    running = false;
    return;
  }
  simulation.tick();
  onTick();
  if (simulation.alpha() < simulation.alphaMin() && !hasPinned()) {
    running = false;
    onTick();
    // The layout has stopped moving. Anything that needs final positions — framing the view, most
    // of all — has to happen here rather than on a guessed timer: a force layout expands before it
    // contracts, so an early fit frames the graph at its widest and leaves it microscopic once it
    // settles.
    onSettle();
    return;
  }
  requestAnimationFrame(frame);
}

function hasPinned() {
  return model.nodes.some(function (n) { return n.fx != null; });
}
