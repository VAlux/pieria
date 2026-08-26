import { $, el, addRow } from "../util/dom.js";
import { typeColor, typeTint, tint } from "../util/palette.js";
import { fmtDate } from "../util/format.js";
import { forgetMemory } from "./memories.js";

let drawerMem = null;

export function openDrawer(m) {
  drawerMem = m;
  const chip = $("drawerChip");
  chip.className = "chip";
  chip.textContent = m.type;
  chip.style.color = typeColor(m.type);
  chip.style.background = typeTint(m.type);
  $("drawerDelete").style.display = m.superseded ? "none" : "";

  let payload = m.payload;
  try { payload = JSON.stringify(JSON.parse(m.payload || "{}"), null, 2); } catch (e) { /* leave as-is */ }

  const body = $("drawerBody");
  body.innerHTML = "";
  body.appendChild(kv("Content", m.content || ""));
  appendCode(body, "Payload", payload || "{}");
  const meta = el("dl", "kv");
  addRow(meta, "Type", m.type);
  addRow(meta, "Topic key", m.topicKey || "—");
  addRow(meta, "Session", m.sessionId || "—");
  addRow(meta, "Created", fmtDate(m.createdAt));
  addRow(meta, "Superseded", m.superseded ? "yes" : "no");
  addRow(meta, "Id", m.id);
  body.appendChild(meta);

  $("drawer").classList.add("open");
  $("drawerBackdrop").classList.add("open");
}

export function closeDrawer() {
  $("drawer").classList.remove("open");
  $("drawerBackdrop").classList.remove("open");
  drawerMem = null;
}

export function openAuditDrawer(e) {
  drawerMem = null;
  const chip = $("drawerChip");
  chip.className = "chip";
  chip.textContent = e.operation;
  const outcomeColor = e.outcome === "success" ? "#3fb950"
    : e.outcome === "cancelled" ? "#8b98a5" : "#f85149";
  chip.style.color = outcomeColor;
  chip.style.background = tint(outcomeColor, .14);
  $("drawerDelete").style.display = "none";

  const body = $("drawerBody");
  body.innerHTML = "";
  const meta = el("dl", "kv");
  addRow(meta, "Outcome", e.outcome);
  addRow(meta, "HTTP", e.httpStatus == null ? "—" : e.httpStatus);
  addRow(meta, "Duration", e.durationMs + " ms");
  addRow(meta, "Caller", e.client + (e.harness ? " · " + e.harness : "") + " · " + e.channel);
  addRow(meta, "Completed", fmtDate(e.completedAt));
  addRow(meta, "Request id", e.requestId);
  addRow(meta, "Parent request", e.parentRequestId || "—");
  addRow(meta, "Task", e.taskId || "—");
  addRow(meta, "Session", e.sessionId || "—");
  addRow(meta, "Resource", e.resourceId || "—");
  addRow(meta, "Route", [e.method, e.path, e.queryString ? "?" + e.queryString : ""].filter(Boolean).join(" "));
  addRow(meta, "Remote", e.remoteAddress || "—");
  addRow(meta, "Client version", e.clientVersion || "—");
  addRow(meta, "Server version", e.serverVersion || "—");
  body.appendChild(meta);

  if (e.errorMessage) body.appendChild(kv("Error", (e.errorKind || "failure") + ": " + e.errorMessage));
  appendCode(body, "Metadata", pretty(e.metadata || "{}"));
  appendCode(body, "Request · " + bytes(e.requestBytes, e.requestTruncated) + " · sha256 " + e.requestSha256,
    pretty(e.requestBody || ""));
  appendCode(body, "Response · " + bytes(e.responseBytes, e.responseTruncated) + " · sha256 " + e.responseSha256,
    pretty(e.responseBody || ""));

  $("drawer").classList.add("open");
  $("drawerBackdrop").classList.add("open");
}

// Forget whatever memory the drawer is currently showing (wired to the drawer's delete button).
export function forgetDrawerMemory() {
  if (drawerMem) forgetMemory(drawerMem);
}

function kv(label, value) {
  const d = el("dl", "kv");
  d.appendChild(el("dt", null, label));
  d.appendChild(el("dd", null, value));
  return d;
}

function appendCode(container, label, value) {
  const d = el("dl", "kv");
  d.appendChild(el("dt", null, label));
  const dd = el("dd");
  dd.appendChild(el("pre", "code", value));
  d.appendChild(dd);
  container.appendChild(d);
}

function pretty(value) {
  if (!value) return "(empty)";
  try { return JSON.stringify(JSON.parse(value), null, 2); } catch (e) { return value; }
}

function bytes(count, truncated) {
  return Number(count || 0).toLocaleString() + " bytes" + (truncated ? " · truncated" : "");
}
