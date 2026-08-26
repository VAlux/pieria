import { $, el, apiFetch } from "../util/dom.js";
import { state } from "./state.js";
import { renderBanner, loadActiveView, syncUrl } from "./router.js";

const profileList = () => $("profileList");

function sortedProfiles(data) {
  return (data.profiles || []).slice().sort(function (a, b) {
    return a.name.localeCompare(b.name);
  });
}

function renderProfileState(message, isError) {
  const list = profileList();
  list.innerHTML = "";
  const row = el("li", "side-panel-status" + (isError ? " err" : ""), message);
  list.appendChild(row);
}

function renderProfiles(profiles) {
  const list = profileList();
  list.innerHTML = "";
  profiles.forEach(function (profile) {
    const row = el("li");
    const button = el("button", "side-panel-item");
    button.type = "button";
    button.dataset.profile = profile.name;
    button.title = profile.name + " (" + profile.memoryCount + ")";
    // Rail marks the active profile; the count is a tabular column rather than part of the label,
    // so a scan down the list reads as names on the left and magnitudes on the right.
    button.appendChild(el("span", "side-panel-rail"));
    button.appendChild(el("span", "side-panel-name", profile.name));
    button.appendChild(el("span", "side-panel-count mono num", String(profile.memoryCount)));
    if (profile.name === state.profile) {
      button.classList.add("active");
      button.setAttribute("aria-current", "page");
    }
    row.appendChild(button);
    list.appendChild(row);
  });
}

function markSelected(profile) {
  profileList().querySelectorAll("button[data-profile]").forEach(function (button) {
    const selected = button.dataset.profile === profile;
    button.classList.toggle("active", selected);
    if (selected) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  });
}

// Populate the panel list from /v1/profiles and select `preferred` (falling back to the first).
export function loadProfiles(preferred) {
  renderProfileState("Loading profiles…");
  return apiFetch("/v1/profiles", { headers: { Accept: "application/json" } })
    .then(function (r) { if (!r.ok) throw new Error("Request failed (" + r.status + ")."); return r.json(); })
    .then(function (data) {
      const profiles = sortedProfiles(data);
      if (!profiles.length) {
        renderProfileState("No profiles");
        renderBanner($("memList"), "No profiles found. Ingest or store a memory first.");
        return;
      }
      const chosen = (preferred && profiles.some(function (p) {
        return p.name === preferred;
      })) ? preferred : profiles[0].name;
      renderProfiles(profiles);
      selectProfile(chosen);
    })
    .catch(function (e) {
      renderProfileState("Profiles unavailable", true);
      renderBanner($("memList"), e.message, true);
    });
}

// Best-effort re-sync of the panel's per-profile counts after a mutation.
export function refreshProfileCounts() {
  apiFetch("/v1/profiles", { headers: { Accept: "application/json" } })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (data) {
      if (!data) return;
      const profiles = sortedProfiles(data);
      if (profiles.length) renderProfiles(profiles);
      else renderProfileState("No profiles");
    }).catch(function () {});
}

export function selectProfile(profile) {
  if (!profile) return;
  state.profile = profile;
  markSelected(profile);
  $("profileLabel").textContent = "· " + profile;
  syncUrl();
  loadActiveView(true);   // reload whatever view is active
}
