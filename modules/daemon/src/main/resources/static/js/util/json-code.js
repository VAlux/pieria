/**
 * Render JSON as a formatted, syntax-highlighted code block. Invalid or truncated JSON falls back
 * to literal text, which is important for audit bodies whose retained preview may be incomplete.
 */
export function jsonCode(value) {
  const formatted = format(value);
  const pre = document.createElement("pre");
  pre.className = "code" + (formatted.json ? " code-json" : "");
  const code = document.createElement("code");

  const prism = globalThis.Prism;
  if (formatted.json && prism && prism.languages && prism.languages.json) {
    code.className = "language-json";
    code.innerHTML = prism.highlight(formatted.text, prism.languages.json, "json");
  } else {
    code.textContent = formatted.text;
  }

  pre.appendChild(code);
  return pre;
}

function format(value) {
  if (value == null || value === "") return { text: "(empty)", json: false };
  try {
    const parsed = typeof value === "string" ? JSON.parse(value) : value;
    const text = JSON.stringify(parsed, null, 2);
    if (text !== undefined) return { text: text, json: true };
  } catch (e) {
    // The source can intentionally be truncated by audit retention. Preserve it exactly.
  }
  return { text: String(value), json: false };
}
