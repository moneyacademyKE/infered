# Incident Report: "all models get streaming errors" — 2026-09-22

**Directive (owner, 15:13):** find out why every model throws streaming errors, don't stop
till it's fixed, dogfood with subagents and logs.
**Method:** 24-probe streaming battery via subagent (16 router + 8 direct, client-side
meters), D1 ledger, `wrangler tail` under load, channel history, OpenCrabs recovery table.
**Outcome:** three distinct root causes. Two fixed and deployed today; one needs an
InferHub dashboard action only the owner can take.

---

## Root cause 1 — FIXED: the $0.50 ceiling raise never actually shipped

`wrangler.jsonc` pinned `MAX_FALLBACK_PRICE: "0.10"` as a Worker env var, and the worker
prefers env over the code default — so prod ran the ladder `[0.10, ∞]` for two days while
the test suite (which never sets the env var) happily tested `[0.50, ∞]`. The D1
`budget_cap "0.1"` rows were the live policy telling the truth; my earlier "stale telemetry
fallback" diagnosis was the lie.

- **Fix:** commit `914fbb1` — env pin deleted, policy lives in code
  (`DEFAULT_BUDGET_LADDER`), a lint policy-drift guard fails the build if anyone re-pins
  budget policy in env, and the admitting tier now propagates to D1 (no more fake values).
- **Deploy:** `60e98f40`. **Proof:** live probe returns `x-infered-budget-tier: 0.5`,
  served by `ali/glm-5.3` (chain head, tier 0); D1 row records `budget_cap "0.5"`, with
  pre-deploy rows still showing `"0.1"` — before/after in one query.

## Root cause 2 — FIXED router-side, ACTION REQUIRED upstream: `ali/qwen3.8-max` is disabled on InferHub

Every direct request to `ali/qwen3.8-max` returns **403 `model_disabled`** —
`"model disabled in your preferences: ali/qwen3.8-max (re-enable under Dashboard →
Models & pricing)"`. That 154-byte JSON error, parsed by a streaming client expecting SSE,
is **exactly** the reported signature: `1 content blocks, 0 output tokens — connection
likely dropped`. The model was the bot's first pick today; every request to it "streaming
errors" while the model itself is fine and merely switched off in account preferences.

Worse, the router used to make this pain **silent and long**: ~10 quoted nodes × ~6s per
refusal = 60+ seconds of zero-byte chase before any answer (reproduced live: curl timed
out at 60s with 0 bytes and no D1 row — the ledger never even saw it).

- **Fix (router):** commit `86e1286`, deploy `6d985a3d`. 4xx opens (400/401/403/404) are
  model-level, not node-level — one refusal ends the chase, and a 30s pre-commit wall-clock
  budget bounds any silent chase. **Proof:** the same probe that hung 60s+ now answers in
  **1.65s** with a clean 503: `Non-retryable upstream rejection (403): every node of this
  model refuses identically`, underlying reason visible in `failovers`.
- **ACTION (owner, only you can):** re-enable `ali/qwen3.8-max` in the InferHub
  dashboard (Dashboard → Models & pricing) if you want it usable. It is also the tail link
  of the `kimi-glm` chain — the router now fails over it cleanly, but it will never serve
  while disabled.

## Root cause 3 — structural, mitigated: deep-reasoning nodes outlast client patience

The prober's one router "failure" (qwen3.8-max-0902 story) was a **live** stream — 1100
reasoning deltas flowing, zero content yet, killed by the probe's own 90s `--max-time`
while the same prompt completed directly in under 90s. Deep reasoners (glm-5.3, qwen,
kimi at max effort) stream contentless reasoning for a minute+; any client deadline that
fires mid-reasoning reports "0 output tokens". This is model behavior, not a bug: the
router now (a) never holds bytes (first-byte commit), (b) splices pre-content corpses,
and (c) fails fast when nothing will come. Recommendation for latency-critical bots: pin
chain products (they degrade to cheap fast nodes) rather than raw reasoners, or raise the
client's deadline.

## Probe battery (24/24, subagent `stream-dogfood-prober`)

- **Router:** 15/16 clean. TTFB 2.6–23.9s. The one non-clean = the live-stream probe
  timeout above (not a worker fault).
- **Direct:** 6/8 clean; the 2 non-clean = the 403 `model_disabled` pair.
- **Worker health:** `wrangler tail` under probe load — 0 exceptions, 0 CPU kills.
- D1 same-day: real production traffic all `ok=1`, including live splice rescues
  (`attempts 2–4`) — the corpse armor is earning its keep on organic traffic.

## Disposition

| Item | State |
|---|---|
| Commits `914fbb1`, `86e1286` | local `main`, parked (say "push") |
| Prod | `6d985a3d` (chase bounding) over `60e98f40` (policy fix) |
| Suite | 11 namespaces, 0 failures (incl. 7 new chase assertions), lint clean, all files <500 LOC |
| Bead `bk-034c` | closed |
| Open item | InferHub dashboard re-enable of `ali/qwen3.8-max` (owner) |
| Noted | `tupesa` provider currently `enabled = false` in OpenCrabs config — chains
  are selectable again once re-enabled; `infer` (direct) is primary and healthy except
  the disabled model. Router-side armor is live for both surfaces. |

**Honest correction logged:** an earlier mid-investigation "caught a no-DONE corpse" was
me grepping a probe body *while curl was still writing it*. Verdicts are only read after
the writer closes.
