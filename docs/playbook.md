# Infered Project Playbook & Operational Guide

## 1. Quick Start Commands
All commands run via Babashka (`bb`):

```bash
# 1. Run full unit and integration test suite
bb test-all

# 2. Run real-time dynamic market spot price simulation & routing benchmark
bb sim

# 3. Verify LOC compliance (<500 LOC per file) and architecture rules
bb lint

# 4. Start local development server on http://localhost:8787
bb dev
```

## 2. Deploying to Cloudflare Workers with Wrangler
Using `bunx wrangler`:

```bash
# Local development with Wrangler
bunx wrangler dev

# Deploy to Cloudflare Edge worldwide
bunx wrangler deploy
```

## 3. Model Catalog (current: 5 chain products)
Routing is data-driven from `src/router/catalog.js` — chains are plain arrays; the router resolves the first link that has a live, in-budget spot ask:

| Product | Cascade order |
|---|---|
| `infered/glm-budget` *(default)* | `ali/glm-5.3` → `zai/glm-5.3-flash` → `zai/glm-5.3` → `ali/kimi-k3` → `cx/gpt-5.6-terra` |
| `infered/astra-budget` | `cx/gpt-6-astra` → `zai/glm-5.3-flash` → `ali/kimi-k3` |
| `infered/astra-terra` | `cx/gpt-6-astra` → `cx/gpt-5.6-terra` → `ali/kimi-k3` → `zai/glm-5.3-flash` |
| `infered/terra-kimi` | `cx/gpt-5.6-terra` → `ali/kimi-k3` → `zai/glm-5.3-flash` |
| `infered/kimi-glm` | `ali/kimi-k3` → `zai/glm-5.3-flash` → `ali/qwen3.8-max-0902` |

- Unknown/unregistered model names and requests without a model fall to `infered/glm-budget`.
- Budget ladder: $0.50 tier-0 ceiling (spot asks only), then an unconstrained last-resort tier that admits official list prices (counted as `officialLastResortServes` on `/v1/metrics`).
- `cx/gpt-6-astra` is ceiling-exempt: any verified spot ask is eligible.
- `X-Infered-Tier: strong` starts a chain at its designated strong link (`CHAIN_STRONG_LINKS` in catalog.js).
- `X-Session-ID` (or an automatic conversation-head fingerprint) pins the served model within a session.
- Raw model requests (e.g. `ali/qwen3.8-max-0902`) route to that exact model if it is registered in `MODEL_TIERS`; unregistered names fall to the default chain. Retired names (`cx/gpt-5.6-sol`, `ali/qwen3.8-max`) are excised — they land on the default chain or fail fast (403-class upstream refusals end the candidate chase immediately).

## 4. Tuning Pareto Weights
You can tune weights globally via environment variables in `wrangler.jsonc` or per-request via the `X-Infered-Weights` header:

```json
{
  "price": 0.6,
  "speed": 0.3,
  "quality": 0.1
}
```

## 5. Telemetry & Decision Auditing
To inspect live production decisions and latency profiles stored in Cloudflare D1:

```bash
# Run D1 aggregate audit script
bb scripts/d1-audit.bb

# Check recent 60 routing decisions
bb scripts/d1-check.bb
```

