/**
 * Streaming Splice Executor — client-visible stream integrity.
 *
 * The commit point is the first upstream byte (TTFB stays honest), but commit
 * is NOT success: InferHub nodes queue behind `: heartbeat` comments and can
 * then fail IN-BAND — `data: {"error":...}` + [DONE] with zero content — or
 * die with a bare EOF. Relayed verbatim, either shape reaches the client as
 * the "Stream ended without [DONE]: 0 output tokens" corpse while the worker
 * happily records ok=1 (reproduced live 2026-09-20: raw flash, 200 status,
 * 32s of heartbeats, error frame, no content).
 *
 * Contract:
 * - Relay every byte live (heartbeats, role, reasoning) — no holding, no TTFB bomb.
 * - CLASSIFY what was relayed: content token seen? [DONE] seen? error frame seen?
 * - Pre-content failure (bare EOF, in-band error frame, [DONE] with zero content)
 *   is a CANDIDATE failure: splice the next candidate into the SAME client
 *   stream. Role/reasoning/heartbeat deltas are additive — the spliced
 *   candidate's content completes the response. A withheld error frame is
 *   dropped when a splice succeeds, forwarded when candidates run out.
 * - Content relayed but no [DONE] at EOF: synthesize the sentinel — the answer
 *   is complete; only the protocol terminator went missing.
 * - Post-content death: unrecoverable (bytes shipped) — abort + report
 *   upstream_stream_died.
 * - All candidates exhausted pre-content: forward the held error frame (or a
 *   synthesized one) + [DONE], so the client gets a clean provider error
 *   instead of a dropped connection.
 * - Every pre-content failure feeds recordSample(false): the circuit breaker
 *   finally learns about stream corpses instead of seeing ok=1 forever.
 */

import { recordSample, recordUsage } from "./metrics.js";
import { getOfficialPrice } from "./catalog.js";

const DEFAULT_TIMEOUT_MS = 25000;
export const DEFAULT_FIRST_BYTE_TIMEOUT_MS = 15000;
const MAX_FIRST_CHUNK_BYTES = 1 * 1024 * 1024;
const CARRY_CHARS = 48;
const CONTENT_NEEDLE = '"content":"';
const ERROR_NEEDLE = '{"error":{';
const DONE_FRAME = "data: [DONE]\n\n";

function windowHasContentToken(window) {
  let idx = window.indexOf(CONTENT_NEEDLE);
  while (idx !== -1) {
    // Empty content is exactly "content":""; anything else (incl. escaped
    // quotes) is a real token. "content":null and "reasoning_content":" never
    // match the needle.
    if (window[idx + CONTENT_NEEDLE.length] !== '"') return true;
    idx = window.indexOf(CONTENT_NEEDLE, idx + 1);
  }
  return false;
}

async function readFirstUpstreamChunk(reader, signal, deadlineMs) {
  if (signal && signal.aborted) {
    return { ok: false, reason: "Client disconnected", clientGone: true };
  }
  let timer;
  try {
    const result = await Promise.race([
      reader.read(),
      new Promise((resolve) => { timer = setTimeout(() => resolve({ timeout: true }), deadlineMs); })
    ]);
    if (timer) clearTimeout(timer);
    if (result.timeout) {
      return { ok: false, reason: "Upstream sent no bytes within the deadline", clientGone: false };
    }
    if (result.done) {
      return { ok: false, reason: "Upstream stream ended before sending any bytes", clientGone: false };
    }
    const chunk = result.value;
    if (chunk.byteLength > MAX_FIRST_CHUNK_BYTES) {
      return { ok: false, reason: "Upstream first chunk exceeded the size limit", clientGone: false };
    }
    // A stream whose first bytes are already [DONE] is an empty completion,
    // not a usable candidate — fail over like any other miss.
    const head = new TextDecoder().decode(chunk.slice(0, 4096));
    if (head.includes("[DONE]") && !head.includes(CONTENT_NEEDLE)) {
      return { ok: false, reason: "Upstream stream completed without any content tokens", clientGone: false };
    }
    return { ok: true, buffered: [chunk] };
  } catch (err) {
    if (timer) clearTimeout(timer);
    const clientGone = Boolean(signal && signal.aborted);
    return {
      ok: false,
      reason: clientGone ? "Client disconnected" : "Upstream stream errored before first byte",
      clientGone
    };
  }
}

