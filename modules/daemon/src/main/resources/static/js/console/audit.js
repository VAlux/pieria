import { $, el, api, apiFetch } from "../util/dom.js";
import { fmtDate } from "../util/format.js";
import { state } from "./state.js";
import { renderBanner } from "./router.js";
import { openAuditDrawer } from "./drawer.js";

let cursor = null;
let nextCursor = null;
let history = [];
let loadSequence = 0;

export function loadAudit(profileChanged) {
  if (profileChanged) {
    cursor = null;
    nextCursor = null;
    history = [];
  }
  const list = $("auditList");
  $("auditPager").innerHTML = "";
  renderBanner(list, "Loading audit history…");
  const params = filters();
  const sequence = ++loadSequence;
  params.set("limit", "50");
  if (cursor) params.set("cursor", cursor);
  apiFetch(api(state.profile, "/audit?" + params.toString()), { headers: { Accept: "application/json" } })
    .then(function (r) { if (!r.ok) throw new Error("Request failed (" + r.status + ")."); return r.json(); })
    .then(function (data) {
      if (sequence !== loadSequence) return;
      nextCursor = data.nextCursor || null;
      renderAudit(data.events || []);
    })
    .catch(function (e) { if (sequence === loadSequence) renderBanner(list, e.message, true); });
}

export function applyAuditFilters() {
  cursor = null; nextCursor = null; history = [];
  loadAudit(false);
}

export function clearAuditFilters() {
  ["auditSearch", "auditOperation", "auditClient", "auditHarness", "auditOutcome",
    "auditFrom", "auditTo"].forEach(function (id) { $(id).value = ""; });
  $("auditTruncated").checked = false;
  applyAuditFilters();
}

function filters() {
  const p = new URLSearchParams();
  put(p, "q", $("auditSearch").value.trim());
  put(p, "operation", $("auditOperation").value);
  put(p, "client", $("auditClient").value);
  put(p, "harness", $("auditHarness").value);
  put(p, "outcome", $("auditOutcome").value);
  put(p, "from", instant($("auditFrom").value));
  put(p, "to", instant($("auditTo").value));
  if ($("auditTruncated").checked) p.set("truncated", "true");
  return p;
}

function put(params, name, value) { if (value) params.set(name, value); }
function instant(value) { return value ? new Date(value).toISOString() : ""; }

function renderAudit(events) {
  const list = $("auditList"), pager = $("auditPager");
  list.innerHTML = ""; pager.innerHTML = "";
  if (!events.length) {
    renderBanner(list, "No audit events match the current filters.");
    return;
  }
  events.forEach(function (event) {
    const row = el("button", "audit-row");
    row.type = "button";
    row.addEventListener("click", function () { loadDetail(event.id); });
    row.appendChild(el("span", "audit-time", fmtDate(event.completedAt)));
    row.appendChild(el("span", "audit-operation", event.operation));
    row.appendChild(el("span", "audit-caller", caller(event)));
    const outcome = el("span", "audit-outcome " + event.outcome, event.httpStatus == null ? event.outcome : String(event.httpStatus));
    row.appendChild(outcome);
    row.appendChild(el("span", "audit-duration", event.durationMs + " ms"));
    row.appendChild(el("span", "audit-preview", event.errorMessage || event.responsePreview || "(empty response)"));
    if (event.requestTruncated || event.responseTruncated) row.appendChild(el("span", "tag-super", "truncated"));
    list.appendChild(row);
  });
  const nav = el("div", "pager-nav");
  const prev = el("button", null, "‹ Prev");
  prev.disabled = history.length === 0;
  prev.addEventListener("click", function () { cursor = history.pop() || null; loadAudit(false); });
  const next = el("button", null, "Next ›");
  next.disabled = !nextCursor;
  next.addEventListener("click", function () { history.push(cursor); cursor = nextCursor; loadAudit(false); });
  nav.appendChild(prev);
  nav.appendChild(next);
  pager.appendChild(el("span", "pager-info", events.length + " event" + (events.length === 1 ? "" : "s")));
  pager.appendChild(nav);
}

function caller(event) {
  return event.harness ? event.harness + " / " + event.channel : event.client + " / " + event.channel;
}

function loadDetail(id) {
  apiFetch(api(state.profile, "/audit/" + encodeURIComponent(id)), { headers: { Accept: "application/json" } })
    .then(function (r) { if (!r.ok) throw new Error("Detail failed (" + r.status + ")."); return r.json(); })
    .then(openAuditDrawer)
    .catch(function (e) { renderBanner($("auditList"), e.message, true); });
}
