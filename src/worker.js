/** Infered — dynamic virtual LLM router for the InferHub spot marketplace. */

import { VIRTUAL_ALIASES, CASCADE_CHAINS, resolveStrongLink } from "./router/catalog.js";
import { createPriceCache, updateSpotPrices, calculateSavingsPct, ingestInferHubModelsResponse, buildRatecard, getQuotesForModel, getMarketState } from "./router/pricing.js";
import { createMetricsStore, getProviderStats, getUsageSummary } from "./router/metrics.js";
import { createCacheStore, computeRequestKey, getCachedResponse, putCachedResponse } from "./router/cache.js";
import { resolveSessionId, getSessionPin, recordSessionPin } from "./router/session.js";
import { rankCandidates, DEFAULT_WEIGHTS, ROUTING_POLICIES } from "./router/pareto.js";
import { executeWithFallback, executeWithChainFallback } from "./router/client.js";
import { collectAnalytics, renderAnalyticsPage } from "./ui/analytics.js";
import { corsHeaders, jsonResponse, buildTelemetryHeaders, recordRoutingAnalytics, createStandaloneMockFetch } from "./router/telemetry.js";
import { checkBodySize, validateChatBody, MAX_BODY_BYTES } from "./router/validate.js";

const MAX_BODY_BYTES_STRING = MAX_BODY_BYTES;

let globalPriceCache = null;
let globalMetricsStore = null;
let globalCacheStore = null;

const SWR_SYNC_INTERVAL_MS = 15000; // 15 seconds

function getOrInitState() {
  if (!globalPriceCache) globalPriceCache = createPriceCache();
  if (!globalMetricsStore) globalMetricsStore = createMetricsStore();
  if (!globalCacheStore) globalCacheStore = createCacheStore();
  return {
    priceCache: globalPriceCache,
    metricsStore: globalMetricsStore,
    cacheStore: globalCacheStore
  };
}

