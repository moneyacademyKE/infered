/**
 * Dynamic Spot Pricing & Market Ingestion
 * Pure functions for market spot quotes, discount calculus, and InferHub ask book parsing.
 */

import { getOfficialPrice, OFFICIAL_PRICES } from "./catalog.js";

const DEFAULT_CACHE_TTL_MS = 15000;

export function calculateBlendedPrice(promptPrice, completionPrice) {
  return Number(((promptPrice * 0.25) + (completionPrice * 0.75)).toFixed(4));
}

/**
 * Calibrated spot multipliers to reflect real InferHub spot marketplace rates
 * where output tokens trade <= $0.10 / 1M tokens.
 */
const MODEL_SPOT_MULTIPLIERS = {
  // Ratios of official completion price. Seeded min asks match the live
  // InferHub book (deadname-audit prober snapshot 2026-09-23).
  "cx/gpt-6-astra": [0.0190, 0.0250, 0.0300],      // $50.00 * 0.019 = $0.95 (feed min ask, 29 asks)
  "ali/glm-5.3": [0.0300, 0.0350, 0.0400],         // $4.40 * 0.03 = $0.132 (feed min ask, 7 asks)
  "ali/qwen3.8-max-0902": [0.0050, 0.0060, 0.0080], // $6.00 * 0.005 = $0.03 (feed min ask, 11 asks)
  "zai/glm-5.3-flash": [0.0990, 0.1100, 0.1300],   // $0.50 * 0.099 = $0.0495 (feed min ask, 9 asks)
  "zai/glm-5.3": [0.0400, 0.0500, 0.0600],         // $4.40 * 0.04 = $0.176 (feed min ask, 10 asks)
  "cx/gpt-5.6-terra": [0.0010, 0.0012, 0.0015],    // $12.00 * 0.001 = $0.012 (feed min ask, 24 asks)
  "ali/kimi-k3": [0.0230, 0.0260, 0.0300]          // $15.00 * 0.023 = $0.345 (feed min ask, 8 asks)
};

export function createDefaultMarketQuotes() {
  const providers = ["inferhub-alpha", "inferhub-beta", "inferhub-gamma"];
  const quotes = [];

  for (const [modelId, official] of Object.entries(OFFICIAL_PRICES)) {
    const multipliers = MODEL_SPOT_MULTIPLIERS[modelId] || [0.0100, 0.0150, 0.0200]; // 98-99% default discount

    providers.forEach((provId, idx) => {
      const mult = multipliers[idx % multipliers.length];
      const spotPrompt = Number((official.prompt * mult).toFixed(4));
      const spotCompletion = Number((official.completion * mult).toFixed(4));
      
      quotes.push({
        providerId: provId,
        modelId,
        prompt: spotPrompt,
        completion: spotCompletion,
        blendedPrice: calculateBlendedPrice(spotPrompt, spotCompletion),
        savingsPct: calculateSavingsPct(modelId, { prompt: spotPrompt, completion: spotCompletion }),
        updatedAt: Date.now()
      });
    });
  }

  return quotes;
}

export function createPriceCache(initialQuotes = null) {
  const cache = {
    quotes: {},
    modelToProviders: {},
    lastSyncedAt: Date.now()
  };

  const seed = initialQuotes !== null ? initialQuotes : createDefaultMarketQuotes();
  updateSpotPrices(cache, seed);
  return cache;
}

function getQuoteKey(providerId, modelId) {
  return `${providerId || "default"}::${modelId}`;
}

export function updateSpotPrices(cache, quoteList) {
  const now = Date.now();
  cache.lastSyncedAt = now;

  for (const raw of quoteList) {
    const providerId = raw.providerId || "default";
    const modelId = raw.modelId;
    const prompt = Number(raw.prompt);
    const completion = Number(raw.completion);
    const blended = calculateBlendedPrice(prompt, completion);
    const savingsPct = raw.savingsPct !== undefined ? raw.savingsPct : calculateSavingsPct(modelId, { prompt, completion });

    const quote = {
      providerId,
      modelId,
      prompt,
      completion,
      blendedPrice: blended,
      savingsPct,
      priceSource: "spot",
      updatedAt: now
    };

    cache.quotes[getQuoteKey(providerId, modelId)] = quote;

    if (!cache.modelToProviders[modelId]) {
      cache.modelToProviders[modelId] = new Set();
    }
    cache.modelToProviders[modelId].add(providerId);
  }

  return cache;
}

/**
 * Parses and ingests live InferHub /v1/models response containing asks_in and asks_out.
 */
