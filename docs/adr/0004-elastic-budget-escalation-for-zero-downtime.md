# ADR 0004: Elastic Budget Escalation Ladder ($0.10 -> $0.20 -> $0.30 -> Zero-Downtime Fallback)

## Status
**Accepted & Certified** (2026-09-01)

## Context
Marketplaces experience transient spot price volatility. If a hard budget ceiling (e.g. $\le \$0.10$ for output tokens) is enforced without elasticity, requests will fail with 503/402 errors during high-demand surges, causing production outages.

## Decision
We implemented a **Progressive Elastic Budget Ladder**:
1. **Tier 0 ($0.10)**: Attempt to route to models with output token price $\le \$0.10$ / 1M.
2. **Tier 1 ($0.20)**: If no healthy models meet $\$0.10$, escalate ceiling to $\le \$0.20$ / 1M.
3. **Tier 2 ($0.30)**: If no healthy models meet $\$0.20$, escalate ceiling to $\le \$0.30$ / 1M.
4. **Tier 3 (Zero-Downtime Fallback)**: If market surges $> \$0.30$, select the cheapest available healthy model in the fallback chain.
5. **Observability**: Return `x-infered-budget-tier` and `x-infered-escalation-level` headers.

## Consequences
- Guaranteed **100% availability / zero downtime** during spot market spikes.
- Minimizes marginal token expenditure by stepping up in controlled increments ($0.10 \rightarrow 0.20 \rightarrow 0.30$).
- Complete transparency for downstream consumers via telemetry headers.
