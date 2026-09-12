import { $, el, api, apiFetch, icon } from "../util/dom.js";
import { typeColor, typeTint } from "../util/palette.js";
import { relTime, fmtDate } from "../util/format.js";
import { memoryPresentation } from "./memory-presentation.js";
import { state } from "./state.js";
import { renderBanner } from "./router.js";
import { toast } from "./toast.js";
import { refreshProfileCounts } from "./profiles.js";
import { openDrawer, closeDrawer } from "./drawer.js";
import { markdown } from "../util/markdown.js";

const PAGE_SIZE = 100;
let page = 1;   // 1-indexed page into the filtered/sorted result

export function loadMemories(force) {
  const list = $("memList");
  $("memPager").innerHTML = "";
  renderBanner(list, "Loading memories…");
  const q = "/memories?includeSuperseded=" + (state.includeSuperseded ? "true" : "false");
  apiFetch(api(state.profile, q), { headers: { Accept: "application/json" } })
    .then(function (r) { if (!r.ok) throw new Error("Request failed (" + r.status + ")."); return r.json(); })
    .then(function (data) {
      state.memories = data.memories || [];
      rebuildSessionFilter();
      rebuildSubtypeFilter();
      page = 1;
      renderMemories();
    })
    .catch(function (e) { renderBanner(list, e.message, true); });
}

// Filters/search/sort change the result set, so jump back to the first page before re-rendering.
export function resetPageAndRender() {
  page = 1;
  renderMemories();
}

function rebuildSessionFilter() {
  const sel = $("sessionFilter"), cur = sel.value;
  const sessions = [];
  state.memories.forEach(function (m) { if (m.sessionId && sessions.indexOf(m.sessionId) < 0) sessions.push(m.sessionId); });
  sessions.sort();
  sel.innerHTML = '<option value="">All sessions</option>';
  sessions.forEach(function (s) {
    const o = el("option");
    o.value = s; o.textContent = s; sel.appendChild(o);
  });
  if (sessions.indexOf(cur) >= 0) sel.value = cur;
}

function filteredSorted() {
  const term = $("searchInput").value.trim().toLowerCase();
  const type = state.typeFilter;
  const session = $("sessionFilter").value;
  const subtype = $("subtypeFilter").value;
  const rows = state.memories.filter(function (m) {
    const view = memoryPresentation(m);
    if (type && m.type !== type) return false;
    if (subtype && (view.subtype || "unclassified") !== subtype) return false;
    if (session && m.sessionId !== session) return false;
    if (term) {
      const hay = [m.content, m.topicKey, m.sessionId, view.subtypeLabel, view.tool, view.statusLabel, view.source, view.preview].join(" ").toLowerCase();
      if (hay.indexOf(term) < 0) return false;
    }
    return true;
  });
  const sort = $("sortSelect").value;
  rows.sort(function (a, b) {
    if (sort === "new") return (b.createdAt || "").localeCompare(a.createdAt || "");
    if (sort === "old") return (a.createdAt || "").localeCompare(b.createdAt || "");
    if (sort === "type") return (a.type || "").localeCompare(b.type || "") || (b.createdAt || "").localeCompare(a.createdAt || "");
    if (sort === "long") return (b.content || "").length - (a.content || "").length;
    return 0;
  });
  return rows;
}

/**
 * One memory row. Shared with the recall view so the two lists cannot drift apart.
 * Type, subtype and execution status have independent visual treatments. The adapter supplies
 * structured previews without changing stored content or recall ordering.
 */
