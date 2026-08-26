// Tiny DOM helpers shared by the console and graph views.

export function $(id) { return document.getElementById(id); }

export function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

export function escapeHtml(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
    return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
  });
}

// ---- icons ----
// One 24px grid, one stroke weight, drawn as SVG so they scale and recolour with currentColor.
// Kept here rather than inline in each renderer so the markup and the JS-built rows agree.
const ICONS = {
  download: "M12 4v11m0 0 4-4m-4 4-4-4M5 19h14",
  trash: "M4 7h16M9 7V5h6v2m-9 0 1 13h10l1-13",
  close: "m6 6 12 12M18 6 6 18",
  search: "M16 16l4 4",
  chevronRight: "m9 6 6 6-6 6",
  activity: "M3 12h3.5l2-6.5 3.5 13 2.5-6.5H21",
  clock: "M12 12V7.5M12 12l3 2.5"
};

// Extra sub-paths for icons that are not a single stroke.
const ICON_EXTRAS = {
  search: '<circle cx="11" cy="11" r="6.5"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/>',
  spinner: '<circle cx="12" cy="12" r="9" stroke-dasharray="40 17"/>'
};

/** Build an `.ico` span wrapping a 24-grid SVG. Returns an element, ready to append. */
export function icon(name, size) {
  const px = size || 16;
  const span = el("span", "ico");
  span.setAttribute("aria-hidden", "true");
  span.innerHTML = '<svg width="' + px + '" height="' + px + '" viewBox="0 0 24 24" focusable="false">'
    + (ICON_EXTRAS[name] || "")
    + (ICONS[name] ? '<path d="' + ICONS[name] + '"/>' : "")
    + "</svg>";
  return span;
}

// Append a <dt>/<dd> pair to an existing definition list (used by the drawer and stats).
export function addRow(dl, k, v) {
  dl.appendChild(el("dt", null, k));
  dl.appendChild(el("dd", null, v));
}

// Build the per-profile REST path, e.g. api("alice", "/memories").
export function api(profile, path) {
  return "/v1/profiles/" + encodeURIComponent(profile) + path;
}

export function apiFetch(url, options) {
  const opts = Object.assign({}, options || {});
  const headers = new Headers(opts.headers || {});
  headers.set("X-Pieria-Client", "console");
  headers.set("X-Pieria-Channel", "console");
  opts.headers = headers;
  return fetch(url, opts);
}
