# Rich Hickey Gap Analysis — infered-virtual-router

**Date:** 2026-09-05 · **Scope:** every module and component · **Method:** simplicity over novelty, composition over coupling, data over abstraction. Complexity = incidental complexity a reader must hold to change the file safely. Utility = contribution to the one essential job: *route each chat request to the cheapest working model inside budget, and tell the truth about it.*

**Tree state at analysis:** 2,141 src LOC, 1,416 test LOC, worker.js at 499/500 (bk-9daf recording fixes in-tree, uncommitted), catalog slim (bk-ccd3) designed but not cut.

---

## Complexity vs Utility

| Module | LOC | Cx (1-5) | Utility (1-5) | Ratio | Verdict |
|---|---|---|---|---|---|
| `src/router/catalog.js` | ~148 | 1 | 5 | ★★★★★ | Keep — becomes pure data when bk-ccd3 lands |
| `src/router/pricing.js` | ~218 | 3 | 5 | ★★★★ | Keep — fix H4 (ghost nodes) |
| `src/router/client.js` | ~292 | 4 | 5 | ★★★★ | Keep — densest but each concern earns its place |
| `src/ui/analytics.js` | ~135 | 1 | 4 | ★★★★ | Keep — model citizen |
| `src/router/pareto.js` | ~307 | 3 | 4 | ★★★ | Prune — dead Pareto path, cascade is the product |
| `src/worker.js` | 499 | 4 | 5 | ★★★ | Split — at ceiling, recorder must move out |
| `test/*` (9 suites) | 1,416 | 2 | 5 | ★★★★★ | Keep — caught every regression today |
| `scripts/*.{bb,clj}` | ~400 | 1 | 3-4 | ★★★ | Commit the two rigs; fix sim's discount regime |
| `src/ui/dashboard.js` | ~278 | 2 | 2 | ★★ | Retire or shrink — drifting from truth |

---

## Module-by-module

### 1. `catalog.js` — the data layer (Cx 1 / U 5)
**What's essential:** chains as data, model metadata, name resolution. Two lookup functions over maps.
**Gap:** it hosted all three routing bugs of the last two days (sol default, 17-model auto pool on unknown fallthrough, alias drift) — not because the module is complex but because *resolution semantics* are load-bearing and were implicit. bk-ccd3 makes it honest: 2 chains, 4 members, everything unknown → default chain.
**Rejected alternative:** a model-registry framework with per-model config objects. Chains-as-data already absorbs a new chain in one line; the registry would be ceremony.
**Action:** finish bk-ccd3 exactly as designed.

### 2. `pareto.js` — selection (Cx 3 / U 4)
**What's essential:** chain cascade, budget ceilings, eligibility (spot-asks only), session affinity bonus.
**Gap:** the name lies. The flagship budget path never runs `findParetoFrontier` — it's dead code (audit H-finding, still true). Utility weights are near-dead inputs on the cascade path (chainIndex-first). Two selection theories in one file; one is unused. That's incidental complexity wearing a computer-science badge.
**Action:** delete the frontier path or wire policy aliases through it. Recommendation: delete — the cascade *is* the product; Pareto is a story we tell about it.
**Keep:** eligibility rules and affinity — they're the fixes that made switching stable.

### 3. `pricing.js` — the order book (Cx 3 / U 5)
**What's essential:** blended price, spot ingestion, priceSource stamping, cheapest-first lookup, ratecard.
**Gap:** H4 — node ids derive from ask *index* and stale quotes are never evicted. The visible market is partly phantoms (sol's `no_capacity` days proved it: offers existed, capacity didn't). `priceSource` fixed the *trust* problem; freshness remains unsolved.
**Also good:** official-price fallback is now display-only for chains (cascade skips non-spot quotes) — trust boundary already moved to the right place.
**Action:** evict quotes older than N minutes + stable node identity (hash of provider::model::price, not index). Small, bounded.

