import { $, el, api, apiFetch } from "../util/dom.js";
import { markdown } from "../util/markdown.js";
import { state } from "./state.js";

let epoch = 0, controller = null, poll = null, searchTimer = null;
let current = null, currentProfile = "", directoryCursor = null, directoryParent = null;
let navigation = 0, treeRequest = 0, directoryPoll = null;
const labels = { absent: "Not generated", queued: "Queued", generating: "Writing article…", ready: "Ready",
  failed: "Generation failed", current: "Current", "needs-check": "Checking for updates", stale: "Sources changed", unavailable: "Evidence only" };
function clearPoll() { clearTimeout(poll); poll = null; }
export function unloadWiki() {
  epoch++; navigation++; treeRequest++; clearPoll(); clearTimeout(searchTimer); clearTimeout(directoryPoll); controller?.abort(); controller = null; current = null;
  $("wikiArticle")?.replaceChildren(); $("wikiContext")?.replaceChildren(); $("wikiTree")?.replaceChildren();
}
function alive(token) { return token === epoch && state.view === "wiki" && state.profile === currentProfile; }
async function request(path, options = {}) {
  const r = await apiFetch(api(currentProfile, "/wiki" + path), { ...options, cache: "no-store", signal: controller?.signal,
    headers: { Accept: "application/json", ...(options.headers || {}) } });
  const body = await r.json().catch(() => ({}));
  if (!r.ok) { const error = new Error((body.code || "wiki_request_failed").replaceAll("_", " ")); error.status = r.status; throw error; }
  return body;
}
function notice(host, text) { host.appendChild(el("p", "wiki-notice", text)); }
function error(host, err) { if (err.name !== "AbortError") notice(host, err.message); }
function link(page) {
  const a = el("a", "wiki-page-link", page.title || "Untitled topic");
  a.href = "/index.html?" + new URLSearchParams({ view: "wiki", profile: currentProfile, page: page.id });
  a.addEventListener("click", e => { if (e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return; e.preventDefault(); openPage(page.id, true, true); });
  return a;
}
function badge(value) { return el("span", "wiki-badge wiki-" + value, labels[value] || value); }
function card(page) {
  const row = el("div", "wiki-tree-row"); row.dataset.pageId = page.id; row.appendChild(link(page));
  const detail = el("div", "wiki-tree-meta");
  detail.appendChild(el("span", "", page.kind === "directory" ? "Directory" : page.evidenceCount + " sources"));
  if (page.kind !== "directory") detail.appendChild(badge(page.generation === "ready" ? page.freshness : page.generation));
  row.appendChild(detail);
  if (page.excerpt) row.appendChild(el("p", "wiki-tree-excerpt", page.excerpt));
  return row;
}
async function directory(parent = null, more = false) {
  directoryParent = parent; const token = epoch, treeToken = ++treeRequest; clearTimeout(directoryPoll);
  const q = new URLSearchParams({ limit: "100" }); if (parent) q.set("parent", parent);
  if (more && directoryCursor) q.set("cursor", directoryCursor);
  try {
    const data = await request("?" + q); if (!alive(token) || treeToken !== treeRequest) return;
    const tree = $("wikiTree"); if (!more) tree.replaceChildren();
    $("wikiStatus").textContent = !data.state.enabled ? "Wiki is disabled for this profile" :
      !data.state.initialized || data.state.projectionWatermark < data.state.corpusWatermark ? "Organizing your evidence…" : "Browse your profile knowledge";
    const pages = data.pages.slice().sort((a, b) => a.title.localeCompare(b.title) || a.id.localeCompare(b.id));
    pages.forEach(page => tree.appendChild(card(page)));
    directoryCursor = data.nextCursor;
    $("wikiMore").hidden = !directoryCursor;
    if (!data.state.enabled) {
      notice(tree, "Enable Wiki in this profile’s configuration to build the directory. Articles are generated only when opened.");
      const button = el("button", "", "Open profile configuration"); button.onclick = () => $("profileConfigBtn").click(); tree.appendChild(button);
    } else if (!pages.length && !more) notice(tree, data.state.initialized ? "No topics yet. Stored memories will appear here." : "Building the directory. You can already search stored evidence.");
    if (data.state.enabled && !data.state.initialized) directoryPoll = setTimeout(() => {
      if (alive(token) && treeToken === treeRequest && !document.hidden) directory(parent);
    }, 2000);
  } catch (err) { if (alive(token) && treeToken === treeRequest) error($("wikiTree"), err); }
}
async function search() {
  const q = $("wikiSearch").value.trim(); if (!q) return directory();
  const token = epoch, treeToken = ++treeRequest; clearTimeout(directoryPoll); const mode = $("wikiSemantic").checked ? "semantic" : "lexical";
  try {
    const data = await request("/search?" + new URLSearchParams({ q, mode, limit: "30" }));
    if (!alive(token) || treeToken !== treeRequest || $("wikiSearch").value.trim() !== q) return;
    const tree = $("wikiTree"); tree.replaceChildren(); $("wikiMore").hidden = true;
    data.pages.forEach(page => tree.appendChild(card(page)));
    if (data.unassigned.length) {
      tree.appendChild(el("h3", "wiki-label", "Unassigned evidence"));
      data.unassigned.forEach(source => { const item = el("details", "wiki-source"); item.appendChild(el("summary", "", source.content.slice(0, 90))); item.appendChild(markdown(source.content)); tree.appendChild(item); });
    }
    if (!data.pages.length && !data.unassigned.length) notice(tree, "No matching topics or evidence.");
    if (data.degraded) notice(tree, "Semantic search is unavailable. Showing text matches.");
    if (data.truncated) notice(tree, "Showing the highest-ranked matches. Refine the search to explore further.");
  } catch (err) { if (alive(token) && treeToken === treeRequest) error($("wikiTree"), err); }
}
function updateUrl(id, push) {
  const url = new URL(location); url.searchParams.set("view", "wiki"); url.searchParams.set("profile", currentProfile);
  if (id) url.searchParams.set("page", id); else url.searchParams.delete("page");
  history[push ? "pushState" : "replaceState"](null, "", url);
}
function schedule(id, nav) {
  clearPoll(); poll = setTimeout(() => { if (state.view === "wiki" && navigation === nav && !document.hidden) openPage(id, false, false); }, 2000);
}
async function evaluate(data, intent, nav) {
  const token = epoch;
  try {
    const result = await request("/pages/" + encodeURIComponent(data.page.id) + "/evaluate", { method: "POST",
      headers: { "Content-Type": "application/json" }, body: JSON.stringify({ expectedDescriptorRevision: data.page.descriptorRevision,
        expectedVersionId: data.versionId, intent }) });
    if (!alive(token) || navigation !== nav) return;
    if (result.status === "evidence-only") notice($("wikiArticle"), "This topic is available as evidence. There is not enough source material for an article yet.");
    else openPage(data.page.id, false, false);
  } catch (err) { if (alive(token) && navigation === nav) { error($("wikiArticle"), err); if (err.status === 409) schedule(data.page.id, nav); } }
}
function render(data, nav) {
  const article = $("wikiArticle"), context = $("wikiContext"); article.replaceChildren(); context.replaceChildren();
  for (const row of $("wikiTree").children) {
    if (row.dataset.pageId === data.page.id) row.querySelector(".wiki-badge")?.replaceWith(
      badge(data.page.generation === "ready" ? data.page.freshness : data.page.generation));
  }
  const title = el("h1", "", data.page.title); title.tabIndex = -1; article.appendChild(title);
  const toolbar = el("div", "wiki-article-toolbar"); toolbar.appendChild(badge(data.page.freshness));
  toolbar.appendChild(el("span", "", data.totalSources + " supporting sources")); article.appendChild(toolbar);
  notice(article, data.page.scope);
  if (data.page.freshness === "stale") notice(article, "Evidence has changed: " + data.addedSources + " added, " + data.removedSources + " removed. Refresh to update this article.");
  if (data.page.freshness === "needs-check") notice(article, "Checking source changes. This article may be out of date.");
  if (data.omittedSources) notice(article, data.omittedSources + " sources were outside this article’s selection budget. Browse the evidence for more context.");
  const running = data.job && ["queued", "generating"].includes(data.job.status);
  if (data.job?.status === "failed") notice(article, "Article generation failed. You can retry; the available evidence and previous article are preserved.");
  if (running) toolbar.appendChild(badge(data.job.status));
  else if (data.page.kind !== "directory") {
    const intent = data.body ? "refresh" : data.page.generation === "failed" ? "retry" : "initial";
    const button = el("button", "", data.body ? data.renderingUpgrade ? "Upgrade article" : "Refresh article" : intent === "retry" ? "Retry generation" : "Generate article");
    button.onclick = () => { button.disabled = true; evaluate(data, intent, nav).finally(() => { button.disabled = false; }); }; toolbar.appendChild(button);
  }
  if (data.body) {
    const body = markdown(data.body, { className: "wiki-prose" });
    body.addEventListener("click", e => {
      const a = e.target.closest("a"); if (!a) return;
      const href = a.getAttribute("href") || "";
      if (href.startsWith("#wiki-")) { e.preventDefault(); openPage(href.slice(6), true, true); }
      else if (href.startsWith("#source-")) { e.preventDefault(); const source = document.getElementById(href.slice(1)); if (source) { source.open = true; source.scrollIntoView({ block: "center", behavior: "smooth" }); source.focus(); } }
    });
    article.appendChild(body);
    context.appendChild(el("h3", "wiki-label", "On this page"));
    body.querySelectorAll("h2,h3").forEach((heading, index) => {
      heading.id = "wiki-heading-" + index; const a = el("a", "wiki-outline-link", heading.textContent); a.href = "#" + heading.id;
      a.onclick = e => { e.preventDefault(); heading.scrollIntoView({ behavior: "smooth" }); }; context.appendChild(a);
    });
  } else if (data.page.kind !== "directory") notice(article, running ? "Your article is being written. Explore its evidence while you wait." : "No article has been generated. The source evidence is available below.");
  context.appendChild(el("h3", "wiki-label", "Linked pages"));
  data.links.forEach(page => context.appendChild(link(page)));
  if (!data.links.length) notice(context, "No linked pages yet.");
  article.appendChild(el("h2", "wiki-sources-title", "Source evidence"));
  data.evidence.forEach(source => {
    const item = el("details", "wiki-source"); item.id = "source-" + source.ref; item.tabIndex = -1;
    item.appendChild(el("summary", "", source.ref + " · " + source.content.slice(0, 100)));
    item.appendChild(markdown(source.content));
    item.appendChild(el("p", "wiki-provenance", source.type + " · " + source.role + " · " + source.memoryId));
    source.validity.forEach(v => item.appendChild(el("p", "wiki-provenance", "Assertion " + v.assertionId + " · Revision " + v.revisionId + " · Valid from " + v.validFrom + (v.validTo ? " until " + v.validTo : ""))));
    if (source.truncated) notice(item, "This is a truncated source excerpt."); article.appendChild(item);
  });
  if (data.totalSources > data.evidence.length) {
    const button = el("button", "", "Browse all current evidence");
    button.onclick = async () => {
      button.disabled = true; const token = epoch;
      try {
        const list = await request("/pages/" + encodeURIComponent(data.page.id) + "/evidence?limit=100" + (button.dataset.cursor ? "&cursor=" + encodeURIComponent(button.dataset.cursor) : ""));
        if (!alive(token) || navigation !== nav) return;
        list.evidence.forEach(source => { const item = el("details", "wiki-source"); item.appendChild(el("summary", "", source.content.slice(0, 100))); item.appendChild(markdown(source.content)); article.insertBefore(item, button); });
        button.dataset.cursor = list.nextCursor || ""; button.hidden = !list.nextCursor; button.textContent = "More evidence";
      } catch (err) { if (alive(token)) error(article, err); } finally { button.disabled = false; }
    }; article.appendChild(button);
  }
  if (data.page.kind === "directory") directory(data.page.id);
  if (running || data.page.freshness === "needs-check") schedule(data.page.id, nav);
}
export async function openPage(id, deliberate = true, push = true) {
  const token = epoch; if (deliberate) { navigation++; clearPoll(); }
  const nav = navigation;
  try {
    const data = await request("/pages/" + encodeURIComponent(id)); if (!alive(token) || nav !== navigation) return;
    current = data; updateUrl(data.page.id, push); render(data, nav);
    if (deliberate) { $("wikiArticle").querySelector("h1")?.focus(); }
    if (deliberate && data.page.kind !== "directory" && data.page.generation === "absent" && data.totalSources >= 2 && data.state.initialized && data.state.corpusWatermark === data.state.projectionWatermark) evaluate(data, "initial", nav);
  } catch (err) {
    if (!alive(token) || nav !== navigation) return;
    $("wikiArticle").replaceChildren(); $("wikiContext").replaceChildren(); current = null; error($("wikiArticle"), err);
  }
}
export function loadWiki(profileChanged) {
  const switchedProfile = profileChanged && !!currentProfile && currentProfile !== state.profile;
  unloadWiki(); currentProfile = state.profile; controller = new AbortController();
  $("wikiSearch").oninput = () => { clearTimeout(searchTimer); searchTimer = setTimeout(search, 250); };
  $("wikiSemantic").onchange = search;
  $("wikiHome").onclick = () => { clearPoll(); navigation++; updateUrl(null, true); $("wikiSearch").value = ""; directory(); $("wikiArticle").replaceChildren(); $("wikiContext").replaceChildren(); notice($("wikiArticle"), "Choose a topic to explore its evidence and article."); };
  $("wikiMore").onclick = () => directory(directoryParent, true);
  const requested = new URLSearchParams(location.search).get("page");
  if (switchedProfile) updateUrl(null, false);
  directory();
  if (requested && !switchedProfile) openPage(requested, true, false);
  else notice($("wikiArticle"), "Choose a topic to explore its evidence and article. Unvisited topics are searchable before their first article is written.");
}
window.addEventListener("popstate", () => {
  if (state.view !== "wiki") return;
  const params = new URLSearchParams(location.search);
  if (params.get("profile") !== state.profile || params.get("view") !== "wiki") { location.reload(); return; }
  const id = params.get("page"); if (id) openPage(id, true, false); else { navigation++; clearPoll(); directory(); $("wikiArticle").replaceChildren(); $("wikiContext").replaceChildren(); }
});
document.addEventListener("visibilitychange", () => { if (!document.hidden && state.view === "wiki" && current) openPage(current.page.id, false, false); });