function parseWeightsAndBudget(request, requestBody, env, url) {
  let weights = { ...DEFAULT_WEIGHTS };
  const headerWeights = request.headers.get("X-Infered-Weights");
  if (headerWeights) {
    try {
      weights = { ...weights, ...JSON.parse(headerWeights) };
    } catch {}
  } else {
    const policyName = env?.ROUTING_POLICY || "budget-cascade";
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
      priceCache.lastSyncOkAt = Date.now();
      priceCache.lastSyncFailure = null;
      const arr = Array.isArray(data) ? data : (data?.data || []);
      priceCache.lastSyncRaw = {
        at: Date.now(),
        total: arr.length,
        withPricing: arr.filter(m => m && m.pricing).length,
        astraEntry: arr.find(m => /astra/i.test(m?.id || "")) || null,
        ids: arr.slice(0, 50).map(m => m?.id)
      };
    }
  } catch (e) {
    priceCache.lastSyncFailure = String(e?.message || e).slice(0, 200);
  }
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

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    const { priceCache, metricsStore, cacheStore } = getOrInitState();
    // Upstream key is the worker's own secret, full stop: a caller's
    // Authorization is INPUT, never a credential we relay or adopt (audit H5).
    const apiKey = env?.INFERHUB_API_KEY || null;
    const baseUrl = env?.INFERHUB_BASE_URL || "https://api.inferhub.dev/v1";

    // Admin-plane auth (audit: metrics/sync were publicly readable/triggerable).
    // Bearer must match ROUTER_AUTH_TOKEN exactly when the token is configured;
    // an unset token keeps today's public behavior for a private deployment.
    const adminToken = env?.ROUTER_AUTH_TOKEN || null;
    const bearer = request.headers.get("Authorization")?.replace(/^Bearer\s+/i, "") || null;
    const adminAuthorized = !adminToken || (bearer !== null && bearer === adminToken);
    const requireAdmin = () => adminAuthorized
      ? null
      : jsonResponse({ error: { message: "Admin token required.", type: "authentication_error" } }, 401);

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
      // Retired 2026-09-05: the ops dashboard drifted every time the surface
      // contracted and the analytics homepage does its job. Old links redirect.
      return Response.redirect(new URL("/", url).toString(), 302);
    }

    if (path === "/v1/health") {
      return jsonResponse({
        status: "healthy",
        edge: "cloudflare-workers",
        timestamp: Date.now(),
        cachedQuotes: Object.keys(priceCache.quotes).length,
        market: getMarketState(priceCache),
        marketModels: Object.keys(priceCache.modelToProviders).sort(),
        lastSyncRaw: priceCache.lastSyncRaw || null,
        chainAsks: [...new Set(Object.values(CASCADE_CHAINS).flat())]
          .reduce((acc, m) => {
            acc[m] = getQuotesForModel(priceCache, m).filter(q => q.priceSource === "spot").length;
            return acc;
          }, {}),
        cachedResponses: cacheStore.responseCache.size,
        activeSessions: cacheStore.sessionAffinity.size,
        usage: getUsageSummary(metricsStore)
      });
    }

    if (path === "/v1/models" && request.method === "GET") {
      // Only declared chain products are listed. Unknown names still route to
      // the default chain; raw catalog models remain requestable by ID.
      const virtualModels = Object.keys(VIRTUAL_ALIASES).map(alias => ({
        id: alias,
        object: "model",
        created: 1700000000,
        owned_by: "infered-virtual",
        permission: [],
        root: alias,
        parent: null
      }));

      return jsonResponse({
        object: "list",
        data: virtualModels
      });
    }

    if (path === "/v1/metrics" && request.method === "GET") {
      const unauthorized = requireAdmin();
      if (unauthorized) return unauthorized;
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
        usage: getUsageSummary(metricsStore),
        history: metricsStore.history
      });
    }

    if (path === "/v1/admin/sync" && (request.method === "POST" || request.method === "GET")) {
      const unauthorized = requireAdmin();
      if (unauthorized) return unauthorized;
      // Only the worker's OWN configured key triggers a live sync — the
      // caller's bearer (if it matched the admin token) is a router
      // credential, not an InferHub credential.
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
        // Bounded input before parsing: declared-size rejection, then shape.
        const oversized = checkBodySize(request);
        if (oversized) return oversized;
        let requestBody;
        try {
          requestBody = await request.json();
        } catch {
          return jsonResponse({ error: { message: "Request body is not valid JSON.", type: "invalid_request_error" } }, 400);
        }
        if (JSON.stringify(requestBody).length > MAX_BODY_BYTES_STRING) {
          return jsonResponse({ error: { message: "Request body exceeds the byte limit.", type: "invalid_request_error" } }, 413);
        }
        const shape = validateChatBody(requestBody);
        if (!shape.ok) {
          return jsonResponse(shape.body, shape.status);
        }
        const requestStart = Date.now();
        // Sol removed 2026-09-05 (owner directive) — glm-budget is the default chain.
        const requestedModel = requestBody.model || "infered/glm-budget";
        const { weights, maxFallbackPrice } = parseWeightsAndBudget(request, requestBody, env, url);
        const startAtModel = request.headers.get("X-Infered-Tier") === "strong" ? resolveStrongLink(requestedModel) : null;

        // 1. Session identity: explicit header wins, else a conversation-head
        // fingerprint — 99.91% of traffic sends no header (D1 audit 2026-09-10),
        // and a pin that engages for 0.1% of requests is dead code.
        const sessionId = resolveSessionId(request, requestBody);
        const sessionAffinity = await getSessionPin(cacheStore, sessionId, env);

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

        // 3. Candidate ranking with chain fallback and strict output token budget ceiling
        const fetchFn = (apiKey && apiKey !== "test-key") ? fetch : createStandaloneMockFetch();
        // Stream deaths are reported asynchronously after a candidate is chosen;
        // reportStreamOutcome late-binds to the recorder once it exists.
        let pendingStreamOutcome = null;
        let reportStreamOutcome = null;
        const rank = (m) => rankCandidates({
          model: m,
          priceCache,
          metricsStore,
          weights,
          maxFallbackPrice,
          sessionAffinityProvider: sessionAffinity?.providerId,
          sessionAffinityModel: sessionAffinity?.modelId,
          startAtModel
        });
        const execute = (cands) => executeWithFallback({
            candidates: cands,
            requestBody,
            apiKey,
            metricsStore,
            fetchFn,
            baseUrl,
            requestSignal: request.signal,
            onStreamOutcome: (o) => {
              if (reportStreamOutcome) reportStreamOutcome(o);
              else pendingStreamOutcome = o;
            }
          });

        const { result, candidates, fallbackChain } = await executeWithChainFallback({
          requestedModel,
          rank,
          execute
        });

        if (!result.success) {
          ctx.waitUntil(recordRoutingAnalytics(env, {
            ok: false,
            requestedModel,
            attempts: result.attempts || 0,
            budgetCap: maxFallbackPrice,
            sessionId,
            error: String(result.failoverErrors?.[0]?.error || result.error || "unknown").slice(0, 200)
          }));
          return jsonResponse({
            error: {
              message: result.error || `No candidate providers found under current constraints for: ${requestedModel}`,
              type: result.attempts === 0 ? "invalid_request_error" : "provider_error",
              failovers: result.failoverErrors
            }
          }, result.status || (result.attempts === 0 ? 400 : 502));
        }

        const selected = candidates[0];
        const served = result.selectedCandidate || selected;

        const responseBody = result.responseBody;

        recordSessionPin(cacheStore, env, ctx, sessionId, served.providerId, served.modelId);

        if (isDeterministic && cacheKey && responseBody) {
          putCachedResponse(cacheStore, cacheKey, responseBody);
        }

        // Durable decision record: one row per request, queryable via D1 SQL.
        // sessionId is stored so model pinning can be verified against real
        // traffic, and the ACTUAL serving candidate is recorded — not the
        // ranked favorite.
        const recordDecision = (metrics, overrides = {}) => ctx.waitUntil(recordRoutingAnalytics(env, {
          ok: true,
          // Spliced streams: the candidate that COMMITTED the response is not
          // always the one that ANSWERED — getMetrics carries the final truth.
          model: metrics.servedModel || served.modelId,
          requestedModel,
          provider: metrics.servedProvider || served.providerId,
          escalationLevel: served.escalationLevel ?? 0,
          attempts: metrics.attempts || result.attempts || 1,
          // Wall-clock fallback: covers any shape where the executor's own
          // measurement is missing (cache-served, aborted stream flush).
          latencyMs: metrics.latencyMs || (Date.now() - requestStart),
          budgetCap: maxFallbackPrice ?? metrics.budgetTier ?? served.budgetTier ?? null,
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
          // Exactly one row per stream: flush on success, cancel on disconnect,
          // outcome hook on upstream death — whichever arrives first wins.
          const recordOnce = (m, overrides) => {
            if (recorded) return;
            recorded = true;
            recordDecision(m || {}, overrides);
          };
          const recordStreamDeath = (o) => recordOnce(o && o.metrics, { ok: false, error: (o && o.error) || "upstream_stream_died" });
          if (pendingStreamOutcome) recordStreamDeath(pendingStreamOutcome);
          reportStreamOutcome = recordStreamDeath;
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

        const telemetryHeaders = buildTelemetryHeaders({ served, result });

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
