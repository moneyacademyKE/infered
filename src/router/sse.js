/**
 * Minimal SSE wire helpers.
 *
 * Completion means the model produced something actionable: non-empty text,
 * a tool/function call, or a tool_calls finish reason. Reasoning alone is not
 * an answer an agent can act on.
 *
 * Framing is separated from completion detection so the relay can emit only
 * complete SSE events. An upstream EOF may leave a partial JSON frame; that
 * tail must stay buffered (and die with the candidate), never be followed by
 * a synthesized [DONE] that makes the client's parser fail.
 */

const CONTENT_NEEDLE = '"content":"';
const TOOL_CALL_RE = /"tool_calls"\s*:\s*\[\s*\{/;
const TOOL_FINISH_RE = /"finish_reason"\s*:\s*"tool_calls"/;
const LEGACY_FUNCTION_RE = /"function_call"\s*:\s*\{/;

export function windowHasCompletionToken(window) {
  if (TOOL_CALL_RE.test(window) || TOOL_FINISH_RE.test(window) || LEGACY_FUNCTION_RE.test(window)) {
    return true;
  }
  let idx = window.indexOf(CONTENT_NEEDLE);
  while (idx !== -1) {
    // Empty content is exactly "content":""; anything else is a real token.
    if (window[idx + CONTENT_NEEDLE.length] !== '"') return true;
    idx = window.indexOf(CONTENT_NEEDLE, idx + 1);
  }
  return false;
}

/**
 * Split all complete SSE events from a text buffer. Events are normalized to
 * LF boundaries; the final incomplete event remains in `rest`.
 */
export function takeCompleteSseFrames(buffer) {
  const frames = [];
  const boundary = /\r?\n\r?\n/g;
  let start = 0;
  let match;
  while ((match = boundary.exec(buffer)) !== null) {
    frames.push(buffer.slice(start, match.index) + "\n\n");
    start = match.index + match[0].length;
  }
  return { frames, rest: buffer.slice(start) };
}

/** Adapt a non-streaming OpenAI JSON completion to a two-event SSE stream. */
export function jsonCompletionToSseStream(json) {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      const choices = (json.choices || []).map((choice) => ({
        index: choice.index ?? 0,
        delta: choice.message || choice.delta || {},
        finish_reason: choice.finish_reason || "stop"
      }));
      const chunk = { ...json, object: "chat.completion.chunk", choices };
      controller.enqueue(encoder.encode(`data: ${JSON.stringify(chunk)}\n\n`));
      controller.enqueue(encoder.encode("data: [DONE]\n\n"));
      controller.close();
    }
  });
}
