import { $ } from "../util/dom.js";

const NARROW_VIEWPORT = "(max-width: 720px)";

function applyState(expanded) {
  const panel = $("sidePanel");
  const toggle = $("sidePanelToggle");
  const label = expanded ? "Collapse side panel" : "Expand side panel";

  panel.classList.toggle("is-expanded", expanded);
  panel.classList.toggle("is-collapsed", !expanded);
  panel.dataset.state = expanded ? "expanded" : "collapsed";
  panel.querySelectorAll(".side-panel-list").forEach(function (list) {
    list.setAttribute("aria-hidden", String(!expanded));
  });
  toggle.setAttribute("aria-expanded", String(expanded));
  toggle.setAttribute("aria-label", label);
  toggle.title = label;
  toggle.querySelector(".side-panel-toggle-label").textContent = expanded ? "Collapse" : "Expand";
}

export function initSidePanel() {
  const toggle = $("sidePanelToggle");
  applyState(!window.matchMedia(NARROW_VIEWPORT).matches);
  toggle.addEventListener("click", function () {
    applyState(toggle.getAttribute("aria-expanded") !== "true");
  });
}
