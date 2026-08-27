// Per-profile configuration: the whitelisted retrieval and ingestion overrides.
//
// Provenance comes from the stored override map returned by /config/detail, never from diffing
// effective against global — a profile may deliberately override a key to the global value, and
// diffing would render that as inherited and then silently drop it on the next save.
import { $, el, api, apiFetch } from "../../util/dom.js";
import { loadSchema, bySection } from "./schema.js";
import { renderFieldRow } from "./field.js";
import { createForm } from "./form.js";
import { renderChannelMix } from "./channel-mix.js";
import { toast } from "../toast.js";

const SECTION_TITLES = {
  channels: "Retrieval channels",
  graph: "Graph traversal",
  "code-graph": "Code graph",
  fusion: "Fusion and limits",
  ingestion: "Ingestion"
};

const OPEN_BY_DEFAULT = { channels: true, ingestion: true };

let form = null;
let schemaFields = null;
let layers = null;
let openSections = Object.assign({}, OPEN_BY_DEFAULT);
let focusedChannel = null;
let currentProfile = "";
let mixHost = null;
let channelFields = null;
let saveBarHost = null;

function at(tree, key) {
  const parts = key.split(".");
  let node = tree;
  for (let i = 0; i < parts.length; i++) {
    if (node == null) return undefined;
    node = node[parts[i]];
  }
  return node;
}

// Rebuild the sparse {ingestion:{}, retrieval:{}} payload the daemon whitelist accepts.
function toPayload(values) {
  const body = {};
  Object.keys(values).forEach(function (key) {
    const parts = key.split(".");
    if (!body[parts[0]]) body[parts[0]] = {};
    body[parts[0]][parts[1]] = values[key];
  });
  return body;
}

function flattenOverrides(overrides) {
  const flat = {};
  ["ingestion", "retrieval"].forEach(function (group) {
    const section = overrides ? overrides[group] : null;
    if (!section) return;
    Object.keys(section).forEach(function (key) {
      if (section[key] !== null && section[key] !== undefined) {
        flat[group + "." + key] = section[key];
      }
    });
  });
  return flat;
}

export function loadProfileConfig(profile) {
  currentProfile = profile;
  const root = $("view-profile-config");
  root.innerHTML = "";
  root.appendChild(el("div", "banner", "Loading configuration…"));

  Promise.all([loadSchema(), fetchDetail(profile)])
    .then(function (results) {
      const fields = {};
      results[0].forEach(function (field) {
        if (field.scope === "profile") fields[field.key] = field;
      });
      schemaFields = fields;
      layers = results[1];
      form = createForm({ fields: fields });
      form.load(flattenOverrides(layers.overrides), fields);
      render();
    })
    .catch(function (e) {
      root.innerHTML = "";
      root.appendChild(el("div", "banner err", e.message));
    });
}

export function unloadProfileConfig() {
  form = null;
  layers = null;
  focusedChannel = null;
  openSections = Object.assign({}, OPEN_BY_DEFAULT);
  mixHost = null;
  channelFields = null;
  saveBarHost = null;
}

function fetchDetail(profile) {
  return apiFetch(api(profile, "/config/detail"), { headers: { Accept: "application/json" } })
    .then(function (r) {
      if (!r.ok) throw new Error("Could not load configuration (" + r.status + ").");
      return r.json();
    });
}

function valueOf(key) {
  return form.isSet(key) ? form.values[key] : at(layers.global, key);
}

// Repainting the mix and the save bar in place is what lets a slider drag survive: neither of
// these lives inside the field row that owns the slider.
function paintMix() {
  if (!mixHost || !channelFields) return;
  const weights = channelFields.map(function (f) {
    return { key: f.key, label: f.key.replace("retrieval.weight-", ""), value: valueOf(f.key) };
  });
  renderChannelMix(mixHost, weights, {
    focused: focusedChannel,
    disabledNote: "A weight of 0 disables the channel; its traversal settings below are inactive.",
    onFocus: function (key) {
      focusedChannel = focusedChannel === key ? null : key;
      render();
    }
  });
}

function paintSaveBar() {
  if (!saveBarHost) return;
  form.renderSaveBar(saveBarHost, {
    endpoint: "PUT /v1/profiles/" + currentProfile + "/config",
    saveLabel: "Save overrides",
    onDiscard: function () { form.discard(); render(); },
    onSave: save
  });
}

