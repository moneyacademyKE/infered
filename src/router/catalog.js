/**
 * Model Catalog & Virtual Tier Mappings
 * Pure data structures and resolution logic for Infered router.
 */

// Model capabilities and quality benchmarks (normalized 0.0 - 1.0)
export const MODEL_TIERS = {
  // 2026 Frontier & Agentic Models
  "cx/gpt-5.6-sol": { tier: "frontier-reasoning", quality: 0.98, family: "openai", context: 256000 },
  "cx/gpt-5.6-terra": { tier: "balanced-frontier", quality: 0.91, family: "openai", context: 128000 },
  "zai/glm-5.3": { tier: "agentic-coding", quality: 0.93, family: "z.ai", context: 1000000 },
  "zai/glm-5.3-flash": { tier: "fast-agentic", quality: 0.85, family: "z.ai", context: 1000000 },
  "ali/kimi-k3": { tier: "moe-frontier", quality: 0.94, family: "moonshot", context: 1000000 },

  // Established Reasoning tier
  "deepseek-r1": { tier: "reasoning", quality: 0.95, family: "deepseek", context: 64000 },
  "o1-preview": { tier: "reasoning", quality: 0.96, family: "openai", context: 128000 },
  "o3-mini": { tier: "reasoning", quality: 0.92, family: "openai", context: 128000 },
  "qwq-32b-preview": { tier: "reasoning", quality: 0.88, family: "qwen", context: 32000 },

  // Established Smart tier
  "claude-3.5-sonnet": { tier: "smart", quality: 0.96, family: "anthropic", context: 200000 },
  "gpt-4o": { tier: "smart", quality: 0.93, family: "openai", context: 128000 },
  "llama-3.3-70b": { tier: "smart", quality: 0.90, family: "meta", context: 128000 },
  "qwen-2.5-72b": { tier: "smart", quality: 0.89, family: "qwen", context: 64000 },

  // Fast / Light tier
  "llama-3.1-8b-instant": { tier: "fast", quality: 0.72, family: "meta", context: 128000 },
  "claude-3-haiku": { tier: "fast", quality: 0.75, family: "anthropic", context: 200000 },
  "gpt-4o-mini": { tier: "fast", quality: 0.78, family: "openai", context: 128000 },
  "mistral-7b-instruct": { tier: "fast", quality: 0.68, family: "mistral", context: 32000 },
  "deepseek-v3": { tier: "fast", quality: 0.85, family: "deepseek", context: 64000 }
};

// Official provider list prices in USD per 1M tokens [Prompt, Completion]
export const OFFICIAL_PRICES = {
  // 2026 Frontier & Agentic Models
  "cx/gpt-5.6-sol": { prompt: 4.00, completion: 16.00 },
  "cx/gpt-5.6-terra": { prompt: 0.30, completion: 0.90 },
  "zai/glm-5.3": { prompt: 0.20, completion: 0.40 },
  "zai/glm-5.3-flash": { prompt: 0.06, completion: 0.10 },
  "ali/kimi-k3": { prompt: 0.15, completion: 0.30 },

  // Established models
  "claude-3.5-sonnet": { prompt: 3.00, completion: 15.00 },
  "gpt-4o": { prompt: 2.50, completion: 10.00 },
  "deepseek-r1": { prompt: 0.55, completion: 2.19 },
  "deepseek-v3": { prompt: 0.14, completion: 0.28 },
  "o1-preview": { prompt: 15.00, completion: 60.00 },
  "o3-mini": { prompt: 1.10, completion: 4.40 },
  "llama-3.3-70b": { prompt: 0.90, completion: 0.90 },
  "llama-3.1-8b-instant": { prompt: 0.10, completion: 0.10 },
  "gpt-4o-mini": { prompt: 0.15, completion: 0.60 },
  "claude-3-haiku": { prompt: 0.25, completion: 1.25 },
  "qwen-2.5-72b": { prompt: 0.40, completion: 0.40 },
  "qwq-32b-preview": { prompt: 0.50, completion: 1.50 },
  "mistral-7b-instruct": { prompt: 0.20, completion: 0.20 }
};

// Fallback cascade ordering
export const SOL_BUDGET_FALLBACK_CHAIN = [
  "cx/gpt-5.6-sol",
  "zai/glm-5.3-flash",
  "zai/glm-5.3",
  "ali/kimi-k3",
  "cx/gpt-5.6-terra"
];

// Virtual aliases mapping to candidate model sets
export const VIRTUAL_ALIASES = {
  "infered/auto": Object.keys(MODEL_TIERS),
  "infered/sol-budget": SOL_BUDGET_FALLBACK_CHAIN,
  "infered/cascade": SOL_BUDGET_FALLBACK_CHAIN,
  "infered/fast": ["zai/glm-5.3-flash", "llama-3.1-8b-instant", "gpt-4o-mini", "claude-3-haiku", "deepseek-v3"],
  "infered/smart": ["cx/gpt-5.6-terra", "zai/glm-5.3", "claude-3.5-sonnet", "gpt-4o", "llama-3.3-70b"],
  "infered/reasoning": ["cx/gpt-5.6-sol", "ali/kimi-k3", "deepseek-r1", "o1-preview", "o3-mini"],
  "infered/cheap": ["zai/glm-5.3-flash", "llama-3.1-8b-instant", "deepseek-v3", "gpt-4o-mini"],
  
  // Specific virtual models
  "infered/cx/gpt-5.6-sol": ["cx/gpt-5.6-sol"],
  "infered/zai/glm-5.3-flash": ["zai/glm-5.3-flash"],
  "infered/zai/glm-5.3": ["zai/glm-5.3"],
  "infered/ali/kimi-k3": ["ali/kimi-k3"],
  "infered/cx/gpt-5.6-terra": ["cx/gpt-5.6-terra"],
  "infered/claude-3.5-sonnet": ["claude-3.5-sonnet"],
  "infered/deepseek-r1": ["deepseek-r1"]
};

/**
 * Resolves a requested model name to a list of candidate model IDs.
 */
export function resolveVirtualModel(modelName) {
  if (!modelName) return VIRTUAL_ALIASES["infered/auto"];
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

  return VIRTUAL_ALIASES["infered/auto"];
}

export function getOfficialPrice(modelId) {
  return OFFICIAL_PRICES[modelId] || { prompt: 1.00, completion: 2.00 };
}

export function getModelMetadata(modelId) {
  return MODEL_TIERS[modelId] || { tier: "general", quality: 0.70, family: "unknown", context: 32000 };
}
