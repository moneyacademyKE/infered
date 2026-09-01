(ns pricing-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-pricing-calculations-and-discounts
  (testing "Calculates accurate discount percentages vs official baselines"
    (let [res (run-node-eval
               "import { createPriceCache, updateSpotPrices, getSpotQuote, calculateSavingsPct } from './src/router/pricing.js';
                const cache = createPriceCache([]);
                
                updateSpotPrices(cache, [
                  { providerId: 'inferhub-node-1', modelId: 'claude-3.5-sonnet', prompt: 1.20, completion: 6.00 },
                  { providerId: 'inferhub-node-2', modelId: 'claude-3.5-sonnet', prompt: 2.70, completion: 13.50 }
                ]);
                
                const quote1 = getSpotQuote(cache, 'inferhub-node-1', 'claude-3.5-sonnet');
                const quote2 = getSpotQuote(cache, 'inferhub-node-2', 'claude-3.5-sonnet');
                const savings1 = calculateSavingsPct('claude-3.5-sonnet', quote1);
                const savings2 = calculateSavingsPct('claude-3.5-sonnet', quote2);

                console.log(JSON.stringify({
                  savings1Pct: savings1,
                  savings2Pct: savings2,
                  isQuote1Cheaper: quote1.blendedPrice < quote2.blendedPrice
                }));")]
      (is (== 60 (:savings1Pct res)))
      (is (== 10 (:savings2Pct res)))
      (is (:isQuote1Cheaper res)))))

(deftest test-inferhub-live-models-ingestion
  (testing "Ingests and unpacks live InferHub /v1/models response with ask books"
    (let [res (run-node-eval
               "import { createPriceCache, ingestInferHubModelsResponse, getQuotesForModel } from './src/router/pricing.js';
                const cache = createPriceCache([]);

                const mockInferHubResponse = {
                  data: [
                    {
                      id: 'cx/gpt-5.6-sol',
                      pricing: {
                        official_in: 5,
                        official_out: 30,
                        asks_in: [0.01, 0.05, 0.10],
                        asks_out: [0.06, 0.30, 0.60],
                        min_ask_in: 0.01,
                        min_ask_out: 0.06
                      }
                    },
                    {
                      id: 'zai/glm-5.3-flash',
                      pricing: {
                        official_in: 0.15,
                        official_out: 0.5,
                        asks_in: [0.0075, 0.015],
                        asks_out: [0.025, 0.05],
                        min_ask_in: 0.0075,
                        min_ask_out: 0.025
                      }
                    }
                  ]
                };

                ingestInferHubModelsResponse(cache, mockInferHubResponse);
                const solQuotes = getQuotesForModel(cache, 'cx/gpt-5.6-sol');
                const flashQuotes = getQuotesForModel(cache, 'zai/glm-5.3-flash');

                console.log(JSON.stringify({
                  solQuotesCount: solQuotes.length,
                  flashQuotesCount: flashQuotes.length,
                  bestSolBlendedPrice: solQuotes[0].blendedPrice,
                  bestSolSavingsPct: solQuotes[0].savingsPct
                }));")]
      (is (== 3 (:solQuotesCount res)))
      (is (== 2 (:flashQuotesCount res)))
      (is (< (:bestSolBlendedPrice res) 0.05))
      (is (> (:bestSolSavingsPct res) 90)))))