export function memoryRow(m, forgettable) {
  const view = memoryPresentation(m);
  const row = el("article", "mem" + (m.superseded ? " superseded" : ""));
  row.addEventListener("click", function () { openDrawer(m); });

  const rail = el("div", "mem-rail");
  rail.style.background = typeColor(m.type);
  row.appendChild(rail);

  const main = el("div", "mem-main");

  const body = el("div", "mem-body");
  const head = el("div", "mem-heading");
  const chip = el("span", "chip", m.type);
  chip.style.color = typeColor(m.type);
  chip.style.background = typeTint(m.type);
  head.appendChild(chip);
  if (view.subtypeLabel) head.appendChild(el("span", "mem-subtype", view.subtypeLabel));
  if (view.status) head.appendChild(el("span", "mem-status " + view.status, view.statusLabel));
  if (view.exitCode !== null) head.appendChild(el("span", "mem-context", "Exit " + view.exitCode));
  if (m.superseded) head.appendChild(el("span", "tag-super", "Superseded"));
  body.appendChild(head);
  if (view.title) body.appendChild(el("div", "mem-title", view.title));
  body.appendChild(view.toolCall
    ? el("div", "mem-content mem-command", view.preview)
    : markdown(view.preview, { className: "mem-content", inline: true }));
  if (view.failure) body.appendChild(el("div", "mem-content mem-failure", view.failure));
  const meta = el("div", "mem-meta");
  if (view.tool) meta.appendChild(el("span", "mem-context", view.tool));
  if (view.source && !view.toolCall) meta.appendChild(el("span", "mem-context", "Source: " + view.source));
  if (m.topicKey && !m.topicKey.startsWith("trace:")) {
    const topic = el("span", "key", "Topic: " + m.topicKey);
    topic.title = m.topicKey;
    meta.appendChild(topic);
  }
  if (m.sessionId) {
    const session = el("span", "mem-context", "Session " + m.sessionId.slice(0, 8));
    session.title = m.sessionId;
    meta.appendChild(session);
  }
  const details = el("button", "mem-details", "View details");
  details.type = "button";
  details.setAttribute("aria-label", "View " + (view.subtypeLabel || m.type) + " memory details");
  details.addEventListener("click", function (ev) { ev.stopPropagation(); openDrawer(m); });
  meta.appendChild(details);
  body.appendChild(meta);
  main.appendChild(body);

  const time = el("time", "mem-time num", relTime(view.date));
  if (view.date) time.dateTime = view.date;
  time.title = view.dateLabel + ": " + fmtDate(view.date) + " · Stored: " + fmtDate(m.createdAt);
  main.appendChild(time);

  if (forgettable && !m.superseded) {
    const del = el("button", "icon-btn");
    del.type = "button";
    del.title = "Forget";
    del.setAttribute("aria-label", "Forget this memory");
    del.appendChild(icon("trash", 15));
    del.addEventListener("click", function (ev) { ev.stopPropagation(); forgetMemory(m); });
    main.appendChild(del);
  }

  row.appendChild(main);
  return row;
}

function rebuildSubtypeFilter() {
  const select = $("subtypeFilter"), current = select.value;
  const subtypes = new Map();
  state.memories.forEach(function (m) {
    const view = memoryPresentation(m);
    subtypes.set(view.subtype || "unclassified", view.subtypeLabel || "No subtype");
  });
  select.replaceChildren();
  const all = el("option", null, "All subtypes");
  all.value = "";
  select.appendChild(all);
  [...subtypes].sort((a, b) => a[1].localeCompare(b[1])).forEach(function ([value, title]) {
    const option = el("option", null, title);
    option.value = value;
    select.appendChild(option);
  });
  if (subtypes.has(current)) select.value = current;
}

export function renderMemories() {
  const list = $("memList");
  const pager = $("memPager");
  const rows = filteredSorted();
  list.innerHTML = "";
  pager.innerHTML = "";
  if (!rows.length) {
    renderBanner(list, state.memories.length ? "No memories match the current filters." : "No memories in this profile yet.");
    return;
  }
  const totalPages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  page = Math.min(Math.max(1, page), totalPages);
  const start = (page - 1) * PAGE_SIZE;
  const pageRows = rows.slice(start, start + PAGE_SIZE);
  pageRows.forEach(function (m) { list.appendChild(memoryRow(m, true)); });
  renderPager(pager, rows.length, totalPages, start, pageRows.length);
}

function renderPager(host, total, totalPages, start, shown) {
  host.appendChild(el("span", "pager-info", "Showing " + (start + 1) + "–" + (start + shown) + " of " + total));
  if (totalPages <= 1) return;
  const nav = el("div", "pager-nav");
  const prev = el("button", null, "Prev");
  prev.disabled = page <= 1;
  prev.addEventListener("click", function () { page--; renderMemories(); scrollListTop(); });
  const next = el("button", null, "Next");
  next.disabled = page >= totalPages;
  next.addEventListener("click", function () { page++; renderMemories(); scrollListTop(); });
  nav.appendChild(prev);
  nav.appendChild(el("span", "pager-page", "Page " + page + " of " + totalPages));
  nav.appendChild(next);
  host.appendChild(nav);
}

function scrollListTop() {
  const main = document.querySelector("main");
  if (main) main.scrollTop = 0;
}

export function forgetMemory(m) {
  if (!confirm("Forget this memory?\n\n" + (m.content || "").slice(0, 160))) return;
  apiFetch(api(state.profile, "/memories/" + encodeURIComponent(m.id)), { method: "DELETE" })
    .then(function (r) {
      if (r.status === 204) {
        state.memories = state.memories.filter(function (x) { return x.id !== m.id; });
        renderMemories();
        refreshProfileCounts();
        closeDrawer();
        toast("Memory forgotten", "ok");
      } else if (r.status === 404) {
        toast("Memory not found", "err");
      } else {
        toast("Delete failed (" + r.status + ")", "err");
      }
    })
    .catch(function (e) { toast(e.message, "err"); });
}
