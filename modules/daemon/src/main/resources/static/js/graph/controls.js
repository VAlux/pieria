// Toolbar: entity search, entity-type facet filter, hop depth, and the fit/reset buttons.
//
// The type filter is a server-side filter, not a dimming effect: toggling a type refetches, so it
// changes which entities are selected out of the profile's tens of thousands. Dimming would only
// ever hide things already fetched, which is the wrong end of the problem.
import { $, el, escapeHtml } from "../util/dom.js";
import { entityTypeColor } from "../util/palette.js";
import { model, setTypes, typeFilter } from "./model.js";
import { fetchSearch } from "./api.js";

const SEARCH_DEBOUNCE_MS = 200;

let handlers = { onTypesChanged: function () {}, onFocus: function () {}, onDepthChanged: function () {} };
let searchTimer = null;

export function init(callbacks) {
  handlers = Object.assign(handlers, callbacks || {});

  $("graphSearch").addEventListener("input", function () {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(runSearch, SEARCH_DEBOUNCE_MS);
  });
  $("graphSearch").addEventListener("keydown", function (e) {
    if (e.key === "Escape") closeResults();
  });
  $("graphSearch").addEventListener("blur", function () {
    // Let a click on a result land before the list disappears.
    setTimeout(closeResults, 150);
  });

  $("graphDepth").addEventListener("change", function () {
    model.depth = Number($("graphDepth").value) || 1;
    handlers.onDepthChanged();
  });

  $("graphTypeAll").addEventListener("click", function () {
    setAllTypes(true);
  });
  $("graphTypeNone").addEventListener("click", function () {
    setAllTypes(false);
  });
}

// Render the type facet from the overview payload. Called once per profile load; user selections
// are preserved across refetches within the same profile.
export function renderFacets(facets) {
  model.facets = facets || [];
  const host = $("graphTypeList");
  host.innerHTML = "";

  // null activeTypes = never narrowed, so everything starts checked.
  const selected = model.activeTypes === null ? null : new Set(model.activeTypes);
  model.facets.forEach(function (facet) {
    const row = el("label", "graph-type-row");
    const box = el("input");
    box.type = "checkbox";
    box.value = facet.type;
    box.checked = selected === null || selected.has(facet.type);
    box.addEventListener("change", onTypeToggled);

    const swatch = el("span", "swatch");
    swatch.style.background = entityTypeColor(facet.type);

    const label = el("span", "graph-type-name", facet.type);
    const count = el("span", "graph-type-count", String(facet.count));

    row.appendChild(box);
    row.appendChild(swatch);
    row.appendChild(label);
    row.appendChild(count);
    host.appendChild(row);
  });

  $("graphTypeSummary").textContent = model.facets.length
    ? model.facets.length + (model.facets.length === 1 ? " type" : " types")
    : "no entities";
}

// The literal set of checked types. Deliberately not collapsed to "empty means all": an empty
// result here means the user unchecked everything, and the caller has to be able to tell that
// apart from everything being checked.
function currentSelection() {
  return Array.from($("graphTypeList").querySelectorAll("input[type=checkbox]"))
    .filter(function (b) { return b.checked; })
    .map(function (b) { return b.value; });
}

function onTypeToggled() {
  setTypes(currentSelection());
  handlers.onTypesChanged();
}

function setAllTypes(checked) {
  $("graphTypeList").querySelectorAll("input[type=checkbox]").forEach(function (b) {
    b.checked = checked;
  });
  setTypes(currentSelection());
  handlers.onTypesChanged();
}

// ---- search ------------------------------------------------------------------------------------

function runSearch() {
  const query = $("graphSearch").value.trim();
  if (query.length < 2) {
    closeResults();
    return;
  }
  fetchSearch(model.profile, query, typeFilter(), 15)
    .then(function (data) {
      if ($("graphSearch").value.trim() !== query) return;   // a newer keystroke won
      renderResults(data.matches || []);
    })
    .catch(function () { closeResults(); });
}

function renderResults(matches) {
  const host = $("graphSearchResults");
  host.innerHTML = "";
  if (!matches.length) {
    host.appendChild(el("div", "graph-search-empty", "No entities match."));
    host.classList.add("is-open");
    return;
  }
  matches.forEach(function (m) {
    const row = el("button", "graph-search-row");
    row.type = "button";
    const swatch = el("span", "swatch");
    swatch.style.background = entityTypeColor(m.type);
    row.appendChild(swatch);
    row.appendChild(el("span", "graph-search-name", m.name));
    const meta = el("span", "graph-search-meta");
    meta.innerHTML = escapeHtml(m.type || "unknown") + " · " + m.degree;
    row.appendChild(meta);
    row.addEventListener("click", function () {
      $("graphSearch").value = m.name;
      closeResults();
      handlers.onFocus(m.id);
    });
    host.appendChild(row);
  });
  host.classList.add("is-open");
}

function closeResults() {
  const host = $("graphSearchResults");
  host.classList.remove("is-open");
  host.innerHTML = "";
}
