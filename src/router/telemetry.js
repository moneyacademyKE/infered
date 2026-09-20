/**
 * Edge HTTP & Durable Routing Telemetry Helper
 * High-cohesion response formatting, CORS management, and D1 decision persistence.
 */

export function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Infered-Weights, X-Infered-Max-Price, X-Infered-Tier, X-Session-ID, X-Session-Affinity, X-Infered-Cache, X-InferHub-Provider",
    "Access-Control-Expose-Headers": "x-infered-selected-model, x-infered-provider, x-infered-savings-pct, x-infered-latency-ms, x-infered-ttft-ms, x-infered-cache, x-infered-budget-tier, x-infered-escalation-level, x-infered-tool-healed, x-infered-attempts"
  };
}

export function jsonResponse(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      ...corsHeaders(),
      ...extraHeaders
    }
  });
}

/**
 * Builds standard x-infered-* response headers for client visibility.
 */
export function buildTelemetryHeaders({ served, result, toolWasHealed = false }) {
  return {
    "x-infered-cache": "MISS",
    "x-infered-selected-model": served.modelId,
    "x-infered-provider": served.providerId,
    "x-infered-savings-pct": String(served.savingsPct ?? 0),
    "x-infered-latency-ms": String(result.latencyMs || 100),
    "x-infered-ttft-ms": String(result.ttftMs || 80),
    "x-infered-budget-tier": String(served.budgetTier || "0.10"),
    "x-infered-escalation-level": String(served.escalationLevel !== undefined ? served.escalationLevel : 0),
    "x-infered-tool-healed": toolWasHealed ? "true" : "false",
    "x-infered-attempts": String(result.attempts || 1)
  };
}

/**
 * Durable decision record — one row per request into D1.
 * Observability must never break routing: every failure is silently caught.
 */
export async function recordRoutingAnalytics(env, rec) {
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
  } catch (e) {
    // Never throw — observability must not break routing — but never be
    // SILENT either: a dead D1 write is ledger blindness (Sep 5-19 proved it).
    console.error("routing-analytics: D1 write failed:", e?.message || e);
  }
}

export function createStandaloneMockFetch() {
  return async (url, opts) => {
    const body = JSON.parse(opts.body || "{}");
    const prov = opts.headers["X-InferHub-Provider"] || "mock-node";
    const model = body.model || "zai/glm-5.3-flash";
    const prompt = body.messages?.[body.messages.length - 1]?.content || "Hello";
    return jsonResponse({
      id: `chatcmpl-${Date.now()}`,
      object: "chat.completion",
      created: Math.floor(Date.now() / 1000),
      model,
      choices: [{ index: 0, finish_reason: "stop", message: { role: "assistant", content: `[Infered Edge Routing via ${prov}] Model ${model} processed prompt: "${prompt}" successfully.` } }],
      usage: { prompt_tokens: 18, completion_tokens: 22, total_tokens: 40 }
    });
  };
}
