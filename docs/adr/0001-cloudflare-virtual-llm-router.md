# ADR 0001: Cloudflare Edge Virtual LLM Router for Dynamic Token Marketplaces (InferHub)

## Status
**Accepted & Certified** (2026-09-01)

## Context
Decentralized and secondary token marketplaces (such as InferHub) exhibit dynamic spot pricing with 50% to 100%+ price swings per model compared to official provider rate cards. However, node reliability, latency (Time-to-First-Token), and error rates vary significantly across nodes. 

Monolithic routers (built in Python/Node with heavy dependency trees) introduce excessive latency (100–300ms) and operational friction.

## Decision
We implemented **Infered** as a pure-data, zero-npm virtual LLM router deployed natively on **Cloudflare Workers** (ESM standard) with the following architectural choices:

1. **Pure Multi-Objective Pareto Utility Calculus**:
   $$U(m) = w_{\text{price}} \cdot S_{\text{price}}(m) + w_{\text{speed}} \cdot S_{\text{speed}}(m) + w_{\text{quality}} \cdot S_{\text{quality}}(m) - P_{\text{error}}(m)$$
   Executes in sub-millisecond time (<100 microseconds) without neural classifier overhead.

2. **Decoupled Edge Streaming Telemetry**:
   Uses standard Web Streams (`TransformStream`) to intercept the first token timestamp (measuring TTFT) without buffering tokens or increasing streaming latency.

3. **In-Memory Volatility Cache with Dynamic Sync**:
   Isolate-level caching of spot quotes with short TTL and background asynchronous revalidation.

4. **Zero-NPM Tooling via Babashka**:
   Automated test harness, dynamic market simulations, and dev workflows are driven entirely by Babashka scripts (`bb.edn`), with zero npm dependencies and support for `bunx wrangler`.

## Consequences
### Positive
- Sub-100 microsecond routing decision overhead.
- Over 65–75% average token cost savings vs official list prices in simulations.
- Global sub-5ms edge latency across 330+ Cloudflare PoPs.
- Full OpenAI API client drop-in compatibility for Cursor, Claude Dev, LangChain, and custom apps.
- Resilient automated failover across Pareto candidates on upstream 429/5xx errors.

### Negative / Trade-Offs
- Stateless edge instances require periodic background cache synchronization for spot pricing.
