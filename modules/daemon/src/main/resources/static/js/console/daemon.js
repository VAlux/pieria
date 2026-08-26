// Daemon status block at the foot of the side panel.
//
// Two endpoints, both daemon-wide rather than per-profile: /pieria-health for the up/degraded
// verdict and /pieria-status for the operational detail. vectorSearch earns its row because
// retrieval degrades silently to FTS-only when sqlite-vec fails to load — nothing else in the
// console distinguishes a fully working daemon from a quietly reduced one.
import { $, el, apiFetch } from "../util/dom.js";

const REFRESH_MS = 30000;
let timer = null;

export function initDaemonStatus() {
  refresh();
  timer = setInterval(refresh, REFRESH_MS);
}

export function stopDaemonStatus() {
  clearInterval(timer);
  timer = null;
}

function refresh() {
  Promise.all([get("/pieria-health"), get("/pieria-status")])
    .then(function (r) { render(r[0], r[1]); })
    .catch(function () { render(null, null); });
}

function get(path) {
  return apiFetch(path, { headers: { Accept: "application/json" } })
    .then(function (r) { return r.ok ? r.json() : null; })
    .catch(function () { return null; });
}

function render(health, status) {
  const dot = $("daemonDot");
  const label = $("daemonLabel");
  const port = $("daemonPort");
  const rows = $("daemonRows");
  if (!dot) return;

  const state = daemonState(health, status);
  dot.className = "daemon-dot " + state.kind;
  label.className = "daemon-label " + state.kind;
  label.textContent = state.label;
  port.textContent = location.port ? ":" + location.port : "";

  rows.innerHTML = "";
  if (!status) {
    if (health) row(rows, "Database", health.db === "ok" ? "ok" : "down", health.db === "ok" ? "ok" : "err");
    return;
  }
  row(rows, "Backend", status.backend || "—");
  row(rows, "Vector search", status.vectorSearch ? "on" : "FTS only", status.vectorSearch ? "ok" : "warn");
  if (status.vectorizationOutboxDepth != null) {
    row(rows, "Outbox", String(status.vectorizationOutboxDepth),
      status.vectorizationOutboxDepth > 0 ? "warn" : "");
  }
  const providerReachable = !health || health.modelProvider !== "unreachable";
  row(rows, "Provider", providerReachable ? (status.modelProvider || "—") : "unreachable",
    providerReachable ? "" : "err");
}

function daemonState(health, status) {
  if (!health && !status) return { kind: "down", label: "Unreachable" };
  if (health && health.status === "up" && (!status || status.vectorSearch !== false)) {
    return { kind: "up", label: "Running" };
  }
  if (health && health.db === "down") return { kind: "down", label: "Database down" };
  return { kind: "degraded", label: "Degraded" };
}

function row(host, k, v, cls) {
  const wrap = el("div", "daemon-row");
  wrap.appendChild(el("dt", null, k));
  wrap.appendChild(el("dd", "mono num" + (cls ? " " + cls : ""), v));
  host.appendChild(wrap);
}
