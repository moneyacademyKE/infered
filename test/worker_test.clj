(ns worker-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-worker-endpoints
  (testing "Worker handles CORS, models, health, and chat completions"
    (let [res (run-node-eval
               "import worker from './src/worker.js';

                const env = {
                  INFERHUB_BASE_URL: 'https://api.inferhub.net/v1',
                  ROUTING_POLICY: 'balanced',
                  PRICE_WEIGHT: '0.6',
                  SPEED_WEIGHT: '0.3',
                  QUALITY_WEIGHT: '0.1'
                };
                const ctx = { waitUntil: () => {} };

                // 1. Health check
                const healthReq = new Request('https://edge.infered.ai/v1/health');
                const healthRes = await worker.fetch(healthReq, env, ctx);
                const healthJson = await healthRes.json();

                // 2. Models listing
                const modelsReq = new Request('https://edge.infered.ai/v1/models');
                const modelsRes = await worker.fetch(modelsReq, env, ctx);
                const modelsJson = await modelsRes.json();

                // 3. Metrics listing
                const metricsReq = new Request('https://edge.infered.ai/v1/metrics');
                const metricsRes = await worker.fetch(metricsReq, env, ctx);
                const metricsJson = await metricsRes.json();

                // 4. Chat completions with Sol budget cascade alias
                const chatReq = new Request('https://edge.infered.ai/v1/chat/completions', {
                  method: 'POST',
                  headers: {
                    'Content-Type': 'application/json',
                    'X-Infered-Max-Price': '0.10'
                  },
                  body: JSON.stringify({
                    model: 'infered/sol-budget',
                    messages: [{ role: 'user', content: 'Design an agentic pipeline' }]
                  })
                });
                const chatRes = await worker.fetch(chatReq, env, ctx);
                const chatJson = await chatRes.json();
                const selectedModel = chatRes.headers.get('x-infered-selected-model');
                const savingsPct = chatRes.headers.get('x-infered-savings-pct');

                console.log(JSON.stringify({
                  healthStatus: healthJson.status,
                  modelsCount: modelsJson.data.length,
                  hasVirtualAuto: modelsJson.data.some(m => m.id === 'infered/auto'),
                  hasSolBudget: modelsJson.data.some(m => m.id === 'infered/sol-budget'),
                  metricsQuotesCount: metricsJson.quotes.length,
                  chatSuccess: chatRes.status === 200,
                  selectedModel: selectedModel,
                  savingsPct: parseFloat(savingsPct) > 0,
                  chatContent: chatJson.choices[0].message.content.length > 0
                }));")]
      (is (= "healthy" (:healthStatus res)))
      (is (> (:modelsCount res) 8))
      (is (:hasVirtualAuto res))
      (is (:hasSolBudget res))
      (is (> (:metricsQuotesCount res) 0))
      (is (:chatSuccess res))
      (is (:savingsPct res))
      (is (:chatContent res)))))

(deftest test-homepage-route-contract
  (testing "/ serves routing analytics; /dashboard preserves the legacy ops dashboard"
    (let [res (run-node-eval
               "import worker from './src/worker.js';

                const env = { ROUTING_DB: {
                  prepare: (sql) => ({ sql }),
                  batch: async (stmts) => stmts.map(() => ({ results: [] }))
                }};
                const ctx = { waitUntil: () => {} };

                const homeRes = await worker.fetch(new Request('https://edge.infered.ai/'), env, ctx);
                const homeHtml = await homeRes.text();
                const legacyRes = await worker.fetch(new Request('https://edge.infered.ai/dashboard'), env, ctx);
                const legacyHtml = await legacyRes.text();

                console.log(JSON.stringify({
                  homeStatus: homeRes.status,
                  homeIsAnalytics: homeHtml.includes('infered routing analytics'),
                  homeHasPrices: homeHtml.includes('live spot'),
                  homeHasRatecardRow: homeHtml.includes('gpt-5.6-sol'),
                  homeHasDollar: homeHtml.includes('$'),
                  legacyStatus: legacyRes.status,
                  legacyIsDashboard: legacyHtml.includes('Virtual LLM Router for InferHub'),
                  legacyNotAnalytics: !legacyHtml.includes('infered routing analytics')
                }));")]
      (is (= 200 (:homeStatus res)))
      (is (true? (:homeIsAnalytics res)) "/ should serve the routing analytics page")
      (is (true? (:homeHasPrices res)) "/ should render the Prices (list → live spot) section")
      (is (true? (:homeHasRatecardRow res)) "prices section should list ratecard models")
      (is (true? (:homeHasDollar res)) "prices rows should carry $ figures")
      (is (= 200 (:legacyStatus res)))
      (is (true? (:legacyIsDashboard res)) "/dashboard should preserve the legacy dashboard")
      (is (true? (:legacyNotAnalytics res)) "legacy dashboard must not leak analytics content"))))
