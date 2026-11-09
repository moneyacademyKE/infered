(ns fallback-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-chain-fallback-both-fail-merges-attempts
  (testing "when the entry chain AND its fallback both fail, telemetry reports the whole story"
    (let [res (run-node-eval
               "import { executeWithChainFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();

                const ranked = {
                  'infered/astra-budget': [
                    { providerId: 'a1', modelId: 'm-a', blendedPrice: 0.1, savingsPct: 90 },
                    { providerId: 'a2', modelId: 'm-a', blendedPrice: 0.11, savingsPct: 89 }
                  ],
                  'infered/glm-budget': [
                    { providerId: 'g1', modelId: 'm-g', blendedPrice: 0.12, savingsPct: 88 },
                    { providerId: 'g2', modelId: 'm-g', blendedPrice: 0.13, savingsPct: 87 }
                  ]
                };

                const execute = async (cands) => {
                  return {
                    success: false,
                    status: 503,
                    error: 'All candidate nodes failed or capacity exhausted.',
                    attempts: cands.length,
                    failoverErrors: cands.map(c => ({ providerId: c.providerId, modelId: c.modelId, status: 503, error: 'upstream dead' }))
                  };
                };

                const { result, candidates, fallbackChain } = await executeWithChainFallback({
                  requestedModel: 'infered/astra-budget',
                  rank: (m) => ranked[m] || [],
                  execute
                });

                console.log(JSON.stringify({
                  success: result.success,
                  fallbackChain,
                  fallbackTried: result.fallbackTried,
                  finalCandidates: candidates.length,
                  totalAttempts: result.attempts,
                  failoverErrorCount: (result.failoverErrors || []).length,
                  errorMentionsBoth: String(result.error).includes('exhausted') || (result.failoverErrors || []).some(e => String(e.error).includes('upstream dead'))
                }));")]
      (is (false? (:success res)))
      (is (nil? (:fallbackChain res)) "fallbackChain marks SERVED-by-fallback; both-fail keeps it null")
      (is (= "infered/glm-budget" (:fallbackTried res)) "the declared fallback chain ran and is recorded")
      (is (= 4 (:totalAttempts res)) "attempts must MERGE across both passes: 2 + 2, not replace with 2")
      (is (= 4 (:failoverErrorCount res)) "failover errors from both passes survive the second failure")
      (is (:errorMentionsBoth res) "the error story includes the primary pass's failures")
      (is (true? (>= (get res :finalCandidates) 2)) "candidates reflect the last pass tried"))))

(deftest test-chain-fallback-success-sums-attempts
  (testing "fallback success still sums attempts across passes (regression guard for the merge)"
    (let [res (run-node-eval
               "import { executeWithChainFallback } from './src/router/client.js';

                const ranked = {
                  'infered/astra-budget': [
                    { providerId: 'a1', modelId: 'm-a', blendedPrice: 0.1, savingsPct: 90 }
                  ],
                  'infered/glm-budget': [
                    { providerId: 'g1', modelId: 'm-g', blendedPrice: 0.12, savingsPct: 88 }
                  ]
                };

                let call = 0;
                const execute = async (cands) => {
                  call++;
                  if (call === 1) {
                    return { success: false, status: 503, error: 'primary dead', attempts: 1, failoverErrors: [{ providerId: 'a1', modelId: 'm-a', status: 503, error: 'primary dead' }] };
                  }
                  return { success: true, status: 200, responseBody: { ok: true }, attempts: 1, failoverErrors: [], latencyMs: 5, ttftMs: 5 };
                };

                const { result, fallbackChain, candidates } = await executeWithChainFallback({
                  requestedModel: 'infered/astra-budget',
                  rank: (m) => ranked[m] || [],
                  execute
                });

                console.log(JSON.stringify({
                  success: result.success,
                  fallbackChain,
                  attempts: result.attempts,
                  servedCount: candidates.length
                }));")]
      (is (:success res) "fallback success serves the request")
      (is (= "infered/glm-budget" (:fallbackChain res)))
      (is (= 2 (:attempts res)) "success path still sums attempts: 1 + 1"))))
