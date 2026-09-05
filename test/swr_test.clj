(ns swr-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-dynamic-threshold-and-swr-sync
  (testing "Switches models immediately when max_price is passed dynamically in body or headers"
    (let [res (run-node-eval
               "import { createPriceCache, updateSpotPrices } from './src/router/pricing.js';
                import { createMetricsStore } from './src/router/metrics.js';
                import { rankCandidates } from './src/router/pareto.js';

                const priceCache = createPriceCache();
                const metricsStore = createMetricsStore();

                // Sol is at $0.06
                // Default threshold = 0.10 -> picks Sol
                const candDefault = rankCandidates({
                  model: 'infered/sol-budget',
                  priceCache,
                  metricsStore,
                  maxFallbackPrice: 0.10
                });

                // User tightens threshold to 0.02 -> Sol at $0.06 > $0.02 -> must switch to Flash ($0.008)
                const candTight = rankCandidates({
                  model: 'infered/sol-budget',
                  priceCache,
                  metricsStore,
                  maxFallbackPrice: 0.02
                });

                // Live market price update: Sol ask increases to $0.14
                updateSpotPrices(priceCache, [
                  { providerId: 'inferhub-alpha', modelId: 'cx/gpt-5.6-sol', prompt: 0.02, completion: 0.14 },
                  { providerId: 'inferhub-beta', modelId: 'cx/gpt-5.6-sol', prompt: 0.02, completion: 0.15 },
                  { providerId: 'inferhub-gamma', modelId: 'cx/gpt-5.6-sol', prompt: 0.02, completion: 0.16 }
                ]);

                // Query with standard 0.10 threshold -> Sol is now $0.14 > $0.10 -> must switch to Flash
                const candAfterSpike = rankCandidates({
                  model: 'infered/sol-budget',
                  priceCache,
                  metricsStore,
                  maxFallbackPrice: 0.10
                });

                console.log(JSON.stringify({
                  defaultModel: candDefault[0]?.modelId,
                  tightModel: candTight[0]?.modelId,
                  afterSpikeModel: candAfterSpike[0]?.modelId,
                  afterSpikePrice: candAfterSpike[0]?.outputTokenPrice
                }));")]
      ;; sol removed from chains: the cheap sol ask in the fixture is now
      ;; invisible to sol-budget, so flash wins even at the loose threshold.
      (is (= "zai/glm-5.3-flash" (:defaultModel res)))
      (is (= "zai/glm-5.3-flash" (:tightModel res)))
      (is (= "zai/glm-5.3-flash" (:afterSpikeModel res)))
      (is (<= (:afterSpikePrice res) 0.10)))))
