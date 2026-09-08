// Presentation only: canonical memory types and stored content remain unchanged.
// Add specialized presentations here; arbitrary payload.subtype values get a neutral badge.
export function objectPayload(value) {
  try {
    const parsed = typeof value === "string" ? JSON.parse(value) : value;
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
  } catch (_) { return {}; }
}

function text(value) { return typeof value === "string" ? value : ""; }
function label(value) { return text(value).replace(/[_-]+/g, " "); }

export function memoryPresentation(m) {
  const p = objectPayload(m.payload);
  const key = text(m.topicKey);
  const trace = p.source === "trace" || key.startsWith("trace:outcome:") || key.startsWith("trace:recipe:");
  const toolCall = trace && m.type === "event";
  const subtype = text(p.subtype) || (toolCall ? "tool-call" : trace && m.type === "instruction" ? "trace-recipe" : "");
  const subtypeLabel = subtype === "tool-call" ? "Tool call" : subtype === "trace-recipe" ? "Trace recipe" : label(subtype);
  const content = text(m.content);
  const invocation = text(p.command);
  const tool = text(p.tool);
  // Invocation can be shell text, JSON, or a tool name followed by JSON. Never evaluate it.
  const argsText = tool && invocation.startsWith(tool + " ") ? invocation.slice(tool.length + 1) : invocation;
  const args = objectPayload(argsText);
  const command = text(args.command) || text(args.cmd);
  const path = text(args.file_path) || text(args.path);
  const title = toolCall ? text(args.description) || text(args.title) || path || tool || "Tool call" : "";
  const preview = toolCall ? command || path || (Object.keys(args).length ? JSON.stringify(args, null, 2) : invocation) || content : content;
  const status = toolCall ? (["success", "failure"].includes(p.status) ? p.status : "unknown") : "";
  const statusLabel = { success: "Succeeded", failure: "Failed", unknown: "Outcome unknown" }[status] || "";
  const failurePrefix = "`" + invocation + "` failed" + (Number.isInteger(p.exit_code) ? " (exit " + p.exit_code + ")" : "") + ": ";
  const failure = status === "failure" && content.startsWith(failurePrefix) ? content.slice(failurePrefix.length) : "";
  const date = text(p.occurred_at) || text(p.stated_at) || m.createdAt;
  const dateLabel = p.occurred_at ? "Occurred" : p.stated_at ? "Stated" : "Stored";
  return { payload: p, subtype, subtypeLabel, toolCall, tool, title, preview, status, statusLabel, failure,
    invocation, args, date, dateLabel, source: label(p.source),
    exitCode: Number.isInteger(p.exit_code) ? p.exit_code : null };
}
