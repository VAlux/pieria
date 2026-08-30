// Process-global configuration: what every profile inherits and no profile can override.
//
// Grouped by what applying a change costs, not by topic. The daemon binds most of these once at
// startup, so a save never changes the running process the way a live-reloaded setting would — the
// page says as much and hands over `pieria daemon restart` rather than offering a button the
// browser cannot honour.
import { $, el, apiFetch } from "../../util/dom.js";
import { renderFieldRow } from "./field.js";
import { createForm } from "./form.js";
import { toast } from "../toast.js";

// Two tiers, not three. There is deliberately no "applies immediately" group: the daemon binds
// pieria.properties once at startup via spring.config.import and never re-reads it, so no global
// key takes effect without a restart. A tier claiming otherwise would be the same lie as a
// restart button the browser cannot honour.
const TIERS = [
  { id: "restart", title: "Takes effect after a restart", note: "bound once at startup" },
  { id: "locked", title: "Locked", note: "changing these invalidates stored data" }
];

const LOCKED_WARNING = "The embedding dimension fixes the width of the memories_vec column — a "
  + "mismatch does not just leave stale vectors, it makes the daemon refuse to start on the next "
  + "boot, and there is no console left to revert from at that point. Recovery means hand-editing "
  + "pieria.properties back to the working value and restarting. Moving the database path points "
  + "the daemon at a different store; the existing one is left behind.";

let form = null;
let snapshot = null;
let fieldsByKey = null;
let unlocked = false;

export function loadGlobalConfig() {
  const root = $("view-global-config");
  root.innerHTML = "";
  root.appendChild(el("div", "banner", "Loading configuration…"));

  apiFetch("/v1/config", { headers: { Accept: "application/json" } })
    .then(function (r) {
      if (!r.ok) throw new Error("Could not load configuration (" + r.status + ").");
      return r.json();
    })
    .then(function (body) {
      snapshot = body;
      fieldsByKey = {};
      const setValues = {};
      body.entries.forEach(function (entry) {
        fieldsByKey[entry.key] = entry;
        if (entry.provenance === "set") setValues[entry.key] = entry["file-value"];
      });
      form = createForm({ fields: fieldsByKey });
      form.load(setValues, fieldsByKey);
      render();
    })
    .catch(function (e) {
      root.innerHTML = "";
      root.appendChild(el("div", "banner err", e.message));
    });
}

export function unloadGlobalConfig() {
  form = null;
  snapshot = null;
  unlocked = false;
}

/** Unsaved edit count, so the router can confirm before tearing this view down mid-edit. */
export function pendingChangeCount() {
  return form ? form.changedKeys().length : 0;
}

function valueOf(entry) {
  return form.isSet(entry.key) ? form.values[entry.key] : entry.value;
}

// Group one tier's entries by section, preserving the order sections first appear. A flat list of
// all 27 restart-tier keys reads as noise; the schema's section already says which of seven
// functional groups (observability, throughput, provider, models, daemon, storage, traces) a key
// belongs to, so the page should say so too rather than dropping that structure on the floor.
function groupEntriesBySection(entries) {
  const order = [];
  const groups = {};
  entries.forEach(function (entry) {
    if (!groups[entry.section]) {
      groups[entry.section] = [];
      order.push(entry.section);
    }
    groups[entry.section].push(entry);
  });
  return order.map(function (section) { return { section: section, entries: groups[section] }; });
}

function render() {
  const root = $("view-global-config");
  const errors = form.errors();
  root.innerHTML = "";

  const head = el("div", "cfg-head");
  head.appendChild(el("h2", null, "Configuration"));
  head.appendChild(el("span", "cfg-scope global mono", "daemon"));
  root.appendChild(head);
  root.appendChild(el("p", "cfg-lede",
    "Process-global settings. Every profile inherits these, and a profile can only override the "
    + "retrieval and ingestion subset. Grouped by what applying a change costs you."));

  const file = el("div", "cfg-summary");
  file.appendChild(el("span", "muted small", "Written to"));
  file.appendChild(el("span", "mono small", snapshot.configFile));
  root.appendChild(file);

  const changed = form.changedKeys();
  const pendingKeys = snapshot.entries
    .filter(function (entry) { return entry["restart-pending"]; })
    .map(function (entry) { return entry.key; });

  TIERS.forEach(function (tier) {
    const entries = snapshot.entries.filter(function (entry) { return entry.tier === tier.id; });
    if (!entries.length) return;
    root.appendChild(renderTier(tier, entries, errors, changed, pendingKeys));
  });

  const bar = el("div", "cfg-savebar");
  root.appendChild(bar);
  form.renderSaveBar(bar, {
    endpoint: "PUT /v1/config",
    saveLabel: "Save configuration",
    onDiscard: function () { form.discard(); render(); },
    onSave: save
  });
}

