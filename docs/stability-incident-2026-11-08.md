# Stability Incident Report — 2026-11-08

**Reported:** moe, 15:56 EAT — "this app is unstable. make stability fixes"
**Verdict:** the router was innocent and idle; the app's front doors were pointed at congested direct-InferHub
pools. Three config repairs + one test-hygiene commit. No worker code changed; no deploy needed.

## Evidence (all from this turn's tooling, nothing from transcript memory)

**Prod state:** `infered-virtual-router` healthy, 1,680 cached quotes, all 7 chain models quoted
(7–29 asks each). Latest deployment `3b90925d` (Sep 26) = repo HEAD `f094a96`. No drift.

**Router ledger:** newest D1 row is Nov 7 10:47 UTC — **zero router traffic today**. Zero failures.
The router was not part of today's failing path at all.

**Today's 22 stream errors (OpenCrabs log), decomposed:**

| Class | Count | Path |
|---|---|---|
| `error decoding response body` (mid-body HTTP corruption) | 3 | direct InferHub (`infer-glm`) |
| `capacity_saturated` (all sellers within account price limit busy) | 4 | direct InferHub |
| `Rate limit exceeded` (model pool at concurrency limit) | 6 | direct InferHub |

**The routing truth:** every live session rode direct InferHub. The armored router (`tupesa`) was
`enabled = false`, and the fallback chain was `["infer", "infer-glm"]` — two doors into the same
burning house. The router-first config from the Nov 7 fix had been overwritten at Nov 7 23:22 by
provider re-curation (new model list, `ali/kimi-k3.1-pro` default, Sol-family vision model).

**Controlled reproduction (live probes, "Say OK"):**

| Target | TTFB | Total | Notes |
|---|---|---|---|
| `ali/kimi-k3.1-pro` direct (was the default) | 5.7s | **35.3s** | 47 reasoning tokens to say OK |
| `ali/kimi-k3.1-pro` direct, 4-way burst | 5.7s | **27.6–28.5s each** | |
| `ali/glm-5.3` direct | 1.1s | 2.8s | healthy |
| `infered/glm-budget` router, 8-way burst | 1.2–5.9s | ≤9.6s | all 200, all `[DONE]` |

The default door was 12× slower than the healthy path on a trivial prompt, and the log wall of
capacity/rate errors rode the same door. That is the instability.

## Fixes applied

1. **Router enabled** — `tupesa.enabled = true` (the splice/cascade/chase armor is back in the path).
2. **Fallback chain armor-first** — `providers.fallback.providers = ["tupesa", "infer", "infer-glm"]`:
   direct-door failures now fall into the cascade instead of retrying the same congested pool.
3. **Default off the congested pool** — `infer.default_model`: `ali/kimi-k3.1-pro` → `ali/glm-5.3`
   (12× measured latency difference + the saturation log wall; glm-5.3 served this session all day).
4. **Test-suite blind spot closed** — `test/run_tests.clj`: the orphaned `session-test` (25 assertions,
   wired into the runner two turns ago) was not counted in the suite verdict; commit `2085860` adds it
   to `total-fail`/`total-err`.

Config verified on disk after write, reloaded, `opencrabs doctor` 9/9 clean.
Suite: green (`bb test-all` exit 0, lint clean, all files <500 LOC).

## Soak receipts

- Tools-bearing request through the router (excision path): HTTP 200, TTFB 1.4s, exactly one
  `[DONE]`, real `get_weather` tool call emitted.
- New default door: glm-5.3 direct, 200, 1.7s, clean completion.
- Router under 8-way concurrency: all 200, all `[DONE]`, max 9.6s total.

## Honest bounds

- The 105 pre-existing sessions keep their provider pins; they'll hit one failure, then fall into the
  router via the new fallback chain (recovery, not immunity).
- `kimi-k3.1-pro` stays in the model list for manual picks — only the default moved.
- `infer.vision_model` is `cx/gpt-6.1-sol` (from the Nov 7 23:22 curation). The Sol ban was for
  `cx/gpt-5.6-sol` specifically, so it stands as curated — flagged for the owner's call.
- One suite run out of five showed 2 transient failures (three consecutive re-runs green); it
  correlated with InferHub throttling right after the burst probes. Unreproduced; if it recurs,
  capture the full output on failure to identify the flaky assertion.
- Yesterday's D1 `client_disconnected` pile was a client sending a typo'd model name
  (`ali/qwen-3.8-max-0902`); unknown names fall to the default chain by design, so it degrades
  gracefully — source pin not found in config or sessions.

## Commits

- `2085860` — Wire session-test into suite verdict totals (close the orphan blind spot) *(parked)*

Prod unchanged (`3b90925d` = HEAD source). Nothing pushed.
