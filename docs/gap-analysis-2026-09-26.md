# Rich Hickey Gap Analysis — infered-virtual-router

**Date:** 2026-09-26 · **Scope:** every src module + test suite · **Method:** simplicity over ease, composition over coupling, data over abstraction. Complexity (Cx) = incidental concepts a reader must hold to change the file safely. Utility (U) = contribution to the one essential job: *route each chat request to the cheapest working model inside budget, and tell the truth about it.*

**Tree state:** ~2,980 src LOC across 13 modules, ~3,010 test LOC across 17 suites. Five chains live, splice armor in prod (`c67287bb`), $0.50 tier-0 ceiling governing. Half the tree (stream, session, healer, metrics, exemplars) did not exist at the last analysis (2026-09-05).

**Headline:** the essential router is now ~2,550 LOC of honest machinery that carries real traffic. The remaining gap is one dormant subsystem pretending to learn (427 LOC), test-calibration data living in a production module, and a handful of dead data structures. Nothing structural is broken; the debt is all falsehood or ballast, not architecture.

---

## Complexity vs Utility

| Module | LOC | Cx | U | Ratio | Verdict |
|---|---|---|---|---|---|
| `router/catalog.js` | 175 | 1 | 5 | ★★★★★ | Keep — pure data; absorbs a new chain in one line |
| `router/session.js` | 101 | 2 | 4 | ★★★★★ | Keep — L1/L2 (mem + D1) composition, pays rent daily |
| `router/telemetry.js` | 87 | 1 | 4 | ★★★★★ | Keep — two small lies to retire (below) |
| `ui/analytics.js` | 168 | 1 | 4 | ★★★★ | Keep — model citizen; the UI is a query |
| `router/pricing.js` | 239 | 3 | 5 | ★★★★ | Keep — gap: seed multipliers live in prod path |
| `router/metrics.js` | 196 | 2 | 4 | ★★★★ | Keep — dead `history` array; per-isolate honesty needed |
| `router/pareto.js` | 323 | 3 | 4 | ★★★★ | Keep — the name still lies; one duplicated block |
| `router/client.js` | 284 | 3 | 4 | ★★★★ | Keep — healthiest it's ever been (shrunk by delegation) |
| `worker.js` | 421 | 4 | 5 | ★★★★ | Keep — headroom recovered; the busiest intersection |
| `router/stream.js` | 454 | 5 | 5 | ★★★★ | Keep — essential complexity; watch the ceiling |
| `router/cache.js` | 105 | 2 | 3 | ★★★ | Keep — two stores, one file; response cache dormant |
| `router/healer.js` | 299 | 4 | 2 | ★★ | Watch — dormant on observed traffic, unproven |
| `router/exemplars.js` | 128 | 2 | 1 | ★ | Excise or fix — the learning loop is fake |

Tests: 17 suites, all green, three files within 3% of the 500-LOC lint ceiling (`stream_test` 490, `client_test` 486, `pareto_test` 476) — next test addition forces a split.

---

## Module-by-module

### 1. `catalog.js` — the data layer (Cx 1 / U 5)
Chains, prices, exemptions, strong links, fallbacks: five maps and two lookup functions. A new chain costs one entry — proven three times this month. `CHAIN_FALLBACKS`, flagged dead in an earlier session, is **alive and wired** (`client.js:248`, chain-exhaustion fallback) — good thing the analysis re-verified instead of trusting session memory. No gap.

### 2. `session.js` — identity + pinning (Cx 2 / U 4)
Fingerprint derivation for the 99.91% of traffic that sends no header; D1-backed pins with a 15-min memory L1. This is what turned model-pinning from dead code into a live feature by *removing a precondition* (client cooperation). The L1/L2 split is composition, not duplication. No gap.

### 3. `telemetry.js` — the recorder (Cx 1 / U 4)
D1 writes, headers, CORS. Two small gaps: (a) the `budgetTier` fallback string is `"0.10"` — a dead policy's ghost; now that tier-0 is $0.50 the fallback lies when the real value is missing. Prefer omitting the header over inventing a tier. (b) `createStandaloneMockFetch` — a test seam — lives in a production module. Move to the test surface or a `test/` support file.

### 4. `analytics.js` — the truth layer (Cx 1 / U 4)
SQL → server-rendered checklist, zero client JS. Failure ledger added this month finally gave the corpse classes a face. Pagination at scale is a future concern. No material gap.

### 5. `pricing.js` — the order book (Cx 3 / U 5)
Spot ingestion, stable node ids, eviction, ratecard. The gap: **`MODEL_SPOT_MULTIPLIERS` — synthetic seed asks for fresh isolates — lives in the production module.** This is test-calibration data complected into the prod path, and it has already bitten: syncing official prices recalibrated nothing, inflated every seeded ask, and broke six tests (2026-09-26 incident). Move the seed map to a dedicated `seed.js` (or gate it behind an env flag) so prod code contains prod data only.

### 6. `metrics.js` — breaker + usage (Cx 2 / U 4)
The circuit breaker is live in the selection path and finally fed by real corpses (splice `recordSample(false)`). Gaps: (a) `store.history` — a 50-entry array written on every sample, read by nothing. Dead data with write cost. Delete. (b) All breaker/usage state is per-isolate: a deploy wipes it. Acceptable for the breaker (30s reset makes durability ceremony), but `/v1/metrics` savings/usage totals reset per isolate — they measure *this isolate*, not the service. Either label them as isolate-scoped in the payload or drop them; D1 is the real ledger.

