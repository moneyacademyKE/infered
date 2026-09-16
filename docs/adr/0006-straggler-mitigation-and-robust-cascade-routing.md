# ADR 0006: Straggler Node Mitigation & Robust Cascade Routing

## Status
Accepted (2026-09-17)

## Context
Production D1 telemetry analysis revealed that certain requests experienced latency between 17s and 36s. The root cause was that `evaluateCascadeTier` only sorted candidates by `chainIndex`, and tied nodes for the same model were ordered purely by `blendedPrice`. When an overloaded spot node suffered high latency (>25s) but still returned HTTP 200, it remained the top-ranked candidate indefinitely because it was a fraction of a cent cheaper than healthy, fast nodes (1.2s).

Additionally, several subtle edge-case bugs were uncovered:
1. Inverted ladder escalation when custom `maxFallbackPrice` was provided.
2. Incomplete circuit-breaker transition where failed probes in `half-open` state remained half-open.
3. Stale candidate references returned by `executeWithChainFallback`.
4. Synthetic `official` provider ID sent upstream on unconstrained price fallback tiers.
5. Incomplete catalog metadata for `ali/qwen3.8-max`.
6. Tool call arguments failing coercion when pre-parsed as objects.
7. Event listener leak on normal streaming termination.
8. `worker.js` approaching the strict 500 LOC ceiling (478 LOC).

## Decisions
1. **Intra-Model Straggler Latency Penalty**: In `evaluateCascadeTier`, nodes with `emaLatency > 3000ms` receive a proportional priority score penalty, allowing fast nodes to win over straggler nodes for microscopic price differences.
2. **Monotonic Budget Ladder**: The escalation ladder is strictly ascending: `[maxPrice, ...[0.20, 0.30, Infinity].filter(p => p > maxPrice)]`.
3. **Fail-Fast Half-Open Circuit**: Any failed request on a `half-open` node immediately trips the circuit back to `open`.
4. **Candidate Sync in Chain Fallback**: `executeWithChainFallback` updates `candidates` to the fallback chain's candidate list upon failover.
5. **Official Provider Omission**: Omit `X-InferHub-Provider` when routing to list price baseline so InferHub's native router selects the node.
6. **Worker Modularity Extraction**: Extracted HTTP response helpers, CORS management, and D1 decision recording to `src/router/telemetry.js`, bringing `worker.js` down to 407 LOC.

## Consequences
- Prevents persistent selection of 20–40s straggler nodes.
- Eliminates 503 errors during unconstrained budget escalation.
- Faithful telemetry candidate logging.
- Guaranteed file length compliance (<500 LOC) across all repository source files.
