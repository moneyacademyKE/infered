(ns pareto-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-pareto-ranking-and-policies
  (testing "Ranks candidates correctly under different routing weights"
    (let [res (run-node-eval
               "import { rankCandidates } from './src/router/pareto.js';
                import { createPriceCache, updateSpotPrices } from './src/router/pricing.js';
                import { createMetricsStore, recordSample } from './src/router/metrics.js';

                const priceCache = createPriceCache([]);
                const metricsStore = createMetricsStore();

                updateSpotPrices(priceCache, [
                  { providerId: 'node-cheap', modelId: 'claude-3.5-sonnet', prompt: 0.90, completion: 4.50 },
                  { providerId: 'node-fast', modelId: 'claude-3.5-sonnet', prompt: 2.40, completion: 12.00 },
                  { providerId: 'node-broken', modelId: 'claude-3.5-sonnet', prompt: 0.50, completion: 2.00 }
                ]);

                recordSample(metricsStore, 'node-cheap', 'claude-3.5-sonnet', { latencyMs: 800, ttftMs: 400, success: true });
                recordSample(metricsStore, 'node-fast', 'claude-3.5-sonnet', { latencyMs: 120, ttftMs: 50, success: true });
                for (let i = 0; i < 5; i++) {
                  recordSample(metricsStore, 'node-broken', 'claude-3.5-sonnet', { latencyMs: 5000, ttftMs: 5000, success: false });
                }

                const cheapRanked = rankCandidates({
                  model: 'infered/claude-3.5-sonnet',
                  priceCache,
                  metricsStore,
                  weights: { price: 0.8, speed: 0.1, quality: 0.1 }
                });

                const fastRanked = rankCandidates({
                  model: 'infered/claude-3.5-sonnet',
                  priceCache,
                  metricsStore,
                  weights: { price: 0.1, speed: 0.8, quality: 0.1 }
                });

                console.log(JSON.stringify({
                  cheapWinner: cheapRanked[0].providerId,
                  fastWinner: fastRanked[0].providerId,
                  brokenExcludedOrLast: cheapRanked[cheapRanked.length - 1].providerId,
                  cheapWinnerSavings: cheapRanked[0].savingsPct
                }));")]
      (is (= "node-cheap" (:cheapWinner res)))
      (is (= "node-fast" (:fastWinner res)))
      (is (= "node-broken" (:brokenExcludedOrLast res)))
      (is (> (:cheapWinnerSavings res) 50)))))

(deftest test-sol-budget-cascade-policy
  (testing "Sol budget cascade routes to cx/gpt-5.6-sol first, then strictly cascades through glm-flash -> glm -> kimi -> terra only when <= $0.10"
    (let [res (run-node-eval
               "import { rankCandidates } from './src/router/pareto.js';
                import { createPriceCache, updateSpotPrices } from './src/router/pricing.js';
                import { createMetricsStore, recordSample } from './src/router/metrics.js';

                const priceCache = createPriceCache([]);
                const metricsStore = createMetricsStore();

                updateSpotPrices(priceCache, [
                  { providerId: 'sol-node-1', modelId: 'cx/gpt-5.6-sol', prompt: 2.00, completion: 8.00 },
                  { providerId: 'flash-node-1', modelId: 'zai/glm-5.3-flash', prompt: 0.05, completion: 0.09 },
                  { providerId: 'glm-node-1', modelId: 'zai/glm-5.3', prompt: 0.06, completion: 0.10 },
                  { providerId: 'kimi-node-1', modelId: 'ali/kimi-k3', prompt: 0.20, completion: 0.40 },
                  { providerId: 'terra-node-1', modelId: 'cx/gpt-5.6-terra', prompt: 0.05, completion: 0.09 }
                ]);

                const candidatesSolHealthy = rankCandidates({
                  model: 'infered/sol-budget',
                  priceCache,
                  metricsStore,
                  maxFallbackPrice: 0.10
                });

                for (let i = 0; i < 5; i++) {
                  recordSample(metricsStore, 'sol-node-1', 'cx/gpt-5.6-sol', { latencyMs: 5000, ttftMs: 5000, success: false });
                }
                const candidatesSolDown = rankCandidates({
                  model: 'infered/sol-budget',
                  priceCache,
                  metricsStore,
                  maxFallbackPrice: 0.10
                });

                console.log(JSON.stringify({
                  firstChoiceWhenSolHealthy: candidatesSolHealthy[0].modelId,
                  firstChoiceWhenSolDown: candidatesSolDown[0].modelId,
                  secondChoiceWhenSolDown: candidatesSolDown[1].modelId,
                  thirdChoiceWhenSolDown: candidatesSolDown[2].modelId,
                  isKimiExcludedDueToPrice: !candidatesSolDown.some(c => c.modelId === 'ali/kimi-k3'),
                  totalCandidatesDown: candidatesSolDown.length
                }));")]
      (is (= "cx/gpt-5.6-sol" (:firstChoiceWhenSolHealthy res)))
      (is (= "zai/glm-5.3-flash" (:firstChoiceWhenSolDown res)))
      (is (= "zai/glm-5.3" (:secondChoiceWhenSolDown res)))
      (is (= "cx/gpt-5.6-terra" (:thirdChoiceWhenSolDown res)))
      (is (:isKimiExcludedDueToPrice res)))))

(deftest test-elastic-budget-escalation-ladder
  (testing "Escalates budget ceiling from $0.10 -> $0.20 -> $0.30 -> zero-downtime fallback when output prices rise"
    (let [res (run-node-eval
               "import { rankCandidates } from './src/router/pareto.js';
                import { createPriceCache, updateSpotPrices } from './src/router/pricing.js';
                import { createMetricsStore, recordSample } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                for (let i = 0; i < 5; i++) {
                  recordSample(metricsStore, 'sol-node', 'cx/gpt-5.6-sol', { latencyMs: 5000, ttftMs: 5000, success: false });
                }

                // Case 1: Models available at <= $0.10
                const cache1 = createPriceCache([]);
                updateSpotPrices(cache1, [
                  { providerId: 'sol-node', modelId: 'cx/gpt-5.6-sol', prompt: 2.0, completion: 8.0 },
                  { providerId: 'p1', modelId: 'zai/glm-5.3-flash', prompt: 0.05, completion: 0.08 }
                ]);
                const res1 = rankCandidates({ model: 'infered/sol-budget', priceCache: cache1, metricsStore });

                // Case 2: Output prices surge to $0.15 (0 models <= $0.10, but available <= $0.20)
                const cache2 = createPriceCache([]);
                updateSpotPrices(cache2, [
                  { providerId: 'sol-node', modelId: 'cx/gpt-5.6-sol', prompt: 2.0, completion: 8.0 },
                  { providerId: 'p2', modelId: 'zai/glm-5.3-flash', prompt: 0.10, completion: 0.15 }
                ]);
                const res2 = rankCandidates({ model: 'infered/sol-budget', priceCache: cache2, metricsStore });

                // Case 3: Output prices surge to $0.25 (0 models <= $0.20, but available <= $0.30)
                const cache3 = createPriceCache([]);
                updateSpotPrices(cache3, [
                  { providerId: 'sol-node', modelId: 'cx/gpt-5.6-sol', prompt: 2.0, completion: 8.0 },
                  { providerId: 'p3', modelId: 'zai/glm-5.3-flash', prompt: 0.15, completion: 0.25 }
                ]);
                const res3 = rankCandidates({ model: 'infered/sol-budget', priceCache: cache3, metricsStore });

                // Case 4: Output prices surge to $0.45 (0 models <= $0.30 -> routes to cheapest healthy for zero downtime)
                const cache4 = createPriceCache([]);
                updateSpotPrices(cache4, [
                  { providerId: 'sol-node', modelId: 'cx/gpt-5.6-sol', prompt: 2.0, completion: 8.0 },
                  { providerId: 'p4', modelId: 'zai/glm-5.3-flash', prompt: 0.20, completion: 0.45 }
                ]);
                const res4 = rankCandidates({ model: 'infered/sol-budget', priceCache: cache4, metricsStore });

                console.log(JSON.stringify({
                  tier1: res1[0]?.budgetTier,
                  tier2: res2[0]?.budgetTier,
                  tier3: res3[0]?.budgetTier,
                  tier4: res4[0]?.budgetTier,
                  tier1Escalation: res1[0]?.escalationLevel,
                  tier2Escalation: res2[0]?.escalationLevel,
                  tier3Escalation: res3[0]?.escalationLevel,
                  tier4ZeroDowntimeSuccess: res4.length > 0
                }));")]
      (is (== 0.10 (:tier1 res)))
      (is (== 0.20 (:tier2 res)))
      (is (== 0.30 (:tier3 res)))
      (is (= 0 (:tier1Escalation res)))
      (is (= 1 (:tier2Escalation res)))
      (is (= 2 (:tier3Escalation res)))
      (is (:tier4ZeroDowntimeSuccess res)))))
