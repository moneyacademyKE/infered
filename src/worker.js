/**
 * Infered - Dynamic Virtual LLM Router for InferHub Marketplace
 * Cloudflare Workers Edge Entry Point with Continuous SWR Live Marketplace Ingestion,
 * Multi-Tier Edge Caching, Elastic Budget Escalation & Tool Call Autohealing
 */

import { VIRTUAL_ALIASES, MODEL_TIERS } from "./router/catalog.js";
import { createPriceCache, updateSpotPrices, calculateSavingsPct, ingestInferHubModelsResponse, buildRatecard, getQuotesForModel } from "./router/pricing.js";
import { createMetricsStore, getProviderStats, getUsageSummary } from "./router/metrics.js";
import { createCacheStore, computeRequestKey, getCachedResponse, putCachedResponse, getSessionAffinity, setSessionAffinity } from "./router/cache.js";
import { healToolCalls } from "./router/healer.js";
import { createExemplarStore, recordToolOutcome, enrichPromptWithToolExemplars } from "./router/exemplars.js";
import { rankCandidates, DEFAULT_WEIGHTS, ROUTING_POLICIES } from "./router/pareto.js";
import { executeWithFallback } from "./router/client.js";
import { renderDashboardHtml } from "./ui/dashboard.js";
import { collectAnalytics, renderAnalyticsPage } from "./ui/analytics.js";

let globalPriceCache = null;
let globalMetricsStore = null;
let globalCacheStore = null;
let globalExemplarStore = null;

const SWR_SYNC_INTERVAL_MS = 15000; // 15 seconds

function getOrInitState() {
  if (!globalPriceCache) globalPriceCache = createPriceCache();
  if (!globalMetricsStore) globalMetricsStore = createMetricsStore();
  if (!globalCacheStore) globalCacheStore = createCacheStore();
  if (!globalExemplarStore) globalExemplarStore = createExemplarStore();
  return {
    priceCache: globalPriceCache,
    metricsStore: globalMetricsStore,
    cacheStore: globalCacheStore,
    exemplarStore: globalExemplarStore
  };
}

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Infered-Weights, X-Infered-Max-Price, X-Session-ID, X-Session-Affinity, X-Infered-Cache, X-InferHub-Provider",
    "Access-Control-Expose-Headers": "x-infered-selected-model, x-infered-provider, x-infered-savings-pct, x-infered-latency-ms, x-infered-ttft-ms, x-infered-cache, x-infered-budget-tier, x-infered-escalation-level, x-infered-tool-healed, x-infered-attempts"
  };
}

function jsonResponse(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      ...corsHeaders(),
      ...extraHeaders
    }
  });
}

function parseWeightsAndBudget(request, requestBody, env, url) {
  let weights = { ...DEFAULT_WEIGHTS };
  const headerWeights = request.headers.get("X-Infered-Weights");
  if (headerWeights) {
    try {
      weights = { ...weights, ...JSON.parse(headerWeights) };
    } catch {}
  } else {
    const policyName = env?.ROUTING_POLICY || "sol-budget-cascade";
    const policy = ROUTING_POLICIES[policyName] || DEFAULT_WEIGHTS;
    weights = {
      price: env?.PRICE_WEIGHT ? parseFloat(env.PRICE_WEIGHT) : policy.price,
      speed: env?.SPEED_WEIGHT ? parseFloat(env.SPEED_WEIGHT) : policy.speed,
      quality: env?.QUALITY_WEIGHT ? parseFloat(env.QUALITY_WEIGHT) : policy.quality
    };
  }

  let maxFallbackPrice = null;
  const headerMaxPrice = request.headers.get("X-Infered-Max-Price");
  const queryMaxPrice = url ? url.searchParams.get("max_price") : null;
  const bodyMaxPrice = requestBody?.max_price || requestBody?.maxPrice || requestBody?.maxPriceThreshold || requestBody?.budget_threshold;

  if (headerMaxPrice) {
    maxFallbackPrice = parseFloat(headerMaxPrice);
  } else if (bodyMaxPrice !== undefined && bodyMaxPrice !== null) {
    maxFallbackPrice = parseFloat(bodyMaxPrice);
  } else if (queryMaxPrice) {
    maxFallbackPrice = parseFloat(queryMaxPrice);
  } else if (env?.MAX_FALLBACK_PRICE) {
    maxFallbackPrice = parseFloat(env.MAX_FALLBACK_PRICE);
  }

  return { weights, maxFallbackPrice };
}

async function syncLiveInferHubQuotes(priceCache, baseUrl, apiKey) {
  try {
    const res = await fetch(`${baseUrl}/models`, {
      headers: { "Authorization": `Bearer ${apiKey}` }
    });
    if (res.ok) {
      const data = await res.json();
      ingestInferHubModelsResponse(priceCache, data);
    }
  } catch {}
}