function render() {
  const root = $("view-profile-config");
  const errors = form.errors();
  root.innerHTML = "";

  const head = el("div", "cfg-head");
  head.appendChild(el("h2", null, "Configuration"));
  head.appendChild(el("span", "cfg-scope mono", currentProfile));
  root.appendChild(head);
  root.appendChild(el("p", "cfg-lede",
    "Overrides for this profile only. Anything left alone inherits the global configuration. "
    + "The daemon accepts the retrieval and ingestion keys below and rejects everything else."));

  const total = Object.keys(schemaFields).length;
  const setCount = Object.keys(form.values).length;
  const summary = el("div", "cfg-summary");
  summary.appendChild(el("span", "cfg-dot"));
  summary.appendChild(el("span", null, setCount + " of " + total + " fields overridden"));
  summary.appendChild(el("span", "muted small", (total - setCount) + " inherited from global"));
  summary.appendChild(el("span", "spacer"));
  if (setCount > 0) {
    const resetAll = el("button", "danger", "Reset all overrides");
    resetAll.type = "button";
    resetAll.addEventListener("click", confirmResetAll);
    summary.appendChild(resetAll);
  }
  root.appendChild(summary);

  const graphOff = Number(valueOf("retrieval.weight-graph")) === 0;

  bySection(Object.values(schemaFields), "profile").forEach(function (group) {
    root.appendChild(renderSection(group, errors, graphOff));
  });

  const bar = el("div", "cfg-savebar");
  root.appendChild(bar);
  saveBarHost = bar;
  paintSaveBar();
}

function renderSection(group, errors, graphOff) {
  const section = el("section", "cfg-section");
  const inactive = group.section === "graph" && graphOff;
  const open = !!openSections[group.section];

  const head = el("button", "cfg-section-head");
  head.type = "button";
  head.setAttribute("aria-expanded", String(open));
  const chevron = el("span", "ico");
  chevron.innerHTML = '<svg width="15" height="15" viewBox="0 0 24 24"><path d="m10 6 6 6-6 6"/></svg>';
  head.appendChild(chevron);
  head.appendChild(el("span", "cfg-section-title", SECTION_TITLES[group.section] || group.section));

  const setHere = group.fields.filter(function (f) { return form.isSet(f.key); }).length;
  head.appendChild(el("span", "spacer"));
  if (setHere > 0) head.appendChild(el("span", "cfg-chip", setHere + " overridden"));
  head.addEventListener("click", function () {
    openSections[group.section] = !open;
    render();
  });
  section.appendChild(head);

  const body = el("div", "cfg-section-body");
  body.hidden = !open;

  if (group.section === "channels") {
    mixHost = el("div");
    body.appendChild(mixHost);
    channelFields = group.fields.filter(function (f) { return f.kind === "weight"; });
    paintMix();
  }

  group.fields.forEach(function (field) {
    body.appendChild(renderFieldRow(field, {
      value: valueOf(field.key),
      source: form.isSet(field.key) ? "set" : "inherited",
      sourceLabel: form.isSet(field.key)
        ? "overridden"
        : "global " + formatValue(at(layers.global, field.key)),
      error: errors[field.key],
      disabled: inactive,
      onChange: function (next) { form.set(field.key, next); render(); },
      onLiveChange: function (next) {
        form.set(field.key, next);
        paintMix();
        paintSaveBar();
      },
      onReset: function () { form.clear(field.key); render(); }
    }));
  });

  section.appendChild(body);
  return section;
}

function formatValue(value) {
  if (value === true) return "on";
  if (value === false) return "off";
  return String(value);
}

function confirmResetAll() {
  if (!window.confirm("Reset every override for " + currentProfile
    + "? All " + Object.keys(form.values).length
    + " fields fall back to the global configuration.")) return;
  form.clearAll();
  render();
}

function save() {
  const values = form.values;
  const empty = Object.keys(values).length === 0;
  const request = empty
    ? apiFetch(api(currentProfile, "/config"), { method: "DELETE" })
    : apiFetch(api(currentProfile, "/config"), {
      method: "PUT",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(toPayload(values))
    });

  request
    .then(function (r) {
      if (!r.ok) return r.text().then(function (text) { throw new Error(text || ("Save failed (" + r.status + ").")); });
      // The write succeeded, so the form is clean regardless of whether the refetch below does.
      form.commit();
      toast("Overrides saved for " + currentProfile, "ok");
      render();
      return fetchDetail(currentProfile);
    })
    .then(function (fresh) {
      layers = fresh;
      render();
    })
    .catch(function (e) {
      toast(e.message, "err");
      render();
    });
}
