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
