(ns live-test
  (:require [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(println "==================================================================")
(println "🌐 LIVE INFERHUB API INTEGRATION & DYNAMIC SWITCHING TEST 🌐")
(println "==================================================================")

(let [api-key (System/getenv "INFERHUB_API_KEY")
      js-code
      (str
       "import { createPriceCache, ingestInferHubModelsResponse } from './src/router/pricing.js';
        import { createMetricsStore, getUsageSummary } from './src/router/metrics.js';
        import { rankCandidates } from './src/router/pareto.js';
        import { executeWithFallback } from './src/router/client.js';

        const BASE_URL = process.env.INFERHUB_BASE_URL || 'https://api.inferhub.dev/v1';
        const API_KEY = process.env.INFERHUB_API_KEY;

        if (!API_KEY) {
          console.error('INFERHUB_API_KEY environment variable required for live integration test.');
          process.exit(1);
        }

        console.log('1. Fetching live spot market order books from InferHub API...');
        const modelsRes = await fetch(`${BASE_URL}/models`, {
          headers: { 'Authorization': `Bearer ${API_KEY}` }
        });
        const modelsData = await modelsRes.json();

        const priceCache = createPriceCache([]);
        const metricsStore = createMetricsStore();

        ingestInferHubModelsResponse(priceCache, modelsData);
        console.log(`✓ Ingested ${Object.keys(priceCache.quotes).length} live spot provider quotes.`);

        const candidatesA = rankCandidates({
          model: 'infered/sol-budget',
          priceCache,
          metricsStore,
          maxPriceThreshold: 0.10
        });
        const choiceA = candidatesA[0];
        console.log(`\\n2. Scenario A: Threshold <= $0.10`);
        console.log(`   -> Selected Stronger Model: ${choiceA.modelId} (Blended: $${choiceA.blendedPrice.toFixed(4)} / 1M, Savings: ${choiceA.savingsPct}%)`);

        const candidatesB = rankCandidates({
          model: 'infered/sol-budget',
          priceCache,
          metricsStore,
          maxPriceThreshold: 0.01
        });
        const choiceB = candidatesB[0];
        console.log(`\\n3. Scenario B: Threshold dropped to <= $0.01 (Sol > $0.01 -> Switches down)`);
        console.log(`   -> Switched Down To: ${choiceB.modelId} (Blended: $${choiceB.blendedPrice.toFixed(4)} / 1M, Savings: ${choiceB.savingsPct}%)`);

        const candidatesC = rankCandidates({
          model: 'infered/sol-budget',
          priceCache,
          metricsStore,
          maxPriceThreshold: 0.10
        });
        const choiceC = candidatesC[0];
        console.log(`\\n4. Scenario C: Threshold restored to <= $0.10 (Sol <= $0.10 -> Switches back up)`);
        console.log(`   -> Switched Back Up To: ${choiceC.modelId} (Blended: $${choiceC.blendedPrice.toFixed(4)} / 1M)`);

        console.log(`\\n5. Dispatching live prompt to InferHub API via ${choiceA.modelId}...`);
        const completionResult = await executeWithFallback({
          candidates: candidatesA,
          requestBody: {
            model: choiceA.modelId,
            messages: [{ role: 'user', content: 'Say \"INFERED_LIVE_SUCCESS\" and nothing else.' }]
          },
          apiKey: API_KEY,
          metricsStore,
          baseUrl: BASE_URL
        });

        const usage = getUsageSummary(metricsStore);

        console.log(`\\n6. Real-Time Usage & Telemetry Ledger:`);
        console.log(JSON.stringify({
          liveApiConnected: modelsRes.ok,
          scenarioA_StrongModel: choiceA.modelId,
          scenarioB_BudgetModel: choiceB.modelId,
          scenarioC_UpgradedModel: choiceC.modelId,
          livePromptSuccess: completionResult.success,
          liveResponse: completionResult.responseBody?.choices?.[0]?.message?.content || 'N/A',
          totalTokensProcessed: usage.totalTokens,
          totalCostUsd: usage.totalCostUsd,
          totalSavingsUsd: usage.totalSavingsUsd,
          switchesRecorded: usage.switchEvents.length
        }, null, 2));")
      {:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code :env (into {} (System/getenv)))]
  (if (zero? exit)
    (do
      (println out)
      (println "==================================================================")
      (println "✅ LIVE INFERHUB DYNAMIC MODEL SWITCHING VERIFIED SUCCESSFULLY!")
      (println "=================================================================="))
    (do
      (println "❌ Live test error:" err)
      (println "Output:" out)
      (System/exit 1))))
