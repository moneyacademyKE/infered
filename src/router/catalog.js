/**
 * Model Catalog & Virtual Tier Mappings
 * Pure data structures and resolution logic for Infered router.
 */

// Model capabilities and quality benchmarks (normalized 0.0 - 1.0).
// Slimmed 2026-09-05 (owner directive): only chain members are catalogued —
// the router sells two policies (astra-budget, glm-budget), not a model zoo.
// Removed names reroute to the default budget chain via resolveVirtualModel.
export const MODEL_TIERS = {
  "cx/gpt-5.6-terra": { tier: "balanced-frontier", quality: 0.91, family: "openai", context: 128000 },
  "zai/glm-5.3": { tier: "agentic-coding", quality: 0.93, family: "z.ai", context: 1000000 },
  "zai/glm-5.3-flash": { tier: "fast-agentic", quality: 0.85, family: "z.ai", context: 1000000 },
  "ali/kimi-k3": { tier: "moe-frontier", quality: 0.94, family: "moonshot", context: 1000000 }
};

// Official provider list prices in USD per 1M tokens [Prompt, Completion]
export const OFFICIAL_PRICES = {
  "cx/gpt-5.6-terra": { prompt: 0.30, completion: 0.90 },
  "zai/glm-5.3": { prompt: 0.20, completion: 0.40 },
  "zai/glm-5.3-flash": { prompt: 0.06, completion: 0.10 },
  "ali/kimi-k3": { prompt: 0.15, completion: 0.30 }
};

// Budget cascade ordering (sol removed 2026-09-05 per owner directive —
// upstream no_capacity made it a liability; starts at glm-5.3-flash).
export const GLM_BUDGET_FALLBACK_CHAIN = [
  "zai/glm-5.3-flash",
  "zai/glm-5.3",
  "ali/kimi-k3",
  "cx/gpt-5.6-terra"
];

// Astra-headed budget cascade: the head activates automatically once
// cx/gpt-6-astra lists live spot asks on InferHub (eligibility skips
// unquoted models, so zai/kimi carry traffic until then).
export const ASTRA_BUDGET_FALLBACK_CHAIN = [
  "cx/gpt-6-astra",
  "zai/glm-5.3-flash",
  "ali/kimi-k3"
];

// Virtual models that route as ordered budget cascades. Single source of truth —
// the router detects cascade requests by lookup here, not by string comparison.
// Exactly two products (2026-09-05): every other name a client sends reroutes
// to the default budget chain.
export const CASCADE_CHAINS = {
  "infered/glm-budget": GLM_BUDGET_FALLBACK_CHAIN,
  "infered/astra-budget": ASTRA_BUDGET_FALLBACK_CHAIN
};

// Default requested model — also where UNRECOGNIZED names land (typos like
// "zai/gm5.3", removed models like raw sol). Unknown must mean "budget-safe
// default chain", never the wide MODEL_TIERS pool where any quoted model can
// win. Matches worker.js's no-model default.
export const DEFAULT_MODEL = "infered/glm-budget";

// Declared sibling-chain fallback: when a chain prices out entirely or every
// candidate fails upstream, the executor may retry exactly once on this chain.
// glm-budget is terminal (no entry — no fallback, no loop).
export const CHAIN_FALLBACKS = {
  "infered/astra-budget": "infered/glm-budget"
};

// Virtual aliases mapping to candidate model sets — the two chains ARE the
// whole surface. Anything unlisted (raw members excepted) lands on the
// default budget chain via resolveVirtualModel.
export const VIRTUAL_ALIASES = {
  ...CASCADE_CHAINS
};

/**
 * Resolves a requested model name to a list of candidate model IDs.
 */
export function resolveVirtualModel(modelName) {
  if (!modelName) return VIRTUAL_ALIASES[DEFAULT_MODEL];
  const cleanName = modelName.trim().toLowerCase();
  
  if (VIRTUAL_ALIASES[cleanName]) {
    return VIRTUAL_ALIASES[cleanName];
  }
  
  const unaliased = cleanName.replace(/^infered\//, "");
  if (MODEL_TIERS[unaliased]) {
    return [unaliased];
  }
  if (MODEL_TIERS[cleanName]) {
    return [cleanName];
  }

  // Unknown names resolve to the default budget chain, not the auto pool —
  // an unrecognized model behaves exactly like no model at all.
  return VIRTUAL_ALIASES[DEFAULT_MODEL];
}

export function getOfficialPrice(modelId) {
  return OFFICIAL_PRICES[modelId] || { prompt: 1.00, completion: 2.00 };
}

export function getModelMetadata(modelId) {
  return MODEL_TIERS[modelId] || { tier: "general", quality: 0.70, family: "unknown", context: 32000 };
}
