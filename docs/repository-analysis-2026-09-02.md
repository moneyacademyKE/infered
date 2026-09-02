# Infered — Repository Analysis (2026-09-02)

Static + empirical analysis of `moneyacademyKE/infered` @ `c28f2b7` (main, clean, == origin/main).
No product code modified. All claims below are verified against file reads or command output
(`(src: file|cmd, conf: h)` unless marked otherwise).

## 1. What this is

Cloudflare Workers edge router that exposes an OpenAI-compatible `/v1/chat/completions`
endpoint and routes each request to the cheapest healthy "spot node" on a token
marketplace (InferHub), under a strict output-token price ceiling with an escalation
ladder, plus deterministic response caching, session affinity, and LLM tool-call JSON
autohealing. Zero npm dependencies — plain ESM + Babashka tooling.

**Stack:** ES2022 JavaScript on Workers (`nodejs_compat`), Babashka (`bb`) test/lint/sim
harness that shells `node --input-type=module -e`, Wrangler for deploy. No package.json,
no lockfile, no CI.

## 2. Architecture (verified)

| Module | LOC | Role |
|---|---|---|
| `src/worker.js` | 377 | Entry point, routing endpoints, SWR sync trigger, cache orchestration |
| `src/router/catalog.js` | 117 | Model/quality/official-price data + virtual alias resolution |
| `src/router/pricing.js` | 180 | Spot quote store, order-book ingestion, blended/savings math |
| `src/router/pareto.js` | 280 | Cascade + multi-objective ranking, budget ladder |
| `src/router/metrics.js` | 192 | EMA latency/TTFT, usage ledger, circuit breaker |
| `src/router/cache.js` | 105 | Deterministic response cache + session affinity (in-memory Maps) |
| `src/router/healer.js` | 285 | JSON repair, type coercion, fuzzy param remap for tool calls |
| `src/router/exemplars.js` | 128 | Tool exemplar injection for budget models |
| `src/router/client.js` | 201 | Upstream fetch, timeout, streaming telemetry, failover loop |
| `src/ui/dashboard.js` | 277 | Embedded zero-dependency dashboard |
| `src/dev_server.clj` | 78 | Node http shim exposing worker.fetch locally |

Shape is genuinely good: routing math is pure (stores passed in as data), I/O is
isolated in `client.js`, every file < 500 LOC, module imports form a clean DAG
(`catalog` ← `pricing` ← `pareto` ← `worker`; `metrics` standalone).

## 3. Empirical verification results

| Command | Result |
|---|---|
| `bb test-all` (with `/opt/homebrew/bin` on PATH) | exit 0 — 9 namespaces, 15 deftests, 81 assertions, 0 failures |
| `bb lint` | exit 0 — all listed files < 500 LOC (but see §5.12: coverage gap) |
| `bb sim` | exit 0 — 100 cycles, avg savings 71.5%, ~207 µs/decision |
| `node --check` on all src JS | pass (via `node --check` on every tracked src file) |
| Git state | `main == origin/main` (0/0 ahead/behind), tree clean, 5 commits, single bot author |
| CI | none (`.github/` absent, no workflow runs on GH) |
| Issues / PRs / tags | none |
| License | README says MIT; **no LICENSE file, GitHub licenseInfo = null** |

Note: `node` is not on the default shell PATH (it lives at `/opt/homebrew/bin/node`);
`bb test-all`/`bb sim` fail with "Cannot run program node" without it. Tooling depends
on an undeclared runtime.

## 4. Findings — High severity

1. **Open proxy, open admin, open spend (H1)** — `(src: file, conf: h)`
   `/v1/chat/completions` has zero authentication; the worker spends its own
   `INFERHUB_API_KEY` on every request (worker.js:154, 298-307). `/v1/admin/sync`
   (worker.js:234-242) lets *anyone* force live order-book refreshes on the worker's
   key. CORS is `*` with `Authorization` allowed (worker.js:39-41). Once the URL
   leaks, the InferHub balance is burnable by strangers. Fix: require an
   Infered-issued key; drop or gate admin/sync.

2. **Silent mock success in the production path (H2)** — worker.js:298
   `fetchFn = (apiKey && apiKey !== "test-key") ? fetch : createStandaloneMockFetch()`.
   With no API key configured, the endpoint returns fabricated 200 completions that
   look real. A router whose failure mode is fake success is worse than a 503. Fix:
   return 503 when no upstream key.

3. **NaN pricing bypasses the budget ceiling (H3)** — pricing.js:72-99 ingests
   upstream asks with `Number(raw.prompt)` and no validation. `NaN` flows into
   `outputTokenPrice`; the strict ceiling check `if (outputTokenPrice > ceiling)
   continue` (pareto.js:56-59) is **false** for NaN, so an unpriceable candidate
   passes the budget filter and can win the cascade (cascade priority is chain-based,
   not price-based). The "strict budget invariant" has a data-quality hole. Fix:
   reject non-finite quotes at ingestion.

4. **Stale/ghost quotes and unstable provider identity (H4)** — pricing.js:108-142
   derives provider ids from ask index (`inferhub-node-${i+1}`) and `updateSpotPrices`
   never evicts. If the live ask count shrinks, old node-N quotes persist forever and
   `getQuotesForModel` routes to stale prices; session affinity pinned to `node-N`
   is meaningless after the next sync. Fix: replace the whole quote set per model on
   ingest (or timestamp-evict).

5. **Caller credentials forwarded upstream (H5)** — worker.js:154 accepts the
   caller's `Authorization: Bearer` as the InferHub key and client.js:50-52 forwards
   it verbatim. Any OpenAI-compatible client pointed at this router sends its real
   key to `api.inferhub.dev`. Fix: consume but never relay caller auth.

