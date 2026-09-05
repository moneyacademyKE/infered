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
  (testing "Sol budget cascade routes to cx/gpt-5.6-sol when <= $0.10, but switches down to glm-5.3-flash when Sol is > $0.10"
    (let [res (run-node-eval
               "import { rankCandidates } from './src/router/pareto.js';
                import { createPriceCache, updateSpotPrices } from './src/router/pricing.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const priceCacheCheapSol = createPriceCache([]);
                const metricsStore = createMetricsStore();

                // Sol is cheap: $0.08 <= $0.10
                updateSpotPrices(priceCacheCheapSol, [
                  { providerId: 'sol-node-1', modelId: 'cx/gpt-5.6-sol', prompt: 0.02, completion: 0.08 },
                  { providerId: 'flash-node-1', modelId: 'zai/glm-5.3-flash', prompt: 0.01, completion: 0.04 }
                ]);
                const candidatesSolCheap = rankCandidates({
                  model: 'infered/sol-budget',
                  priceCache: priceCacheCheapSol,
                  metricsStore,
                  maxFallbackPrice: 0.10
                });

                // Sol is expensive: $13.50 > $0.10 -> must switch down to GLM-Flash
                const priceCacheExpensiveSol = createPriceCache([]);
                updateSpotPrices(priceCacheExpensiveSol, [
                  { providerId: 'sol-node-2', modelId: 'cx/gpt-5.6-sol', prompt: 2.25, completion: 13.50 },
                  { providerId: 'flash-node-2', modelId: 'zai/glm-5.3-flash', prompt: 0.01, completion: 0.04 },
                  { providerId: 'glm-node-2', modelId: 'zai/glm-5.3', prompt: 0.02, completion: 0.08 },
                  { providerId: 'kimi-node-2', modelId: 'ali/kimi-k3', prompt: 0.20, completion: 0.40 },
                  { providerId: 'terra-node-2', modelId: 'cx/gpt-5.6-terra', prompt: 0.01, completion: 0.05 }
                ]);
                const candidatesSolExpensive = rankCandidates({
                  model: 'infered/sol-budget',
                  priceCache: priceCacheExpensiveSol,
                  metricsStore,
                  maxFallbackPrice: 0.10
                });

                console.log(JSON.stringify({
                  firstChoiceWhenSolCheap: candidatesSolCheap[0]?.modelId,
                  firstChoiceWhenSolExpensive: candidatesSolExpensive[0]?.modelId,
                  secondChoiceWhenSolExpensive: candidatesSolExpensive[1]?.modelId,
                  thirdChoiceWhenSolExpensive: candidatesSolExpensive[2]?.modelId,
                  isSolExcludedWhenExpensive: !candidatesSolExpensive.some(c => c.modelId === 'cx/gpt-5.6-sol'),
                  isKimiExcludedDueToPrice: !candidatesSolExpensive.some(c => c.modelId === 'ali/kimi-k3')
                }));")]
      (is (= "cx/gpt-5.6-sol" (:firstChoiceWhenSolCheap res)))
      (is (= "zai/glm-5.3-flash" (:firstChoiceWhenSolExpensive res)))
      (is (= "zai/glm-5.3" (:secondChoiceWhenSolExpensive res)))
      (is (= "cx/gpt-5.6-terra" (:thirdChoiceWhenSolExpensive res)))
      (is (:isSolExcludedWhenExpensive res))
      (is (:isKimiExcludedDueToPrice res)))))

(deftest test-official-fallback-skipped-in-budget-cascade
  (testing "Models with no live spot asks are skipped (with a metric), never silently priced at official list in a budget cascade"
    (let [res (run-node-eval
               "import { rankCandidates } from './src/router/pareto.js';
                import { createPriceCache, updateSpotPrices } from './src/router/pricing.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                // Only glm-flash has live spot asks. Sol/terra/kimi/glm are absent from the order book,
                // so they fall back to official list prices (sol $30/M output) which CANNOT prove
                // budget compliance and must be skipped, not silently disqualified.
                const cache = createPriceCache([]);
                updateSpotPrices(cache, [
                  { providerId: 'flash-node-1', modelId: 'zai/glm-5.3-flash', prompt: 0.01, completion: 0.04 }
                ]);

                const candidates = rankCandidates({
                  model: 'infered/sol-budget',
                  priceCache: cache,
                  metricsStore,
                  maxFallbackPrice: 0.10
                });

                console.log(JSON.stringify({
                  topPick: candidates[0]?.modelId,
                  noOfficialPricedCandidates: candidates.every(c => c.quote.priceSource !== 'official'),
                  officialFallbackSkips: metricsStore.usage.officialFallbackSkips || 0
                }));")]
      (is (= "zai/glm-5.3-flash" (:topPick res)))
      (is (:noOfficialPricedCandidates res))
      (is (>= (:officialFallbackSkips res) 1)))))

(deftest test-nan-quotes-never-budget-eligible
  (testing "NaN/malformed asks are unpriceable and can never pass the budget ceiling (H3)"
    (let [res (run-node-eval
               "import { rankCandidates } from './src/router/pareto.js';
                import { createPriceCache, updateSpotPrices } from './src/router/pricing.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const cache = createPriceCache([]);
                updateSpotPrices(cache, [
                  { providerId: 'nan-node', modelId: 'cx/gpt-5.6-sol', prompt: 'garbage', completion: 'garbage' },
                  { providerId: 'flash-node-2', modelId: 'zai/glm-5.3-flash', prompt: 0.01, completion: 0.04 }
                ]);

                const candidates = rankCandidates({
                  model: 'infered/sol-budget',
                  priceCache: cache,
                  metricsStore,
                  maxFallbackPrice: 0.10
                });

                console.log(JSON.stringify({
                  topPick: candidates[0]?.modelId,
                  anyNanCandidate: candidates.some(c => !Number.isFinite(c.outputTokenPrice))
                }));")]
      (is (= "zai/glm-5.3-flash" (:topPick res)))
      (is (not (:anyNanCandidate res))))))

(deftest test-model-affinity-pins-selection
  (testing "Session affinity pins the MODEL (not just provider): last model stays selected while eligible"
    (let [res (run-node-eval
               "import { rankCandidates } from './src/router/pareto.js';
                import { createPriceCache, updateSpotPrices } from './src/router/pricing.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();
                const cache = createPriceCache([]);
                // Sol is the chain head (would normally always win), terra is a later chain position
                updateSpotPrices(cache, [
                  { providerId: 'sol-node-1', modelId: 'cx/gpt-5.6-sol', prompt: 0.02, completion: 0.08 },
                  { providerId: 'terra-node-1', modelId: 'cx/gpt-5.6-terra', prompt: 0.01, completion: 0.05 }
                ]);

                const withoutAffinity = rankCandidates({
                  model: 'infered/sol-budget',
                  priceCache: cache,
                  metricsStore,
                  maxFallbackPrice: 0.10
                });

                const withAffinity = rankCandidates({
                  model: 'infered/sol-budget',
                  priceCache: cache,
                  metricsStore,
                  maxFallbackPrice: 0.10,
                  sessionAffinityModel: 'cx/gpt-5.6-terra'
                });

                console.log(JSON.stringify({
                  defaultPick: withoutAffinity[0]?.modelId,
                  pinnedPick: withAffinity[0]?.modelId
                }));")]
      (is (= "cx/gpt-5.6-sol" (:defaultPick res)))
      (is (= "cx/gpt-5.6-terra" (:pinnedPick res))))))

(deftest test-elastic-budget-escalation-ladder
  (testing "Escalates budget ceiling from $0.10 -> $0.20 -> $0.30 -> zero-downtime fallback when output prices rise"
    (let [res (run-node-eval
               "import { rankCandidates } from './src/router/pareto.js';
                import { createPriceCache, updateSpotPrices } from './src/router/pricing.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();

                // Case 1: Models available at <= $0.10
                const cache1 = createPriceCache([]);
                updateSpotPrices(cache1, [
                  { providerId: 'p1', modelId: 'zai/glm-5.3-flash', prompt: 0.05, completion: 0.08 }
                ]);
                const res1 = rankCandidates({ model: 'infered/sol-budget', priceCache: cache1, metricsStore });

                // Case 2: Output prices surge to $0.15 (0 models <= $0.10, but available <= $0.20)
                const cache2 = createPriceCache([]);
                updateSpotPrices(cache2, [
                  { providerId: 'p2', modelId: 'zai/glm-5.3-flash', prompt: 0.10, completion: 0.15 }
                ]);
                const res2 = rankCandidates({ model: 'infered/sol-budget', priceCache: cache2, metricsStore });

                // Case 3: Output prices surge to $0.25 (0 models <= $0.20, but available <= $0.30)
                const cache3 = createPriceCache([]);
                updateSpotPrices(cache3, [
                  { providerId: 'p3', modelId: 'zai/glm-5.3-flash', prompt: 0.15, completion: 0.25 }
                ]);
                const res3 = rankCandidates({ model: 'infered/sol-budget', priceCache: cache3, metricsStore });

                // Case 4: Output prices surge to $0.45 (0 models <= $0.30 -> routes to cheapest healthy for zero downtime)
                const cache4 = createPriceCache([]);
                updateSpotPrices(cache4, [
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

(deftest test-glm-budget-cascade-behavior
  (testing "glm-budget routes as a budget cascade over the sol-excluded chain; sol-budget path unchanged"
    (let [res (run-node-eval
               "import { rankCandidates } from './src/router/pareto.js';
                import { createPriceCache, updateSpotPrices } from './src/router/pricing.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const priceCache = createPriceCache([]);
                const metricsStore = createMetricsStore();

                updateSpotPrices(priceCache, [
                  { providerId: 'n-flash', modelId: 'zai/glm-5.3-flash', prompt: 0.03, completion: 0.05 },
                  { providerId: 'n-glm', modelId: 'zai/glm-5.3', prompt: 0.10, completion: 0.08 },
                  { providerId: 'n-kimi', modelId: 'ali/kimi-k3', prompt: 0.08, completion: 0.12 },
                  { providerId: 'n-terra', modelId: 'cx/gpt-5.6-terra', prompt: 0.15, completion: 0.20 },
                  { providerId: 'n-sol', modelId: 'cx/gpt-5.6-sol', prompt: 1.00, completion: 0.05 }
                ]);

                const glmRanked = rankCandidates({ model: 'infered/glm-budget', priceCache, metricsStore });
                const solRanked = rankCandidates({ model: 'infered/sol-budget', priceCache, metricsStore });

                console.log(JSON.stringify({
                  glmWinner: glmRanked[0] && glmRanked[0].modelId,
                  glmChain: glmRanked.map(c => c.modelId),
                  glmEscalation: glmRanked[0] && glmRanked[0].escalationLevel,
                  glmIsCascade: glmRanked[0] && typeof glmRanked[0].budgetTier === 'number',
                  solWinner: solRanked[0] && solRanked[0].modelId
                }));")]
      (is (= "zai/glm-5.3-flash" (:glmWinner res))
          "cheapest chain position under the $0.10 ceiling wins")
      (is (not-any? #(= "cx/gpt-5.6-sol" %) (:glmChain res))
          "sol must never appear in glm-budget candidates, even with a cheap spot ask")
      (is (= ["zai/glm-5.3-flash" "zai/glm-5.3"] (:glmChain res))
          "kimi (0.12) and terra (0.20) are over the $0.10 ceiling")
      (is (= 0 (:glmEscalation res)))
      (is (:glmIsCascade res) "must take the ordered budget-cascade path")
      (is (= "cx/gpt-5.6-sol" (:solWinner res))
          "refactor must not change sol-budget behavior"))))

(deftest test-astra-budget-cascade-behavior
  (testing "astra-budget defers an unquoted head and promotes it once the market lists it"
    (let [res (run-node-eval
               "import { rankCandidates } from './src/router/pareto.js';
                import { createPriceCache, updateSpotPrices } from './src/router/pricing.js';
                import { createMetricsStore } from './src/router/metrics.js';

                const metricsStore = createMetricsStore();

                // Scenario 1 — today's live market: no astra quotes anywhere
                const noAstra = createPriceCache([]);
                updateSpotPrices(noAstra, [
                  { providerId: 'n-glm', modelId: 'zai/glm-5.3', prompt: 0.10, completion: 0.08 },
                  { providerId: 'n-kimi', modelId: 'ali/kimi-k3', prompt: 0.08, completion: 0.12 }
                ]);
                const deferred = rankCandidates({ model: 'infered/astra-budget', priceCache: noAstra, metricsStore });

                // Scenario 2 — astra lists a cheap spot ask: it must become the head
                const withAstra = createPriceCache([]);
                updateSpotPrices(withAstra, [
                  { providerId: 'n-astra', modelId: 'cx/gpt-6-astra', prompt: 0.50, completion: 0.08 },
                  { providerId: 'n-glm', modelId: 'zai/glm-5.3', prompt: 0.10, completion: 0.08 },
                  { providerId: 'n-kimi', modelId: 'ali/kimi-k3', prompt: 0.08, completion: 0.12 }
                ]);
                const promoted = rankCandidates({ model: 'infered/astra-budget', priceCache: withAstra, metricsStore });

                console.log(JSON.stringify({
                  deferredWinner: deferred[0] && deferred[0].modelId,
                  deferredChain: deferred.map(c => c.modelId),
                  deferredIsCascade: deferred[0] && typeof deferred[0].budgetTier === 'number',
                  promotedWinner: promoted[0] && promoted[0].modelId
                }));")]
      (is (= "zai/glm-5.3" (:deferredWinner res))
          "unquoted head is skipped; next chain position carries traffic")
      (is (not-any? #(= "cx/gpt-6-astra" %) (:deferredChain res))
          "astra must not appear as a candidate without verified spot asks")
      (is (= ["zai/glm-5.3"] (:deferredChain res))
          "kimi (0.12 completion) is over the $0.10 ceiling at tier 0")
      (is (:deferredIsCascade res) "must take the ordered budget-cascade path")
      (is (= "cx/gpt-6-astra" (:promotedWinner res))
          "once the market lists astra, it automatically becomes the head"))))
