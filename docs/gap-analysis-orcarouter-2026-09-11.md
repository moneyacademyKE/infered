# Gap Analysis — infered vs OrcaRouter

**Date:** 2026-09-11 · **Author:** OpenCrabs · **Scope:** routing + operations surface (firewall/guardrails covered separately in `~/.opencrabs/research/orcarouter-firewall-analysis.md`)

**Sources (all verified this session):** `docs.orcarouter.ai/llms.txt` (full page inventory), `routing/model-fallbacks`, `routing/auto-router`, `routing/named-routers` (via auto-router), `routing/routing-dsl`, `routing/frontier-escalation`, `routing/session-affinity` (conf: high). Infered side: live D1 query on `routing_decisions`, repo state @ `8ce3db5` (conf: high).

---

## 0. Framing — this is not a like-for-like comparison

OrcaRouter is a **multi-tenant commercial platform**: 10+ providers, YAML+CEL routing DSL, LinUCB bandits, fusion panels, guardrails, firewall, video APIs, billing, refunds, OAuth. It sells breadth and governance to everyone.

infered is a **single-tenant spot-market arbitrage instrument**: 5 chain products over one marketplace (InferHub), budget ceilings, one decision row per request, 6 modules, zero npm deps, free-tier Cloudflare.

Per Hickey: a gap is the distance between what a thing is and what it is *for*. Most of OrcaRouter's surface is a gap we should **refuse**, not close — adopting platform machinery would complect the instrument. But their docs expose **three real gaps in what infered is for**, one of them proven by our own data this session.

## 1. Feature-set differences

| Capability | OrcaRouter | infered | Verdict |
|---|---|---|---|
| Provider breadth | 10+ providers, one billing relationship | InferHub spot market only | **Refuse** — the market IS the edge (96–99.5% off list) |
| Fallback chains | Caller-supplied per request (`extra_body.models`, max 5) | Server-side named chains (data map) | **Same, shaped differently** — theirs is per-request, ours is product-shaped. No gap. |
| Cheapest/quality/balanced strategies | Built-in named-router strategies | Budget ceiling + cascade = our "cheapest that works" | **Same in spirit**; theirs picks over list prices, ours gates on verified spot asks |
| LinUCB / gated_adaptive bandit | Learns quality/cost/latency from traffic | None — explicit chains | **Refuse** — a bandit needs volume we don't have; explicit data > adaptive mystery at 1-user scale |
| Routing DSL (YAML+CEL, 30 rules, agent_state, difficulty classifier) | Full expression language, shadow + canary rollout | `CASCADE_CHAINS` map | **Refuse** — a language to say what 5 arrays say |
| Fusion panels (judge / MoA) | Parallel frontier calls, arbiter picks best | None | **Refuse** — multiplies cost; antithetical to budget routing |
| Frontier escalation (difficulty + failure strikes, per-session tier) | Auto + manual headers, spend-share cap | Price-driven tier escalation only; no "task outgrew model" | **Gap — partial close** (see §3.2) |
| Session affinity | 6-header priority, body-field fallbacks, **prefix fingerprint**, 30-day model pins, fleet-persistent | `X-Session-ID` model pinning, **per-isolate memory** | **Gap — proven** (see §3.1) |
| Session identification without client cooperation | Prefix fingerprint of conversation head | None | **Gap — proven** (see §3.1) |
| Mid-stream fallback | None (bytes sent = committed) | None (same constraint) | **Parity** — industry-wide honesty point |
| Client-disconnect spend protection | Not documented (conf: low) | Abort-chained upstream, 499 + D1 row | **We're ahead** |
| Anthropic `/v1/messages`, Gemini `/v1beta` native | Yes | OpenAI-compat only | **Watch** — zero callers need it today |
| Per-request billed cost header | `X-Orca-Cost` (exact) | `x-infered-savings` (estimated vs official) | **Minor gap** — exactness needs InferHub usage data |
| Guardrails / firewall / MCP / prompts registry / video / TTS / arena | Yes | No | **Refuse** — governance lives in OpenCrabs; the rest is out of scope |
| Zero data retention | Prompts in memory only | Same by construction (D1 logs metadata only) | **Parity** |
| Multi-tenancy, billing, OAuth | Yes | No | **Refuse** — single tenant is the design, not the limitation |

## 2. The reverse gap — what infered has that OrcaRouter doesn't

