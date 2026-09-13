// Date / number formatting helpers.

export function relTime(iso) {
  if (!iso) return "";
  const d = new Date(iso), now = Date.now(), s = Math.round((now - d.getTime()) / 1000);
  if (isNaN(s)) return iso;
  if (s < 60) return s + "s ago";
  const m = Math.round(s / 60);
  if (m < 60) return m + "m ago";
  const h = Math.round(m / 60);
  if (h < 24) return h + "h ago";
  const days = Math.round(h / 24);
  if (days < 30) return days + "d ago";
  return d.toLocaleDateString();
}

export function fmtDate(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return isNaN(d) ? iso : d.toLocaleString();
}

export function fmtInt(n) { return (n == null ? 0 : n).toLocaleString(); }

/** Format an instant for a datetime-local control without losing the browser's local zone. */
export function localDateTimeValue(iso) {
  if (!iso) return "";
  const date = new Date(iso);
  if (isNaN(date)) return "";
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 16);
}

/** Convert a datetime-local value to the offset-bearing UTC form required by temporal APIs. */
export function isoInstant(value) {
  if (!value) return null;
  const date = new Date(value);
  return isNaN(date) ? null : date.toISOString();
}
