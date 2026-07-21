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
