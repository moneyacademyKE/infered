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

## 3. Configuring Virtual Tiers & Routing Policies
Infered provides 5 standard virtual aliases and supports custom model targets:
- `infered/auto`: Dynamically selects the best global Pareto model across all tiers.
- `infered/fast`: Focuses on lowest TTFT & sub-300ms response time (`llama-3.1-8b`, `gpt-4o-mini`, `deepseek-v3`).
- `infered/smart`: Directs to flagship models (`claude-3.5-sonnet`, `gpt-4o`, `llama-3.3-70b`).
- `infered/reasoning`: Selects deep reasoning models (`deepseek-r1`, `o1-preview`, `o3-mini`).
- `infered/cheap`: Maximum token discount optimizer.
- Direct models (e.g. `infered/claude-3.5-sonnet`): Routes to the cheapest healthy spot node serving that exact model.

## 4. Tuning Pareto Weights
You can tune weights globally via environment variables in `wrangler.jsonc` or per-request via the `X-Infered-Weights` header:

```json
{
  "price": 0.6,
  "speed": 0.3,
  "quality": 0.1
}
```
