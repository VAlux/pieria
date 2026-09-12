import markdownit from "../vendor/markdown-it.js";

// Model output and stored memories are untrusted input. markdown-it escapes raw HTML when `html`
// is false and rejects dangerous link protocols through its default validateLink implementation.
const parser = markdownit({
  html: false,
  linkify: true,
  breaks: true
});

// Keep navigation in the console when a rendered answer links to external material.
const defaultLinkOpen = parser.renderer.rules.link_open
  || function (tokens, index, options, env, renderer) {
    return renderer.renderToken(tokens, index, options);
  };
parser.renderer.rules.link_open = function (tokens, index, options, env, renderer) {
  const href = tokens[index].attrGet("href") || "";
  if (/^(?:https?:|mailto:)/i.test(href)) {
    tokens[index].attrSet("target", "_blank");
    tokens[index].attrSet("rel", "noopener noreferrer");
  }
  return defaultLinkOpen(tokens, index, options, env, renderer);
};

/** Render Markdown into a new element. Use inline mode for compact, line-clamped previews. */
export function markdown(source, options) {
  const opts = options || {};
  const host = document.createElement(opts.tag || "div");
  host.className = ["markdown", opts.className || "", opts.inline ? "markdown-inline" : ""]
    .filter(Boolean).join(" ");
  host.innerHTML = opts.inline
    ? parser.renderInline(String(source == null ? "" : source))
    : parser.render(String(source == null ? "" : source));
  return host;
}
