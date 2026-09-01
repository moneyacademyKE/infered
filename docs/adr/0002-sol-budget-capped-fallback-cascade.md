# ADR 0002: Sol Budget-Capped Fallback Cascade Policy

## Status
**Accepted & Certified** (2026-09-01)

## Context
Applications requiring high-end frontier intelligence want `cx/gpt-5.6-sol` as the default primary model. When `cx/gpt-5.6-sol` is unavailable, experiencing outages, or outside cost parameters, the router must fallback in strict prioritized order:
1. `zai/glm-5.3-flash`
2. `zai/glm-5.3`
3. `ali/kimi-k3`
4. `cx/gpt-5.6-terra`

Crucially, fallback models must only be selected when their current spot marketplace price is **$\le \$0.10$ / 1M tokens**. If a model in the fallback chain costs more than $\$0.10$, it must be skipped in favor of subsequent candidates meeting the budget ceiling.

## Decision
1. Implemented data-driven ordered cascade resolver in [`src/router/pareto.js`](file:///Users/moe/Desktop/infered/src/router/pareto.js).
2. Added `infered/sol-budget` and `infered/cascade` virtual model aliases to the model catalog [`src/router/catalog.js`](file:///Users/moe/Desktop/infered/src/router/catalog.js).
3. Added support for `X-Infered-Max-Price` header and `env.MAX_FALLBACK_PRICE` configuration.
4. Enforced price ceiling filtering: any fallback candidate with $\text{blendedPrice} > \text{maxFallbackPrice}$ is excluded before candidate dispatch.

## Consequences
- Guaranteed upper-bound cost control for secondary fallbacks ($\le \$0.10$ / 1M tokens).
- Zero-downtime automatic degradation from flagship Sol to efficient agentic models.
- Full verification under simulated real-time market spot fluctuations.
