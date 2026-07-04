import { $ } from "../util/dom.js";

let toastTimer = null;

// Transient bottom-center notification; kind is "ok" | "err" | undefined.
export function toast(msg, kind) {
  const t = $("toast");
  t.textContent = msg;
  t.className = "toast show" + (kind ? " " + kind : "");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(function () { t.className = "toast"; }, 2600);
}
