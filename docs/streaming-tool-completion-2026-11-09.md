# Streaming tool-completion incident — 2026-11-09

## Outcome

The client-visible streaming failure was reproduced, fixed, deployed, and soak-tested. The same turn also registered `infered/solterra` as an exact two-model cascade.

Production version: `59c04ddb-b0b8-4a44-81e8-0935eeb882c3`.

## Symptoms in the client log

- `Stream ended without [DONE]: 1 content blocks, 0 output tokens`.
- `Streaming error: error decoding response body`.
- Intermittent Cloudflare `503 error code: 1102` on the router.
- Failures occurred on real agent turns with tool calls and large histories, including one 555-message / ~300K-token session.

## Reproduction

Persistent probe artifacts live in:

`~/.opencrabs/projects/infered/files/stream-incident-2026-11-09/`

### Tool-only stream

Direct InferHub controls using `ali/kimi-k3.1-pro` and `ali/qwen3.8-max-0902` returned valid tool calls and one `[DONE]`.

Before the fix, the router returned the tool-call deltas and then appended:

`all candidates exhausted before content`

One captured response also contained an incomplete trailing JSON event followed by a synthetic error, exactly the malformed-wire shape a client cannot parse.

### Large history

A generated request with 555 messages, 47 tool schemas, and a 1,083,262-byte body reproduced the boundary that exceeded the router's former 512-message gate.

## Root causes

1. **Completion detection only recognized non-empty text `content`.** Valid OpenAI tool-only responses (`delta.tool_calls` plus `finish_reason: tool_calls`) were classified as empty completions and retried until exhaustion.
2. **The relay forwarded arbitrary byte chunks rather than complete SSE events.** A truncated upstream tail could be followed by a synthetic frame or `[DONE]`, leaving invalid JSON on the client wire.
3. **The 512-message shape cap rejected real agent sessions.** OpenCrabs treated the resulting 400 as provider failure and fell back to direct InferHub, bypassing the router's stream protection.

## Fixes

### `2448c29` — stream semantics and framing

- Added `src/router/sse.js`.
- Completion now means non-empty text, `tool_calls`, a `tool_calls` finish reason, or a legacy function call.
- The relay emits only complete SSE events. A partial tail stays buffered and is discarded with a failed candidate.
- A non-streaming OpenAI JSON response is explicitly adapted to SSE when an upstream returns JSON to a streaming request.
- Added `test/tool_stream_test.clj` with tool-only and truncated-tail regressions.

### `ca56b81` — real agent history boundary

- Raised `MAX_MESSAGES` from 512 to 1024; the 2 MiB byte cap remains the physical bound.
- Added a live-derived 555-message regression; 1100 messages is still rejected.

### `d8b9a46` — diagnostic truth

- Fixed `scripts/d1-stress-window.bb` REST result indexing and a shadowed `key` binding that prevented row summaries.

### `c6823ad` — solterra

- Added `infered/solterra`: `oai/gpt-6.1-sol` → `oai/gpt-6.1-terra`.
- Registered both raw model IDs, official prices, context metadata, calibrated spot asks, and the strong-link head.
- Added the chain to the OpenCrabs `tupesa` picker and updated the playbook.

## Verification

- Full suite: **15 namespaces, 0 failures, 0 errors**.
- `bb lint`: clean; all production and test files strictly below 500 LOC.
- `bb scripts/smoke.bb`: **PASS**, including six-chain model listing and stream sentinel checks.
- Tool-only live probe after deployment: HTTP 200, valid tool call, exactly one `[DONE]`, no synthetic error.
- Six-chain soak: **24/24 streams passed**; zero missing `[DONE]`; maximum sequential TTFB **3.7s**.
- 555-message / 47-tool live probe: HTTP 200, real answer, one `[DONE]`, no Cloudflare 1102.
- D1 recent window: **33/34 ok**; the sole failure was the pre-deploy 09:59 row. `solterra` is 3/3 served by `oai/gpt-6.1-sol` on attempt 1.
- Client log after deployment: no new stream-error signatures; a real session was served by `tupesa/infered/solterra`.

## Residual upstream fact

`oai/gpt-6.1-sol` is live and healthy. Direct probes of `oai/gpt-6.1-terra` returned HTTP 400 `upstream request failed; please retry` in both streaming and non-streaming modes. It remains the requested second cascade link; Sol currently carries the chain. This is an upstream condition, not hidden by the router.

## Hickey certification

The fix separates two facts the old relay complected: *a protocol event arrived* and *the model produced an actionable answer*. It also makes byte boundaries explicit instead of hoping upstream chunks align with SSE frames. One small parser module, one completion predicate, no new routing machinery.
