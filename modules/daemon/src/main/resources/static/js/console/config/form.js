// Dirty tracking, client-side validation and the save bar, shared by both config pages.
//
// The daemon rejects a config payload wholesale if any single value fails to bind, so the client
// refuses to send a batch it already knows is bad rather than letting the operator discover it as
// a 400 with the other edits lost.
import { el } from "../../util/dom.js";

function sameValue(a, b) {
  if (a === undefined && b === undefined) return true;
  if (a === undefined || b === undefined) return false;
  return String(a) === String(b);
}

export function validateValue(field, value) {
  if (value === undefined || value === null) return null;
  if (field.kind === "bool" || field.kind === "string" || field.kind === "secret") return null;
  if (field.kind === "enum") {
    return (field.options || []).indexOf(String(value)) >= 0
      ? null : "Must be one of " + (field.options || []).join(", ") + ".";
  }
  const text = String(value).trim();
  if (text === "") return "Must be a number.";
  const parsed = Number(text);
  if (!isFinite(parsed)) {
    return "Must be a number. The daemon rejects the whole payload if any value fails to bind.";
  }
  if (parsed < 0) return "Must be zero or greater.";
  if (field.kind === "int" && text.indexOf(".") >= 0) return "Must be a whole number.";
  return null;
}

export function createForm(options) {
  const state = { baseline: {}, values: {}, fields: options.fields || {} };

  return {
    get values() { return state.values; },

    load: function (setValues, fields) {
      state.baseline = Object.assign({}, setValues);
      state.values = Object.assign({}, setValues);
      if (fields) state.fields = fields;
    },

    set: function (key, value) { state.values[key] = value; },

    clear: function (key) { delete state.values[key]; },

    clearAll: function () { state.values = {}; },

    discard: function () { state.values = Object.assign({}, state.baseline); },

    commit: function () { state.baseline = Object.assign({}, state.values); },

    isSet: function (key) {
      return Object.prototype.hasOwnProperty.call(state.values, key);
    },

    changedKeys: function () {
      const seen = {};
      Object.keys(state.values).forEach(function (k) { seen[k] = 1; });
      Object.keys(state.baseline).forEach(function (k) { seen[k] = 1; });
      return Object.keys(seen).filter(function (k) {
        const inNew = Object.prototype.hasOwnProperty.call(state.values, k);
        const inOld = Object.prototype.hasOwnProperty.call(state.baseline, k);
        if (inNew !== inOld) return true;
        return !sameValue(state.values[k], state.baseline[k]);
      });
    },

    errors: function () {
      const found = {};
      const self = this;
      Object.keys(state.values).forEach(function (key) {
        const field = state.fields[key];
        if (!field) return;
        const message = validateValue(field, state.values[key]);
        if (message) found[key] = message;
      });
      return found;
    },

    renderSaveBar: function (container, opts) {
      const changed = this.changedKeys();
      const errors = this.errors();
      const blocked = Object.keys(errors).length > 0;
      container.innerHTML = "";
      // Own the styling hook rather than trusting the caller to have classed the container: a
      // save bar rendered anywhere always looks like a save bar.
      container.classList.add("cfg-savebar");
      container.hidden = changed.length === 0;
      if (!changed.length) return;

      container.appendChild(el("span", "cfg-dot", ""));
      container.appendChild(el("span", null,
        changed.length === 1 ? "1 change" : changed.length + " changes"));
      container.appendChild(el("span", "endpoint", opts.endpoint));
      const spacer = el("span", "spacer");
      container.appendChild(spacer);

      if (blocked) {
        container.appendChild(el("span", "blocked", "Fix the highlighted field first"));
      }

      const discard = el("button", null, "Discard");
      discard.type = "button";
      discard.addEventListener("click", opts.onDiscard);
      container.appendChild(discard);

      const save = el("button", "primary", opts.saveLabel || "Save");
      save.type = "button";
      save.disabled = blocked;
      save.addEventListener("click", opts.onSave);
      container.appendChild(save);
    }
  };
}