function triggerBackgroundSwrSyncIfNeeded(priceCache, baseUrl, apiKey, ctx) {
  if (!apiKey) return;
  const now = Date.now();
  if (now - priceCache.lastSyncedAt > SWR_SYNC_INTERVAL_MS) {
    priceCache.lastSyncedAt = now; // Prevent duplicate concurrent sync triggers
    const syncPromise = syncLiveInferHubQuotes(priceCache, baseUrl, apiKey);
    if (ctx && typeof ctx.waitUntil === "function") {
      ctx.waitUntil(syncPromise);
    }
  }
}

// Durable decision record — one row per request into D1 (free tier:
// 100k writes/day vs our ~2k; Analytics Engine is Paid-only, a nongoal).
// Observability must never break routing: every failure is swallowed.
async function recordRoutingAnalytics(env, rec) {
  try {
    if (!env?.ROUTING_DB) return;
    await env.ROUTING_DB.prepare(
      "INSERT INTO routing_decisions (session_id, requested_model, selected_model, selected_provider, escalation_level, attempts, latency_ms, budget_cap, ok, error) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)"
    ).bind(
      rec.sessionId ?? null,
      rec.requestedModel ?? rec.model ?? null,
      rec.model ?? null,
      rec.provider ?? null,
      rec.escalationLevel ?? 0,
      rec.attempts ?? 1,
      rec.latencyMs ?? null,
      rec.budgetCap ?? null,
      rec.ok ? 1 : 0,
      rec.error ?? null
    ).run();
  } catch {}
}

