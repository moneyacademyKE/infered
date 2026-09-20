/**
 * Upstream InferHub Client & Resilient Fallback Engine
 * Handles streaming SSE and standard JSON completions with automatic retry across Pareto candidates
 * and real-time token usage / cost telemetry.
 */

import { recordSample, recordUsage } from "./metrics.js";
import { getOfficialPrice, CHAIN_FALLBACKS } from "./catalog.js";

const DEFAULT_TIMEOUT_MS = 25000;
const DEFAULT_FIRST_CONTENT_TIMEOUT_MS = 120000;

// Failover window: a stream that hasn't produced a CONTENT token yet has shown
// the client nothing, so dying here is a candidate failure like any other.
// Role/reasoning-only frames are buffered and replayed once content arrives.
const CONTENT_NEEDLE = '"content":"';
const MAX_PRECONTENT_BUFFER_BYTES = 4 * 1024 * 1024;

function windowHasContentToken(window) {
  let idx = window.indexOf(CONTENT_NEEDLE);
  while (idx !== -1) {
    // Empty content is exactly "content":""; anything else (incl. escaped
    // quotes) means a real token. "content":null never matches the needle.
    if (window[idx + CONTENT_NEEDLE.length] !== '"') return true;
    idx = window.indexOf(CONTENT_NEEDLE, idx + 1);
  }
  return false;
}

async function readUpToFirstContent(reader, signal, deadlineMs) {
  const decoder = new TextDecoder();
  const buffered = [];
  let carry = "";
  let totalBytes = 0;
  const deadline = Date.now() + deadlineMs;

  for (;;) {
    if (signal && signal.aborted) {
      return { ok: false, reason: "Client disconnected", clientGone: true };
    }
    let chunk;
    try {
      const result = await reader.read();
      if (result.done) {
        return { ok: false, reason: "Upstream stream ended before producing any content tokens", clientGone: false };
      }
      chunk = result.value;
    } catch (err) {
      const clientGone = Boolean(signal && signal.aborted);
      return {
        ok: false,
        reason: clientGone ? "Client disconnected" : "Upstream stream errored before first content token",
        clientGone
      };
    }

    buffered.push(chunk);
    totalBytes += chunk.byteLength;
    if (totalBytes > MAX_PRECONTENT_BUFFER_BYTES) {
      return { ok: false, reason: "Upstream exceeded the pre-content buffer limit", clientGone: false };
    }

    const window = carry + decoder.decode(chunk, { stream: true });
    if (windowHasContentToken(window)) return { ok: true, buffered };
    if (window.includes("[DONE]")) {
      return { ok: false, reason: "Upstream stream completed without any content tokens", clientGone: false };
    }
    if (Date.now() > deadline) {
      return { ok: false, reason: "Upstream produced no content within the deadline", clientGone: false };
    }
    carry = window.slice(-32);
  }
}

// Replays the buffered prefix, then streams the remainder. If the upstream
// dies mid-flight it reports through onStreamOutcome — the exact hook that
// makes previously-invisible stream deaths land in the decision ledger.
function createReplayingStream({ buffered, reader, onStreamOutcome, requestSignal, getMetrics, onDone }) {
  const ts = new TransformStream({
    transform(chunk, controller) { controller.enqueue(chunk); }
  });
  (async () => {
    const writer = ts.writable.getWriter();
    try {
      for (const chunk of buffered) await writer.write(chunk);
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        await writer.write(value);
      }
      await writer.close();
    } catch (err) {
      try { await writer.abort(err); } catch {}
      const clientGone = Boolean(requestSignal && requestSignal.aborted);
      if (onStreamOutcome) {
        onStreamOutcome({
          ok: false,
          error: clientGone ? "client_disconnected" : "upstream_stream_died",
          metrics: getMetrics()
        });
      }
    } finally {
      if (onDone) onDone();
    }
  })();
  return ts.readable;
}