| Ours | Theirs | Why it matters |
|---|---|---|
| Live order-book routing: verified spot asks, NaN guards, stable node identity, eviction of vanished nodes | List-price + quality-score routing | They cannot offer 99.5%-off routing — no market exists upstream of them |
| Hard budget ceiling as an *eligibility gate* (official prices can never satisfy a budget tier) | `cheapest` strategy = pick lowest list price | Ours is a proof; theirs is a heuristic |
| Free-tier-shaped engineering: 10ms CPU budget, 50-subrequest discipline, abort propagation | N/A (they're the paid platform) | Our constraints forced simpler designs |
| One honest decision row per request → D1 → server-rendered homepage | Dashboard SaaS | Audit-by-construction, no observability bill |
| 6 modules, ≤500 LOC each, zero deps, Babashka suite | Platform team, 159 security pages alone | We can hold the whole system in one head |

## 3. The three gaps that matter

### 3.1 Session affinity is dead code in prod — **proven this session**

D1 ground truth (17,754 decisions):

| Metric | Value |
|---|---|
| Rows with no `session_id` | **17,738 (99.91%)** |
| Distinct session ids ever seen | 6 |
| Implication | The model-pinning built to kill Sol/Terra flapping engages for ~0.1% of traffic |

OrcaRouter's answer is **prefix fingerprinting**: hash the conversation head (system + first message) into a pseudo session id when no header arrives (src: session-affinity.md, conf: high). Zero client cooperation required. They also persist pins fleet-wide (30-day model pin with explicit header); ours is per-isolate memory, wiped by deploys.

**Close:** (a) derive a fingerprint session id in `worker.js` when `X-Session-ID` is absent (one hash, ~15 LOC); (b) persist pins in D1 with TTL instead of isolate memory (~40 LOC). Complexity: low-medium. Utility: makes a shipped feature actually run; kills cross-isolate flapping as a class.

### 3.2 No "task outgrew the model" path

Their frontier escalation moves a *session* to a strong pool on difficulty/failure signals, with spend caps and shadow-gated auto mode. The full version is a classifier + session state machine — refuse that. But their **manual mode** is one header (`X-OrcaRouter-Tier: strong`) and no classifier at all.

**Close (subset):** accept an optional `X-Infered-Tier: strong` request header that starts the cascade at the chain's strongest link instead of the cheapest (~20 LOC, no state). The client (OpenCrabs) already knows when a turn is hard; no classifier required. Auto-detection (their "strikes": errored tool results, test-failure shapes) — **refuse until D1 shows chains mis-serving**; we have no evidence of the problem at our scale.

### 3.3 Single-market fragility

The 13:27–14:07 UTC brownout (61 failures, requests burning 15–34 attempts) is in our own history. One market = one point of failure. OrcaRouter's whole pitch is provider redundancy.

**Honest trade-off:** a second upstream behind the same chain abstraction is medium-high complexity (order book keyed per market, health state, key management) and — decisive — second providers cost real money, colliding with the free-plan law. **Verdict: watch, don't build.** The 10-attempt cap already bounds the blast radius; D1 tells us when the market is sick. Re-open only if brownouts become routine.

## 4. Complexity vs utility — recommended actions

| # | Action | Complexity | Utility | Recommendation |
|---|---|---|---|---|
| 1 | Session fingerprint fallback (no-header case) | ~15 LOC, pure function | Makes affinity real for 99.9% of traffic | **Do first** |
| 2 | Persist session pins in D1 (TTL ~30d) | ~40 LOC + schema | Kills cross-isolate/deploy flapping | **Do with #1** |
| 3 | `X-Infered-Tier: strong` manual header | ~20 LOC, no state | Hard turns skip the cheap links on demand | **Do after #1–2** |
| 4 | Exact per-request cost (needs InferHub usage API) | Unknown — check API exists first | Replaces savings estimates with truth | **Spike only** |
| 5 | Difficulty/strikes auto-escalation | Classifier + session state machine | Speculative at our volume | **Refuse now** |
| 6 | Anthropic/Gemini native ingress | Protocol adapters | Zero current callers | **Watch** |
| 7 | Second upstream market | Medium-high + real money | Brownout insurance | **Watch** (free-plan law) |
| 8 | DSL / bandits / fusion / guardrails / firewall in-router | Platform machinery | Negative — complects the instrument | **Refuse** |

## 5. Certification

The analysis asked "which of their features are gaps in what infered is *for*?" and answered with data, not taste: 99.9% of our traffic never touches the one feature we share with them, which converts a theoretical gap into the top action item. Everything else either already exists in simpler form (fallbacks, cheapest, session pinning-by-header), is platform machinery we're right to refuse (DSL, bandits, fusion), or waits for evidence (auto-escalation, second market). The instrument stays an instrument; the three closes above are all subtractions of dishonesty or deadness, not additions of machinery.