### 7. `pareto.js` — selection (Cx 3 / U 4)
Spot-strict eligibility, ceiling exemption, circuit demotion, affinity bonus — every policy fix of the last month earns its line here. Gaps: (a) the name still lies — no Pareto frontier exists; it's `rankCandidates`. Rename when convenient. (b) The circuit-stats block (getStatsKey/isCircuitOpen/getProviderStats) appears twice (~lines 59 and ~249) — extract one helper. Both are cosmetic; neither misleads behavior.

### 8. `client.js` — the executor (Cx 3 / U 4)
Shrunk twice (394 → 284) by delegating stream machinery to `stream.js`. Remaining: candidate loop, retry cap, abort chaining, chain-level fallback (`CHAIN_FALLBACKS` — now genuinely used). This is the file the 2026-09-05 analysis called "densest in the repo"; delegation fixed exactly that. No structural gap.

### 9. `worker.js` — the surface (Cx 4 / U 5)
421/500 — headroom recovered by the recorder's move to telemetry and stream delegation. It remains the busiest intersection: every subsystem wires here. The asymmetry worth naming: **the non-streaming path hosts the entire dormant tool subsystem** (exemplar injection, healer, response cache) while the streaming path — the one all observed traffic uses — hosts the splice armor. The speculative features live on the path nothing exercises; the essential ones live on the path everything does. That's accidental quarantine, and it works, but it means the dormant code is also *untested by traffic*, only by unit tests.

### 10. `stream.js` — the splice executor (Cx 5 / U 5)
The reliability product: chunk classification, first-byte commitment, per-candidate parser reset, splice, sentinel synthesis, exhaustion-as-in-band-error, settle-time live getters. Cx 5 is honest — but it is **essential** complexity: the wire genuinely carries every one of these cases, and each was a production corpse before it was a code branch. Gap: at 454/500 it cannot absorb another failure class. The next stream feature must split it (classification is the natural seam: `classify.js` pure functions + `relay.js` state machine). Do not grow it in place.

### 11. `cache.js` — two stores, one file (Cx 2 / U 3)
Response cache (deterministic non-stream requests only — dormant for bot traffic, which streams) and session-affinity L1 (consumed by `session.js`). Unrelated concerns sharing a file and a `globalThis` slot; a rename to match reality (`stores.js`) or a split would remove the accidental coupling. Low priority — the file is small and honest about both jobs.

### 12. `healer.js` — tool-call repair (Cx 4 / U 2, dormant)
299 LOC: levenshtein fuzzy key remap, synonym maps, schema validation, deterministic JSON repair. Fires only for non-streaming responses with tools. No observed traffic matches that gate; no ledger column can prove otherwise. The machinery is well-built and inert. **Verdict: keep, dormant — but make the question measurable** (one D1 column: `request_had_tools`) so the module's activation is observable. If a month of real tool traffic never appears, excise.

### 13. `exemplars.js` — the weakest module (Cx 2 / U 1)
Three strikes, all verified this analysis:
1. **The learning loop is fake.** The only `recordToolOutcome` call site hardcodes `success = true` (`worker.js:337`). `toolStats.failures` is structurally always zero — the "continuous learning store" cannot record a failure, so it cannot learn anything. It counts successes and repairs of a subsystem whose failure mode is the entire reason it exists.
2. **`modelToolStats` is write-only.** Populated per model+tool, read by nothing. Dead data.
3. **Silent prompt mutation.** `enrichPromptWithToolExemplars` injects a synthetic assistant turn (fake tool call + formatting guideline) into the user's conversation for any model matching `/flash|mini|8b|7b/` — changing the request, the token billing, and potentially the answer, with **no metric counting injections**. It also gates on `cands[0].modelId` while the splice may serve a different model — the exemplar can be injected for a model that never sees the request.

**Verdict: excise** (my recommendation — 128 LOC + wiring, zero observable benefit, one silent behavior mutation), or at minimum: record real success values, count injections in metrics, and gate on the *served* model. Note: this and `healer.js` arrived via a sibling session's commits (`9c376b8`, `107b011`) — the breaker half of that work is genuinely load-bearing; the exemplar half is the part that doesn't pay rent.

---

## Cross-cutting verdict

**The spine is right and battle-proven.** One honest decision row per request → D1 → homepage; chains as data; budget law on verified asks; failover at every failure boundary the wire actually has (open-fail, first-byte, pre-content, splice, exhaustion). Every fix since September has been *removing a lie* (phantom asks, silent corpses, fake success rows, env-pinned policy) — never adding machinery. That signature held through the worst market weeks on record.

**Remaining incidental complexity, ranked by cost:**
1. `exemplars.js` fake learning + silent prompt mutation — excise (−128 LOC src, −125 test, −wiring)
2. `MODEL_SPOT_MULTIPLIERS` in the prod path — move to seed module (prevents the next calibration incident)
3. `metrics.js` dead `history` array — delete
4. `telemetry.js` lying `"0.10"` fallback + test mock in prod module — retire both
5. Per-isolate usage totals presented as service totals on `/v1/metrics` — label or drop
6. Three test files at 96-98% of the LOC ceiling — split before the next test lands
7. `stream.js` at 90% of ceiling — next feature splits it (classification seam)
8. `cache.js` dual-store name — rename or split, cosmetic

**Complementing to refuse:** durable breaker state in D1 (the 30s reset makes persistence ceremony), an exemplar framework v2, a model-registry abstraction over the chains map, admin auth UI, per-model context config. The chains map already absorbs the next product in one line; every abstraction beyond it is a guess about a future nobody has asked for.

**One-line summary:** the router reached its essential shape — what's left is deleting the parts that pretend (fake stats, lying fallbacks, write-only data), not building parts that might.
