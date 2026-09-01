/**
 * Edge Caching & Session Prefix Affinity Store
 * Provides deterministic response caching and KV-cache session affinity routing.
 */

const DEFAULT_CACHE_TTL_MS = 1000 * 60 * 60; // 1 hour for deterministic completions
const SESSION_AFFINITY_TTL_MS = 1000 * 60 * 15; // 15 minutes for conversational sessions

export function createCacheStore() {
  return {
    responseCache: new Map(), // key -> { response, expiresAt }
    sessionAffinity: new Map() // sessionId -> { providerId, modelId, expiresAt }
  };
}

/**
 * Computes a deterministic cache key for a request payload.
 */
export function computeRequestKey(requestBody) {
  const normalized = {
    model: requestBody.model || "default",
    messages: (requestBody.messages || []).map(m => ({
      role: m.role,
      content: typeof m.content === "string" ? m.content.trim() : JSON.stringify(m.content)
    })),
    temperature: requestBody.temperature !== undefined ? requestBody.temperature : 1.0,
    top_p: requestBody.top_p !== undefined ? requestBody.top_p : 1.0,
    tools: requestBody.tools || null
  };

  // Simple string hash representation
  const str = JSON.stringify(normalized);
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    const char = str.charCodeAt(i);
    hash = ((hash << 5) - hash) + char;
    hash |= 0;
  }
  return `infered_cache_${Math.abs(hash)}_${str.length}`;
}

/**
 * Checks and retrieves cached response if available and not expired.
 */
export function getCachedResponse(store, key) {
  const entry = store.responseCache.get(key);
  if (!entry) return null;

  if (Date.now() > entry.expiresAt) {
    store.responseCache.delete(key);
    return null;
  }

  return JSON.parse(JSON.stringify(entry.response));
}

/**
 * Stores a response in the deterministic response cache.
 */
export function putCachedResponse(store, key, response, ttlMs = DEFAULT_CACHE_TTL_MS) {
  // Bound cache size (max 500 items)
  if (store.responseCache.size > 500) {
    const oldestKey = store.responseCache.keys().next().value;
    store.responseCache.delete(oldestKey);
  }

  store.responseCache.set(key, {
    response: JSON.parse(JSON.stringify(response)),
    expiresAt: Date.now() + ttlMs
  });
}

/**
 * Retrieves session affinity (last used node for warm KV prefix caching).
 */
export function getSessionAffinity(store, sessionId) {
  if (!sessionId) return null;
  const entry = store.sessionAffinity.get(sessionId);
  if (!entry) return null;

  if (Date.now() > entry.expiresAt) {
    store.sessionAffinity.delete(sessionId);
    return null;
  }

  return { ...entry };
}

/**
 * Records session affinity to pin subsequent turns to the warm GPU node.
 */
export function setSessionAffinity(store, sessionId, providerId, modelId, ttlMs = SESSION_AFFINITY_TTL_MS) {
  if (!sessionId) return;
  
  if (store.sessionAffinity.size > 1000) {
    const oldestKey = store.sessionAffinity.keys().next().value;
    store.sessionAffinity.delete(oldestKey);
  }

  store.sessionAffinity.set(sessionId, {
    providerId,
    modelId,
    expiresAt: Date.now() + ttlMs
  });
}