export async function openUpstreamStream({
  candidate, requestBody, apiKey, fetchFn, baseUrl,
  timeoutMs, firstByteTimeoutMs, requestSignal
}) {
  const startTime = Date.now();
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
  const abortFromClient = () => controller.abort();
  if (requestSignal) {
    if (requestSignal.aborted) controller.abort();
    else requestSignal.addEventListener("abort", abortFromClient, { once: true });
  }
  const detachClientAbort = () => {
    if (requestSignal) requestSignal.removeEventListener("abort", abortFromClient);
  };

  const headers = { "Content-Type": "application/json", "Accept": "text/event-stream" };
  if (candidate.providerId && candidate.providerId !== "official") {
    headers["X-InferHub-Provider"] = candidate.providerId;
  }
  if (apiKey) headers["Authorization"] = `Bearer ${apiKey}`;

  try {
    const response = await fetchFn(`${baseUrl}/chat/completions`, {
      method: "POST",
      headers,
      body: JSON.stringify({ ...requestBody, model: candidate.modelId }),
      signal: controller.signal
    });
    clearTimeout(timeoutId);

    if (!response.ok) {
      detachClientAbort();
      const errText = await response.text().catch(() => "Unknown error");
      return {
        ok: false, status: response.status, latencyMs: Date.now() - startTime,
        reason: `Provider ${candidate.providerId} (${candidate.modelId}) returned ${response.status}: ${errText}`
      };
    }
    if (!response.body) {
      detachClientAbort();
      return { ok: false, status: 502, latencyMs: Date.now() - startTime, reason: "Upstream returned no body" };
    }

    const reader = response.body.getReader();
    const prefix = await readFirstUpstreamChunk(reader, requestSignal, firstByteTimeoutMs);
    if (!prefix.ok) {
      detachClientAbort();
      try { await reader.cancel(); } catch {}
      return {
        ok: false, status: prefix.clientGone ? 499 : 503,
        latencyMs: Date.now() - startTime, reason: prefix.reason, clientGone: prefix.clientGone
      };
    }
    return { ok: true, reader, buffered: prefix.buffered, startTime, detachClientAbort };
  } catch (err) {
    clearTimeout(timeoutId);
    detachClientAbort();
    const clientGone = Boolean(requestSignal && requestSignal.aborted);
    return {
      ok: false, status: clientGone ? 499 : 504, latencyMs: Date.now() - startTime,
      reason: clientGone ? "Client disconnected" : (err.name === "AbortError" ? "Request timed out" : err.message),
      clientGone
    };
  }
}

function streamUsage(candidate, promptTokens, completionTokens) {
  const official = getOfficialPrice(candidate.modelId);
  const spot = candidate.quote || { prompt: candidate.blendedPrice, completion: candidate.blendedPrice };
  return {
    costUsd: Number((((promptTokens / 1e6) * (spot.prompt || 0.1)) +
                     ((completionTokens / 1e6) * (spot.completion || 0.1))).toFixed(7)),
    officialCostUsd: Number((((promptTokens / 1e6) * official.prompt) +
                             ((completionTokens / 1e6) * official.completion)).toFixed(7))
  };
}