export function ingestInferHubModelsResponse(cache, apiResponse) {
  const modelsData = apiResponse?.data || (Array.isArray(apiResponse) ? apiResponse : []);
  const quoteList = [];
  const freshKeysByModel = new Map();

  for (const m of modelsData) {
    const modelId = m.id;
    const pricing = m.pricing;
    if (!pricing) continue;

    const asksIn = pricing.asks_in || (pricing.min_ask_in !== undefined ? [pricing.min_ask_in] : []);
    const asksOut = pricing.asks_out || (pricing.min_ask_out !== undefined ? [pricing.min_ask_out] : []);

    const askCount = Math.max(asksIn.length, asksOut.length);
    for (let i = 0; i < askCount; i++) {
      const pIn = asksIn[i] !== undefined ? asksIn[i] : (pricing.min_ask_in || pricing.official_in);
      const pOut = asksOut[i] !== undefined ? asksOut[i] : (pricing.min_ask_out || pricing.official_out);
      const provId = getInferHubNodeId(modelId, pIn, pOut);

      let freshKeys = freshKeysByModel.get(modelId);
      if (!freshKeys) {
        freshKeys = new Set();
        freshKeysByModel.set(modelId, freshKeys);
      }
      freshKeys.add(getQuoteKey(provId, modelId));

      quoteList.push({
        providerId: provId,
        modelId,
        prompt: Number(pIn),
        completion: Number(pOut),
        blendedPrice: calculateBlendedPrice(pIn, pOut),
        savingsPct: calculateSavingsPct(modelId, { prompt: pIn, completion: pOut })
      });
    }
  }

  if (quoteList.length > 0) {
    updateSpotPrices(cache, quoteList);
    evictStaleInferHubNodes(cache, freshKeysByModel);
  }

  return cache;
}

// Stable node identity (H4): derived from the ASK PRICES, not the array
// index — an unchanged node keeps its id across syncs, so circuit-breaker
// history and per-node metrics survive market refreshes.
function getInferHubNodeId(modelId, pIn, pOut) {
  const slug = String(modelId).replace(/[^a-zA-Z0-9]+/g, "-");
  return `inferhub-node-${slug}-in${Number(pIn).toFixed(6)}-out${Number(pOut).toFixed(6)}`;
}

// Ghost-node eviction (H4): a model's fresh ask set is the whole truth about
// the market. Once live quotes arrive for a model, initial synthetic seeds
// (inferhub-alpha/beta/gamma) are purged so phantom capacity cannot win routing,
// and any inferhub-node not in the fresh ask set is evicted.
function evictStaleInferHubNodes(cache, freshKeysByModel) {
  for (const [modelId, freshKeys] of freshKeysByModel) {
    const providerSet = cache.modelToProviders[modelId];
    if (!providerSet) continue;
    for (const provId of Array.from(providerSet)) {
      const key = getQuoteKey(provId, modelId);
      if (provId === "inferhub-alpha" || provId === "inferhub-beta" || provId === "inferhub-gamma") {
        delete cache.quotes[key];
        providerSet.delete(provId);
      } else if (provId.startsWith("inferhub-node-") && !freshKeys.has(key)) {
        delete cache.quotes[key];
        providerSet.delete(provId);
      }
    }
  }
}

export function getSpotQuote(cache, providerId, modelId) {
  const key = getQuoteKey(providerId, modelId);
  if (cache.quotes[key]) {
    return { ...cache.quotes[key] };
  }
  
  const official = getOfficialPrice(modelId);
  return {
    providerId: providerId || "official",
    modelId,
    prompt: official.prompt,
    completion: official.completion,
    blendedPrice: calculateBlendedPrice(official.prompt, official.completion),
    savingsPct: 0,
    priceSource: "official",
    updatedAt: Date.now()
  };
}

export function getQuotesForModel(cache, modelId) {
  const providerSet = cache.modelToProviders[modelId];
  if (!providerSet || providerSet.size === 0) {
    return [getSpotQuote(cache, "official", modelId)];
  }
  return Array.from(providerSet)
    .map(provId => getSpotQuote(cache, provId, modelId))
    .sort((a, b) => a.blendedPrice - b.blendedPrice);
}

export function calculateSavingsPct(modelId, spotQuote) {
  const official = getOfficialPrice(modelId);
  const officialBlended = calculateBlendedPrice(official.prompt, official.completion);
  if (officialBlended <= 0) return 0;

  const spotBlended = spotQuote.blendedPrice || calculateBlendedPrice(spotQuote.prompt, spotQuote.completion);
  const savings = ((officialBlended - spotBlended) / officialBlended) * 100;
  return Number(Math.max(0, savings).toFixed(1));
}

/**
 * Static rate card for display: official list prices per model, with the
 * calibration flag from the spot-multiplier table. Display layers join
 * recorded usage on top — this function only ever reports known numbers.
 */
export function buildRatecard() {
  return Object.entries(OFFICIAL_PRICES).map(([modelId, p]) => {
    const mult = MODEL_SPOT_MULTIPLIERS[modelId];
    return {
      modelId,
      prompt: p.prompt,
      completion: p.completion,
      blended: calculateBlendedPrice(p.prompt, p.completion),
      calibrated: Boolean(mult)
    };
  });
}