async function executeCandidateRequest({
  candidate,
  requestBody,
  apiKey,
  fetchFn = fetch,
  baseUrl = "https://api.inferhub.dev/v1",
  timeoutMs = DEFAULT_TIMEOUT_MS,
  firstContentTimeoutMs = DEFAULT_FIRST_CONTENT_TIMEOUT_MS,
  requestSignal = null,
  onStreamOutcome = null
}) {
  const startTime = Date.now();
  let firstTokenTime = null;

  const upstreamBody = {
    ...requestBody,
    model: candidate.modelId
  };

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);

  // Link the caller's abort signal: when the client disconnects, upstream work
  // stops immediately instead of burning subrequests and billed tokens.
  const abortFromClient = () => controller.abort();
  if (requestSignal) {
    if (requestSignal.aborted) controller.abort();
    else requestSignal.addEventListener("abort", abortFromClient, { once: true });
  }
  // Kept attached on the streaming path so a mid-stream disconnect kills upstream too.
  const detachClientAbort = () => {
    if (requestSignal) requestSignal.removeEventListener("abort", abortFromClient);
  };

  const headers = {
    "Content-Type": "application/json",
    "Accept": requestBody.stream ? "text/event-stream" : "application/json"
  };
  if (candidate.providerId && candidate.providerId !== "official") {
    headers["X-InferHub-Provider"] = candidate.providerId;
  }
  if (apiKey) {
    headers["Authorization"] = `Bearer ${apiKey}`;
  }

  try {
    const response = await fetchFn(`${baseUrl}/chat/completions`, {
      method: "POST",
      headers,
      body: JSON.stringify(upstreamBody),
      signal: controller.signal
    });

    clearTimeout(timeoutId);

    if (!response.ok) {
      const errText = await response.text().catch(() => "Unknown error");
      return {
        success: false,
        status: response.status,
        error: `Provider ${candidate.providerId} (${candidate.modelId}) returned ${response.status}: ${errText}`,
        latencyMs: Date.now() - startTime
      };
    }

    if (requestBody.stream && response.body) {
      const getMetrics = () => {
        const totalMs = Date.now() - startTime;
        const ttftMs = firstTokenTime ? firstTokenTime - startTime : totalMs;
        return { latencyMs: totalMs, ttftMs, success: true };
      };

      // Hold until first content: nothing shown to the client yet, so a dead
      // or empty stream here is just a candidate failure — the fallback loop
      // moves to the next candidate and the client never sees the corpse.
      const reader = response.body.getReader();
      const prefix = await readUpToFirstContent(reader, requestSignal, firstContentTimeoutMs);
      if (!prefix.ok) {
        detachClientAbort();
        try { await reader.cancel(); } catch {}
        return {
          success: false,
          status: prefix.clientGone ? 499 : 503,
          error: prefix.reason,
          latencyMs: Date.now() - startTime
        };
      }
      firstTokenTime = Date.now();

      const stream = createReplayingStream({
        buffered: prefix.buffered,
        reader,
        onStreamOutcome,
        requestSignal,
        getMetrics,
        onDone: detachClientAbort
      });

      return {
        success: true,
        status: 200,
        stream,
        getMetrics
      };
    }

    const json = await response.json();
    detachClientAbort();
    const totalMs = Date.now() - startTime;
    return {
      success: true,
      status: 200,
      responseBody: json,
      latencyMs: totalMs,
      ttftMs: totalMs,
      usage: json.usage || { prompt_tokens: 15, completion_tokens: 25, total_tokens: 40 }
    };
  } catch (err) {
    clearTimeout(timeoutId);
    detachClientAbort();
    const clientGone = requestSignal && requestSignal.aborted;
    return {
      success: false,
      status: clientGone ? 499 : 504,
      error: clientGone
        ? "Client disconnected"
        : (err.name === "AbortError" ? "Request timed out" : err.message),
      latencyMs: Date.now() - startTime
    };
  }
}

