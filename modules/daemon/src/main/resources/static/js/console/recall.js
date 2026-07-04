import { $, el, api } from "../util/dom.js";
import { typeColor } from "../util/palette.js";
import { relTime } from "../util/format.js";
import { state } from "./state.js";
import { renderBanner } from "./router.js";
import { openDrawer } from "./drawer.js";

export function submitRecall() {
  const query = $("recallQuery").value.trim();
  if (!query) return;
  const limit = parseInt($("recallLimit").value, 10) || 10;
  const out = $("recallResult");
  const btn = $("recallBtn");
  btn.disabled = true;
  out.innerHTML = '<div class="panel"><span class="spinner"></span> Recalling… this can take a while.</div>';
  fetch(api(state.profile, "/recall"), {
    method: "POST", headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ query: query, limit: limit })
  })
    .then(function (r) {
      if (r.status === 204) return null;
      if (!r.ok) throw new Error("Recall failed (" + r.status + ")");
      return r.json();
    })
    .then(function (data) {
      out.innerHTML = "";
      if (!data) { renderBanner(out, "No relevant memories found."); return; }
      if (data.answer) {
        const ans = el("div", "panel");
        ans.appendChild(el("h2", "section", "Answer"));
        ans.appendChild(el("div", null, data.answer));
        out.appendChild(ans);
      }
      const mems = data.memories || [];
      const wrap = el("div");
      wrap.style.marginTop = "16px";
      wrap.appendChild(el("h2", "section", "Supporting memories (" + mems.length + ")"));
      const listEl = el("div", "mem-list");
      if (!mems.length) listEl.appendChild(el("div", "banner", "None."));
      mems.forEach(function (m) {
        const row = el("div", "mem" + (m.superseded ? " superseded" : ""));
        row.addEventListener("click", function () { openDrawer(m); });
        const chip = el("span", "chip", m.type);
        chip.style.background = typeColor(m.type);
        row.appendChild(chip);
        const body = el("div", "mem-body");
        body.appendChild(el("div", "mem-content", m.content || ""));
        const meta = el("div", "mem-meta");
        meta.textContent = [m.topicKey, m.sessionId, relTime(m.createdAt)].filter(Boolean).join(" · ");
        body.appendChild(meta); row.appendChild(body);
        listEl.appendChild(row);
      });
      wrap.appendChild(listEl);
      out.appendChild(wrap);
    })
    .catch(function (e) { renderBanner(out, e.message, true); })
    .finally(function () { btn.disabled = false; });
}