## 5. Findings — Medium severity

6. **The advertised "Pareto" engine doesn't run on the flagship path** —
   `rankCandidates` takes the cascade branch for `infered/sol-budget|cascade` or any
   `max_price`; weights are unused there (pareto.js:166-178). Sol-budget ordering is
   fixed chain priority ± circuit/affinity. Fine design, but README/marketing oversell;
   also `findParetoFrontier` (pareto.js:259) is exported and never called; `DEFAULT_CACHE_TTL_MS`
   in pricing.js is dead.

7. **Budget ceiling is soft, not hard** — with a user `max_price`, the ladder is
   `[max, 0.20, 0.30, Infinity]` (pareto.js:128-130). A client demanding ≤ $0.05 will
   still be served $0.30/unconstrained candidates under spot pressure. That matches
   ADR 0004's availability goal, but the header name promises a guarantee it does not
   keep. Needs an explicit "hard ceiling" opt-out (return 503 instead of escalating).

8. **Cache key is a 32-bit toy hash** — cache.js:19-43 uses a JS string hash + length
   as key. Collision probability across many distinct prompts is non-trivial for a
   shared public endpoint; a collision silently returns the wrong cached completion.
   ADR 0003 claims "normalized SHA-256" — doc/implementation mismatch. Also eviction
   is off-by-one (evicts when `size > 500` *before* insert → max 501).

9. **Streaming is a second-class citizen** — for `stream: true`: no tool healing, no
   caching, no usage/cost recording, no session affinity; `result.getMetrics()` is
   never invoked in worker.js so TTFT telemetry is dropped and headers fall back to
   fake defaults (`latencyMs || 100`). Failover cannot happen mid-stream (a stream
   that 200s then dies counts as success).

10. **Telemetry truthfulness** — healer records `success=true` for every observed
    tool call (worker.js:329-333); failures are never observed at the proxy, so
    `failures` in exemplarStore is always 0. Circuit-breaker reset marks half-open
    inside a read (`isCircuitOpen` mutates store — metrics.js:180-192), and the
    breaker trips on any ≥40% failure window ≥3 samples regardless of sample age
    (slow traffic = permanent tripping).

11. **`bb lint` covers less than it claims** — its globs (`test` `**/*.{js,clj}`,
    `.` root-only pattern) empirically miss `src/worker.js`, `src/dev_server.clj`,
    and **all** `test/*.clj` (lint output lists exactly 12 files; verified via a
    direct `fs/glob` probe). The LOC gate does not protect the largest file.

12. **The simulator doesn't exercise production pricing** — `sim_market.clj` draws
    30–80% discounts while production calibration uses 92–99.8% (pricing.js:18-27).
    Result: Sol is *never* eligible (`solSelected: 0`) yet the sim prints ✅ and
    asserts nothing about the distribution. The cascade's primary path is untested
    under load.

13. **`live_test.clj` passes a parameter the engine ignores** — it calls
    `rankCandidates({... maxPriceThreshold: 0.10})`; the engine's key is
    `maxFallbackPrice`. Scenarios A/B/C are identical calls; the "live dynamic
    switching verified" banner is not testing switching (test/live_test.clj:41-70).

## 6. Findings — Low severity / hygiene

14. No LICENSE file despite MIT claim in README.
15. No CI: the whole `bb` suite is green today but nothing runs it on push.
16. No package.json/lockfile: `bunx wrangler` version is unpinned; toolchain is
    non-reproducible (and `node` missing from PATH broke `bb test-all` out of the box).
17. `wrangler.jsonc` compatibility_date 2024-09-01 is ~2 years stale.
18. Base-url drift: tests/dev use `api.inferhub.net`, wrangler uses `api.inferhub.dev`.
19. Dashboard builds `innerHTML` from `/v1/metrics` values whose ids originate from
    the upstream API — stored-XSS-shaped, low impact (operator-only view) but cheap
    to fix with textContent.
20. `parseWeightsAndBudget` silently swallows malformed `X-Infered-Weights`; NaN
    weights from `parseFloat` are unvalidated (worker.js:57-72).
21. Global in-memory state (price cache, response cache, metrics) is per-isolate:
    reset on deploy/crash, inconsistent across PoPs. Fine for v1; document it.

## 7. What's genuinely good

- Pure-data routing core; I/O quarantined in `client.js` — the tests hit real module
  code through Node ESM, not mocks of the logic under test.
- The healer (fence stripping, bracket-stack repair, type coercion, Levenshtein param
  remap) is a real, testable piece of engineering, not vaporware.
- Escalation ladder + strict output-token filter is a coherent cost-control story,
  with an ADR trail that actually matches most of the code.
- Zero-dependency ESM + Babashka keeps the surface tiny: 3.7k LOC total, ~2.3k src.

## 8. Recommended order of attack

1. Auth on `/v1/chat/completions` + kill/gate `/v1/admin/sync` (H1) — smallest diff, largest risk removed.
2. Delete the mock-fetch branch; 503 without key (H2).
3. Validate quotes at ingestion (finite numbers) (H3) + replace-per-model quote eviction (H4).
4. Stop relaying caller Authorization (H5).
5. Fix `live_test` param name; make sim use calibrated multipliers; add lint coverage for worker/tests.
6. LICENSE file + minimal GitHub Actions running `bb test-all`, `bb lint`, `bb sim`.

---

*Analysis by OpenCrabs (bk-734f). Verification: `bb test-all` exit 0 (81 assertions),
`bb lint` exit 0, `bb sim` exit 0, `git status` clean @ c28f2b7.*
