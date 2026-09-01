/**
 * Metrics, Usage Monitoring & Circuit Breaker Store
 * Pure functions and EMA tracking for node speed, token usage, dollar savings, and switch events.
 */

const DEFAULT_ALPHA = 0.3;
const CIRCUIT_BREAKER_WINDOW = 10;
const CIRCUIT_BREAKER_FAILURE_THRESHOLD = 0.4;
const CIRCUIT_RESET_TIMEOUT_MS = 30000;

export function createMetricsStore() {
  return {
    providers: {},
    history: [],
    usage: {
      totalRequests: 0,
      promptTokens: 0,
      completionTokens: 0,
      totalTokens: 0,
      totalCostUsd: 0,
      totalOfficialCostUsd: 0,
      totalSavingsUsd: 0,
      currentActiveModel: null,
      switchEvents: []
    }
  };
}

function getStatsKey(providerId, modelId) {
  return `${providerId || "default"}::${modelId}`;
}

export function recordSample(store, providerId, modelId, sample, alpha = DEFAULT_ALPHA) {
  const key = getStatsKey(providerId, modelId);
  const now = Date.now();
  
  if (!store.providers[key]) {
    store.providers[key] = {
      providerId: providerId || "default",
      modelId,
      emaLatency: sample.latencyMs || 500,
      emaTtft: sample.ttftMs || 250,
      totalRequests: 0,
      successfulRequests: 0,
      failedRequests: 0,
      recentSamples: [],
      circuitState: "closed",
      circuitTrippedAt: null,
      lastSeenAt: now
    };
  }

  const stats = store.providers[key];
  stats.lastSeenAt = now;
  stats.totalRequests += 1;

  if (sample.success) {
    stats.successfulRequests += 1;
    if (stats.totalRequests === 1) {
      stats.emaLatency = sample.latencyMs;
      stats.emaTtft = sample.ttftMs || sample.latencyMs;
    } else {
      stats.emaLatency = Number((alpha * sample.latencyMs + (1 - alpha) * stats.emaLatency).toFixed(2));
      if (sample.ttftMs) {
        stats.emaTtft = Number((alpha * sample.ttftMs + (1 - alpha) * stats.emaTtft).toFixed(2));
      }
    }

    if (stats.circuitState === "half-open") {
      stats.circuitState = "closed";
      stats.circuitTrippedAt = null;
    }
  } else {
    stats.failedRequests += 1;
  }

  stats.recentSamples.push({
    success: sample.success,
    latencyMs: sample.latencyMs,
    timestamp: now
  });
  if (stats.recentSamples.length > CIRCUIT_BREAKER_WINDOW) {
    stats.recentSamples.shift();
  }

  if (stats.recentSamples.length >= 3) {
    const failures = stats.recentSamples.filter(s => !s.success).length;
    const failureRate = failures / stats.recentSamples.length;

    if (failureRate >= CIRCUIT_BREAKER_FAILURE_THRESHOLD) {
      stats.circuitState = "open";
      stats.circuitTrippedAt = now;
    }
  }

  store.history.unshift({
    timestamp: now,
    providerId: providerId || "default",
    modelId,
    latencyMs: sample.latencyMs,
    ttftMs: sample.ttftMs,
    success: sample.success,
    savingsPct: sample.savingsPct || 0,
    costUsd: sample.costUsd || 0
  });
  if (store.history.length > 50) {
    store.history.pop();
  }

  return { ...stats };
}

/**
 * Records token usage, calculates dollar spend and savings, and tracks model switch events.
 */
export function recordUsage(store, usageEvent) {
  const u = store.usage;
  const now = Date.now();

  const promptTokens = usageEvent.promptTokens || 0;
  const completionTokens = usageEvent.completionTokens || 0;
  const totalTokens = promptTokens + completionTokens;
  const costUsd = usageEvent.costUsd || 0;
  const officialCostUsd = usageEvent.officialCostUsd || costUsd;
  const savingsUsd = Math.max(0, officialCostUsd - costUsd);

  u.totalRequests += 1;
  u.promptTokens += promptTokens;
  u.completionTokens += completionTokens;
  u.totalTokens += totalTokens;
  u.totalCostUsd = Number((u.totalCostUsd + costUsd).toFixed(6));
  u.totalOfficialCostUsd = Number((u.totalOfficialCostUsd + officialCostUsd).toFixed(6));
  u.totalSavingsUsd = Number((u.totalSavingsUsd + savingsUsd).toFixed(6));

  // Detect model switch (upgrade or downgrade)
  if (u.currentActiveModel && u.currentActiveModel !== usageEvent.modelId) {
    u.switchEvents.unshift({
      timestamp: now,
      fromModel: u.currentActiveModel,
      toModel: usageEvent.modelId,
      reason: usageEvent.reason || "dynamic-threshold-switch",
      costUsd,
      savingsUsd
    });
    if (u.switchEvents.length > 50) {
      u.switchEvents.pop();
    }
  }

  u.currentActiveModel = usageEvent.modelId;
  return { ...u };
}

export function getUsageSummary(store) {
  return {
    ...store.usage,
    switchEvents: [...store.usage.switchEvents]
  };
}

export function getProviderStats(store, providerId, modelId) {
  const key = getStatsKey(providerId, modelId);
  const stats = store.providers[key];
  if (!stats) {
    return {
      providerId,
      modelId,
      emaLatency: 400,
      emaTtft: 200,
      totalRequests: 0,
      successfulRequests: 0,
      failedRequests: 0,
      circuitState: "closed",
      recentSamples: []
    };
  }
  return { ...stats, recentSamples: [...stats.recentSamples] };
}

export function isCircuitOpen(store, providerId, modelId) {
  const stats = getProviderStats(store, providerId, modelId);
  if (stats.circuitState === "open") {
    if (Date.now() - (stats.circuitTrippedAt || 0) > CIRCUIT_RESET_TIMEOUT_MS) {
      if (store.providers[getStatsKey(providerId, modelId)]) {
        store.providers[getStatsKey(providerId, modelId)].circuitState = "half-open";
      }
      return false;
    }
    return true;
  }
  return false;
}
