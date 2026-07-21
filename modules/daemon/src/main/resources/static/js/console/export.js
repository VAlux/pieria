import { $, el, api, apiFetch } from "../util/dom.js";
import { state } from "./state.js";
import { toast } from "./toast.js";

export function exportProfile() {
  if (!state.profile) return;
  const btn = $("exportBtn");
  btn.disabled = true;
  apiFetch(api(state.profile, "/export"), { headers: { Accept: "application/x-ndjson" } })
    .then(function (r) { if (!r.ok) throw new Error("Export failed (" + r.status + ")"); return r.text(); })
    .then(function (text) {
      const blob = new Blob([text], { type: "application/x-ndjson" });
      const url = URL.createObjectURL(blob);
      const a = el("a");
      a.href = url; a.download = "pieria-" + state.profile + ".ndjson";
      document.body.appendChild(a); a.click(); a.remove();
      URL.revokeObjectURL(url);
      toast("Exported", "ok");
    })
    .catch(function (e) { toast(e.message, "err"); })
    .finally(function () { btn.disabled = false; });
}
