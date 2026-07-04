import { $, el, api, escapeHtml } from "../util/dom.js";
import { typeColor } from "../util/palette.js";
import { relTime } from "../util/format.js";
import { state } from "./state.js";
import { renderBanner } from "./router.js";
import { toast } from "./toast.js";
import { refreshProfileCounts } from "./profiles.js";
import { openDrawer, closeDrawer } from "./drawer.js";

const PAGE_SIZE = 100;
let page = 1;   // 1-indexed page into the filtered/sorted result

export function loadMemories(force) {
  const list = $("memList");
  $("memPager").innerHTML = "";
  renderBanner(list, "Loading memories…");
  const q = "/memories?includeSuperseded=" + (state.includeSuperseded ? "true" : "false");
  fetch(api(state.profile, q), { headers: { Accept: "application/json" } })
    .then(function (r) { if (!r.ok) throw new Error("Request failed (" + r.status + ")."); return r.json(); })
    .then(function (data) {
      state.memories = data.memories || [];
      rebuildSessionFilter();
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
  const type = $("typeFilter").value;
  const session = $("sessionFilter").value;
  const rows = state.memories.filter(function (m) {
    if (type && m.type !== type) return false;
    if (session && m.sessionId !== session) return false;
    if (term) {
      const hay = ((m.content || "") + " " + (m.topicKey || "") + " " + (m.sessionId || "")).toLowerCase();
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
  pageRows.forEach(function (m) {
    const row = el("div", "mem" + (m.superseded ? " superseded" : ""));
    row.addEventListener("click", function () { openDrawer(m); });

    const chip = el("span", "chip", m.type);
    chip.style.background = typeColor(m.type);
    row.appendChild(chip);

    const body = el("div", "mem-body");
    body.appendChild(el("div", "mem-content", m.content || ""));
    const meta = el("div", "mem-meta");
    const bits = [];
    if (m.topicKey) bits.push('<span class="key">' + escapeHtml(m.topicKey) + "</span>");
    if (m.sessionId) bits.push(escapeHtml(m.sessionId));
    bits.push(relTime(m.createdAt));
    meta.innerHTML = bits.join(" · ");
    body.appendChild(meta);
    row.appendChild(body);

    if (m.superseded) {
      row.appendChild(el("span", "tag-super", "superseded"));
    } else {
      const del = el("button", "icon-btn", "🗑");
      del.title = "Forget";
      del.addEventListener("click", function (ev) { ev.stopPropagation(); forgetMemory(m); });
      row.appendChild(del);
    }
    list.appendChild(row);
  });
  renderPager(pager, rows.length, totalPages, start, pageRows.length);
}

function renderPager(host, total, totalPages, start, shown) {
  host.appendChild(el("span", "pager-info", "Showing " + (start + 1) + "–" + (start + shown) + " of " + total));
  if (totalPages <= 1) return;
  const nav = el("div", "pager-nav");
  const prev = el("button", null, "‹ Prev");
  prev.disabled = page <= 1;
  prev.addEventListener("click", function () { page--; renderMemories(); scrollListTop(); });
  const next = el("button", null, "Next ›");
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
  fetch(api(state.profile, "/memories/" + encodeURIComponent(m.id)), { method: "DELETE" })
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
