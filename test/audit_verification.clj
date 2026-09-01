(ns audit-verification
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(println "==========================================================")
(println "🔬 HISTORICAL AUDIT & INVARIANT VERIFICATION SUITE 🔬")
(println "==========================================================")

(let [res (run-node-eval
           "import { createPriceCache, updateSpotPrices } from './src/router/pricing.js';
            import { createMetricsStore } from './src/router/metrics.js';
            import { rankCandidates } from './src/router/pareto.js';

            const metricsStore = createMetricsStore();

            // Scenario 1: Default state (Sol at $0.06 <= $0.10)
            const cache1 = createPriceCache();
            const cand1 = rankCandidates({ model: 'infered/sol-budget', priceCache: cache1, metricsStore });

            // Scenario 2: ALL Sol providers spike past $0.10 (Flash is $0.008 <= $0.10)
            const cache2 = createPriceCache();
            updateSpotPrices(cache2, [
              { providerId: 'inferhub-alpha', modelId: 'cx/gpt-5.6-sol', prompt: 0.05, completion: 0.15 },
              { providerId: 'inferhub-beta', modelId: 'cx/gpt-5.6-sol', prompt: 0.05, completion: 0.16 },
              { providerId: 'inferhub-gamma', modelId: 'cx/gpt-5.6-sol', prompt: 0.05, completion: 0.18 }
            ]);
            const cand2 = rankCandidates({ model: 'infered/sol-budget', priceCache: cache2, metricsStore });

            // Scenario 3: All Sol providers > $0.10, All Flash providers > $0.10 (GLM-5.3 is $0.05 <= $0.10)
            const cache3 = createPriceCache();
            updateSpotPrices(cache3, [
              { providerId: 'inferhub-alpha', modelId: 'cx/gpt-5.6-sol', prompt: 0.05, completion: 0.15 },
              { providerId: 'inferhub-beta', modelId: 'cx/gpt-5.6-sol', prompt: 0.05, completion: 0.16 },
              { providerId: 'inferhub-gamma', modelId: 'cx/gpt-5.6-sol', prompt: 0.05, completion: 0.18 },
              { providerId: 'inferhub-alpha', modelId: 'zai/glm-5.3-flash', prompt: 0.05, completion: 0.12 },
              { providerId: 'inferhub-beta', modelId: 'zai/glm-5.3-flash', prompt: 0.05, completion: 0.13 },
              { providerId: 'inferhub-gamma', modelId: 'zai/glm-5.3-flash', prompt: 0.05, completion: 0.14 }
            ]);
            const cand3 = rankCandidates({ model: 'infered/sol-budget', priceCache: cache3, metricsStore });

            // Scenario 4: All models spike past $0.10 but Flash is <= $0.20 (Escalates to Tier 1 $0.20)
            const cache4 = createPriceCache([]);
            updateSpotPrices(cache4, [
              { providerId: 'inferhub-alpha', modelId: 'cx/gpt-5.6-sol', prompt: 0.05, completion: 0.25 },
              { providerId: 'inferhub-alpha', modelId: 'zai/glm-5.3-flash', prompt: 0.05, completion: 0.15 },
              { providerId: 'inferhub-alpha', modelId: 'zai/glm-5.3', prompt: 0.05, completion: 0.25 }
            ]);
            const cand4 = rankCandidates({ model: 'infered/sol-budget', priceCache: cache4, metricsStore });

            console.log(JSON.stringify({
              s1_model: cand1[0]?.modelId,
              s1_outputPrice: cand1[0]?.outputTokenPrice,
              s1_tier: cand1[0]?.budgetTier,

              s2_model: cand2[0]?.modelId,
              s2_outputPrice: cand2[0]?.outputTokenPrice,
              s2_tier: cand2[0]?.budgetTier,

              s3_model: cand3[0]?.modelId,
              s3_outputPrice: cand3[0]?.outputTokenPrice,
              s3_tier: cand3[0]?.budgetTier,

              s4_model: cand4[0]?.modelId,
              s4_outputPrice: cand4[0]?.outputTokenPrice,
              s4_tier: cand4[0]?.budgetTier,
              s4_escalationLevel: cand4[0]?.escalationLevel
            }, null, 2));")]

  (println "Audit Results:")
  (println (json/generate-string res {:pretty true}))
  (println "----------------------------------------------------------")
  (if (and (= "cx/gpt-5.6-sol" (:s1_model res))
           (= "zai/glm-5.3-flash" (:s2_model res))
           (= "zai/glm-5.3" (:s3_model res))
           (= "zai/glm-5.3-flash" (:s4_model res))
           (= 0.2 (:s4_tier res))
           (= 1 (:s4_escalationLevel res)))
    (println "✅ 100% INVARIANT VERIFICATION SUCCESS: All switching boundary conditions are functioning strictly!")
    (do
      (println "❌ Invariant verification failed!")
      (System/exit 1))))
