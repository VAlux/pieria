import { $, el } from "../util/dom.js";
import { state } from "./state.js";
import { renderBanner, loadActiveView, syncUrl } from "./router.js";

const profileSelect = () => $("profileSelect");

// Populate the selector from /v1/profiles and select `preferred` (falling back to the first).
export function loadProfiles(preferred) {
  const sel = profileSelect();
  sel.disabled = true;
  return fetch("/v1/profiles", { headers: { Accept: "application/json" } })
    .then(function (r) { if (!r.ok) throw new Error("Request failed (" + r.status + ")."); return r.json(); })
    .then(function (data) {
      const profiles = (data.profiles || []).slice().sort(function (a, b) {
        return a.name.localeCompare(b.name);
      });
      if (!profiles.length) {
        sel.innerHTML = '<option value="">No profiles</option>';
        renderBanner($("memList"), "No profiles found. Ingest or store a memory first.");
        return;
      }
      sel.innerHTML = "";
      profiles.forEach(function (p) {
        const opt = el("option");
        opt.value = p.name;
        opt.textContent = p.name + " (" + p.memoryCount + ")";
        sel.appendChild(opt);
      });
      sel.disabled = false;
      const chosen = (preferred && profiles.some(function (p) {
        return p.name === preferred;
      })) ? preferred : profiles[0].name;
      selectProfile(chosen);
    })
    .catch(function (e) {
      sel.innerHTML = '<option value="">Unavailable</option>';
      renderBanner($("memList"), e.message, true);
    });
}

// Best-effort re-sync of the selector's per-profile counts after a mutation.
export function refreshProfileCounts() {
  const sel = profileSelect();
  fetch("/v1/profiles", { headers: { Accept: "application/json" } })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (data) {
      if (!data) return;
      (data.profiles || []).forEach(function (p) {
        for (let i = 0; i < sel.options.length; i++) {
          if (sel.options[i].value === p.name) {
            sel.options[i].textContent = p.name + " (" + p.memoryCount + ")";
          }
        }
      });
    }).catch(function () {});
}

export function selectProfile(profile) {
  if (!profile) return;
  const sel = profileSelect();
  state.profile = profile;
  sel.value = profile;
  $("profileLabel").textContent = "· " + profile;
  syncUrl();
  loadActiveView(true);   // reload whatever view is active
}