### 4. `client.js` — the executor (Cx 4 / U 5)
**What's essential:** retry with cap, per-candidate timeout, client-abort chaining, circuit breaker, response cache, SWR sync.
**Gap:** four orthogonal concerns interleaved in one loop — the densest reading in the repo. But each is small, each is tested through the injected-fetch seam, and the AbortController pairing (client lifecycle ≠ upstream lifecycle) is *essential* complexity: the two lifecycles genuinely differ.
**Action:** none structural. The 10-attempt cap and abort wiring closed the real gaps. If it grows again, extract the circuit breaker first.

### 5. `worker.js` — the surface (Cx 4 / U 5, at ceiling)
**What's essential:** path dispatch, request normalization, streaming plumbing, decision recording, 7 routes.
**Gap:** at 499/500 LOC it cannot absorb one more feature — and it's the module every new directive lands in. The recorder (~60 LOC: `recordDecision`, overrides, record-once, cancel hook) is a coherent unit screaming to be `src/router/recorder.js`.
**Action (forced by rule, not taste):** extract the recorder next session. Restores ~60 LOC headroom and gives D1 writes one home.
**Keep:** `===` path dispatch. A router framework would be the definition of incidental complexity at 7 routes.

### 6. `analytics.js` — the truth layer (Cx 1 / U 4)
**What's essential:** SQL over decision rows → server-rendered checklist. Zero client JS.
**Gap:** none material. Pagination at ~2k rows/day is a future concern, not a present one. This module is what happens when the data model is right: the UI is a query.

### 7. `dashboard.js` — the legacy page (Cx 2 / U 2, falling)
**What's essential:** health, live quotes, model dropdown.
**Gap:** every surface contraction (sol removal, catalog slim) makes its dropdown list models that no longer resolve — it drifts from truth *by design of the codebase around it*. The analytics homepage already covers its job better.
**Action:** after bk-ccd3 lands, either reduce it to a thin status card or retire it. Highest utility-per-LOC debt in the repo.

### 8. Tests (Cx 2 / U 5)
**What's essential:** bb-native suites over the injected-fetch seam; empirical green before every commit.
**Proven:** caught the INSERT bind-index shift, the streaming recorder blind spot, and acted as antibodies on sol removal (7 stale pins inverted = excision verified).
**Gap:** `worker_test` reads insert rows by column *index* — one schema change away from silent misreads (bit me once, caught by luck of assertion). Named-field reads in the stub would cost ~10 lines.
**Also:** `sim_market.clj` exercises 30-80% discounts vs the calibrated 92-99.8% reality — it prints ✅ while simulating a different market. Fix the regime or delete the sim.

### 9. Scripts (Cx 1 / U 3-4)
`stress-astra.bb` + `d1-check.bb`: real load + ground-truth verification — commit them, they're the only way the answer to "is it working?" is ever more than a vibe. `live_test.clj` still passes `maxPriceThreshold` which nothing reads — the "live dynamic switching verified" banner verifies nothing. Fix or delete.

---

## Cross-cutting verdict

**The spine is right:** one honest decision row per request → D1 → homepage. Every improvement this week (eligibility stamps, chain attribution, stream-flush recording, cache-hit rows) was *removing a lie from that spine*, never adding machinery. That's the signature of a design worth keeping.

**Remaining incidental complexity, ranked by cost:**
1. worker.js at the ceiling (forced split: recorder out)
2. dashboard.js drift (retire)
3. pareto.js dead path (delete)
4. ghost nodes in the order book (evict + stable ids)
5. sim/live_test verifying fiction (fix or delete)

**Complementing to refuse:** a model-registry framework, admin auth UI, a plugin system for chains, per-model context config. The chains-as-data map already absorbs the next chain in one line; every abstraction beyond that is a guess about a future nobody has asked for.

**One-line summary:** this router is close to its essential shape — what's left is deleting the parts that pretend, not building parts that might.
