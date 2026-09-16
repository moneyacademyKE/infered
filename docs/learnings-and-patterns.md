# Learnings & Patterns: Dynamic Marketplace LLM Routing

## 1. Spot Price Arbitrage in Token Marketplaces
- **Pattern**: Marketplaces like InferHub have elastic spot prices where different GPU host nodes provide identical models (e.g. `claude-3.5-sonnet`, `deepseek-r1`, `llama-3.3-70b`) with fluctuating capacity discounts ranging from 30% to 85% below official list prices.
- **Learning**: Price-only routing is flawed because the cheapest node is often overburdened or experiencing high TTFT. Multi-objective Pareto routing ($w_{\text{price}} + w_{\text{speed}} + w_{\text{quality}}$) prevents queue stalls while capturing maximum savings.

## 2. Zero-Buffer Streaming Telemetry
- **Pattern**: Measuring Time-to-First-Token (TTFT) on edge proxies often leads developers to buffer responses, destroying streaming responsiveness.
- **Solution**: Use standard `TransformStream` that flips a boolean flag on the very first chunk enqueued and immediately forwards each byte downstream without delay.

## 3. Pure Value State & Decomplecting I/O
- **Pattern**: Keeping routing decision algorithms pure (passing snapshot data structures: `{ priceCache, metricsStore, weights }`) makes the core routing logic 100% deterministic, testable, and lightning fast (<100 microseconds).
- **Learning**: Side effects (I/O, fetch, network retries) are strictly isolated in the client adapter layer (`client.js`), ensuring testability without heavy integration mocks.

## 4. Zero-NPM Edge Tooling with Babashka
- **Pattern**: By using Babashka for development automation, test runners, and market simulation scripts, we avoid the overhead, vulnerabilities, and complex dependency graphs of `npm`.
- **Learning**: Pure ESM JavaScript files (`src/**/*.js`) running natively on Node, Bun, and Cloudflare Workers combined with Babashka scripts provide instant feedback cycles (<50ms).

## 5. Intra-Model Straggler Latency Latching in Spot Cascades
- **Pattern**: In ordered budget cascades, multiple spot nodes often offer the same model. Sorting purely by `blendedPrice` creates a pathology where a 35-second node with a $0.001 cheaper price latches as the primary provider indefinitely because it technically never returned a 5xx error.
- **Solution**: Penalize nodes with `emaLatency > 3000ms` in cascade priority scoring so a 1.2s node at $0.038 easily beats a 35s node at $0.035.

## 6. Monotonic Budget Ladder Invariant
- **Pattern**: When allowing callers to override the budget ceiling (e.g. `X-Infered-Max-Price: 0.25`), naive insertion into a static array `[maxPrice, 0.20, 0.30, Infinity]` inverts escalation order (`0.25 -> 0.20`), tightening constraints instead of loosening them.
- **Solution**: Always filter and sort escalation ladders strictly ascending: `[maxPrice, ...rest.filter(p => p > maxPrice)]`.

## 7. Fail-Fast Circuit State Transitions
- **Pattern**: When transitioning a circuit breaker from `open` to `half-open`, relying on a rolling sample window failure rate (e.g. 40%) to re-trip the circuit causes consecutive failures to slip through if recent historical samples contained successes.
- **Solution**: Michael Nygard / Martin Fowler fail-fast semantics: a *single* failure in `half-open` state must immediately trip the circuit back to `open`.

## 8. Bidirectional Synonym Resolution in Edge Schemas
- **Pattern**: LLM output arguments often use short aliases (`q`, `cmd`, `url`). Unidirectional map lookups (`COMMON_SYNONYMS[rawKey]`) fail when canonical keys are used as group headings.
- **Solution**: Invert and traverse synonym clusters to check if any member matches an expected schema property.

