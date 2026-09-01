# ADR 0003: Multi-Tier Edge Caching and KV Prefix Session Affinity

## Status
**Accepted & Certified** (2026-09-01)

## Context
In LLM inference proxying, traditional caching is often complected into a single storage tier. However, LLM routing involves multiple orthogonal concerns:
1. Dynamic spot pricing quotes from InferHub.
2. Deterministic response outputs for identical prompts ($T=0$).
3. KV cache prefill retention across multi-turn conversations on GPU host nodes (prompt caching).

## Decision
We implemented a **decomplected 4-dimension caching strategy**:
1. **Market State Cache**: Short TTL (15s) in-memory spot price quote store with stale-while-revalidate background refresh.
2. **Deterministic Response Cache**: Exact matching via normalized SHA-256 request hashing for `temperature: 0` queries returning in <1ms with $0 token cost.
3. **Session / Prefix Affinity Routing**: Maps `X-Session-ID` to previously used GPU host nodes, granting a Pareto utility affinity bonus ($+0.25$) to maintain warm KV prefix cache hit rates (saving 80–90% on input tokens).
4. **Telemetry Cache**: Rolling ring buffer tracking EMA latency and circuit breaker status.

## Consequences
- 100% token savings on repeated deterministic requests.
- Substantial 80-90% input token cost reductions on multi-turn conversations through session affinity.
- Zero external database dependencies (pure in-memory isolate + Web APIs).
