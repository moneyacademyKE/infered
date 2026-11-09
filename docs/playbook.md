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

## 3. Model Catalog (current: 6 chain products)
Routing is data-driven from `src/router/catalog.js` — chains are plain arrays; the router resolves the first link that has a live, in-budget spot ask:

| Product | Cascade order |
|---|---|
| `infered/glm-budget` *(default)* | `ali/glm-5.3` → `zai/glm-5.3` → `ali/kimi-k3.1-pro` → `cx/gpt-6.1-terra` → `ali/qwen3.8-max-0902` |
| `infered/astra-budget` | `cx/gpt-6-astra` → `ali/kimi-k3.1-pro` → `ali/qwen3.8-max-0902` |
| `infered/astra-terra` | `cx/gpt-6-astra` → `cx/gpt-6.1-terra` → `ali/kimi-k3.1-pro` → `ali/qwen3.8-max-0902` |
| `infered/terra-kimi` | `cx/gpt-6.1-terra` → `ali/kimi-k3.1-pro` → `ali/qwen3.8-max-0902` |
| `infered/kimi-glm` | `ali/kimi-k3.1-pro` → `ali/qwen3.8-max-0902` |
| `infered/solterra` | `oai/gpt-6.1-sol` → `oai/gpt-6.1-terra` |

- Unknown/unregistered model names and requests without a model fall to `infered/glm-budget`.
- Budget ladder: $0.50 tier-0 ceiling (spot asks only), then an unconstrained last-resort tier that admits official list prices (counted as `officialLastResortServes` on `/v1/metrics`).
- `cx/gpt-6-astra` is ceiling-exempt: any verified spot ask is eligible.
- Refreshed 2026-11-09 (owner directive): `zai/glm-5.3-flash`, `cx/gpt-5.6-terra`, `ali/kimi-k3` excised; `cx/gpt-6.1-terra` and `ali/kimi-k3.1-pro` verified enabled on the live feed; `ali/qwen3.8-max-0902` tails the five budget chains. `infered/solterra` is the explicit two-model exception (`oai/gpt-6.1-sol` → `oai/gpt-6.1-terra`, no qwen tail). Note `cx/gpt-6.1-terra`'s min ask ($1.29/M out) sits above the tier-0 ceiling — it serves at the last-resort tier until cheaper asks list.
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

## 6. D1 Schema (migrations/0001_init.sql)
The D1 schema is reproducible from the repo. `routing_decisions` + `session_pins`
are byte-matched to the live database `infered-routing` (pull 2026-11-09).

```bash
# Apply to remote (idempotent — safe to re-run)
bunx wrangler d1 execute infered-routing --remote --file migrations/0001_init.sql

# Local dev copy
bunx wrangler d1 execute infered-routing --file migrations/0001_init.sql
```

The INSERT in `src/router/telemetry.js` and the pin upsert in
`src/router/session.js` are the code contract for that schema; change one,
change the other, and add a new numbered migration — never a live ALTER
without a repo-side migration beside it.

