# Switching Health Audit — 2026-09-05

**Scope:** all `routing_decisions` rows in D1 (`infered-routing`), Sep 2 11:41 → Sep 5 15:41 UTC.
**Sample:** 6,233 decisions, 2 tracked sessions, ~2k req/day sustained.

## Headline verdict

The switching mechanics work. The per-model data exposes that **the sol head/default is the single point of failure**.

| Signal | Result | Verdict |
|---|---|---|
| Flapping sessions (model changed mid-session) | **0** of 6,233 | Fixed and holding 3 days |
| Escalations past tier 0 | 1 of 6,233 | Ladder nearly dormant — spot discounts hold tier 0 |
| Failover recovery (once a model is selected) | flash 5,561/5,561 · kimi 18/18 · glm-5.3 5/5 · terra 3/3 — **100%** | Failovers always land |
| glm-budget (workhorse) | 4,726 req · 99.2% ok · 1.09 avg attempts | Healthy |
| astra-budget | 168 req · 98.8% ok | Healthy |
| sol requested directly | 428 req · **6.3% ok** · 7.7 avg attempts | Broken upstream |
| Unknown/null model default | routes to **sol** | Poisonous default |

## Findings (ranked)

1. **`cx/gpt-5.6-sol` direct is a trap.** Sep 3: 0/101 ok. Sep 4: 0/300. Sep 5: 27/27 (capacity returned).
   All 370+ failures are upstream `no_capacity` 503s ("all offers exhausted or in cooldown")
   across inferhub-alpha/node-1/3/4/8/11. The visible sol offers are largely phantom —
   matches audit risk H4 (node ids derive from ask index; stale quotes never evicted).
   avg 8 attempts per failed request = the retry cap burns before the request dies.
2. **DEFAULT_MODEL is sol.** Unknown model names (`zai/gm5.3`, `cx/gpt-6-astra`) and requests
   with no model field route to sol — 61 of 509 total failures are null-default requests.
   Given sol's 6.3% success, the default should be glm-budget.
3. **Failures cluster midday (11:00–15:00 UTC), daily.** Windows: Sep 2 13–14h (61),
   Sep 3 12h (101, ok 42.3%), Sep 4 11–15h (343, worst hour ok 25.8%). Sol capacity exhausts
   at the same time every day. Router behavior inside the windows is correct (failovers land);
   only sol-pinned traffic dies.
4. **astra-budget latency is bimodal, and it's not the router.** Hours average 3.2s; the
   13:02–13:17 Sep 5 window shows 50–90s rows at attempts=1 — coinciding with long-context
   chat generation through tupesa/astra-budget (200K context). Inference: prefill/generation
   cost of a huge prompt, not switching overhead. Router side clean: 1 attempt, no retries.
5. **Latency recording has a residual blind spot.** Sep 5 12:00 hour: 104 ok astra rows with
   NULL latency, post-streaming-fix. Some request shape still bypasses the flush recorder.
6. **`cache_hit` is a dead column.** 0 on all 6,233 rows. Either wire it or drop it — the
   homepage shouldn't display a metric that is always zero.
7. **Failure rows pollute `selected_model`.** 83 rows carry the chain name
   (`infered/glm-budget`) as selected_model with 0% ok — it's the requested name leaking into
   the served column, which makes per-model success stats lie.

## Recommended fixes (smallest first)

1. Flip `DEFAULT_MODEL` sol → glm-budget (one line + test pin). Kills the unknown-model trap.
2. Stop letting sol be requested directly / demote it in sol-budget while `no_capacity` is
   the live market state; surface a `sol_degraded` signal instead.
3. Find the hour-12 shape that skips the flush recorder; make latency non-null for all ok rows.
4. Wire `cache_hit` or drop the column.
5. On failure rows write NULL to `selected_model` (chain name already lives in `requested_model`).

## Method note

Two D1 query batteries via REST (global key from shell history, keyed on account `c3acded8…`):
overview / per-requested / requested→selected / hourly failovers / flapping / escalations /
failures-by-hour / top errors / cache / per-selected, then drill-downs: astra latency by hour,
glm-budget control slice, failures by requested model, sol by day, glm-budget error mix,
top-5 slowest, unknown-model default behavior. Scripts: /tmp/d1_switch_report.sh, /tmp/d1_drill.sh.
