(ns pricing-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-pricing-calculations-and-defaults
  (testing "Calculates blended prices and defaults all cascade models to <= $0.10 output tokens"
    (let [res (run-node-eval
               "import { calculateBlendedPrice, createDefaultMarketQuotes, createPriceCache, getQuotesForModel } from './src/router/pricing.js';

                const blended = calculateBlendedPrice(0.01, 0.06);
                const cache = createPriceCache();

                const flashQuotes = getQuotesForModel(cache, 'zai/glm-5.3-flash');
                const glmQuotes = getQuotesForModel(cache, 'zai/glm-5.3');
                const terraQuotes = getQuotesForModel(cache, 'cx/gpt-5.6-terra');
                const kimiQuotes = getQuotesForModel(cache, 'ali/kimi-k3');

                console.log(JSON.stringify({
                  blended,
                  flashOutput: flashQuotes[0].completion,
                  glmOutput: glmQuotes[0].completion,
                  terraOutput: terraQuotes[0].completion,
                  kimiOutput: kimiQuotes[0].completion,
                  allUnderTenCents: [flashQuotes[0], glmQuotes[0], terraQuotes[0], kimiQuotes[0]].every(q => q.completion <= 0.10)
                }));")]
      (is (= 0.0475 (:blended res)))
      (is (<= (:flashOutput res) 0.10))
      (is (<= (:glmOutput res) 0.10))
      (is (<= (:terraOutput res) 0.10))
      (is (<= (:kimiOutput res) 0.10))
      (is (:allUnderTenCents res)))))

(deftest test-inferhub-orderbook-ingestion
  (testing "Parses live asks_in and asks_out from InferHub models response"
    (let [res (run-node-eval
               "import { createPriceCache, ingestInferHubModelsResponse, getQuotesForModel } from './src/router/pricing.js';

                const cache = createPriceCache([]);
                const mockInferHubData = {
                  data: [
                    {
                      id: 'cx/gpt-5.6-sol',
                      pricing: {
                        official_in: 5.00,
                        official_out: 30.00,
                        asks_in: [0.010, 0.015, 0.020],
                        asks_out: [0.060, 0.075, 0.090]
                      }
                    }
                  ]
                };

                ingestInferHubModelsResponse(cache, mockInferHubData);
                const quotes = getQuotesForModel(cache, 'cx/gpt-5.6-sol');

                console.log(JSON.stringify({
                  quoteCount: quotes.length,
                  bestPromptPrice: quotes[0].prompt,
                  bestCompletionPrice: quotes[0].completion,
                  bestBlendedPrice: quotes[0].blendedPrice,
                  savingsPct: quotes[0].savingsPct
                }));")]
      (is (= 3 (:quoteCount res)))
      (is (= 0.01 (:bestPromptPrice res)))
      (is (= 0.06 (:bestCompletionPrice res)))
      (is (= 0.0475 (:bestBlendedPrice res)))
      (is (> (:savingsPct res) 95)))))

(deftest test-build-ratecard
  (testing "Ratecard lists every official model with blended list price and calibration flag"
    (let [res (run-node-eval
               "import { buildRatecard, calculateBlendedPrice } from './src/router/pricing.js';
                import { OFFICIAL_PRICES } from './src/router/catalog.js';

                const rc = buildRatecard();
                const sol = rc.find(r => r.modelId === 'cx/gpt-5.6-sol');
                const terra = rc.find(r => r.modelId === 'cx/gpt-5.6-terra');
                const mini = rc.find(r => r.modelId === 'gpt-4o-mini');
                console.log(JSON.stringify({
                  count: rc.length,
                  officialCount: Object.keys(OFFICIAL_PRICES).length,
                  solAbsent: !rc.some(r => r.modelId === 'cx/gpt-5.6-sol'),
                  terraPresent: Boolean(terra),
                  terraCalibrated: terra ? terra.calibrated === true : null,
                  terraBlendedConsistent: terra ? terra.blended === calculateBlendedPrice(terra.prompt, terra.completion) : null,
                  miniPresent: Boolean(mini),
                  miniUncalibrated: mini ? mini.calibrated === false : null,
                  allWellFormed: rc.every(r => typeof r.blended === 'number' && r.blended >= 0 && typeof r.calibrated === 'boolean')
                }));")]
      (is (= (:count res) (:officialCount res)) "ratecard must cover every official model")
      (is (true? (:solAbsent res)) "removed sol must not appear in the ratecard")
      (is (true? (:terraPresent res)))
      (is (true? (:terraCalibrated res)) "terra has a calibrated multiplier")
      (is (true? (:terraBlendedConsistent res)))
      (is (true? (:miniPresent res)))
      (is (true? (:miniUncalibrated res)) "models without multipliers are flagged uncalibrated")
      (is (true? (:allWellFormed res))))))
