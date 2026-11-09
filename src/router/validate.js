/**
 * Request Input Validation — boundary rules before any parsing or routing.
 * Bounded input is the contract: byte cap, message count, tool count, and
 * numeric sanity. Everything else is upstream's problem.
 */

export const MAX_BODY_BYTES = 2 * 1024 * 1024; // 2 MiB request bodies
export const MAX_MESSAGES = 128;
export const MAX_TOOLS = 64;
export const MAX_TOP_TOKENS = 1_000_000;
const MAX_STRING_CONTENT = 512 * 1024; // single content string cap

function badRequest(message) {
  return { error: { message, type: "invalid_request_error" } };
}

/**
 * Pre-parse gate: uses declared Content-Length when present so an oversized
 * body can be rejected before request.json() buffers it.
 * @returns {Response|null} 413 response when the declared size is over cap.
 */
export function checkBodySize(request) {
  const declared = Number(request.headers.get("Content-Length"));
  if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) {
    return new Response(JSON.stringify(badRequest(
      `Request body exceeds the ${MAX_BODY_BYTES} byte limit (declared ${declared}).`
    )), { status: 413, headers: { "Content-Type": "application/json" } });
  }
  return null;
}

/**
 * Post-parse shape validation for a chat completion body.
 * @returns {{ok: true}|{ok: false, status: number, body: object}}
 */
export function validateChatBody(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    return { ok: false, status: 400, body: badRequest("Request body must be a JSON object.") };
  }

  const messages = body.messages;
  if (!Array.isArray(messages) || messages.length === 0) {
    return { ok: false, status: 400, body: badRequest("messages must be a non-empty array.") };
  }
  if (messages.length > MAX_MESSAGES) {
    return { ok: false, status: 400, body: badRequest(`messages exceeds the ${MAX_MESSAGES} message limit.`) };
  }
  for (const m of messages) {
    if (!m || typeof m !== "object" || typeof m.role !== "string" || m.role.length === 0) {
      return { ok: false, status: 400, body: badRequest("every message needs a non-empty string role.") };
    }
    const content = m.content;
    if (content !== undefined && content !== null) {
      if (typeof content === "string") {
        if (content.length > MAX_STRING_CONTENT) {
          return { ok: false, status: 400, body: badRequest("message content exceeds the per-message cap.") };
        }
      } else if (!Array.isArray(content)) {
        return { ok: false, status: 400, body: badRequest("message content must be a string or an array of parts.") };
      }
    }
  }

  if (body.tools !== undefined && body.tools !== null) {
    if (!Array.isArray(body.tools) || body.tools.length > MAX_TOOLS) {
      return { ok: false, status: 400, body: badRequest(`tools must be an array of at most ${MAX_TOOLS} entries.`) };
    }
  }

  if (body.max_tokens !== undefined && body.max_tokens !== null) {
    const n = Number(body.max_tokens);
    if (!Number.isFinite(n) || n < 0 || n > MAX_TOP_TOKENS) {
      return { ok: false, status: 400, body: badRequest("max_tokens must be a non-negative number.") };
    }
  }
  if (body.temperature !== undefined && body.temperature !== null) {
    const t = Number(body.temperature);
    if (!Number.isFinite(t) || t < 0 || t > 2) {
      return { ok: false, status: 400, body: badRequest("temperature must be a number in [0, 2].") };
    }
  }

  if (body.model !== undefined && body.model !== null && typeof body.model !== "string") {
    return { ok: false, status: 400, body: badRequest("model must be a string.") };
  }

  return { ok: true };
}