export function executeStreamingWithSplice({
  candidates, requestBody, apiKey, metricsStore, fetchFn = fetch,
  baseUrl = "https://api.inferhub.dev/v1", timeoutMs = DEFAULT_TIMEOUT_MS,
  firstByteTimeoutMs = DEFAULT_FIRST_BYTE_TIMEOUT_MS, requestSignal = null,
  onStreamOutcome = null, maxAttempts = 10
}) {
  const clientTs = new TransformStream();
  const writer = clientTs.writable.getWriter();
  let decoder = new TextDecoder();
  const encoder = new TextEncoder();
  const errors = [];
  const startedAt = Date.now();

  let served = null;
  let firstCommitAt = null;
  let attemptsUsed = 0;
  let contentSeen = false;
  let doneSeen = false;
  let committed = false;
  let settle;
  const commitGate = new Promise((resolve) => { settle = resolve; });

  const bumpUsage = (key) => {
    if (metricsStore && metricsStore.usage) {
      metricsStore.usage[key] = (metricsStore.usage[key] || 0) + 1;
    }
  };

  const getMetrics = () => ({
    latencyMs: Date.now() - startedAt,
    ttftMs: firstCommitAt ? firstCommitAt - startedAt : null,
    success: true,
    servedModel: served ? served.modelId : null,
    servedProvider: served ? served.providerId : null,
    attempts: attemptsUsed
  });

  const writeChunk = async (chunk) => {
    // Settle the commit gate BEFORE awaiting the write: a TransformStream's
    // first write only completes once the consumer reads, and the consumer
    // (the worker's Response) does not exist until the gate resolves.
    // Settling after the await is a circular wait — a deadlock the worker
    // tests caught on day one.
    if (!committed) {
      committed = true;
      firstCommitAt = Date.now();
      settle({
        success: true, status: 200, stream: clientTs.readable,
        // Live getters: a splice swaps the serving candidate AFTER the gate
        // settles, so post-drain reads (D1 attribution, tests) must see the
        // candidate that completed the answer, not the one that committed
        // the first byte. Response headers ship at commit and stay
        // commit-truthful; outcome readers get outcome truth.
        get selectedCandidate() { return served; },
        get attempts() { return attemptsUsed; },
        failoverErrors: errors, getMetrics
      });
    }
    await writer.write(chunk);
  };

  const fail = (status, error) => {
    if (!committed) {
      settle({
        success: false, status, error,
        selectedCandidate: served || candidates[0],
        attempts: attemptsUsed, failoverErrors: errors
      });
    }
  };

  const reportFailure = (error) => {
    if (onStreamOutcome) onStreamOutcome({ ok: false, error, metrics: getMetrics() });
  };

  (async () => {
    let heldErrorChunk = null;
    let carry = "";
    try {
      for (const candidate of candidates) {
        // Only CONTENT is terminal. A [DONE] with zero content is a corpse
        // handled inside relay — it must never end the candidate loop.
        if (contentSeen) break;
        if (requestSignal && requestSignal.aborted) break;
        if (errors.length >= maxAttempts) break;
        attemptsUsed++;

        const opened = await openUpstreamStream({
          candidate, requestBody, apiKey, fetchFn, baseUrl,
          timeoutMs, firstByteTimeoutMs, requestSignal
        });
        if (!opened.ok) {
          recordSample(metricsStore, candidate.providerId, candidate.modelId, {
            latencyMs: opened.latencyMs || timeoutMs, ttftMs: opened.latencyMs || timeoutMs,
            success: false, error: opened.reason
          });
          errors.push({
            providerId: candidate.providerId, modelId: candidate.modelId,
            status: opened.status, error: opened.reason
          });
          if (opened.clientGone) break;
          continue;
        }

        served = candidate;
        // A candidate opening AFTER an earlier one already committed client
        // bytes IS the splice — count it.
        if (committed && errors.length > 0) bumpUsage("streamSplices");
        heldErrorChunk = null;
        let candidateDied = false;
        let emptyDone = false;
        // Per-candidate parser state: the carry (and the decoder's pending
        // byte sequence) from a DEAD candidate must not leak into the next
        // one — a stale carry holding "[DONE]" would classify the spliced
        // candidate's first chunk as an empty-completion corpse and kill the
        // splice in its first frame (found empirically: the splice opened,
        // then instantly "died" with an error it never sent).
        carry = "";
        decoder = new TextDecoder();

        // Classify-then-relay, one chunk at a time. A pre-content in-band
        // error frame is WITHHELD (not relayed): if a splice follows, the
        // client never sees it; if candidates run out, it is forwarded. A
        // POST-content error frame is relayed (bytes already shipped) but
        // poisons the stream — it ends as a recorded failure, never a
        // synthesized success.
        const relay = async (chunk) => {
          const text = carry + decoder.decode(chunk, { stream: true });
          carry = text.slice(-CARRY_CHARS);
          if (!contentSeen && windowHasContentToken(text)) contentSeen = true;
          const errorFrame = text.includes(ERROR_NEEDLE);
          if (errorFrame && !contentSeen) {
            heldErrorChunk = chunk;
            return "dead";
          }
          if (text.includes("[DONE]")) {
            // A [DONE] with ZERO content is a corpse, not a completion: the
            // empty terminator is WITHHELD (relaying it would commit the
            // client response to an empty answer), this candidate fails, and
            // the next candidate's content completes the response. Only a
            // [DONE] that FOLLOWS content terminates the protocol.
            if (!contentSeen) {
              emptyDone = true;
              return "dead";
            }
            doneSeen = true;
          }
          await writeChunk(chunk);
          if (errorFrame) return "poisoned";
          return doneSeen ? "finished" : "continue";
        };

        try {
          let state = "continue";
          for (const chunk of opened.buffered) {
            state = await relay(chunk);
            if (state !== "continue") break;
          }
          while (state === "continue") {
            const { done, value } = await opened.reader.read();
            if (done) break;
            state = await relay(value);
          }
          if (state === "dead" || state === "poisoned") candidateDied = true;
        } catch (err) {
          candidateDied = true;
        } finally {
          try { opened.detachClientAbort(); } catch {}
        }

        if (contentSeen) {
          // This candidate produced the answer — it owns the stream to the end.
          if (candidateDied) {
            try { await writer.abort(new Error("upstream stream died mid-content")); } catch {}
            reportFailure(requestSignal && requestSignal.aborted ? "client_disconnected" : "upstream_stream_died");
            return;
          }
          if (!doneSeen) {
            bumpUsage("doneSynthesized");
            await writeChunk(encoder.encode(DONE_FRAME));
          }
          // Guarded: a concurrent client disconnect can error the writable
          // mid-close (Node rejects with undefined) — the row is already
          // recorded via flush; nothing honest is left to do.
          try { await writer.close(); } catch {}
          recordSample(metricsStore, candidate.providerId, candidate.modelId, {
            latencyMs: Date.now() - opened.startTime,
            ttftMs: firstCommitAt ? firstCommitAt - startedAt : null,
            success: true
          });
          const usage = streamUsage(candidate, 20, 30);
          recordUsage(metricsStore, {
            modelId: candidate.modelId, providerId: candidate.providerId,
            promptTokens: 20, completionTokens: 30,
            costUsd: usage.costUsd, officialCostUsd: usage.officialCostUsd,
            reason: "budget-stream"
          });
          return;
        }

        // Pre-content end (bare EOF, withheld error frame, or [DONE] with zero
        // content): a candidate failure the client never has to see.
        try { await opened.reader.cancel(); } catch {}
        bumpUsage("preContentFailures");
        if (emptyDone) bumpUsage("emptyDoneCorpses");
        recordSample(metricsStore, candidate.providerId, candidate.modelId, {
          latencyMs: Date.now() - opened.startTime, ttftMs: Date.now() - opened.startTime,
          success: false,
          error: candidateDied ? "upstream stream errored before content" : "upstream ended before content"
        });
        errors.push({
          providerId: candidate.providerId, modelId: candidate.modelId, status: 502,
          error: emptyDone ? "empty_completion"
            : candidateDied ? "stream_errored_pre_content" : "stream_ended_pre_content"
        });
        if (requestSignal && requestSignal.aborted) break;
      }

      // Loop ended without a completed answer. (There is no empty-[DONE]
      // branch here anymore: a zero-content [DONE] is a withheld candidate
      // failure above, so reaching this point means candidates ran out.)
      if (requestSignal && requestSignal.aborted) {
        if (committed) {
          try { await writer.abort(new Error("client disconnected")); } catch {}
          reportFailure("client_disconnected");
        } else {
          fail(499, "Client disconnected before completion.");
        }
        return;
      }
      if (committed) {
        // Exhausted mid-splice: hand the client a clean in-band provider error
        // instead of a dropped connection. Report BEFORE the closing writes —
        // flush-on-close records ok=1 and recordOnce is first-wins.
        reportFailure("all_candidates_exhausted_pre_content");
        if (heldErrorChunk) { try { await writeChunk(heldErrorChunk); } catch {} }
        try { await writeChunk(encoder.encode(`data: ${JSON.stringify({
          error: { code: 502, message: "all candidates exhausted before content", type: "server_error" }
        })}\n\n`)); } catch {}
        try { await writeChunk(encoder.encode(DONE_FRAME)); } catch {}
        try { await writer.close(); } catch {}
      } else {
        fail(503, errors.length >= maxAttempts
          ? `Retry budget exhausted after ${errors.length} upstream attempts.`
          : "All candidate nodes failed or capacity exhausted.");
      }
    } catch (err) {
      try { await writer.abort(err); } catch {}
      reportFailure(requestSignal && requestSignal.aborted ? "client_disconnected" : "upstream_stream_died");
      // Node's harness can reject stream ops with `undefined` on concurrent
      // cancellation — never read .message blind.
      fail(503, (err && err.message) || "stream relay failed");
    }
  })();

  return commitGate;
}
