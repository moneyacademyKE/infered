(ns stream-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-inband-error-frame-splices-to-next-candidate
  (testing "Node that heartbeats then fails IN-BAND with zero content is spliced away — the client never sees the error frame"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore, getProviderStats } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const enc = new TextEncoder();
                const sse = (delta) => 'data: ' + JSON.stringify({ choices: [{ delta }] }) + '\\n\\n';

                const fetchFn = async (url, opts) => {
                  const prov = opts.headers['X-InferHub-Provider'];
                  if (prov === 'corpse-node') {
                    // Exact shape reproduced live 2026-09-20: heartbeats, in-band 502, [DONE], zero content.
                    return new Response(new ReadableStream({
                      async start(c) {
                        c.enqueue(enc.encode(': heartbeat\\n\\n'));
                        await new Promise(r => setTimeout(r, 10));
                        c.enqueue(enc.encode('data: {\"error\":{\"code\":502,\"message\":\"upstream stream failed\",\"type\":\"server_error\"}}\\n\\n'));
                        c.enqueue(enc.encode('data: [DONE]\\n\\n'));
                        c.close();
                      }
                    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
                  }
                  return new Response(new ReadableStream({
                    async start(c) {
                      c.enqueue(enc.encode(sse({ role: 'assistant', content: '' })));
                      await new Promise(r => setTimeout(r, 10));
                      c.enqueue(enc.encode(sse({ content: 'recovered answer' })));
                      c.enqueue(enc.encode('data: [DONE]\\n\\n'));
                      c.close();
                    }
                  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
                };

                const result = await executeWithFallback({
                  candidates: [
                    { providerId: 'corpse-node', modelId: 'zai/glm-5.3-flash', savingsPct: 99, blendedPrice: 0.01 },
                    { providerId: 'healthy-node', modelId: 'ali/glm-5.3', savingsPct: 97, blendedPrice: 0.05 }
                  ],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn
                });

                const reader = result.stream.getReader();
                const dec = new TextDecoder();
                let received = '';
                try { for (;;) { const { done, value } = await reader.read(); if (done) break; received += dec.decode(value, { stream: true }); } } catch (e) {}
                await new Promise(r => setTimeout(r, 30));
                const m = result.getMetrics();

                console.log(JSON.stringify({
                  success: result.success,
                  hasRecovered: received.includes('recovered answer'),
                  hasErrorFrame: received.includes('upstream stream failed'),
                  hasHeartbeat: received.includes('heartbeat'),
                  hasDone: received.includes('[DONE]'),
                  finalServed: m.servedModel,
                  finalAttempts: m.attempts,
                  corpseFailed: getProviderStats(metricsStore, 'corpse-node', 'zai/glm-5.3-flash').failedRequests,
                  preContentFailures: metricsStore.usage.preContentFailures || 0,
                  streamSplices: metricsStore.usage.streamSplices || 0
                }));")]
      (is (:success res))
      (is (:hasRecovered res) "spliced candidate's content must reach the client")
      (is (false? (:hasErrorFrame res)) "the withheld in-band error frame must NOT reach the client")
      (is (:hasHeartbeat res) "heartbeats relay live — they keep the client warm during the splice")
      (is (:hasDone res))
      (is (= "ali/glm-5.3" (:finalServed res)) "ledger truth = the model that ANSWERED, not the one that committed")
      (is (= 2 (:finalAttempts res)))
      (is (= 1 (:corpseFailed res)) "the corpse must feed the circuit breaker")
      (is (= 1 (:preContentFailures res)))
      (is (= 1 (:streamSplices res))))))

(deftest test-bare-eof-pre-content-splices
  (testing "Clean EOF after role/reasoning frames but zero content is a candidate failure — next candidate splices into the same client stream"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const enc = new TextEncoder();
                const sse = (delta) => 'data: ' + JSON.stringify({ choices: [{ delta }] }) + '\\n\\n';

                const fetchFn = async (url, opts) => {
                  const prov = opts.headers['X-InferHub-Provider'];
                  if (prov === 'dies-early') {
                    return new Response(new ReadableStream({
                      async start(c) {
                        c.enqueue(enc.encode(sse({ role: 'assistant', content: '', reasoning_content: '' })));
                        await new Promise(r => setTimeout(r, 12));
                        c.enqueue(enc.encode(sse({ reasoning_content: 'thinking...' })));
                        await new Promise(r => setTimeout(r, 12));
                        c.close();
                      }
                    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
                  }
                  return new Response(new ReadableStream({
                    async start(c) {
                      c.enqueue(enc.encode(sse({ content: 'spliced answer' })));
                      c.enqueue(enc.encode('data: [DONE]\\n\\n'));
                      c.close();
                    }
                  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
                };

                const result = await executeWithFallback({
                  candidates: [
                    { providerId: 'dies-early', modelId: 'ali/kimi-k3', savingsPct: 96, blendedPrice: 0.04 },
                    { providerId: 'flash-node', modelId: 'zai/glm-5.3-flash', savingsPct: 99, blendedPrice: 0.01 }
                  ],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn
                });

                const reader = result.stream.getReader();
                const dec = new TextDecoder();
                let received = '';
                try { for (;;) { const { done, value } = await reader.read(); if (done) break; received += dec.decode(value, { stream: true }); } } catch (e) {}
                await new Promise(r => setTimeout(r, 30));
                const m = result.getMetrics();

                console.log(JSON.stringify({
                  success: result.success,
                  sawReasoning: received.includes('thinking...'),
                  hasAnswer: received.includes('spliced answer'),
                  hasDone: received.includes('[DONE]'),
                  finalServed: m.servedModel,
                  finalAttempts: m.attempts,
                  preContentFailures: metricsStore.usage.preContentFailures || 0
                }));")]
      (is (:success res))
      (is (:sawReasoning res) "the dead candidate's reasoning frames were already relayed live — that is the contract")
      (is (:hasAnswer res) "the spliced candidate's content completes the response")
      (is (:hasDone res))
      (is (= "zai/glm-5.3-flash" (:finalServed res)))
      (is (= 2 (:finalAttempts res)))
      (is (= 1 (:preContentFailures res))))))

(deftest test-content-then-bare-eof-synthesizes-done
  (testing "Content relayed but upstream EOFs without the [DONE] sentinel: the worker synthesizes it — complete answer, clean protocol end"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const enc = new TextEncoder();
                const sse = (delta) => 'data: ' + JSON.stringify({ choices: [{ delta }] }) + '\\n\\n';
                const outcomes = [];

                const fetchFn = async () => new Response(new ReadableStream({
                  async start(c) {
                    c.enqueue(enc.encode(sse({ role: 'assistant', content: '' })));
                    await new Promise(r => setTimeout(r, 10));
                    c.enqueue(enc.encode(sse({ content: 'the answer' })));
                    await new Promise(r => setTimeout(r, 10));
                    c.close();
                  }
                }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });

                const result = await executeWithFallback({
                  candidates: [{ providerId: 'n1', modelId: 'm1', savingsPct: 10, blendedPrice: 0.1 }],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn,
                  onStreamOutcome: (o) => outcomes.push(o)
                });

                const reader = result.stream.getReader();
                const dec = new TextDecoder();
                let received = '';
                try { for (;;) { const { done, value } = await reader.read(); if (done) break; received += dec.decode(value, { stream: true }); } } catch (e) {}
                await new Promise(r => setTimeout(r, 30));

                console.log(JSON.stringify({
                  success: result.success,
                  hasAnswer: received.includes('the answer'),
                  hasDone: received.includes('[DONE]'),
                  doneSynthesized: metricsStore.usage.doneSynthesized || 0,
                  outcomeCount: outcomes.length
                }));")]
      (is (:success res))
      (is (:hasAnswer res))
      (is (:hasDone res) "missing sentinel must be synthesized — the answer was complete")
      (is (= 1 (:doneSynthesized res)))
      (is (zero? (:outcomeCount res)) "a synthesized-DONE success is not a failure — flush records it ok=1"))))

(deftest test-all-candidates-exhausted-pre-content
  (testing "Every candidate dies before content: client gets a clean in-band provider error + [DONE], never a dropped connection"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const enc = new TextEncoder();
                const sse = (delta) => 'data: ' + JSON.stringify({ choices: [{ delta }] }) + '\\n\\n';
                const outcomes = [];

                const fetchFn = async () => new Response(new ReadableStream({
                  async start(c) {
                    c.enqueue(enc.encode(sse({ role: 'assistant', content: '' })));
                    await new Promise(r => setTimeout(r, 10));
                    c.close();
                  }
                }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });

                const result = await executeWithFallback({
                  candidates: [
                    { providerId: 'a', modelId: 'ma', savingsPct: 10, blendedPrice: 0.1 },
                    { providerId: 'b', modelId: 'mb', savingsPct: 10, blendedPrice: 0.1 }
                  ],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn,
                  onStreamOutcome: (o) => outcomes.push(o)
                });

                const reader = result.stream.getReader();
                const dec = new TextDecoder();
                let received = '';
                try { for (;;) { const { done, value } = await reader.read(); if (done) break; received += dec.decode(value, { stream: true }); } } catch (e) {}
                await new Promise(r => setTimeout(r, 30));

                console.log(JSON.stringify({
                  committed: result.success,
                  hasSyntheticError: received.includes('all candidates exhausted before content'),
                  hasDone: received.includes('[DONE]'),
                  outcomeError: outcomes[0] ? outcomes[0].error : null,
                  finalAttempts: result.getMetrics().attempts
                }));")]
      (is (:committed res) "first role byte committed the response — failure is delivered in-band")
      (is (:hasSyntheticError res) "client receives a clean provider error frame")
      (is (:hasDone res) "and a proper [DONE] — never a bare connection drop")
      (is (= "all_candidates_exhausted_pre_content" (:outcomeError res)) "ledger records the exhaustion")
      (is (= 2 (:finalAttempts res))))))

(deftest test-empty-completion-done-without-content
  (testing "[DONE] with zero content across the stream: protocol completed, answer empty — recorded as empty_completion, not success"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const enc = new TextEncoder();
                const sse = (delta) => 'data: ' + JSON.stringify({ choices: [{ delta }] }) + '\\n\\n';
                const outcomes = [];

                const fetchFn = async () => new Response(new ReadableStream({
                  async start(c) {
                    c.enqueue(enc.encode(sse({ role: 'assistant', content: '' })));
                    await new Promise(r => setTimeout(r, 10));
                    c.enqueue(enc.encode('data: [DONE]\\n\\n'));
                    c.close();
                  }
                }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });

                const result = await executeWithFallback({
                  candidates: [{ providerId: 'n1', modelId: 'm1', savingsPct: 10, blendedPrice: 0.1 }],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn,
                  onStreamOutcome: (o) => outcomes.push(o)
                });

                const reader = result.stream.getReader();
                const dec = new TextDecoder();
                let received = '';
                try { for (;;) { const { done, value } = await reader.read(); if (done) break; received += dec.decode(value, { stream: true }); } } catch (e) {}
                await new Promise(r => setTimeout(r, 30));

                console.log(JSON.stringify({
                  success: result.success,
                  hasSyntheticError: received.includes('all candidates exhausted before content'),
                  hasDone: received.includes('[DONE]'),
                  outcomeError: outcomes[0] ? outcomes[0].error : null
                }));")]
      (is (:success res) "committed at the first role byte")
      (is (:hasSyntheticError res) "empty [DONE] is withheld and exhausted cleanly in-band — never relayed as a hollow completion")
      (is (:hasDone res) "the client still gets a proper terminator")
      (is (= "all_candidates_exhausted_pre_content" (:outcomeError res))
          "an empty answer is a candidate failure the ledger names — never ok=1"))))

(deftest test-empty-done-splices-to-next-candidate
  (testing "Upstream [DONE] with zero content is a corpse: withhold it, splice the next candidate"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const enc = new TextEncoder();
                const sseFrame = (delta) => 'data: ' + JSON.stringify({ choices: [{ delta }] }) + '\\n\\n';

                let call = 0;
                const fetchFn = async () => {
                  call++;
                  if (call === 1) {
                    // The flash disease: role + reasoning, then [DONE] with ZERO content
                    return new Response(new ReadableStream({
                      start(c) {
                        c.enqueue(enc.encode(sseFrame({ content: '', role: 'assistant' })));
                        c.enqueue(enc.encode(sseFrame({ reasoning_content: 'half-thought' })));
                        c.enqueue(enc.encode('data: [DONE]\\n\\n'));
                        c.close();
                      }
                    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
                  }
                  return new Response(new ReadableStream({
                    start(c) {
                      c.enqueue(enc.encode(sseFrame({ content: '', role: 'assistant' })));
                      c.enqueue(enc.encode(sseFrame({ content: 'He' })));
                      c.enqueue(enc.encode(sseFrame({ content: 'llo' })));
                      c.enqueue(enc.encode('data: [DONE]\\n\\n'));
                      c.close();
                    }
                  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
                };

                const result = await executeWithFallback({
                  candidates: [
                    { providerId: 'corpse-node', modelId: 'zai/glm-5.3-flash', savingsPct: 98, blendedPrice: 0.04 },
                    { providerId: 'healthy-node', modelId: 'zai/glm-5.3', savingsPct: 95, blendedPrice: 0.06 }
                  ],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn
                });

                let received = '';
                const reader = result.stream.getReader();
                const dec = new TextDecoder();
                for (;;) { const { done, value } = await reader.read(); if (done) break; received += dec.decode(value, { stream: true }); }

                console.log(JSON.stringify({
                  success: result.success,
                  attempts: result.attempts,
                  servedBy: result.selectedCandidate?.providerId,
                  hasHello: received.includes('\"content\":\"He\"') && received.includes('\"content\":\"llo\"'),
                  hasReasoning: received.includes('half-thought'),
                  hasDone: received.includes('[DONE]'),
                  firstError: result.failoverErrors?.[0]?.error,
                  splices: metricsStore.usage.streamSplices || 0,
                  preContentFailures: metricsStore.usage.preContentFailures || 0
                }));")]
      (is (:success res) "splice landed — selection succeeds")
      (is (= 2 (:attempts res)) "the corpse must not terminate the candidate loop")
      (is (= "healthy-node" (:servedBy res)) "the second candidate owns the answer")
      (is (:hasHello res) "the client receives real content, never the empty corpse")
      (is (:hasReasoning res) "corpse reasoning bytes already relayed stay (additive)")
      (is (:hasDone res) "exactly one [DONE] — the healthy one — terminates the stream")
      (is (= "empty_completion" (:firstError res)) "the corpse is labeled by its class")
      (is (= 1 (:splices res)) "the splice is counted"))))

(deftest test-all-empty-done-exhausts-in-band
  (testing "Every candidate serving an empty [DONE] ends in a clean in-band error, not an empty corpse"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const outcomes = [];
                const enc = new TextEncoder();
                const sseFrame = (delta) => 'data: ' + JSON.stringify({ choices: [{ delta }] }) + '\\n\\n';

                const corpse = () => new Response(new ReadableStream({
                  start(c) {
                    c.enqueue(enc.encode(sseFrame({ content: '', role: 'assistant' })));
                    c.enqueue(enc.encode(sseFrame({ reasoning_content: 'half-thought' })));
                    c.enqueue(enc.encode('data: [DONE]\\n\\n'));
                    c.close();
                  }
                }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });

                const fetchFn = async () => corpse();

                const result = await executeWithFallback({
                  candidates: [
                    { providerId: 'corpse-a', modelId: 'zai/glm-5.3-flash', savingsPct: 98, blendedPrice: 0.04 },
                    { providerId: 'corpse-b', modelId: 'zai/glm-5.3-flash', savingsPct: 98, blendedPrice: 0.04 }
                  ],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn,
                  onStreamOutcome: (o) => outcomes.push(o)
                });

                let received = '';
                const reader = result.stream.getReader();
                const dec = new TextDecoder();
                try { for (;;) { const { done, value } = await reader.read(); if (done) break; received += dec.decode(value, { stream: true }); } } catch (e) {}
                await new Promise((r) => setTimeout(r, 30));

                console.log(JSON.stringify({
                  success: result.success,
                  attempts: result.attempts,
                  hasCleanError: received.includes('all candidates exhausted before content'),
                  hasDone: received.includes('[DONE]'),
                  errors: (result.failoverErrors || []).map((e) => e.error),
                  firstOutcome: outcomes[0] ? { ok: outcomes[0].ok, error: outcomes[0].error } : null
                }));")]
      (is (:success res) "committed at first byte")
      (is (= 2 (:attempts res)) "BOTH corpses are tried — an empty [DONE] never ends the loop early")
      (is (:hasCleanError res) "client gets a clean in-band provider error, never an empty corpse")
      (is (:hasDone res) "and a proper [DONE] terminator")
      (is (= ["empty_completion" "empty_completion"] (:errors res)))
      (is (= "all_candidates_exhausted_pre_content" (get-in res [:firstOutcome :error]))
          "exhaustion lands in the ledger"))))

(deftest test-hard-death-pre-content-splices
  (testing "Connection reset mid-reasoning (pre-content) with a second candidate available splices"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const enc = new TextEncoder();
                const sseFrame = (delta) => 'data: ' + JSON.stringify({ choices: [{ delta }] }) + '\\n\\n';

                let call = 0;
                const fetchFn = async () => {
                  call++;
                  if (call === 1) {
                    // Hard death: reasoning streams, then the connection resets mid-flight
                    return new Response(new ReadableStream({
                      start(c) {
                        c.enqueue(enc.encode(sseFrame({ content: '', role: 'assistant', reasoning_content: '' })));
                        c.enqueue(enc.encode(sseFrame({ reasoning_content: 'torture-reasoning' })));
                        setTimeout(() => c.error(new Error('upstream connection reset')), 25);
                      }
                    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
                  }
                  return new Response(new ReadableStream({
                    start(c) {
                      c.enqueue(enc.encode(sseFrame({ content: 'He' })));
                      c.enqueue(enc.encode(sseFrame({ content: 'llo' })));
                      c.enqueue(enc.encode('data: [DONE]\\n\\n'));
                      c.close();
                    }
                  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
                };

                const result = await executeWithFallback({
                  candidates: [
                    { providerId: 'dying-node', modelId: 'zai/glm-5.3-flash', savingsPct: 98, blendedPrice: 0.04 },
                    { providerId: 'healthy-node', modelId: 'zai/glm-5.3', savingsPct: 95, blendedPrice: 0.06 }
                  ],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn
                });

                let received = '';
                const reader = result.stream.getReader();
                const dec = new TextDecoder();
                try { for (;;) { const { done, value } = await reader.read(); if (done) break; received += dec.decode(value, { stream: true }); } } catch (e) {}

                console.log(JSON.stringify({
                  success: result.success,
                  attempts: result.attempts,
                  servedBy: result.selectedCandidate?.providerId,
                  hasHello: received.includes('\"content\":\"He\"') && received.includes('\"content\":\"llo\"'),
                  hasDone: received.includes('[DONE]'),
                  splices: metricsStore.usage.streamSplices || 0
                }));")]
      (is (:success res) "splice landed")
      (is (= 2 (:attempts res)) "the reset is a candidate failure, not a stream end")
      (is (= "healthy-node" (:servedBy res)))
      (is (:hasHello res) "client receives the completed answer")
      (is (:hasDone res))
      (is (= 1 (:splices res))))))
