// One configuration field, rendered as a row. This is the entire visual vocabulary of both
// config pages: provenance dot, label over the literal key, one control, a provenance chip and a
// reset. Only a value set at THIS layer is marked — inherited rows stay quiet, so a page of 28
// fields reads as "these six are mine" without counting.
import { el, icon } from "../../util/dom.js";

const WIDE_KINDS = { "string": true, "secret": true };

function control(field, view) {
  const wrap = el("div", "cfg-control");

  if (field.kind === "bool") {
    const box = el("input");
    box.type = "checkbox";
    box.checked = view.value === true || view.value === "true";
    box.disabled = !!view.disabled;
    box.addEventListener("change", function () { view.onChange(box.checked); });
    wrap.appendChild(box);
    return wrap;
  }

  if (field.kind === "enum") {
    const select = el("select", "mono");
    (field.options || []).forEach(function (option) {
      const item = el("option", null, option);
      item.value = option;
      if (String(view.value) === option) item.selected = true;
      select.appendChild(item);
    });
    select.disabled = !!view.disabled;
    select.addEventListener("change", function () { view.onChange(select.value); });
    wrap.appendChild(select);
    return wrap;
  }

  // Every kind that isn't handled above (int, double, string, secret) shares this one text
  // input; the only distinction between them is width and, for secret, the masked placeholder.
  const input = el("input", "mono num" + (WIDE_KINDS[field.kind] ? " wide" : "")
    + (view.error ? " err" : ""));
  input.type = "text";
  input.value = view.value == null ? "" : String(view.value);
  input.disabled = !!view.disabled;
  if (field.kind === "secret" && view.source !== "set") input.placeholder = "••••••••";
  input.addEventListener("change", function () { view.onChange(input.value); });

  // A weight is a number you compare to its siblings, so it gets a slider as well as the number.
  if (field.kind === "weight") {
    const slider = el("input");
    slider.type = "range";
    slider.min = "0";
    slider.max = "5";
    slider.step = "0.1";
    slider.value = String(view.value);
    slider.disabled = !!view.disabled;
    // Dragging must not rebuild this row: the browser tracks a drag against the node that received
    // the mousedown, so replacing the slider mid-drag drops the capture and it stops moving after
    // one tick. While dragging we only push the value outward — the caller repaints the mix bar and
    // the save bar, both of which live outside this row. The full re-render waits for `change`.
    slider.addEventListener("input", function () {
      const next = parseFloat(slider.value).toFixed(1);
      input.value = next;
      if (view.onLiveChange) view.onLiveChange(next);
    });
    slider.addEventListener("change", function () {
      view.onChange(parseFloat(slider.value).toFixed(1));
    });
    wrap.appendChild(slider);
  }

  wrap.appendChild(input);
  return wrap;
}

export function renderFieldRow(field, view) {
  const row = el("div", "cfg-row" + (view.source === "set" ? " is-set" : "")
    + (view.disabled ? " inactive" : ""));
  const main = el("div", "cfg-row-main");

  main.appendChild(el("span", "cfg-dot"));

  const label = el("div", "cfg-label");
  label.appendChild(el("div", "cfg-label-text", field.label));
  label.appendChild(el("div", "cfg-key", field.key));
  main.appendChild(label);

  main.appendChild(control(field, view));

  const provenance = el("div", "cfg-provenance");
  if (view.source === "set") {
    provenance.appendChild(el("span", "cfg-chip", view.sourceLabel || "overridden"));
  } else if (view.sourceLabel) {
    provenance.appendChild(el("span", "cfg-inherited", view.sourceLabel));
  }
  main.appendChild(provenance);

  const resetCell = el("div");
  resetCell.style.flex = "none";
  resetCell.style.width = "28px";
  if (view.onReset && view.source === "set" && !view.disabled) {
    const reset = el("button", "cfg-reset");
    reset.type = "button";
    reset.title = "Reset to the inherited value";
    reset.appendChild(icon("chevronRight", 15));
    reset.querySelector("svg").innerHTML = '<path d="M4 12a8 8 0 1 0 2.6-5.9M4 4v4h4"/>';
    reset.addEventListener("click", view.onReset);
    resetCell.appendChild(reset);
  }
  main.appendChild(resetCell);

  row.appendChild(main);

  if (view.error) row.appendChild(el("div", "cfg-row-error", view.error));
  else if (field.hint) row.appendChild(el("div", "cfg-hint", field.hint));

  return row;
}
