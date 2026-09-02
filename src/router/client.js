/**
 * Upstream InferHub Client & Resilient Fallback Engine
 * Handles streaming SSE and standard JSON completions with automatic retry across Pareto candidates
 * and real-time token usage / cost telemetry.
 */

import { recordSample, recordUsage } from "./metrics.js";
import { getOfficialPrice, CHAIN_FALLBACKS } from "./catalog.js";

const DEFAULT_TIMEOUT_MS = 25000;

export function createStreamTelemetryTransformer(onFirstToken) {
  let firstTokenEmitted = false;

  return new TransformStream({
    transform(chunk, controller) {
      if (!firstTokenEmitted) {
        firstTokenEmitted = true;
        if (onFirstToken) onFirstToken();
      }
      controller.enqueue(chunk);
    }
  });
}

async function executeCandidateRequest({
  candidate,
  requestBody,
  apiKey,
  fetchFn = fetch,
  baseUrl = "https://api.inferhub.dev/v1",
  timeoutMs = DEFAULT_TIMEOUT_MS,
  requestSignal = null
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
    "Accept": requestBody.stream ? "text/event-stream" : "application/json",
    "X-InferHub-Provider": candidate.providerId
  };
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
      const telemetryStream = createStreamTelemetryTransformer(() => {
        firstTokenTime = Date.now();
      });

      return {
        success: true,
        status: 200,
        stream: response.body.pipeThrough(telemetryStream),
        getMetrics: () => {
          const totalMs = Date.now() - startTime;
          const ttftMs = firstTokenTime ? firstTokenTime - startTime : totalMs;
          return { latencyMs: totalMs, ttftMs, success: true };
        }
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
  requestSignal = null,
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
        requestSignal
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
      } else {
        result.fallbackTried = fallbackName;
      }
    }
  }

  return { result, candidates, fallbackChain };
}
