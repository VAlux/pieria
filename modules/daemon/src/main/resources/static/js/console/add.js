import { $, api } from "../util/dom.js";
import { state } from "./state.js";
import { toast } from "./toast.js";
import { refreshProfileCounts } from "./profiles.js";

export function submitAdd() {
  const type = $("addType").value;
  const content = $("addContent").value.trim();
  const status = $("addStatus");
  if (!content) { status.textContent = "Content is required."; status.style.color = "var(--danger)"; return; }
  const payloadRaw = $("addPayload").value.trim();
  if (payloadRaw) {
    try { JSON.parse(payloadRaw); }
    catch (e) { status.textContent = "Payload is not valid JSON."; status.style.color = "var(--danger)"; return; }
  }
  const reqBody = {
    type: type,
    content: content,
    sessionId: $("addSession").value.trim() || null,
    topicKey: $("addTopicKey").value.trim() || null,
    payload: payloadRaw || null
  };
  const btn = $("addSubmit");
  btn.disabled = true;
  status.style.color = ""; status.innerHTML = '<span class="spinner"></span> Storing…';
  fetch(api(state.profile, "/memories"), {
    method: "POST", headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(reqBody)
  })
    .then(function (r) {
      if (r.status !== 201) return r.text().then(function (t) { throw new Error("Store failed (" + r.status + ")"); });
      return r.json();
    })
    .then(function (m) {
      status.style.color = "var(--ok)";
      status.textContent = "Stored ✓ (" + m.id.slice(0, 12) + "…)";
      $("addContent").value = ""; $("addPayload").value = ""; $("addTopicKey").value = "";
      refreshProfileCounts();
      toast("Memory stored", "ok");
    })
    .catch(function (e) { status.style.color = "var(--danger)"; status.textContent = e.message; })
    .finally(function () { btn.disabled = false; });
}
