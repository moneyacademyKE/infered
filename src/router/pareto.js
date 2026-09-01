/**
 * Pareto Multi-Objective Routing Engine & Ordered Budget Cascade with Elastic Escalation
 * Balances price savings, speed (EMA latency / TTFT), capability, KV prefix cache session affinity,
 * and progressive budget ceiling escalation ($0.10 -> $0.20 -> $0.30 -> zero-downtime fallback).
 */

import { resolveVirtualModel, getModelMetadata, SOL_BUDGET_FALLBACK_CHAIN } from "./catalog.js";
import { getQuotesForModel, calculateSavingsPct } from "./pricing.js";
import { getProviderStats, isCircuitOpen } from "./metrics.js";

export const DEFAULT_WEIGHTS = {
  price: 0.5,
  speed: 0.3,
  quality: 0.2
};

export const ROUTING_POLICIES = {
  balanced: { price: 0.5, speed: 0.3, quality: 0.2 },
  "cost-optimized": { price: 0.8, speed: 0.1, quality: 0.1 },
  "speed-first": { price: 0.1, speed: 0.8, quality: 0.1 },
  "quality-first": { price: 0.1, speed: 0.2, quality: 0.7 },
  "sol-budget-cascade": { price: 0.6, speed: 0.2, quality: 0.2, maxFallbackPrice: 0.10 }
};

const CACHE_AFFINITY_BONUS = 0.25;
export const DEFAULT_BUDGET_LADDER = [0.10, 0.20, 0.30, Infinity];

/**
 * Resolves candidates for a specific price ceiling tier.
 */
function evaluateCascadeTier({
  chain,
  priceCache,
  metricsStore,
  ceiling,
  escalationLevel,
  sessionAffinityProvider
}) {
  const candidates = [];

  for (let i = 0; i < chain.length; i++) {
    const modelId = chain[i];
    const isPrimary = (i === 0);
    const meta = getModelMetadata(modelId);
    const quotes = getQuotesForModel(priceCache, modelId);

    for (const quote of quotes) {
      const providerId = quote.providerId;
      const stats = getProviderStats(metricsStore, providerId, modelId);
      const circuitTripped = isCircuitOpen(metricsStore, providerId, modelId);
      const savingsPct = calculateSavingsPct(modelId, quote);
      const outputTokenPrice = quote.completion !== undefined ? quote.completion : quote.blendedPrice;
      const blendedPrice = quote.blendedPrice;

      // Strict output token budget ceiling: Disqualify any candidate whose output token price exceeds the ceiling
      if (ceiling !== Infinity) {
        if (outputTokenPrice > ceiling) {
          continue;
        }
      }

      let priorityScore = (chain.length - i) * 10.0;
      
      const hasAffinity = (sessionAffinityProvider && sessionAffinityProvider === providerId);
      if (hasAffinity) {
        priorityScore += 2.5;
      }

      if (circuitTripped) {
        priorityScore -= 50.0;
      } else if (stats.totalRequests > 0) {
        const failureRate = stats.failedRequests / stats.totalRequests;
        priorityScore -= failureRate * 10.0;
      }

      candidates.push({
        providerId,
        modelId,
        family: meta.family,
        tier: meta.tier,
        quality: meta.quality,
        quote,
        blendedPrice,
        outputTokenPrice,
        savingsPct,
        emaLatency: stats.emaLatency,
        emaTtft: stats.emaTtft,
        totalRequests: stats.totalRequests,
        failedRequests: stats.failedRequests,
        circuitTripped,
        isPrimary,
        hasAffinity,
        budgetTier: ceiling === Infinity ? "unconstrained-fallback" : ceiling,
        escalationLevel,
        chainIndex: i,
        utility: Number(priorityScore.toFixed(3))
      });
    }
  }

  candidates.sort((a, b) => {
    if (a.circuitTripped !== b.circuitTripped) {
      return a.circuitTripped ? 1 : -1;
    }
    if (a.chainIndex !== b.chainIndex) {
      return a.chainIndex - b.chainIndex;
    }
    return b.utility - a.utility;
  });

  return candidates;
}

/**
 * Handles explicit ordered fallback cascades with progressive budget escalation.
 */
function rankOrderedBudgetCascade({
  model,
  priceCache,
  metricsStore,
  maxFallbackPrice = null,
  sessionAffinityProvider = null
}) {
  const chain = (model === "infered/sol-budget" || model === "infered/cascade")
    ? SOL_BUDGET_FALLBACK_CHAIN
    : resolveVirtualModel(model);

  const ladder = maxFallbackPrice !== null
    ? [maxFallbackPrice, 0.20, 0.30, Infinity].filter((v, idx, arr) => arr.indexOf(v) === idx)
    : DEFAULT_BUDGET_LADDER;

  for (let level = 0; level < ladder.length; level++) {
    const ceiling = ladder[level];
    const candidates = evaluateCascadeTier({
      chain,
      priceCache,
      metricsStore,
      ceiling,
      escalationLevel: level,
      sessionAffinityProvider
    });

    const healthyCandidates = candidates.filter(c => !c.circuitTripped);
    if (healthyCandidates.length > 0) {
      return candidates;
    }
    if (level === ladder.length - 1 && candidates.length > 0) {
      return candidates;
    }
  }

  return [];
}

