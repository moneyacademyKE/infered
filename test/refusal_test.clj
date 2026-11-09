(ns refusal-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-streaming-403-refusal-cascades-to-next-model
  (testing "a 403-disabled model fails its OWN candidates, not the request — the next chain member must serve"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const enc = new TextEncoder();
                const sse = (delta) => 'data: ' + JSON.stringify({ choices: [{ delta }] }) + '\\n\\n';

                const fetchFn = async (url, opts) => {
                  const prov = opts.headers['X-InferHub-Provider'];
                  if (prov === 'refusing-node-a' || prov === 'refusing-node-b') {
                    // Exact live shape 2026-11-09: account-preference 403 model_disabled.
                    return new Response(JSON.stringify({
                      error: { message: 'model disabled in your preferences', type: 'model_disabled', source: 'inferhub' }
                    }), { status: 403, headers: { 'Content-Type': 'application/json' } });
                  }
                  return new Response(new ReadableStream({
                    async start(c) {
                      c.enqueue(enc.encode(sse({ role: 'assistant', content: '' })));
                      await new Promise(r => setTimeout(r, 10));
                      c.enqueue(enc.encode(sse({ content: 'cascade survivor' })));
                      c.enqueue(enc.encode('data: [DONE]\\n\\n'));
                      c.close();
                    }
                  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
                };

                const result = await executeWithFallback({
                  candidates: [
                    { providerId: 'refusing-node-a', modelId: 'dead/terra', savingsPct: 95, blendedPrice: 0.01 },
                    { providerId: 'refusing-node-b', modelId: 'dead/terra', savingsPct: 94, blendedPrice: 0.02 },
                    { providerId: 'live-node', modelId: 'live/glm', savingsPct: 90, blendedPrice: 0.04 }
                  ],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn,
                  firstByteTimeoutMs: 5000
                });

                const reader = result.success ? result.stream.getReader() : null;
                const dec = new TextDecoder();
                let received = '';
                try { if (reader) { for (;;) { const { done, value } = await reader.read(); if (done) break; received += dec.decode(value, { stream: true }); } } } catch (e) {}
                await new Promise(r => setTimeout(r, 30));

                console.log(JSON.stringify({
                  success: result.success,
                  status: result.status || 200,
                  error: result.error || null,
                  servedBy: result.selectedCandidate?.modelId,
                  hasSurvivor: received.includes('cascade survivor'),
                  hasDone: received.includes('[DONE]')
                }));")]
      (is (:success res) "the live chain member must serve when the head model 403s")
      (is (= "live/glm" (:servedBy res)) "served by the third candidate, not the refusing head")
      (is (:hasSurvivor res) "client receives the completed answer")
      (is (:hasDone res)))))

(deftest test-nonstreaming-403-refusal-skips-same-model
  (testing "non-streaming: a 4xx-refusing model is poisoned once — its remaining nodes are not retried"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                let fetches = 0;

                const fetchFn = async (url, opts) => {
                  fetches++;
                  const prov = opts.headers['X-InferHub-Provider'];
                  if (prov === 'ns-a1' || prov === 'ns-a2') {
                    return new Response(JSON.stringify({
                      error: { message: 'model disabled in your preferences', type: 'model_disabled' }
                    }), { status: 403, headers: { 'Content-Type': 'application/json' } });
                  }
                  return new Response(JSON.stringify({
                    choices: [{ message: { role: 'assistant', content: 'OK' } }],
                    usage: { prompt_tokens: 5, completion_tokens: 2 }
                  }), { status: 200, headers: { 'Content-Type': 'application/json' } });
                };

                const result = await executeWithFallback({
                  candidates: [
                    { providerId: 'ns-a1', modelId: 'dead/flash', savingsPct: 95, blendedPrice: 0.01 },
                    { providerId: 'ns-a2', modelId: 'dead/flash', savingsPct: 94, blendedPrice: 0.02 },
                    { providerId: 'ns-live', modelId: 'live/glm', savingsPct: 90, blendedPrice: 0.04 }
                  ],
                  requestBody: { stream: false, messages: [{ role: 'user', content: 'Say OK' }] },
                  apiKey: 'test-key',
                  metricsStore,
                  fetchFn
                });

                console.log(JSON.stringify({
                  success: result.success,
                  servedBy: result.selectedCandidate?.modelId,
                  attempts: result.attempts,
                  fetches
                }));")]
      (is (:success res) "the live model serves")
      (is (= "live/glm" (:servedBy res)))
      (is (= 2 (:fetches res)) "node ns-a2 must be SKIPPED: one 403 poisons the whole model — 3 fetches means it wasn't")
      (is (= 2 (:attempts res)) "1 refused attempt + 1 successful serve"))))