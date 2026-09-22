(ns chase-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-non-retryable-open-short-circuits-the-chase
  (testing "A 4xx open (401/403/404) fails identically on every node: one attempt, not a silent ten-node chase"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                // Every open returns the account-level disable InferHub serves
                // for ali/qwen3.8-max (reproduced live 2026-09-22: 60s+ of
                // zero-byte silence while the router chased ~10 dead nodes).
                const fetchFn = async () => new Response(
                  JSON.stringify({ error: { message: 'model disabled in your preferences', type: 'model_disabled' } }),
                  { status: 403, headers: { 'Content-Type': 'application/json' } });

                const result = await executeWithFallback({
                  candidates: [
                    { providerId: 'a', modelId: 'ma', savingsPct: 10, blendedPrice: 0.1 },
                    { providerId: 'b', modelId: 'mb', savingsPct: 10, blendedPrice: 0.1 },
                    { providerId: 'c', modelId: 'mc', savingsPct: 10, blendedPrice: 0.1 }
                  ],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'k', metricsStore, fetchFn
                });

                console.log(JSON.stringify({
                  success: result.success,
                  status: result.status,
                  attempts: result.attempts,
                  refusedFast: (result.error || '').includes('Non-retryable upstream rejection'),
                  firstErrorHas403: (result.failoverErrors && result.failoverErrors[0] ? String(result.failoverErrors[0].error) : '').includes('403')
                }));")]
      (is (false? (:success res)) "the request fails — the model is disabled upstream")
      (is (= 1 (:attempts res))
          "a 4xx open must stop the chase after ONE attempt: every node of a disabled/auth-failing model refuses identically")
      (is (:refusedFast res) "the client gets an explicit non-retryable rejection instead of a hang")
      (is (:firstErrorHas403 res) "the underlying 403 stays visible in failover errors for the ledger"))))

(deftest test-pre-commit-budget-bounds-the-silent-chase
  (testing "Before any byte reaches the client, the candidate chase is wall-clock bounded"
    (let [res (run-node-eval
               "import { executeWithFallback } from './src/router/client.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                // Slow, RETRYABLE open failures (429-class): each open burns
                // 150ms before failing. With a 250ms pre-commit budget the
                // chase must stop before burning all three candidates.
                const fetchFn = async () => {
                  await new Promise(r => setTimeout(r, 150));
                  return new Response('rate limited', { status: 429 });
                };

                const result = await executeWithFallback({
                  candidates: [
                    { providerId: 'a', modelId: 'ma', savingsPct: 10, blendedPrice: 0.1 },
                    { providerId: 'b', modelId: 'mb', savingsPct: 10, blendedPrice: 0.1 },
                    { providerId: 'c', modelId: 'mc', savingsPct: 10, blendedPrice: 0.1 }
                  ],
                  requestBody: { stream: true, messages: [{ role: 'user', content: 'hi' }] },
                  apiKey: 'k', metricsStore, fetchFn,
                  preCommitBudgetMs: 250
                });

                console.log(JSON.stringify({
                  success: result.success,
                  attempts: result.attempts,
                  budgetCut: (result.error || '').includes('Pre-commit budget')
                }));")]
      (is (false? (:success res)))
      (is (< 0 (:attempts res) 3)
          "the budget cuts the silent chase before every candidate burns 150ms of client-facing nothing")
      (is (:budgetCut res) "the failure names the budget so the ledger can distinguish it from plain exhaustion"))))
