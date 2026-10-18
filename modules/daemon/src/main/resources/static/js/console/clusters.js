import { $, el, api, apiFetch } from "../util/dom.js";
import { state } from "./state.js";

let currentId = null, epoch = 0, timer = null, wired = false;

async function request(path, options = {}) {
  const response = await apiFetch(api(state.profile, path), {
    cache: "no-store", ...options,
    headers: { Accept: "application/json", ...(options.headers || {}) }
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || body.code || "Cluster request failed");
  return body;
}

function button(label, action) {
  const node = el("button", "", label);
  node.type = "button";
  node.addEventListener("click", action);
  return node;
}

function notice(host, message) { host.replaceChildren(el("p", "", message)); }

async function mutate(path, body) {
  try {
    const result = await request(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    await loadClusters(false);
    if (result.id && !result.state) await openCluster(result.id);
    return result;
  } catch (error) {
    notice($("clusterRunStatus"), error.message);
    return null;
  }
}

function memberInput() {
  const raw = window.prompt("Member: memory:ID or cluster:ID");
  if (!raw) return null;
  const colon = raw.indexOf(":");
  const kind = raw.slice(0, colon), id = raw.slice(colon + 1).trim();
  if (!["memory", "cluster"].includes(kind) || !id) {
    notice($("clusterRunStatus"), "Use memory:ID or cluster:ID.");
    return null;
  }
  return { kind, id };
}

function memberKey(member) { return member.kind + ":" + member.id; }

function renderHistory(host, history) {
  const section = el("section", "cluster-section");
  section.appendChild(el("h3", "", "Relationship history"));
  history.forEach((revision, index) => {
    const previous = history[index + 1];
    const row = el("details", "");
    row.appendChild(el("summary", "", "Revision " + revision.revision + " · " + revision.reason + " · " + revision.recordedAt));
    row.appendChild(el("p", "", revision.title + " — " + revision.scope + " (" + revision.state + ")"));
    if (previous) {
      const before = new Set(previous.members.map(memberKey));
      const after = new Set(revision.members.map(memberKey));
      const added = [...after].filter(id => !before.has(id));
      const removed = [...before].filter(id => !after.has(id));
      if (added.length) row.appendChild(el("p", "", "Added: " + added.join(", ")));
      if (removed.length) row.appendChild(el("p", "", "Removed: " + removed.join(", ")));
    }
    section.appendChild(row);
  });
  host.appendChild(section);
}

async function openCluster(id) {
  currentId = id;
  const token = ++epoch, profile = state.profile;
  const host = $("clusterDetail");
  notice(host, "Loading cluster…");
  const url = new URL(location);
  url.searchParams.set("cluster", id);
  history.replaceState(null, "", url);
  try {
    const base = "/clusters/" + encodeURIComponent(id);
    const [detail, originals, synthesis] = await Promise.all([
      request(base), request(base + "/originals?limit=100"),
      request(base + "/synthesis").catch(() => null)
    ]);
    if (token !== epoch || profile !== state.profile || state.view !== "clusters") return;
    host.replaceChildren();
    host.appendChild(el("h2", "", detail.cluster.title));
    host.appendChild(el("p", "", detail.cluster.scope));
    host.appendChild(el("p", "cluster-meta", detail.cluster.state + " · " + detail.cluster.freshness +
      " · " + detail.cluster.directCount + " direct members · " + detail.cluster.distinctOriginalCount + " distinct originals"));

    const parents = el("section", "cluster-section");
    parents.appendChild(el("h3", "", "Also belongs to"));
    if (!detail.parents.length) parents.appendChild(el("p", "", "No parent cluster"));
    detail.parents.forEach(parent => parents.appendChild(button(parent, () => openCluster(parent))));
    host.appendChild(parents);

    const children = el("section", "cluster-section");
    children.appendChild(el("h3", "", "Direct members"));
    const originalsById = new Map(originals.map(source => [source.memoryId, source]));
    detail.members.forEach(member => {
      const row = el("div", "cluster-member");
      if (member.kind === "cluster") {
        row.appendChild(button("Cluster " + member.id, () => openCluster(member.id)));
        const nested = el("div", "cluster-meta");
        row.appendChild(button("Expand children", async () => {
          try {
            const child = await request("/clusters/" + encodeURIComponent(member.id));
            nested.replaceChildren();
            child.members.forEach(item => nested.appendChild(el("div", "", memberKey(item))));
          } catch (error) { nested.textContent = error.message; }
        }));
        row.appendChild(nested);
      }
      else row.appendChild(el("span", "", originalsById.get(member.id)?.content || "Memory " + member.id));
      children.appendChild(row);
    });
    host.appendChild(children);

    const claims = el("section", "cluster-section");
    claims.appendChild(el("h3", "", "Synthesis and source support"));
    if (!synthesis) claims.appendChild(el("p", "", "No eligible synthesis is available."));
    else synthesis.claims.forEach(claim => {
      const row = el("div", "cluster-claim");
      row.appendChild(el("div", "", claim.text));
      row.appendChild(el("small", "", "Supports: " + claim.supports.map(source => source.memoryId).join(", ")));
      row.appendChild(el("small", "", "Covered details: " + claim.covers.map(source => source.memoryId).join(", ")));
      claims.appendChild(row);
    });
    host.appendChild(claims);

    const sources = el("section", "cluster-section");
    sources.appendChild(el("h3", "", "Original evidence"));
    originals.forEach(source => {
      const row = el("details", "");
      row.appendChild(el("summary", "", source.content.slice(0, 120)));
      row.appendChild(el("p", "", source.content));
      row.appendChild(el("small", "", "Assertion " + source.assertionRevisionId));
      sources.appendChild(row);
    });
    if (detail.cluster.distinctOriginalCount > originals.length)
      sources.appendChild(el("p", "cluster-meta", "Showing the first 100 originals. Use the paginated API for the rest."));
    host.appendChild(sources);
    renderHistory(host, detail.history);

    const actions = el("div", "cluster-actions");
    const revision = detail.cluster.revision;
    if (detail.cluster.state === "active") {
      actions.appendChild(button("Rename", async () => {
        const title = window.prompt("Cluster title", detail.cluster.title);
        if (!title) return;
        const scope = window.prompt("Scope", detail.cluster.scope);
        if (!scope) return;
        await mutate(base + "/rename", { expectedRevision: revision, title, scope });
      }));
      actions.appendChild(button("Attach", async () => {
        const member = memberInput(); if (member) await mutate(base + "/members", { expectedRevision: revision, member });
      }));
      actions.appendChild(button("Detach", async () => {
        const member = memberInput(); if (member) await mutate(base + "/members/detach", { expectedRevision: revision, member });
      }));
      actions.appendChild(button("Regenerate", () => mutate(base + "/regenerate", { expectedRevision: revision })));
      actions.appendChild(button("Dissolve", async () => {
        if (window.confirm("Dissolve this cluster? Its original memories and other memberships remain."))
          await mutate(base + "/dissolve", { expectedRevision: revision });
      }));
    } else actions.appendChild(button("Restore", async () => {
      const sourceRevision = Number(window.prompt("Membership revision to restore", "1"));
      if (Number.isInteger(sourceRevision) && sourceRevision > 0)
        await mutate(base + "/restore", { expectedRevision: revision, sourceRevision });
    }));
    host.appendChild(actions);
    document.querySelectorAll(".cluster-row").forEach(row => row.classList.toggle("active", row.dataset.id === id));
  } catch (error) {
    if (token === epoch) notice(host, error.message);
  }
}

async function runStatus() {
  try {
    const runs = await request("/clustering/runs?limit=1");
    const host = $("clusterRunStatus"); host.replaceChildren();
    if (!runs.length) return;
    const run = runs[0];
    host.appendChild(el("span", "cluster-meta", "Run " + run.state + " · " + run.id));
    if (["running", "queued", "waiting"].includes(run.state))
      host.appendChild(button("Pause", async () => { await mutate("/clustering/runs/" + run.id + "/pause", {}); await runStatus(); }));
    if (["paused", "waiting"].includes(run.state))
      host.appendChild(button("Resume", async () => { await mutate("/clustering/runs/" + run.id + "/resume", {}); await runStatus(); }));
    if (!["completed", "completed_with_errors", "cancelled", "failed"].includes(run.state))
      host.appendChild(button("Cancel", async () => { await mutate("/clustering/runs/" + run.id + "/cancel", {}); await runStatus(); }));
  } catch (error) { notice($("clusterRunStatus"), error.message); }
}

export async function loadClusters(profileChanged) {
  if (!wired) {
    wired = true;
    $("clusterSearch").addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(() => loadClusters(false), 250); });
    $("clusterRunStart").addEventListener("click", async () => { await mutate("/clustering/runs", {}); await runStatus(); });
    $("clusterCreate").addEventListener("click", async () => {
      const title = window.prompt("Cluster title"); if (!title) return;
      const scope = window.prompt("Cluster scope"); if (!scope) return;
      const raw = window.prompt("Direct members, comma separated: memory:ID or cluster:ID", "");
      if (raw === null) return;
      const members = raw.split(",").map(value => value.trim()).filter(Boolean).map(value => {
        const colon = value.indexOf(":"); return { kind: value.slice(0, colon), id: value.slice(colon + 1) };
      });
      await mutate("/clusters", { title, scope, members });
    });
  }
  if (profileChanged) { currentId = null; epoch++; }
  const profile = state.profile, token = ++epoch;
  const query = $("clusterSearch").value.trim();
  const path = "/clusters?limit=100" + (query ? "&q=" + encodeURIComponent(query) : "");
  try {
    const clusters = await request(path);
    if (token !== epoch || profile !== state.profile || state.view !== "clusters") return;
    const list = $("clusterList"); list.replaceChildren();
    if (!clusters.length) list.appendChild(el("p", "", "No clusters yet. Start a run or create one through the API."));
    clusters.forEach(cluster => {
      const row = button(cluster.title, () => openCluster(cluster.id));
      row.className = "cluster-row";
      row.dataset.id = cluster.id;
      row.appendChild(el("div", "cluster-meta", cluster.directCount + " direct · " +
        cluster.distinctOriginalCount + " originals · " + cluster.freshness));
      list.appendChild(row);
    });
    await runStatus();
    const selected = currentId || new URLSearchParams(location.search).get("cluster");
    if (selected && clusters.some(cluster => cluster.id === selected)) await openCluster(selected);
    else if (!currentId) notice($("clusterDetail"), "Select a cluster to inspect its claims, evidence, and relationships.");
  } catch (error) {
    if (token === epoch) notice($("clusterList"), error.message);
  }
}
