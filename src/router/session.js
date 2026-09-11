/**
 * Session Identity & Durable Model Pins
 *
 * Identity: an explicit X-Session-ID header always wins; otherwise a stable
 * fingerprint is derived from the conversation head (first user message).
 * D1 audit 2026-09-10: 17,738 of 17,754 decisions (99.91%) carried no session
 * id — without derivation, model pinning engaged for 0.1% of traffic. The
 * fingerprint is a hash only: grouping key, never the text, never an auth token.
 *
 * Pins: L1 in-memory (cache.js, 15 min sliding) backed by L2 D1 (60 min sliding)
 * so a pin survives deploys and colo hops. Observability must never break
 * routing: every D1 failure degrades silently to memory-only.
 */

import { getSessionAffinity, setSessionAffinity } from "./cache.js";

const FINGERPRINT_HEAD_CHARS = 512;
const PIN_TTL_MS = 1000 * 60 * 60;
const PIN_CLEANUP_AGE_MS = 1000 * 60 * 60 * 24 * 7;

function djb2(seed, str) {
  let h = seed;
  for (let i = 0; i < str.length; i++) {
    h = ((h << 5) - h + str.charCodeAt(i)) | 0;
  }
  return h >>> 0;
}

function conversationHead(messages) {
  if (!Array.isArray(messages)) return null;
  const firstUser = messages.find(m => m && m.role === "user");
  if (!firstUser) return null;
  const c = firstUser.content;
  const text = typeof c === "string"
    ? c
    : Array.isArray(c)
      ? c.filter(p => p && p.type === "text").map(p => p.text || "").join(" ")
      : null;
  if (!text) return null;
  const normalized = text.replace(/\s+/g, " ").trim();
  return normalized.length > 0 ? normalized.slice(0, FINGERPRINT_HEAD_CHARS) : null;
}

export function fingerprintSessionId(messages) {
  const head = conversationHead(messages);
  if (!head) return null;
  // Two independent djb2 passes (~64 bits): this is a grouping key, not a
  // security boundary — collisions cost a shared model pin, nothing more.
  const a = djb2(5381, head).toString(16).padStart(8, "0");
  const b = djb2(52711, head).toString(16).padStart(8, "0");
  return `fp-${a}${b}`;
}

export function resolveSessionId(request, requestBody) {
  return request.headers.get("X-Session-ID")
      || request.headers.get("X-Session-Affinity")
      || fingerprintSessionId(requestBody?.messages);
}

export async function getSessionPin(store, sessionId, env) {
  if (!sessionId) return null;
  const mem = getSessionAffinity(store, sessionId);
  if (mem) return { providerId: mem.providerId, modelId: mem.modelId };
  try {
    if (!env?.ROUTING_DB) return null;
    const row = await env.ROUTING_DB
      .prepare("SELECT provider, model, updated_at FROM session_pins WHERE session_id = ?1")
      .bind(sessionId)
      .first();
    if (!row || (Date.now() - row.updated_at) > PIN_TTL_MS) return null;
    setSessionAffinity(store, sessionId, row.provider, row.model); // rehydrate L1
    return { providerId: row.provider, modelId: row.model };
  } catch {
    return null;
  }
}

export function recordSessionPin(store, env, ctx, sessionId, providerId, modelId) {
  if (!sessionId || !modelId) return;
  const prior = getSessionAffinity(store, sessionId);
  setSessionAffinity(store, sessionId, providerId, modelId);
  if (prior && prior.modelId === modelId) return; // unchanged: skip the D1 write
  try {
    if (!env?.ROUTING_DB) return;
    const now = Date.now();
    const stmts = [
      env.ROUTING_DB.prepare(
        "INSERT INTO session_pins (session_id, provider, model, updated_at) VALUES (?1,?2,?3,?4) " +
        "ON CONFLICT(session_id) DO UPDATE SET provider=excluded.provider, model=excluded.model, updated_at=excluded.updated_at"
      ).bind(sessionId, providerId ?? null, modelId, now).run()
    ];
    if (Math.random() < 0.01) {
      stmts.push(env.ROUTING_DB
        .prepare("DELETE FROM session_pins WHERE updated_at < ?1")
        .bind(now - PIN_CLEANUP_AGE_MS)
        .run());
    }
    const p = Promise.all(stmts).catch(() => {});
    if (ctx && typeof ctx.waitUntil === "function") ctx.waitUntil(p);
  } catch {}
}
