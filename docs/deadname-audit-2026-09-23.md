# Deadname Audit — 2026-09-23

Two subagents swept the router catalog, chains, tests, docs, scripts, and OpenCrabs
provider pickers against the live InferHub market feed (253 models: 105 host-prefixed
listings + 138 bare aliases) plus direct completion probes for every load-bearing name.
Ground truth timestamp: 2026-09-23 ~12:31 UTC.

## 1. Deadnames found (2)

| Name | Referenced at | Upstream truth | Verdict |
|---|---|---|---|
| `combo/glm35flash` | `config.toml:106` (infer picker) | 404 `model_not_found`; zero `combo/*` ids in the entire feed | **DEADNAME — absent from market** |
| `cb/glm-5.3` | `config.toml:106` (infer picker) | 403 `model_disabled` (verified 3×: prober, settling probe; auditor's "live" was feed-presence only — the feed lists it with asks, but the account has it disabled) | **DEADNAME — disabled in account prefs** |

Both sit in the enabled `[providers.custom.infer]` models list. Any bot session that
picks either name from the switcher gets an instant 403/404 — the exact failure shape
that produced yesterday's "streaming error" reports (raw qwen3.8-max pin).

Note: `cb/glm-5.3` is NOT in the router's `MODEL_TIERS`, so through the worker it
would reroute to glm-budget — only the direct `infer` provider walks into the 403.

## 2. Confirmed alive (all 7 router catalog models)

| Model | Feed asks | Min ask out | Direct probe |
|---|---|---|---|
| `cx/gpt-6-astra` | 29 | 0.95 | 200 |
| `cx/gpt-5.6-terra` | 24 | 0.012 | 200 |
| `zai/glm-5.3` | 10 | 0.176 | 200 |
| `zai/glm-5.3-flash` | 9 | 0.0495 | 200 (one transient transport timeout) |
| `ali/glm-5.3` | 7 | 0.132 | 200 |
| `ali/kimi-k3` | 8 | 0.345 | 200 |
| `ali/qwen3.8-max-0902` | 11 | 0.03 | 200 |

All five chains are fully live; every ask is within the $0.50 tier-0 ceiling (astra
ceiling-exempt). Bare `glm-5.3-flash` in the infer picker also works — it's an alias
that resolves to `zai/glm-5.3-flash` (redundant duplicate of an explicit entry, not
a deadname).

## 3. Kept-dead names (correctly excised, still dead for us)

- `ali/qwen3.8-max` — on the feed with asks, but 403 `model_disabled` for this
  account (re-confirmed this audit). The 2026-09-22 excision stands. Referenced only
  in comments/tests now (archival). Other providers' listings of the same capability
  DO exist on the feed (`cp/cline-pass/qwen3.8-max`, `ocg/qwen3.8-max`) — untested,
  noted in case the capability is ever wanted back.
- `cx/gpt-5.6-sol` — alive upstream (cx + cb listings, min out 0.54-0.60) but banned
  by owner directive 2026-09-05. Tests/docs correctly treat it as locally removed.

## 4. Price drift — 4 of 7 official prices stale

| Model | Catalog official (in/out) | Feed official (in/out) | Drift |
|---|---|---|---|
| `cx/gpt-5.6-terra` | 0.30 / 0.90 | 2 / 12 | ×6.7 / ×13.3 |
| `zai/glm-5.3` | 0.20 / 0.40 | 1.4 / 4.4 | ×7 / ×11 |
| `zai/glm-5.3-flash` | 0.06 / 0.10 | 0.15 / 0.5 | ×2.5 / ×5 |
| `ali/kimi-k3` | 0.15 / 0.30 | 3 / 15 | ×20 / ×50 |

Matched: astra 10/50, ali/glm-5.3 1.4/4.4, qwen-0902 2/6.

Consequence: `calculateSavingsPct` uses these as the denominator, so reported savings
are wrong — kimi's cheapest ask computes ~0% savings against the stale official but
~97.7% against the real 3/15. The `/` ratecard displays stale list prices, and the
official-price fallback in `getSpotQuote` carries stale numbers. Routing is unaffected
(spot asks govern eligibility and ordering).

## 5. Stale docs

`docs/playbook.md:33-38` advertises six retired virtual products (`infered/auto`,
`fast`, `smart`, `reasoning`, `cheap`, `claude-3.5-sonnet`) as live — all silently
reroute to the default chain now. Pre-dates the 2026-09-05 catalog slimming.

## 6. Meta-finding: the handoff key is revoked

`sk-airo-GJjcT…` (from the original project handoff doc) → 401 `api key revoked`
on every request. Live keys found: `~/.opencrabs/keys.toml` (sk-airo-zN…, used by
the prober) and the repo's `.dev.vars` INFERHUB_API_KEY (sk-airo-WqT…, used by the
auditor) — two different working keys coexist. The router's prod worker secret works
(real completions verified 2026-09-22). Lesson recorded: never re-use credentials
from stale handoff docs — extract from live config.

## 7. Unregistered inventory (context, not actionable)

241 of 253 live feed ids are unreferenced by us — by design (the router sells
chains, not a zoo). Notably cheap unreferenced listings, if ever wanted:
`ali/qwen3.8-omni-flash` (0.00047 out), `cb/deepseek-v4.1-flash` (0.0006),
`ag/gemini-3.8-flash-high` (0.00375), `cx/gpt-6-luna` (0.009),
`cbcn/glm-5.3-flash` (0.009), `ali/qwen3.8-flash` (0.00423).

## Recommended actions

1. **Excise `combo/glm35flash` and `cb/glm-5.3` from the infer picker**
   (config.toml:106). Alternative for cb/glm-5.3: re-enable it in the InferHub
   dashboard (Dashboard → Models & pricing) if the capability is wanted.
2. **Sync the 4 stale official prices** in `catalog.js` + the spot-multiplier
   comments in `pricing.js` so savings/ratecard tell the truth.
3. **Rewrite playbook.md's model table** to the five live chains.

Receipts: prober log `/tmp/probe-results.txt`, feed snapshot `/tmp/feed-v2.json`,
auditor full report `/Users/moe/.opencrabs/tmp/detached/f9cedd94.json`.
