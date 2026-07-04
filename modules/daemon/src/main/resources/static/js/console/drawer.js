import { $, el, addRow } from "../util/dom.js";
import { typeColor } from "../util/palette.js";
import { fmtDate } from "../util/format.js";
import { forgetMemory } from "./memories.js";

let drawerMem = null;

export function openDrawer(m) {
  drawerMem = m;
  const chip = $("drawerChip");
  chip.className = "chip"; chip.textContent = m.type; chip.style.background = typeColor(m.type);
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
