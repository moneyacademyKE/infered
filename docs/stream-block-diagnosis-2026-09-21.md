# Stream-Block Diagnosis — tupesa router (2026-09-21)

**Question (moe):** what is leading to stream blocking on the tupesa provider?
**Method:** two subagents (e2e prober `5183740c` + D1 forensics `f2b4aa26`), live `wrangler tail` capture, inline health/metrics/log checks. Read-only — no code changed, no deploy.

## Executive summary

The worker is healthy. The **`zai/glm-5.3-flash` upstream pool intermittently delivers corpse streams** (empty completions and hard mid-frame deaths), and the splice executor has **one loop guard that treats an empty `[DONE]` as a completed answer** — so when a flash candidate wins a request and corpses, the client receives the exact reported error: opening frame shipped, zero tokens, stream over. 13 of 14 live probes passed; the single failure (the 2500-word torture stream) reproduced the user disease verbatim.

## Evidence

### E2E battery (subagent `tupesa-e2e-prober`, 17:42–17:51 UTC)

14 requests: trivial, reasoning-heavy, 900-word essays, a 2500-word torture story, 6-way concurrent burst.

- **13/14 clean:** HTTP 200, `[DONE]` intact, TTFB 3.3–5.7s (burst included), bodies up to 739KB / 2835 chunks.
- **p7 (torture) FAILED exactly like the reports:** HTTP 200, 89 `reasoning_content` chunks relayed, **0 content chunks**, then `curl: (56) Recv failure: Connection reset by peer` at 11.4s — mid-JSON truncation, no `[DONE]`. Served by `zai/glm-5.3-flash`.
- `astra-budget` consistently needed `attempts=4` (astra head nodes dying pre-byte; only 3 asks on the book) — TTFB 8.8–11.1s, but succeeded via flash.
- One mid-stream budget switch recorded (`ali/glm-5.3 → zai/glm-5.3-flash`, reason `budget-stream`) — working as designed.

### D1 forensics (subagent `stream-forensics`)

- **`empty_completion`: 11 rows, 2026-09-20 21:30 → 09-21 00:57, ALL on flash, all `attempts=1`.** The class appeared exactly when the splice deploy landed — it is the honest new label for a pre-existing corpse. One session (`fp-061846…`) hit 8 failures among ~17 tries interleaved with successes → flaky upstream sellers, not a router crash.
- 3 × `all_candidates_exhausted_pre_content` (09-20 18:32–18:34, raw flash requests during the pool outage).
- No `upstream_stream_died`, no `client_disconnected`, no recorded hangs post-fix.
- 16.75h zero-row window (00:57 → 17:42 UTC): most consistent with the pre-existing traffic collapse (~50–100 req/day), not a hidden outage — the 17:42 OK row proves the write path works.

### Worker-side (wrangler tail, 17:37–17:42 UTC window)

- 5 events captured, all `outcome: ok`, `cpuTime` 2–20ms, `exceptions: []`, prod version `9acefc5a` confirmed live.
- **No CPU-limit kills, no worker exceptions.** (Window ended before p7's death at ~17:49 — evidence gap, not exoneration.)

## Root cause — pinned to code

`src/router/stream.js`, candidate loop guard (line 245):

```js
if (contentSeen || doneSeen) break;
```

`doneSeen` is set the moment any chunk contains `[DONE]` — even when **zero content tokens ever arrived**. The loop then stops: **no splice, no next candidate.** The post-loop path honestly records `empty_completion` and closes the client stream, but the client already received the first-byte-committed opening frame → "1 content blocks, 0 output tokens".

Two corpse variants, one family:

1. **Empty `[DONE]`** (the 11 ledger rows): upstream ends the protocol politely with no content → the guard kills failover → the client gets a clean-but-empty corpse.
2. **Hard mid-frame death** (p7): the upstream connection dies mid-chunk (reader rejects → `candidateDied`) — this path *does* continue to the next candidate, but the client-visible result was still a connection reset. Needs a fix-turn to instrument (no tail event in window, no D1 row pulled after 17:42 — evidence gap, not exoneration).

**Why so much traffic hits flash:** cheapest-first ranking under the $0.50 ceiling — flash's spot asks are usually the cheapest on the book, so the flakiest pool serves the most requests. The circuit breaker is already learning (pre-content failures are recorded as samples), but nothing fails over on the empty-corpse class.

## Secondary findings

- `x-infered-ttft-ms` / `x-infered-latency-ms` headers measure **upstream**, not client-perceived latency (80/100ms claimed vs 3–11s observed wall-clock).
- The `attempts` header is frozen at first-byte commit (p7 reported `attempts=1` even if a splice followed).
- `/v1/health` + `/v1/metrics` expose **no version string and no stream counters** — `streamSplices` / `preContentFailures` / `emptyCompletions` / `doneSynthesized` live only in per-isolate memory.
- `usage.totalRequests` counted 6 of 14 requests (per-isolate counters reset on eviction).
- Market sync is healthy: book grew 21 → 1333 quotes (102 models) mid-battery.

## Recommended fixes (ranked)

1. **Splice on empty `[DONE]`:** in the loop guard, only `contentSeen` should be terminal. A pre-content `[DONE]` is a candidate failure → continue to the next candidate (withhold the `[DONE]` frame pre-content, exactly like error frames are withheld). ~5 lines.
2. **Verify the hard-death path splices end-to-end** (the p7 class) and add a test: reader rejection after committed bytes must continue the candidate loop, never reset the client connection.
3. **Expose version + stream counters on `/v1/metrics`** (`emptyCompletions` especially — it is this incident's canary).
4. Optional: per-seller empty-completion demotion (the breaker already collects the samples).

## Verdict

Worker stable, market feed stable, failover machinery works for pre-byte deaths and in-band error frames. The remaining "stream block" is a one-guard bug: an empty `[DONE]` is treated as an answer. Fix #1 is small; everything else is instrumentation.
