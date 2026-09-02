# Model-Switching Behavior Analysis — 2026-09-02

Live-probe evidence (14 requests against prod `infered-virtual-router`, headers + `/v1/metrics` + engine source audit).

## Question
*How well does the router switch models?*

## Verdict
**Mechanically sound, behaviorally unstable.** The ladder, caps, and fallback all work as coded —
but identical requests land on different models ~70% of the time, sessions cannot pin a model,
and prod records almost none of it.

## Empirical results

### 1. Model identity flaps on identical input (10 identical probes, `model:"auto"`)

| Selected model | Count | Escalation | Savings | TTFT range |
|---|---|---|---|---|
| `cx/gpt-5.6-sol` | 3/10 | 0 | 99.8% | 1.7–2.5s |
| `cx/gpt-5.6-terra` | 7/10 | 0 | 99.0% | 2.1–4.0s |

No glm-flash/glm/kimi ever appeared — the flap is strictly "primary vs next-in-line".

### 2. Budget caps: enforced but economically inert
- `max_price: 0.03` → served `cx/gpt-5.6-terra` (top-tier model) at tier `0.03`, no escalation.
- Spot multipliers (92–99.8% off) mean every model fits even a $0.03/M cap. The 4-rung ladder
  ($0.10→$0.20→$0.30→∞) has never had a reason to escalate in practice.

### 3. Session affinity is provider-scoped, not model-scoped
- Wired correctly (`worker.js:251` reads `X-Session-ID`, `:337` records it, `cache.js` stores it).
- But the affinity bonus (+2.5 utility) only ranks *providers within one model*, and the tier sort
  puts `chainIndex` above `utility` (`pareto.js` `evaluateCascadeTier` sort) — so a sticky session
  still flaps sol↔terra freely. Affinity pins the GPU node, never the model.

### 4. Switch observability is per-isolate and undercounted
- `switchEvents` held 2 entries, both labeled `budget-fallback`; the battery's 7 terra falls
  produced no visible events on the isolate serving `/v1/metrics`.
- All routing state (price cache, metrics, affinity, switch events) is in-isolate memory:
  wiped on deploy, fragmented across edge machines, invisible across isolates.
- No persistent log exists. Response headers are the only per-request truth.

## Root-cause mechanism (code-verified)

Within a tier, `evaluateCascadeTier` sorts: circuit-tripped last → **chainIndex asc** → utility desc.
`terra` is chain index 1; `sol` is 0. Terra can only win when sol has **zero non-tripped eligible
quotes in that isolate's view**. Three candidate causes, each a defect:

1. **Official-price fallback = silent blacklist.** `getQuotesForModel` returns the *official* price
   when no spot quotes exist (sol official output = $30/M), and the $0.10 ceiling then disqualifies
   it. Quote absence is priced as unaffordable instead of handled as "unknown".
2. **Per-isolate circuit/failure memory.** Cold isolates retry known-bad providers; warm ones may
   have circuits tripped from other tenants' traffic.
3. **Silent execution fall-through.** `executeWithFallback` retries the next candidate on upstream
   failure; headers show only the winner, so the caller can't see a retry happened.

Which cause dominates in prod cannot be determined today — because nothing persists per-request
decisions. That is finding #4 and the reason this analysis needed live probes.

## Recommended fixes (smallest first)

1. **Persist switching telemetry** — Workers Analytics Engine write per request (~15 lines);
   headers already carry every field needed. Makes this analysis repeatable from prod data.
2. **Model-scoped affinity / hysteresis** — pin `session → modelId`; keep the last model while it
   stays eligible and under ceiling. ~10 lines in `cache.js` + one check in the tier sort.
3. **Fix the official-fallback disqualification** — absent spot quotes should inherit last-known
   spot price or be skipped with a metric, never silently priced at $30/M under a budget ceiling.
4. **Expose retry count** — `x-infered-attempts` header so silent fall-through becomes visible.

## Rich Hickey check
The router conflates three kinds of "switching" — escalation (by budget), degradation (by failure),
and re-selection (by data drift) — into one sort and one event label. Separating them is what makes
the behavior trustworthy; the data (one persisted decision record per request) is simpler than the
inference currently required to reconstruct what happened.
