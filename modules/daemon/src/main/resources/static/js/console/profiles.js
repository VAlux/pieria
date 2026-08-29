import { $, el, apiFetch, icon } from "../util/dom.js";
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

// The profile's Configuration entry.
function configItem() {
  const item = el("li");
  const link = el("button", "side-panel-item side-panel-subitem");
  link.type = "button";
  link.dataset.view = "profile-config";
  link.appendChild(el("span", "side-panel-rail"));
  const gear = el("span", "side-panel-icon");
  gear.setAttribute("aria-hidden", "true");
  gear.innerHTML = '<svg viewBox="0 0 24 24" focusable="false"><circle cx="12" cy="12" r="3.2"/>'
    + '<path d="M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-1.8-.3'
    + ' 1.6 1.6 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-1-1.5 1.6 1.6 0 0 0-1.8.3l-.1.1a2 2'
    + ' 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0 .3-1.8 1.6 1.6 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.6 1.6 0'
    + ' 0 0 1.5-1 1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3H9a1.6'
    + ' 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1'
    + ' 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0'
    + ' 0 0-1.5 1Z"/></svg>';
  link.appendChild(gear);
  link.appendChild(el("span", "side-panel-name", "Configuration"));
  if (state.view === "profile-config") {
    link.classList.add("active");
    link.setAttribute("aria-current", "page");
  }
  item.appendChild(link);
  return item;
}

/**
 * The profile's delete entry. It lives here rather than on the profile row itself so a destructive
 * control is never the neighbour of the click that merely switches profiles: reaching it takes
 * selecting the profile first, which is also what makes it unambiguous *which* profile it deletes.
 * Quiet like its sibling until hovered — the red belongs to the gesture, not to the resting panel.
 */
function deleteItem(name, memoryCount) {
  const item = el("li");
  const button = el("button", "side-panel-item side-panel-subitem side-panel-danger");
  button.type = "button";
  button.dataset.deleteProfile = name;
  // The count travels on the element so the confirmation can name what is about to be destroyed
  // without a second round trip.
  button.dataset.memoryCount = memoryCount || "0";
  button.setAttribute("aria-label", "Delete profile " + name);
  button.appendChild(el("span", "side-panel-rail"));
  const trash = icon("trash", 15);
  trash.classList.add("side-panel-icon");   // sit in the same column as Configuration's gear
  button.appendChild(trash);
  button.appendChild(el("span", "side-panel-name", "Delete profile"));
  item.appendChild(button);
  return item;
}

// Build the selected profile's sub-entries. Extracted so renderProfiles (initial paint) and
// markSelected (profile switch) share one definition rather than each building the markup inline.
function renderSubList(row) {
  const sub = el("ul", "side-panel-sublist");
  sub.appendChild(configItem());
  // Name and count come off the row's own entry, so markSelected — which only knows the name —
  // does not need a second source for them.
  const owner = row.querySelector("button[data-profile]");
  if (owner) sub.appendChild(deleteItem(owner.dataset.profile, owner.dataset.memoryCount));
  row.appendChild(sub);
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

    // The selected profile reveals its own sections. Configuration is per-profile, so its entry
    // belongs under the profile rather than in the global nav.
    if (profile.name === state.profile) renderSubList(row);

    list.appendChild(row);
  });
}

function markSelected(profile) {
  profileList().querySelectorAll("button[data-profile]").forEach(function (button) {
    const selected = button.dataset.profile === profile;
    button.classList.toggle("active", selected);
    if (selected) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
    // The Configuration sub-entry belongs to the selected profile only.
    const row = button.closest("li");
    const sub = row ? row.querySelector(".side-panel-sublist") : null;
    if (sub && !selected) sub.remove();
    if (selected && !sub) renderSubList(row);
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
  if (!state.profile) return;
  state.profile = "";
  $("profileLabel").textContent = "";
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