/**
 * Evaluates and scores all available provider/model candidates.
 */
export function rankCandidates({
  model,
  priceCache,
  metricsStore,
  weights = DEFAULT_WEIGHTS,
  maxFallbackPrice = null,
  sessionAffinityProvider = null
}) {
  const isCascadeRequest = model === "infered/sol-budget" ||
                           model === "infered/cascade" ||
                           maxFallbackPrice !== null;

  if (isCascadeRequest) {
    return rankOrderedBudgetCascade({
      model,
      priceCache,
      metricsStore,
      maxFallbackPrice,
      sessionAffinityProvider
    });
  }

  const candidateModels = resolveVirtualModel(model);
  const rawCandidates = [];

  for (const modelId of candidateModels) {
    const meta = getModelMetadata(modelId);
    const quotes = getQuotesForModel(priceCache, modelId);

    for (const quote of quotes) {
      const providerId = quote.providerId;
      const stats = getProviderStats(metricsStore, providerId, modelId);
      const circuitTripped = isCircuitOpen(metricsStore, providerId, modelId);
      const savingsPct = calculateSavingsPct(modelId, quote);
      const hasAffinity = (sessionAffinityProvider && sessionAffinityProvider === providerId);

      rawCandidates.push({
        providerId,
        modelId,
        family: meta.family,
        tier: meta.tier,
        quality: meta.quality,
        quote,
        blendedPrice: quote.blendedPrice,
        outputTokenPrice: quote.completion || quote.blendedPrice,
        savingsPct,
        emaLatency: stats.emaLatency,
        emaTtft: stats.emaTtft,
        totalRequests: stats.totalRequests,
        failedRequests: stats.failedRequests,
        circuitTripped,
        hasAffinity,
        budgetTier: "pareto-optimized",
        escalationLevel: 0
      });
    }
  }

  if (rawCandidates.length === 0) return [];

  const minPrice = Math.min(...rawCandidates.map(c => c.blendedPrice));
  const maxPrice = Math.max(...rawCandidates.map(c => c.blendedPrice));
  const minLat = Math.min(...rawCandidates.map(c => c.emaLatency));
  const maxLat = Math.max(...rawCandidates.map(c => c.emaLatency));

  const scored = rawCandidates.map(c => {
    const priceScore = maxPrice === minPrice ? 1.0 : (maxPrice - c.blendedPrice) / (maxPrice - minPrice);
    const speedScore = maxLat === minLat ? 1.0 : (maxLat - c.emaLatency) / (maxLat - minLat);
    const qualityScore = c.quality;
    const affinityBonus = c.hasAffinity ? CACHE_AFFINITY_BONUS : 0;

    let errorPenalty = 0;
    if (c.circuitTripped) {
      errorPenalty = 5.0;
    } else if (c.totalRequests > 0) {
      errorPenalty = (c.failedRequests / c.totalRequests) * 2.0;
    }

    const utility = Number((
      (weights.price * priceScore) +
      (weights.speed * speedScore) +
      (weights.quality * qualityScore) +
      affinityBonus -
      errorPenalty
    ).toFixed(4));

    return {
      ...c,
      priceScore: Number(priceScore.toFixed(3)),
      speedScore: Number(speedScore.toFixed(3)),
      qualityScore: Number(qualityScore.toFixed(3)),
      affinityBonus,
      errorPenalty: Number(errorPenalty.toFixed(3)),
      utility
    };
  });

  scored.sort((a, b) => b.utility - a.utility);
  return scored;
}

export function findParetoFrontier(candidates) {
  const frontier = [];
  for (const c1 of candidates) {
    let isDominated = false;
    for (const c2 of candidates) {
      if (c1 === c2) continue;
      const priceBetter = c2.blendedPrice <= c1.blendedPrice;
      const speedBetter = c2.emaLatency <= c1.emaLatency;
      const qualityBetter = c2.quality >= c1.quality;
      const strictlyBetter = (c2.blendedPrice < c1.blendedPrice) ||
                             (c2.emaLatency < c1.emaLatency) ||
                             (c2.quality > c1.quality);

      if (priceBetter && speedBetter && qualityBetter && strictlyBetter && !c2.circuitTripped) {
        isDominated = true;
        break;
      }
    }
    if (!isDominated) frontier.push(c1);
  }
  return frontier;
}
