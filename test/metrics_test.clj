(ns metrics-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-metrics-ema-and-circuit-breaker
  (testing "EMA latency calculations update correctly"
    (let [res (run-node-eval
               "import { createMetricsStore, recordSample, getProviderStats, isCircuitOpen } from './src/router/metrics.js';
                const store = createMetricsStore();
                
                recordSample(store, 'provider-a', 'claude-3.5-sonnet', { latencyMs: 200, ttftMs: 80, success: true });
                const stats1 = getProviderStats(store, 'provider-a', 'claude-3.5-sonnet');
                
                recordSample(store, 'provider-a', 'claude-3.5-sonnet', { latencyMs: 100, ttftMs: 40, success: true });
                const stats2 = getProviderStats(store, 'provider-a', 'claude-3.5-sonnet');
                
                for (let i = 0; i < 5; i++) {
                  recordSample(store, 'provider-b', 'gpt-4o', { latencyMs: 5000, ttftMs: 5000, success: false, error: '503 Service Unavailable' });
                }
                const isCircuitOpenB = isCircuitOpen(store, 'provider-b', 'gpt-4o');
                const isCircuitOpenA = isCircuitOpen(store, 'provider-a', 'claude-3.5-sonnet');

                console.log(JSON.stringify({
                  initialLatency: stats1.emaLatency,
                  updatedLatency: stats2.emaLatency,
                  stats1Samples: stats1.totalRequests,
                  stats2Samples: stats2.totalRequests,
                  circuitOpenB: isCircuitOpenB,
                  circuitOpenA: isCircuitOpenA
                }));")]
      (is (== 200 (:initialLatency res)))
      (is (< (:updatedLatency res) 200))
      (is (= 2 (:stats2Samples res)))
      (is (:circuitOpenB res))
      (is (false? (:circuitOpenA res))))))

(deftest test-usage-monitoring-and-switch-ledger
  (testing "Records token usage, dollar savings, and model switch events"
    (let [res (run-node-eval
               "import { createMetricsStore, recordUsage, getUsageSummary } from './src/router/metrics.js';
                const store = createMetricsStore();

                // 1. Initial request on budget model
                recordUsage(store, {
                  modelId: 'zai/glm-5.3-flash',
                  providerId: 'node-1',
                  promptTokens: 100,
                  completionTokens: 200,
                  costUsd: 0.00002,
                  officialCostUsd: 0.00015,
                  reason: 'budget-mode'
                });

                // 2. Stronger model drops in price -> Upgrade switch
                recordUsage(store, {
                  modelId: 'cx/gpt-5.6-sol',
                  providerId: 'node-1',
                  promptTokens: 100,
                  completionTokens: 200,
                  costUsd: 0.00008,
                  officialCostUsd: 0.00350,
                  reason: 'price-drop-upgrade'
                });

                // 3. Stronger model price rises -> Downgrade switch back
                recordUsage(store, {
                  modelId: 'zai/glm-5.3-flash',
                  providerId: 'node-1',
                  promptTokens: 100,
                  completionTokens: 200,
                  costUsd: 0.00002,
                  officialCostUsd: 0.00015,
                  reason: 'price-surge-downgrade'
                });

                const summary = getUsageSummary(store);

                console.log(JSON.stringify({
                  totalRequests: summary.totalRequests,
                  totalTokens: summary.totalTokens,
                  totalCostUsd: summary.totalCostUsd,
                  totalSavingsUsd: summary.totalSavingsUsd,
                  switchCount: summary.switchEvents.length,
                  lastSwitch: summary.switchEvents[0]
                }));")]
      (is (= 3 (:totalRequests res)))
      (is (= 900 (:totalTokens res)))
      (is (> (:totalSavingsUsd res) 0.003))
      (is (= 2 (:switchCount res)))
      (is (= "zai/glm-5.3-flash" (get-in res [:lastSwitch :toModel]))))))
