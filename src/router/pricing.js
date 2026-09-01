/**
 * Dynamic Spot Pricing & Market Ingestion
 * Pure functions for market spot quotes, discount calculus, and InferHub ask book parsing.
 */

import { getOfficialPrice, OFFICIAL_PRICES } from "./catalog.js";

const DEFAULT_CACHE_TTL_MS = 15000;

export function calculateBlendedPrice(promptPrice, completionPrice) {
  return Number(((promptPrice * 0.25) + (completionPrice * 0.75)).toFixed(4));
}

export function createDefaultMarketQuotes() {
  const providers = ["inferhub-alpha", "inferhub-beta", "inferhub-gamma"];
  const quotes = [];

  for (const [modelId, official] of Object.entries(OFFICIAL_PRICES)) {
    providers.forEach((provId, idx) => {
      const discountMultipliers = [0.45, 0.65, 0.85];
      const mult = discountMultipliers[idx % discountMultipliers.length];
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

    // Create a quote for each available ask tier in the order book
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