function createStandaloneMockFetch() {
  return async (url, opts) => {
    const body = JSON.parse(opts.body || "{}");
    const prov = opts.headers["X-InferHub-Provider"] || "mock-node";
    const model = body.model || "zai/glm-5.3-flash";
    const prompt = body.messages?.[body.messages.length - 1]?.content || "Hello";

    return new Response(JSON.stringify({
      id: `chatcmpl-${Date.now()}`,
      object: "chat.completion",
      created: Math.floor(Date.now() / 1000),
      model,
      choices: [{
        index: 0,
        message: {
          role: "assistant",
          content: `[Infered Edge Routing via ${prov}] Model ${model} processed prompt: "${prompt}" successfully.`
        },
        finish_reason: "stop"
      }],
      usage: { prompt_tokens: 18, completion_tokens: 22, total_tokens: 40 }
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  };
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    const { priceCache, metricsStore, cacheStore, exemplarStore } = getOrInitState();
    const apiKey = env?.INFERHUB_API_KEY || request.headers.get("Authorization")?.replace(/^Bearer\s+/i, "");
    const baseUrl = env?.INFERHUB_BASE_URL || "https://api.inferhub.dev/v1";

    // Continuous SWR Market Polling: Asynchronously refresh order books if stale
    triggerBackgroundSwrSyncIfNeeded(priceCache, baseUrl, apiKey, ctx);

    if (path === "/" || path === "/analytics") {
      if (!env?.ROUTING_DB && path === "/analytics") {
        return jsonResponse({ error: "ROUTING_DB binding missing — decision analytics unavailable" }, 503);
      }
      const data = env?.ROUTING_DB
        ? await collectAnalytics(env.ROUTING_DB)
        : { topModels: [], switching: {}, flappingSessions: 0, totalSessions: 0, last24h: 0 };
      const ratecard = buildRatecard().map(r => {
        const cheapest = getQuotesForModel(priceCache, r.modelId)[0];
        return { ...r, spot: cheapest && cheapest.priceSource === "spot" ? cheapest : null };
      });
      return new Response(renderAnalyticsPage(data, ratecard), {
        status: 200,
        headers: { "Content-Type": "text/html; charset=utf-8", ...corsHeaders() }
      });
    }

    if (path === "/dashboard") {
      return new Response(renderDashboardHtml(), {
        status: 200,
        headers: { "Content-Type": "text/html; charset=utf-8", ...corsHeaders() }
      });
    }

    if (path === "/v1/health") {
      return jsonResponse({
        status: "healthy",
        edge: "cloudflare-workers",
        timestamp: Date.now(),
        cachedQuotes: Object.keys(priceCache.quotes).length,
        cachedResponses: cacheStore.responseCache.size,
        activeSessions: cacheStore.sessionAffinity.size,
        toolStats: exemplarStore.toolStats,
        usage: getUsageSummary(metricsStore)
      });
    }

    if (path === "/v1/models" && request.method === "GET") {
      const virtualModels = Object.keys(VIRTUAL_ALIASES).map(alias => ({
        id: alias,
        object: "model",
        created: 1700000000,
        owned_by: "infered-virtual",
        permission: [],
        root: alias,
        parent: null
      }));

      const concreteModels = Object.keys(MODEL_TIERS).map(modelId => ({
        id: modelId,
        object: "model",
        created: 1700000000,
        owned_by: MODEL_TIERS[modelId].family,
        permission: [],
        root: modelId,
        parent: null
      }));

      return jsonResponse({
        object: "list",
        data: [...virtualModels, ...concreteModels]
      });
    }

    if (path === "/v1/metrics" && request.method === "GET") {
      const quoteList = Object.values(priceCache.quotes).map(q => {
        const stats = getProviderStats(metricsStore, q.providerId, q.modelId);
        return {
          ...q,
          savingsPct: calculateSavingsPct(q.modelId, q),
          emaLatency: stats.emaLatency,
          emaTtft: stats.emaTtft,
          totalRequests: stats.totalRequests
        };
      });

      const avgSavings = quoteList.length > 0
        ? Number((quoteList.reduce((acc, q) => acc + q.savingsPct, 0) / quoteList.length).toFixed(1))
        : 0;

      return jsonResponse({
        totalQuotes: quoteList.length,
        avgSavingsPct: avgSavings,
        quotes: quoteList,
        cachedResponsesCount: cacheStore.responseCache.size,
        toolStats: exemplarStore.toolStats,
        usage: getUsageSummary(metricsStore),
        history: metricsStore.history
      });
    }

    if (path === "/v1/admin/sync" && (request.method === "POST" || request.method === "GET")) {
      if (apiKey) {
        await syncLiveInferHubQuotes(priceCache, baseUrl, apiKey);
      }
      return jsonResponse({
        success: true,
        syncedQuotes: Object.keys(priceCache.quotes).length
      });
    }

    if (path === "/v1/chat/completions" && request.method === "POST") {
      try {
        let requestBody = await request.json();
        const requestStart = Date.now();
        // Sol removed 2026-09-05 (owner directive) — glm-budget is the default chain.
        const requestedModel = requestBody.model || "infered/glm-budget";
        const { weights, maxFallbackPrice } = parseWeightsAndBudget(request, requestBody, env, url);

        // 1. Session affinity check
        const sessionId = request.headers.get("X-Session-ID") || request.headers.get("X-Session-Affinity");
        const sessionAffinity = getSessionAffinity(cacheStore, sessionId);

        // 2. Exact Deterministic Response Caching check
        const isDeterministic = (requestBody.temperature === 0 || request.headers.get("X-Infered-Cache") === "true") && !requestBody.stream;
        const cacheKey = isDeterministic ? computeRequestKey(requestBody) : null;

        if (isDeterministic && cacheKey) {
          const cached = getCachedResponse(cacheStore, cacheKey);
          if (cached) {
            // Cache hits are routing decisions too: without a row, per-model
            // analytics silently undercounts exactly the cheapest traffic.
            ctx.waitUntil(recordRoutingAnalytics(env, {
              ok: true,
              model: cached.model || requestedModel,
              requestedModel,
              attempts: 0,
              latencyMs: Date.now() - requestStart,
              budgetCap: maxFallbackPrice,
              sessionId
            }));
            return jsonResponse(cached, 200, {
              "x-infered-cache": "HIT",
              "x-infered-selected-model": cached.model || requestedModel,
              "x-infered-savings-pct": "100.0",
              "x-infered-latency-ms": "1",
              "x-infered-budget-tier": "cache-hit",
              "x-infered-escalation-level": "0",
              "x-infered-attempts": "0"
            });
          }
        }

        // 3. Candidate ranking with strict output token budget ceiling
        const candidates = rankCandidates({
          model: requestedModel,
          priceCache,
          metricsStore,
          weights,
          maxFallbackPrice,
          sessionAffinityProvider: sessionAffinity?.providerId,
          sessionAffinityModel: sessionAffinity?.modelId
        });

        if (candidates.length === 0) {
          return jsonResponse({
            error: {
              message: `No candidate providers found under current constraints for: ${requestedModel}`,
              type: "invalid_request_error"
            }
          }, 400);
        }

        const selected = candidates[0];

        // 4. Continuous Tool Learning: Enrich prompt with dynamic exemplars if routing to budget models
        if (requestBody.tools && Array.isArray(requestBody.tools)) {
          requestBody = enrichPromptWithToolExemplars(requestBody, selected.modelId);
        }

        const fetchFn = (apiKey && apiKey !== "test-key") ? fetch : createStandaloneMockFetch();

        const result = await executeWithFallback({
          candidates,
          requestBody,
          apiKey,
          metricsStore,
          fetchFn,
          baseUrl,
          requestSignal: request.signal
        });

        if (!result.success) {
          ctx.waitUntil(recordRoutingAnalytics(env, {
            ok: false,
            // No `model` here: a failed request never selected anything, so
            // selected_model stays NULL instead of leaking the chain name.
            requestedModel,
            attempts: result.attempts || 0,
            budgetCap: maxFallbackPrice,
            sessionId,
            error: String(result.failoverErrors?.[0]?.error || result.error || "unknown").slice(0, 200)
          }));
          return jsonResponse({
            error: {
              message: result.error,
              type: "provider_error",
              failovers: result.failoverErrors
            }
          }, result.status || 502);
        }

        // Report and pin the candidate that ACTUALLY served the request —
        // candidates[0] can fail over silently (attempts > 1), and headers
        // or session affinity pointing at a model that never answered is
        // exactly the kind of fiction this router must not tell.
        const served = result.selectedCandidate || selected;

        let responseBody = result.responseBody;
        let toolWasHealed = false;

        // 5. Tool Call Autohealing: Apply Semantic Sieve
        if (responseBody && responseBody.choices && requestBody.tools) {
          const healedChoices = healToolCalls(responseBody.choices, requestBody.tools);
          toolWasHealed = healedChoices.some(c => c.message?.tool_calls?.some(tc => tc._healed));
          responseBody = { ...responseBody, choices: healedChoices };

          // Record tool learning telemetry
          for (const c of healedChoices) {
            for (const tc of c.message?.tool_calls || []) {
              recordToolOutcome(exemplarStore, tc.function?.name, selected.modelId, true, tc._healed);
            }
          }
        }

        if (sessionId) {
          setSessionAffinity(cacheStore, sessionId, served.providerId, served.modelId);
        }

        if (isDeterministic && cacheKey && responseBody) {
          putCachedResponse(cacheStore, cacheKey, responseBody);
        }

        // Durable decision record: one row per request, queryable via D1 SQL.
        // sessionId is stored so model pinning can be verified against real
        // traffic, and the ACTUAL serving candidate is recorded — not the
        // ranked favorite.
        const recordDecision = (metrics, overrides = {}) => ctx.waitUntil(recordRoutingAnalytics(env, {
          ok: true,
          model: served.modelId,
          requestedModel,
          provider: served.providerId,
          escalationLevel: served.escalationLevel ?? 0,
          attempts: result.attempts || 1,
          // Wall-clock fallback: covers any shape where the executor's own
          // measurement is missing (cache-served, aborted stream flush).
          latencyMs: metrics.latencyMs || (Date.now() - requestStart),
          budgetCap: maxFallbackPrice,
          sessionId,
          ...overrides
        }));

        // Streaming results carry getMetrics() — total duration is only
        // known once the body drains, so record on flush instead of inline
        // (inline wrote null for every streamed request).
        let responseStream = null;
        if (result.getMetrics && result.stream) {
          const getMetrics = result.getMetrics;
          let recorded = false;
          // Exactly one row per stream: flush on success, cancel on disconnect.
          const recordOnce = (m, overrides) => {
            if (recorded) return;
            recorded = true;
            recordDecision(m || {}, overrides);
          };
          const ts = new TransformStream({
            flush() { recordOnce(getMetrics()); },
            cancel() { recordOnce(null, { ok: false, error: "client_disconnected" }); }
          });
          // Explicit pipeTo, not pipeThrough: a mid-stream client cancel rejects
          // the source pipe — expected, so it's caught, never unhandled.
          responseStream = ts.readable;
          result.stream.pipeTo(ts.writable).catch(() => {});
        } else {
          recordDecision(result);
        }

        const telemetryHeaders = {
          "x-infered-cache": "MISS",
          "x-infered-selected-model": served.modelId,
          "x-infered-provider": served.providerId,
          "x-infered-savings-pct": String(served.savingsPct),
          "x-infered-latency-ms": String(result.latencyMs || 100),
          "x-infered-ttft-ms": String(result.ttftMs || 80),
          "x-infered-budget-tier": String(served.budgetTier || "0.10"),
          "x-infered-escalation-level": String(served.escalationLevel !== undefined ? served.escalationLevel : 0),
          "x-infered-tool-healed": toolWasHealed ? "true" : "false",
          "x-infered-attempts": String(result.attempts || 1)
        };

        if (requestBody.stream && responseStream) {
          return new Response(responseStream, {
            status: 200,
            headers: {
              "Content-Type": "text/event-stream",
              "Cache-Control": "no-cache",
              "Connection": "keep-alive",
              ...corsHeaders(),
              ...telemetryHeaders
            }
          });
        }

        return jsonResponse(responseBody, 200, telemetryHeaders);
      } catch (err) {
        return jsonResponse({ error: { message: err.message, type: "internal_error" } }, 500);
      }
    }

    return jsonResponse({ error: "Route not found", path }, 404);
  }
};
