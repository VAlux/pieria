// Background task tray in the top bar.
//
// GET /v1/tasks returns running tasks plus recently-finished ones, newest first. Each task's
// `lanes` carry name/state/phase/done/total, which is what makes a real progress bar possible;
// without this the console gives no sign that a 48-second code index is alive.
import { $, el, apiFetch, icon } from "../util/dom.js";
import { toast } from "./toast.js";

const POLL_MS = 2000;
const IDLE_POLL_MS = 15000;
let timer = null;
let tasks = [];

export function initTaskTray() {
  $("trayBtn").addEventListener("click", toggleTray);
  // A click anywhere else closes the panel; the tray is a transient popover, not a mode.
  document.addEventListener("click", function (e) {
    if (!e.target.closest(".tray")) closeTray();
  });
  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape") closeTray();
  });
  poll();
}

function toggleTray() {
  const panel = $("trayPanel");
  const open = panel.hidden;
  panel.hidden = !open;
  $("trayBtn").setAttribute("aria-expanded", open ? "true" : "false");
  $("trayBtn").classList.toggle("is-active", open);
}

function closeTray() {
  const panel = $("trayPanel");
  if (panel.hidden) return;
  panel.hidden = true;
  $("trayBtn").setAttribute("aria-expanded", "false");
  $("trayBtn").classList.remove("is-active");
}

// Poll fast while something is running, slowly when idle: the tray is a status light, not a feed.
function poll() {
  apiFetch("/v1/tasks", { headers: { Accept: "application/json" } })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (data) {
      tasks = (data && data.tasks) || [];
      render();
    })
    .catch(function () { /* daemon momentarily unreachable — keep the last render */ })
    .finally(function () {
      clearTimeout(timer);
      timer = setTimeout(poll, running().length ? POLL_MS : IDLE_POLL_MS);
    });
}

function running() {
  return tasks.filter(function (t) { return t.status === "RUNNING"; });
}

function render() {
  const btn = $("trayBtn");
  const active = running();
  // Nothing running and nothing recent: the tray disappears rather than sitting there empty.
  btn.classList.toggle("is-idle", !tasks.length);
  $("trayLabel").textContent = active.length
    ? active.length + (active.length === 1 ? " running" : " running")
    : tasks.length + " recent";
  btn.querySelector(".ico").classList.toggle("spin", active.length > 0);
  $("traySummary").textContent = tasks.length + " in the retention window";

  const list = $("trayList");
  list.innerHTML = "";
  if (!tasks.length) {
    list.appendChild(el("div", "tray-empty", "No background tasks."));
    return;
  }
  tasks.forEach(function (t) { list.appendChild(taskRow(t)); });
}

function taskRow(t) {
  const row = el("div", "tray-task");

  const head = el("div", "tray-task-head");
  head.appendChild(el("span", "tray-kind mono", t.kind));
  head.appendChild(el("span", "tray-profile mono", t.profile || ""));
  head.appendChild(el("span", "tray-status mono " + String(t.status || "").toLowerCase(), t.status));
  if (t.status === "RUNNING") head.appendChild(cancelButton(t));
  row.appendChild(head);

  (t.lanes || []).forEach(function (lane) { row.appendChild(laneRow(lane)); });

  if (t.errorMessage) {
    row.appendChild(el("div", "tray-error",
      (t.errorKind ? t.errorKind + " — " : "") + t.errorMessage));
  }
  row.appendChild(el("div", "tray-elapsed", elapsed(t)));
  return row;
}

function laneRow(lane) {
  const total = lane.total || 0;
  const done = lane.done || 0;
  const pct = total ? Math.round(done / total * 100) : 0;
  // A lane that has not started reads as an empty track rather than a zero-width bar, so
  // "pending" and "0% done" do not look identical.
  const state = total && done >= total ? "done" : done === 0 ? "pending" : "";

  const row = el("div", "tray-lane");
  row.appendChild(el("span", "tray-lane-name", lane.name));
  const meter = el("div", "meter " + state);
  const fill = el("span");
  fill.style.width = (state === "pending" ? 100 : pct) + "%";
  meter.appendChild(fill);
  row.appendChild(meter);
  row.appendChild(el("span", "tray-lane-count mono num", done + "/" + total));
  return row;
}

function cancelButton(t) {
  const btn = el("button", "icon-btn");
  btn.type = "button";
  btn.title = "Cancel this task";
  btn.setAttribute("aria-label", "Cancel " + t.kind);
  btn.style.width = "22px";
  btn.style.height = "22px";
  btn.appendChild(icon("close", 13));
  btn.addEventListener("click", function (e) {
    e.stopPropagation();
    btn.disabled = true;
    apiFetch("/v1/tasks/" + encodeURIComponent(t.id), { method: "DELETE" })
      .then(function (r) {
        if (!r.ok) throw new Error("Cancel failed (" + r.status + ")");
        toast("Task cancelled", "ok");
        poll();
      })
      .catch(function (err) { btn.disabled = false; toast(err.message, "err"); });
  });
  return btn;
}

function elapsed(t) {
  if (!t.startedAtEpochMs) return "";
  const secs = Math.max(0, Math.round((Date.now() - t.startedAtEpochMs) / 1000));
  const label = secs < 60 ? secs + "s" : Math.round(secs / 60) + "m";
  return t.status === "RUNNING" ? "started " + label + " ago" : "finished " + label + " ago";
}