function renderTier(tier, entries, errors, changed, pendingKeys) {
  const section = el("section", "cfg-section " + tier.id);

  const head = el("div", "cfg-section-head");
  head.appendChild(el("span", "cfg-section-title", tier.title));
  head.appendChild(el("span", "cfg-section-note", tier.note));
  head.appendChild(el("span", "spacer"));
  const setHere = entries.filter(function (e) { return form.isSet(e.key); }).length;
  if (setHere > 0) head.appendChild(el("span", "cfg-chip", setHere + " set"));
  section.appendChild(head);

  if (tier.id === "restart") {
    // A key can be BOTH locally edited and already flagged restart-pending by the server, so
    // this must be a union of keys — summing the two lists counts that key twice.
    const pendingHereKeys = entries
      .map(function (entry) { return entry.key; })
      .filter(function (key) { return changed.indexOf(key) >= 0 || pendingKeys.indexOf(key) >= 0; });
    const count = pendingHereKeys.length;
    if (count > 0) {
      const banner = el("div", "cfg-banner warn");
      banner.appendChild(el("div", null,
        count === 1 ? "1 change needs a restart" : count + " changes need a restart"));
      banner.appendChild(el("div", "muted small",
        "Saving writes the value now; the running daemon keeps the old one until it restarts. "
        + "The console cannot restart it — run this yourself:"));
      banner.appendChild(el("code", null, snapshot.restartCommand));
      section.appendChild(banner);
    }
  }

  if (tier.id === "locked") {
    const gate = el("div", "cfg-banner danger");
    gate.appendChild(el("div", null, LOCKED_WARNING));
    const button = el("button", null, unlocked ? "Lock again" : "Unlock to edit");
    button.type = "button";
    button.addEventListener("click", function () {
      if (unlocked) {
        const stranded = entries.filter(function (entry) { return changed.indexOf(entry.key) >= 0; });
        // Re-locking would leave these edits unreachable — the row goes read-only and its reset
        // button disappears — and a save while locked is refused. Discard them, but say so first.
        if (stranded.length && !window.confirm("Lock these settings again? Your unsaved changes to "
          + stranded.length + " locked setting(s) will be discarded.")) {
          return;
        }
        stranded.forEach(function (entry) { form.revert(entry.key); });
        unlocked = false;
        render();
        return;
      }
      if (window.confirm("Unlock these settings? Changing them still requires a restart and a "
        + "full re-embed of every memory in the store.")) {
        unlocked = true;
        render();
      }
    });
    gate.appendChild(button);
    section.appendChild(gate);
  }

  const body = el("div", "cfg-section-body");
  const groups = groupEntriesBySection(entries);
  groups.forEach(function (group) {
    if (groups.length > 1) body.appendChild(el("div", "section", group.section));
    group.entries.forEach(function (entry) {
      const disabled = tier.id === "locked" && !unlocked;
      const pending = pendingKeys.indexOf(entry.key) >= 0;
      body.appendChild(renderFieldRow(entry, {
        value: valueOf(entry),
        source: form.isSet(entry.key) ? "set" : "default",
        sourceLabel: form.isSet(entry.key)
          ? (pending ? "restart pending" : "set")
          : "default",
        error: errors[entry.key],
        disabled: disabled,
        onChange: function (next) { form.set(entry.key, next); render(); },
        onReset: function () { form.clear(entry.key); render(); }
      }));
    });
  });
  section.appendChild(body);
  return section;
}

function save() {
  const values = {};
  // A key that was set and has been reset is sent as null, which clears it on the daemon.
  form.changedKeys().forEach(function (key) {
    values[key] = form.isSet(key) ? String(form.values[key]) : null;
  });

  apiFetch("/v1/config", {
    method: "PUT",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ values: values, acknowledgeDestructive: unlocked })
  })
    .then(function (r) {
      if (!r.ok) return r.text().then(function (text) { throw new Error(text || ("Save failed (" + r.status + ").")); });
      return r.json();
    })
    .then(function (result) {
      const needsRestart = (result["restart-required"] || []).length;
      toast(needsRestart
        ? "Saved. " + needsRestart + " setting(s) apply after a restart."
        : "Configuration saved.", "ok");
      loadGlobalConfig();
    })
    .catch(function (e) { toast(e.message, "err"); });
}
