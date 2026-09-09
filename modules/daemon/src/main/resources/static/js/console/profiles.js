import { $, el, apiFetch } from "../util/dom.js";
import { state } from "./state.js";
import { renderBanner, loadActiveView, syncUrl } from "./router.js";
import { unloadProfileConfig } from "./config/profile.js";
import { toast } from "./toast.js";

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

function syncProfileActions(profile, memoryCount) {
  const config = $("profileConfigBtn");
  const remove = $("deleteProfileBtn");
  const selected = Boolean(profile);
  config.disabled = !selected;
  remove.disabled = !selected;
  config.title = selected ? "Configure profile " + profile : "No profile selected";
  config.setAttribute("aria-label", selected ? "Configure profile " + profile : "Configure profile");
  remove.title = selected ? "Delete profile " + profile : "No profile selected";
  remove.setAttribute("aria-label", selected ? "Delete profile " + profile : "Delete profile");
  remove.dataset.deleteProfile = profile || "";
  remove.dataset.memoryCount = String(memoryCount || 0);
}

function renderProfiles(profiles) {
  const list = profileList();
  list.innerHTML = "";
  profiles.forEach(function (profile) {
    const row = el("li");
    const button = el("button", "side-panel-item");
    button.type = "button";
    button.dataset.profile = profile.name;
    button.dataset.memoryCount = String(profile.memoryCount);
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
  const selected = profiles.find(function (profile) { return profile.name === state.profile; });
  syncProfileActions(selected ? selected.name : "", selected ? selected.memoryCount : 0);
}

function markSelected(profile) {
  let memoryCount = 0;
  profileList().querySelectorAll("button[data-profile]").forEach(function (button) {
    const selected = button.dataset.profile === profile;
    button.classList.toggle("active", selected);
    if (selected) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
    if (selected) memoryCount = Number(button.dataset.memoryCount) || 0;
  });
  syncProfileActions(profile, memoryCount);
}

// Populate the panel list from /v1/profiles and select `preferred` (falling back to the first).
export function loadProfiles(preferred) {
  renderProfileState("Loading profiles…");
  return apiFetch("/v1/profiles", { headers: { Accept: "application/json" } })
    .then(function (r) { if (!r.ok) throw new Error("Request failed (" + r.status + ")."); return r.json(); })
    .then(function (data) {
      const profiles = sortedProfiles(data);
      if (!profiles.length) {
        clearProfileSelection();
        renderProfileState("No profiles");
        renderBanner($("memList"), "No profiles found. Ingest or store a memory first.");
        // A non-profile-scoped view still has to load here. loadActiveView dispatches
        // global-config before its profile guard, and returns early for everything else.
        loadActiveView(false);
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

// Drop the selection when the profile it names is gone. Leaving state.profile pointing at a
// deleted profile would send the next fetch of every view to a 404.
function clearProfileSelection() {
  state.profile = "";
  $("profileLabel").textContent = "";
  syncProfileActions("", 0);
  // profile-config is scoped to the profile that just disappeared; without this its form stays on
  // screen offering to save overrides to a profile the daemon no longer has.
  if (state.view === "profile-config") {
    unloadProfileConfig();
    renderBanner($("view-profile-config"), "No profile selected.");
  }
  syncUrl();
}

/**
 * Delete a profile and every memory it owns. Unlike forget, which supersedes, the endpoint is a
 * hard physical delete with no undo — so the dialog names both the profile and the size of what is
 * about to go, and the panel is re-resolved from the daemon rather than patched locally.
 */
export function deleteProfile(name, memoryCount) {
  const count = Number.isFinite(memoryCount) ? memoryCount : 0;
  const memories = count === 1 ? "1 memory" : count + " memories";
  if (!window.confirm('Delete profile "' + name + '" and all ' + memories + " it owns?\n\n"
    + "This cannot be undone.")) return;
  apiFetch("/v1/profiles/" + encodeURIComponent(name), { method: "DELETE" })
    .then(function (r) {
      if (r.status === 204) toast("Profile deleted", "ok");
      else if (r.status === 404) toast("Profile not found", "err");     // already gone; still resync
      else { toast("Delete failed (" + r.status + ")", "err"); return; }
      // Deleting the selected profile leaves every view pointing at a name that no longer resolves,
      // so clear it first and let loadProfiles pick whatever is left.
      const preferred = state.profile === name ? "" : state.profile;
      if (state.profile === name) clearProfileSelection();
      loadProfiles(preferred);
    })
    .catch(function (e) { toast(e.message, "err"); });
}
