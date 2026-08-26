import { $ } from "../util/dom.js";
import { state } from "./state.js";
import { setView } from "./router.js";
import { loadProfiles, selectProfile } from "./profiles.js";
import { resetPageAndRender, loadMemories } from "./memories.js";
import { closeDrawer, forgetDrawerMemory } from "./drawer.js";
import { submitAdd } from "./add.js";
import { submitRecall } from "./recall.js";
import { exportProfile } from "./export.js";
import { applyAuditFilters, clearAuditFilters } from "./audit.js";
import { initSidePanel } from "./side-panel.js";
import { initDaemonStatus } from "./daemon.js";
import { initTaskTray } from "./tasks.js";

const VIEWS = ["memories", "add", "recall", "stats", "audit", "graph"];
let auditSearchTimer = null;

function applyAuditFiltersNow() {
  clearTimeout(auditSearchTimer);
  auditSearchTimer = null;
  applyAuditFilters();
}

function wireUp() {
  $("profileList").addEventListener("click", function (e) {
    const button = e.target.closest("button[data-profile]");
    if (button) selectProfile(button.dataset.profile);
  });
  $("nav").addEventListener("click", function (e) {
    const b = e.target.closest("button[data-view]");
    if (b) setView(b.dataset.view);
  });
  $("searchInput").addEventListener("input", resetPageAndRender);
  $("typeFilter").addEventListener("click", function (e) {
    const b = e.target.closest("button[data-type]");
    if (!b) return;
    state.typeFilter = b.dataset.type;
    $("typeFilter").querySelectorAll("button[data-type]").forEach(function (other) {
      other.classList.toggle("active", other === b);
    });
    resetPageAndRender();
  });
  $("sessionFilter").addEventListener("change", resetPageAndRender);
  $("sortSelect").addEventListener("change", resetPageAndRender);
  $("supToggle").addEventListener("change", function () {
    state.includeSuperseded = $("supToggle").checked;
    loadMemories(true);
  });
  $("addSubmit").addEventListener("click", submitAdd);
  $("recallBtn").addEventListener("click", submitRecall);
  $("recallQuery").addEventListener("keydown", function (e) { if (e.key === "Enter") submitRecall(); });
  $("exportBtn").addEventListener("click", exportProfile);
  $("auditClear").addEventListener("click", function () {
    clearTimeout(auditSearchTimer);
    auditSearchTimer = null;
    clearAuditFilters();
  });
  ["auditOperation", "auditClient", "auditHarness", "auditOutcome", "auditFrom", "auditTo",
    "auditTruncated"].forEach(function (id) { $(id).addEventListener("change", applyAuditFiltersNow); });
  $("auditSearch").addEventListener("input", function () {
    clearTimeout(auditSearchTimer);
    auditSearchTimer = setTimeout(applyAuditFilters, 250);
  });
  $("auditSearch").addEventListener("keydown", function (e) {
    if (e.key !== "Enter") return;
    applyAuditFiltersNow();
  });
  $("drawerClose").addEventListener("click", closeDrawer);
  $("drawerBackdrop").addEventListener("click", closeDrawer);
  $("drawerDelete").addEventListener("click", forgetDrawerMemory);
  document.addEventListener("keydown", function (e) { if (e.key === "Escape") closeDrawer(); });
}

function boot() {
  initSidePanel();
  initDaemonStatus();
  initTaskTray();
  wireUp();
  const params = new URLSearchParams(location.search);
  const initialView = params.get("view");
  if (VIEWS.indexOf(initialView) >= 0) {
    state.view = initialView;
    document.querySelectorAll(".nav button").forEach(function (b) { b.classList.toggle("active", b.dataset.view === initialView); });
    document.querySelectorAll(".view").forEach(function (s) { s.classList.toggle("active", s.id === "view-" + initialView); });
    document.body.classList.toggle("view-graph", initialView === "graph");
  }
  loadProfiles(params.get("profile") || "");
}

boot();
