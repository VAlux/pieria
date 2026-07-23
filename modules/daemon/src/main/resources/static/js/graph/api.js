// HTTP access for the graph explorer. Returns plain data; touches no DOM and holds no state.
//
// Every endpoint here returns a bounded slice — there is deliberately no "fetch the whole graph"
// call, because a real profile runs to tens of thousands of entities and edges.
import { api, apiFetch } from "../util/dom.js";

function get(profile, path, params) {
  const url = new URL(api(profile, path), location.origin);
  Object.keys(params || {}).forEach(function (key) {
    const value = params[key];
    if (value == null || value === "") return;
    if (Array.isArray(value)) value.forEach(function (v) { url.searchParams.append(key, v); });
    else url.searchParams.set(key, value);
  });
  return apiFetch(url.pathname + url.search, { headers: { Accept: "application/json" } })
    .then(function (r) {
      if (r.status === 404) throw new Error("Not found.");
      if (!r.ok) throw new Error("Request failed (" + r.status + ").");
      return r.json();
    });
}

// Landing view: profile totals, type facets, top hubs and the edges among them.
export function fetchOverview(profile, types, limit) {
  return get(profile, "/graph/overview", { types: types, limit: limit });
}

// Entity name search, most-connected first.
export function fetchSearch(profile, query, types, limit) {
  return get(profile, "/graph/search", { q: query, types: types, limit: limit });
}

// Focused subgraph: a bounded walk out from one entity.
export function fetchNeighborhood(profile, entityId, depth, types, limit) {
  return get(profile, "/graph/neighborhood", {
    entity: entityId, depth: depth, types: types, limit: limit
  });
}

// Inspector detail: one entity's relations and the memories they came from.
export function fetchEntity(profile, entityId) {
  return get(profile, "/graph/entities/" + encodeURIComponent(entityId));
}