export function executeWithFallback({
  candidates,
  requestBody,
  apiKey,
  metricsStore,
  fetchFn = fetch,
  baseUrl = "https://api.inferhub.dev/v1",
  timeoutMs = DEFAULT_TIMEOUT_MS,
  firstContentTimeoutMs = DEFAULT_FIRST_CONTENT_TIMEOUT_MS,
  requestSignal = null,
  onStreamOutcome = null,
  maxAttempts = 10
}) {
  return (async () => {
    if (!candidates || candidates.length === 0) {
      throw new Error("No candidate providers available for routing.");
    }

    const errors = [];

    for (const candidate of candidates) {
      // Don't start new upstream attempts for a caller who already left,
      // or once the retry budget is spent (free plan: 50 subrequests/request —
      // a long order book must not be able to burn them all on one request).
      if (requestSignal && requestSignal.aborted) break;
      if (errors.length >= maxAttempts) break;

      const result = await executeCandidateRequest({
        candidate,
        requestBody,
        apiKey,
        fetchFn,
        baseUrl,
        timeoutMs,
        firstContentTimeoutMs,
        requestSignal,
        onStreamOutcome
      });

      if (result.success) {
        const usage = result.usage || { prompt_tokens: 20, completion_tokens: 30 };
        const promptTokens = usage.prompt_tokens || 20;
        const completionTokens = usage.completion_tokens || 30;

        const official = getOfficialPrice(candidate.modelId);
        const spot = candidate.quote || { prompt: candidate.blendedPrice, completion: candidate.blendedPrice };

        const costUsd = Number((((promptTokens / 1e6) * (spot.prompt || 0.1)) +
                                ((completionTokens / 1e6) * (spot.completion || 0.1))).toFixed(7));
        const officialCostUsd = Number((((promptTokens / 1e6) * official.prompt) +
                                        ((completionTokens / 1e6) * official.completion)).toFixed(7));

        recordSample(metricsStore, candidate.providerId, candidate.modelId, {
          latencyMs: result.latencyMs || 250,
          ttftMs: result.ttftMs || 150,
          success: true,
          savingsPct: candidate.savingsPct,
          costUsd
        });

        recordUsage(metricsStore, {
          modelId: candidate.modelId,
          providerId: candidate.providerId,
          promptTokens,
          completionTokens,
          costUsd,
          officialCostUsd,
          reason: candidate.isPrimary ? "primary-model" : "budget-fallback"
        });

        return {
          ...result,
          selectedCandidate: candidate,
          attempts: errors.length + 1,
          failoverErrors: errors
        };
      } else {
        recordSample(metricsStore, candidate.providerId, candidate.modelId, {
          latencyMs: result.latencyMs || timeoutMs,
          ttftMs: result.latencyMs || timeoutMs,
          success: false,
          error: result.error
        });

        errors.push({
          providerId: candidate.providerId,
          modelId: candidate.modelId,
          status: result.status,
          error: result.error
        });
      }
    }

    if (requestSignal && requestSignal.aborted) {
      return {
        success: false,
        status: 499,
        error: "Client disconnected before completion.",
        selectedCandidate: candidates[0],
        attempts: errors.length,
        failoverErrors: errors
      };
    }

    const retryBudgetSpent = errors.length >= maxAttempts;
    return {
      success: false,
      status: 503,
      error: retryBudgetSpent
        ? `Retry budget exhausted after ${errors.length} upstream attempts.`
        : "All candidate nodes failed or capacity exhausted.",
      selectedCandidate: candidates[0],
      attempts: errors.length,
      failoverErrors: errors
    };
  })();
}

/**
 * Chain-level fallback policy, data-driven from catalog.CHAIN_FALLBACKS.
 * Two honest passes, no loops: rank the entry chain; if it prices out
 * entirely or every candidate fails upstream, retry exactly once with the
 * declared fallback chain. Attempts are summed across passes so telemetry
 * never undercounts what actually happened.
 *
 * @param {object} args
 * @param {string} args.requestedModel - entry model/chain name as requested
 * @param {function} args.rank - (model) => ranked candidates
 * @param {function} args.execute - (candidates) => executeWithFallback result
 * @returns {Promise<{result: object, candidates: Array, fallbackChain: string|null}>}
 */
export async function executeWithChainFallback({ requestedModel, rank, execute }) {
  const fallbackName = CHAIN_FALLBACKS[requestedModel] || null;

  let candidates = rank(requestedModel);
  let fallbackChain = null;

  // Entry chain priced out entirely under the current constraints —
  // give the declared fallback chain a chance to rank candidates.
  if (candidates.length === 0 && fallbackName) {
    fallbackChain = fallbackName;
    candidates = rank(fallbackName);
  }

  if (candidates.length === 0) {
    return { result: { success: false, attempts: 0 }, candidates, fallbackChain };
  }

  let result = await execute(candidates);

  if (!result.success && fallbackName) {
    const fbCandidates = rank(fallbackName);
    if (fbCandidates.length > 0) {
      const primaryAttempts = result.attempts || 0;
      const fbResult = await execute(fbCandidates);
      if (fbResult.success) {
        result = fbResult;
        result.attempts = primaryAttempts + (fbResult.attempts || 1);
        fallbackChain = fallbackName;
        candidates = fbCandidates;
      } else {
        result.fallbackTried = fallbackName;
        candidates = fbCandidates;
      }
    }
  }

  return { result, candidates, fallbackChain };
}
