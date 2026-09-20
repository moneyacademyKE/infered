(ns client-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-client-fallback-and-metrics
  (testing "Client executes fallback when primary node fails and records metrics"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore, getProviderStats } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();

                // Mock candidates: primary will fail (503), secondary will succeed
                const candidates = [
                  {
                    providerId: 'bad-node',
                    modelId: 'deepseek-r1',
                    savingsPct: 80,
                    blendedPrice: 0.20
                  },
                  {
                    providerId: 'good-node',
                    modelId: 'deepseek-r1',
                    savingsPct: 65,
                    blendedPrice: 0.35
                  }
                ];

                // Mock fetch function
                const mockFetch = async (url, opts) => {
                  const body = JSON.parse(opts.body);
                  const prov = opts.headers['X-InferHub-Provider'];
                  if (prov === 'bad-node') {
                    return new Response(JSON.stringify({ error: { message: 'Capacity exceeded' } }), {
                      status: 503,
                      headers: { 'Content-Type': 'application/json' }
                    });
                  }
                  return new Response(JSON.stringify({
                    id: 'chatcmpl-123',
                    object: 'chat.completion',
                    created: Date.now(),
                    model: 'deepseek-r1',
                    choices: [{ message: { role: 'assistant', content: 'Hello from InferHub fallback!' }, finish_reason: 'stop' }]
                  }), {
                    status: 200,
                    headers: { 'Content-Type': 'application/json' }
                  });
                };

                const result = await executeWithFallback({
                  candidates,
                  requestBody: { model: 'infered/reasoning', messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn: mockFetch,
                  baseUrl: 'https://api.inferhub.net/v1'
                });

                const badStats = getProviderStats(metricsStore, 'bad-node', 'deepseek-r1');
                const goodStats = getProviderStats(metricsStore, 'good-node', 'deepseek-r1');

                console.log(JSON.stringify({
                  success: result.success,
                  selectedProvider: result.selectedCandidate.providerId,
                  badFailedRequests: badStats.failedRequests,
                  goodSuccessRequests: goodStats.successfulRequests,
                  responseContent: result.responseBody.choices[0].message.content
                }));")]
      (is (:success res))
      (is (= "good-node" (:selectedProvider res)))
      (is (= 1 (:badFailedRequests res)))
      (is (= 1 (:goodSuccessRequests res)))
      (is (= "Hello from InferHub fallback!" (:responseContent res))))))

(deftest test-client-abort-propagation
  (testing "Client disconnect aborts upstream work and skips remaining candidates"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const ac = new AbortController();

                let attempts = 0;
                // Hangs forever until its abort signal fires (simulates a slow upstream)
                const hangingFetch = (url, opts) => new Promise((resolve, reject) => {
                  attempts++;
                  opts.signal.addEventListener('abort', () => {
                    const e = new Error('The operation was aborted');
                    e.name = 'AbortError';
                    reject(e);
                  });
                });

                setTimeout(() => ac.abort(), 50);

                const result = await executeWithFallback({
                  candidates: [
                    { providerId: 'slow-node-1', modelId: 'm1', savingsPct: 10, blendedPrice: 0.1 },
                    { providerId: 'slow-node-2', modelId: 'm2', savingsPct: 20, blendedPrice: 0.1 }
                  ],
                  requestBody: { messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn: hangingFetch,
                  requestSignal: ac.signal,
                  timeoutMs: 5000
                });

                console.log(JSON.stringify({
                  success: result.success,
                  status: result.status,
                  error: result.error,
                  attempts: result.attempts,
                  upstreamStarted: attempts
                }));")]
      (is (false? (:success res)))
      (is (= 499 (:status res)))
      (is (= "Client disconnected before completion." (:error res)))
      (is (= 1 (:upstreamStarted res)) "second candidate must be skipped after client abort"))))

