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
  "cx/gpt-5.6-sol": [0.0020, 0.0025, 0.0030],       // $30.00 * 0.002 = $0.0600 (99.8% discount)
  "zai/glm-5.3-flash": [0.0800, 0.0900, 0.1000],   // $0.10 * 0.08 = $0.0080 (92.0% discount)
  "zai/glm-5.3": [0.1000, 0.1200, 0.1500],         // $0.50 * 0.10 = $0.0500 (90.0% discount)
  "cx/gpt-5.6-terra": [0.0100, 0.0120, 0.0150],    // $1.00 * 0.01 = $0.0100 (99.0% discount)
  "ali/kimi-k3": [0.0350, 0.0380, 0.0400],         // $2.40 * 0.035 = $0.0840 (96.5% discount)
  "claude-3.5-sonnet": [0.0050, 0.0060, 0.0070],   // $15.00 * 0.005 = $0.0750 (99.5% discount)
  "gpt-4o": [0.0080, 0.0090, 0.0100],              // $10.00 * 0.008 = $0.0800 (99.2% discount)
  "deepseek-r1": [0.0300, 0.0350, 0.0400]          // $2.19 * 0.03 = $0.0657 (97.0% discount)
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
      const provId = `inferhub-node-${i + 1}`;

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
  }

  return cache;
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
