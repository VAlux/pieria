// The editable-configuration schema, fetched once and shared by both config pages.
//
// The daemon owns what is editable: keys, control kinds and tiers all come from
// /v1/config/schema, so adding a property is a resource edit on the daemon rather than a change
// here. Cached in memory for the page's lifetime — the schema only changes when the daemon does.
import { apiFetch } from "../../util/dom.js";

let cached = null;

export function loadSchema() {
  if (cached) return Promise.resolve(cached);
  return apiFetch("/v1/config/schema", { headers: { Accept: "application/json" } })
    .then(function (r) {
      if (!r.ok) throw new Error("Could not load the configuration schema (" + r.status + ").");
      return r.json();
    })
    .then(function (fields) {
      cached = fields;
      return cached;
    });
}

// Group one scope's fields into sections, preserving the schema's declaration order.
export function bySection(fields, scope) {
  const order = [];
  const groups = {};
  fields.forEach(function (field) {
    if (field.scope !== scope) return;
    if (!groups[field.section]) {
      groups[field.section] = [];
      order.push(field.section);
    }
    groups[field.section].push(field);
  });
  return order.map(function (section) {
    return { section: section, fields: groups[section] };
  });
}