(deftest test-client-retry-budget-cap
  (testing "Executor stops at maxAttempts even when the order book offers more candidates"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();

                let upstreamStarted = 0;
                // Every node fails — simulates a brownout with a long candidate list
                const failingFetch = async () => {
                  upstreamStarted++;
                  return new Response(JSON.stringify({ error: { message: 'upstream down' } }), {
                    status: 503,
                    headers: { 'Content-Type': 'application/json' }
                  });
                };

                // Order book offers 8 nodes; budget only pays for 3
                const candidates = Array.from({ length: 8 }, (_, i) => ({
                  providerId: `node-${i}`, modelId: 'm1', savingsPct: 10, blendedPrice: 0.1
                }));

                const result = await executeWithFallback({
                  candidates,
                  requestBody: { messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn: failingFetch,
                  maxAttempts: 3
                });

                console.log(JSON.stringify({
                  success: result.success,
                  status: result.status,
                  error: result.error,
                  attempts: result.attempts,
                  upstreamStarted,
                  failoverCount: result.failoverErrors.length
                }));")]
      (is (re-find #"Retry budget exhausted after 3" (:error res)) "error must say the retry budget was spent, not that ALL nodes failed"))))

(deftest test-chain-fallback-candidate-sync
  (testing "executeWithChainFallback returns fallback chain candidates when primary chain fails"
    (let [res (run-node-eval
               "import { executeWithChainFallback } from './src/router/client.js';

                const primaryCandidates = [{ providerId: 'p-prim', modelId: 'cx/gpt-6-astra' }];
                const fallbackCandidates = [{ providerId: 'p-fb', modelId: 'ali/glm-5.3' }];

                const rank = (m) => (m === 'infered/astra-budget' ? primaryCandidates : fallbackCandidates);
                const execute = async (cands) => {
                  if (cands[0].providerId === 'p-prim') {
                    return { success: false, attempts: 1, error: 'no capacity' };
                  }
                  return { success: true, attempts: 1, selectedCandidate: cands[0] };
                };

                const { result, candidates, fallbackChain } = await executeWithChainFallback({
                  requestedModel: 'infered/astra-budget',
                  rank,
                  execute
                });

                console.log(JSON.stringify({
                  success: result.success,
                  fallbackChain,
                  returnedCandidateModel: candidates[0]?.modelId,
                  selectedCandidateModel: result.selectedCandidate?.modelId
                }));")]
      (is (:success res))
      (is (= "infered/glm-budget" (:fallbackChain res)))
      (is (= "ali/glm-5.3" (:returnedCandidateModel res))
          "candidates returned must be updated to fallback chain's candidates")
      (is (= "ali/glm-5.3" (:selectedCandidateModel res))))))

(deftest test-official-provider-omits-header
  (testing "X-InferHub-Provider header is omitted when candidate providerId is official"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                let receivedHeaders = null;

                const mockFetch = async (url, opts) => {
                  receivedHeaders = opts.headers;
                  return new Response(JSON.stringify({
                    id: 'chatcmpl-official',
                    object: 'chat.completion',
                    created: Date.now(),
                    model: 'cx/gpt-5.6-terra',
                    choices: [{ message: { role: 'assistant', content: 'ok' }, finish_reason: 'stop' }]
                  }), { status: 200, headers: { 'Content-Type': 'application/json' } });
                };

                const candidate = {
                  providerId: 'official',
                  modelId: 'cx/gpt-5.6-terra',
                  blendedPrice: 1.0,
                  savingsPct: 0
                };

                await executeWithFallback({
                  candidates: [candidate],
                  requestBody: { messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn: mockFetch
                });

                console.log(JSON.stringify({
                  hasProviderHeader: Boolean(receivedHeaders['X-InferHub-Provider'])
                }));")]
      (is (false? (:hasProviderHeader res))
          "X-InferHub-Provider must NOT be sent when providerId is 'official'"))))

(deftest test-stream-failover-before-first-content
  (testing "Upstream stream that ends before any content token fails over to the next candidate"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore, getProviderStats } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();

                // Reasoning model that streams role + reasoning frames then dies (no content, no DONE)
                const deadStreamFetch = async (url, opts) => {
                  if (opts.headers['X-InferHub-Provider'] === 'reasoning-node') {
                    return new Response(new ReadableStream({
                      start(c) {
                        const enc = new TextEncoder();
                        c.enqueue(enc.encode('data: ' + JSON.stringify({ choices: [{ delta: { content: '', role: 'assistant', reasoning_content: '' } }] }) + '\\n\\n'));
                        c.enqueue(enc.encode('data: ' + JSON.stringify({ choices: [{ delta: { reasoning_content: 'thinking...' } }] }) + '\\n\\n'));
                        c.close(); // EOF before any content — the dropped-stream disease
                      }
                    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
                  }
                  return new Response(JSON.stringify({
                    choices: [{ message: { role: 'assistant', content: 'served by flash' }, finish_reason: 'stop' }]
                  }), { status: 200, headers: { 'Content-Type': 'application/json' } });
                };

                const result = await executeWithFallback({
                  candidates: [
                    { providerId: 'reasoning-node', modelId: 'ali/glm-5.3', savingsPct: 97, blendedPrice: 0.05 },
                    { providerId: 'flash-node', modelId: 'zai/glm-5.3-flash', savingsPct: 99, blendedPrice: 0.01 }
                  ],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn: deadStreamFetch
                });

                console.log(JSON.stringify({
                  success: result.success,
                  selectedProvider: result.selectedCandidate?.providerId,
                  attempts: result.attempts,
                  firstError: result.failoverErrors?.[0]?.error,
                  reasoningNodeFailed: getProviderStats(metricsStore, 'reasoning-node', 'ali/glm-5.3').failedRequests
                }));")]
      (is (:success res))
      (is (= "flash-node" (:selectedProvider res))
          "stream that dies before first content must fail over to next candidate")
      (is (= 2 (:attempts res)))
      (is (re-find #"before producing any content|stream ended" (str (:firstError res))))
      (is (= 1 (:reasoningNodeFailed res)) "the dead stream must be recorded as a candidate failure"))))

(deftest test-stream-prefix-replay-preserves-frames
  (testing "Buffered prefix (role + reasoning frames) is replayed before remaining stream"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';

                const enc = new TextEncoder();
                const sseFrame = (delta) => 'data: ' + JSON.stringify({ choices: [{ delta }] }) + '\\n\\n';

                const fetchFn = async () => new Response(new ReadableStream({
                  start(c) {
                    c.enqueue(enc.encode(sseFrame({ content: '', role: 'assistant', reasoning_content: '' })));
                    c.enqueue(enc.encode(sseFrame({ reasoning_content: ' pondering' })));
                    c.enqueue(enc.encode(sseFrame({ content: 'He' })));
                    c.enqueue(enc.encode(sseFrame({ content: 'llo' })));
                    c.enqueue(enc.encode('data: [DONE]\\n\\n'));
                    c.close();
                  }
                }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });

                const result = await executeWithFallback({
                  candidates: [{ providerId: 'n1', modelId: 'ali/glm-5.3', savingsPct: 97, blendedPrice: 0.05 }],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore: (await import('./src/router/metrics.js')).createMetricsStore(),
                  fetchFn
                });

                let received = '';
                const reader = result.stream.getReader();
                const dec = new TextDecoder();
                for (;;) {
                  const { done, value } = await reader.read();
                  if (done) break;
                  received += dec.decode(value, { stream: true });
                }

                console.log(JSON.stringify({
                  success: result.success,
                  hasRoleFrame: received.includes('\"role\":\"assistant\"'),
                  hasReasoning: received.includes('pondering'),
                  hasHello: received.includes('\"content\":\"He\"') && received.includes('\"content\":\"llo\"'),
                  hasDone: received.includes('[DONE]'),
                  orderOk: received.indexOf('pondering') < received.indexOf('\"content\":\"He\"') && received.indexOf('\"content\":\"He\"') < received.indexOf('[DONE]')
                }));")]
      (is (:success res))
      (is (:hasRoleFrame res) "role frame must be replayed")
      (is (:hasReasoning res) "reasoning frames must be replayed")
      (is (:hasHello res) "content frames must appear exactly once (not duplicated between buffer and stream)")
      (is (:hasDone res) "[DONE] sentinel must pass through")
      (is (:orderOk res) "frame order must be preserved"))))

(deftest test-mid-stream-death-reports-outcome
  (testing "Stream dying after content is committed reports via onStreamOutcome, not silently"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const outcomes = [];
                const enc = new TextEncoder();
                const sseFrame = (delta) => 'data: ' + JSON.stringify({ choices: [{ delta }] }) + '\\n\\n';

                const fetchFn = async () => new Response(new ReadableStream({
                  start(c) {
                    c.enqueue(enc.encode(sseFrame({ content: '', role: 'assistant' })));
                    // Real streams have wall-clock gaps: content lands, THEN the
                    // connection dies (error() would otherwise discard the queue).
                    setTimeout(() => c.enqueue(enc.encode(sseFrame({ content: 'partial' }))), 15);
                    setTimeout(() => c.error(new Error('upstream connection reset')), 40);
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

                // Drain the stream so the pump runs to its error
                const reader = result.stream.getReader();
                try { for (;;) { const { done } = await reader.read(); if (done) break; } } catch (e) {}

                await new Promise((r) => setTimeout(r, 30));

                console.log(JSON.stringify({
                  success: result.success,
                  outcomeCount: outcomes.length,
                  firstOutcome: outcomes[0] ? { ok: outcomes[0].ok, error: outcomes[0].error } : null
                }));")]
      (is (:success res) "committed stream still reports success at selection time")
      (is (= 1 (:outcomeCount res)) "exactly one stream outcome reported")
      (is (= "upstream_stream_died" (get-in res [:firstOutcome :error]))
          "mid-stream upstream death must be visible, not swallowed"))))

(deftest test-client-abort-during-precontent-buffer
  (testing "Client disconnect while waiting for first content aborts cleanly with 499"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const ac = new AbortController();
                const enc = new TextEncoder();

                // Streams one reasoning frame then hangs until the abort tears it down
                const hangingStreamFetch = (url, opts) => new Promise((resolve) => {
                  setTimeout(() => {
                    resolve(new Response(new ReadableStream({
                      start(c) {
                        c.enqueue(enc.encode('data: ' + JSON.stringify({ choices: [{ delta: { reasoning_content: '...' } }] }) + '\\n\\n'));
                        opts.signal.addEventListener('abort', () => {
                          try { c.error(new Error('connection torn down by client abort')); } catch {}
                        });
                      }
                    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));
                  }, 10);
                });

                setTimeout(() => ac.abort(), 80);

                const result = await executeWithFallback({
                  candidates: [{ providerId: 'n1', modelId: 'm1', savingsPct: 10, blendedPrice: 0.1 }],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn: hangingStreamFetch,
                  requestSignal: ac.signal,
                  firstContentTimeoutMs: 5000
                });

                console.log(JSON.stringify({
                  success: result.success,
                  status: result.status,
                  error: result.error
                }));")]
      (is (false? (:success res)))
      (is (= 499 (:status res)))
      (is (re-find #"Client disconnected" (str (:error res)))))))

