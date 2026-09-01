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
