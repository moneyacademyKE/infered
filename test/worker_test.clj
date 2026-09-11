(ns worker-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
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
                  hasGlmBudget: modelsJson.data.some(m => m.id === 'infered/glm-budget'),
                  hasAstraBudget: modelsJson.data.some(m => m.id === 'infered/astra-budget'),
                  hasAstraTerra: modelsJson.data.some(m => m.id === 'infered/astra-terra'),
                  hasTerraKimi: modelsJson.data.some(m => m.id === 'infered/terra-kimi'),
                  hasKimiGlm: modelsJson.data.some(m => m.id === 'infered/kimi-glm'),
                  onlyFiveChains: modelsJson.data.every(m => ['infered/glm-budget', 'infered/astra-budget', 'infered/astra-terra', 'infered/terra-kimi', 'infered/kimi-glm'].includes(m.id)),
                  metricsQuotesCount: metricsJson.quotes.length,
                  chatSuccess: chatRes.status === 200,
                  selectedModel: selectedModel,
                  savingsPct: parseFloat(savingsPct) > 0,
                  chatContent: chatJson.choices[0].message.content.length > 0
                }));")]
      (is (= "healthy" (:healthStatus res)))
      (is (= 5 (:modelsCount res)) "exactly the five chain products are listed")
      (is (:hasGlmBudget res))
      (is (:hasAstraBudget res))
      (is (:hasAstraTerra res))
      (is (:hasTerraKimi res))
      (is (:hasKimiGlm res))
      (is (:onlyFiveChains res) "no policy aliases, no raw models in the listing")
      (is (> (:metricsQuotesCount res) 0))
      (is (:chatSuccess res))
      (is (:savingsPct res))
      (is (:chatContent res)))))

(deftest test-homepage-route-contract
  (testing "/ serves routing analytics; /dashboard redirects to it (dashboard retired)"
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

                console.log(JSON.stringify({
                  homeStatus: homeRes.status,
                  homeIsAnalytics: homeHtml.includes('infered routing analytics'),
                  homeHasPrices: homeHtml.includes('live spot'),
                  homeHasRatecardRow: homeHtml.includes('gpt-5.6-terra'),
                  homeHasNoSol: !homeHtml.includes('gpt-5.6-sol'),
                  homeHasDollar: homeHtml.includes('$'),
                  legacyStatus: legacyRes.status,
                  legacyLocation: legacyRes.headers.get('location') || ''
                }));")]
      (is (= 200 (:homeStatus res)))
      (is (true? (:homeIsAnalytics res)) "/ should serve the routing analytics page")
      (is (true? (:homeHasPrices res)) "/ should render the Prices (list → live spot) section")
      (is (true? (:homeHasRatecardRow res)) "prices section should list ratecard models")
      (is (true? (:homeHasNoSol res)) "removed sol must not render anywhere on the homepage")
      (is (true? (:homeHasDollar res)) "prices rows should carry $ figures")
      (is (= 302 (:legacyStatus res)) "/dashboard redirects after retirement")
      (is (str/ends-with? (str (:legacyLocation res)) "/") "redirect targets the analytics homepage"))))

(deftest test-streaming-latency-recorded
  (testing "streamed requests must record a real latency_ms, not null"
    (let [res (run-node-eval
               "import worker from './src/worker.js';

                const inserts = [];
                const env = {
                  INFERHUB_BASE_URL: 'https://api.inferhub.net/v1',
                  ROUTING_DB: {
                    prepare: (sql) => ({
                      bind: (...args) => ({
                        run: async () => { if (sql.includes('routing_decisions')) inserts.push(args); },
                        first: async () => null
                      })
                  })
                  }
                };
                const waits = [];
                const ctx = { waitUntil: (p) => waits.push(p) };

                const req = new Request('https://edge.infered.ai/v1/chat/completions', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({
                    model: 'infered/sol-budget',
                    stream: true,
                    messages: [{ role: 'user', content: 'ping' }]
                  })
                });
                const res = await worker.fetch(req, env, ctx);
                const isStream = (res.headers.get('content-type') || '').includes('event-stream');
                const drained = await res.text();
                await Promise.all(waits);

                console.log(JSON.stringify({
                  isStream,
                  drainedLen: drained.length,
                  insertCount: inserts.length,
                  // bind order: session(0), requested(1), selected(2), provider(3),
                  // escalation(4), attempts(5), latency(6), budget(7), ok(8)
                  latency: inserts.length ? inserts[0][6] : null,
                  okFlag: inserts.length ? inserts[0][8] : null
                }));")]
      (is (:isStream res) "stream:true should get an event-stream response")
      (is (> (:drainedLen res) 0) "stream body should carry content")
      (is (= 1 (:insertCount res)) "exactly one decision row after the stream drains")
      (is (some? (:latency res)) "latency_ms must be recorded for streamed requests")
      (is (= 1 (:okFlag res)) "streamed request recorded as success"))))

(deftest test-cache-hit-recorded
  (testing "deterministic cache hits must record a decision row (attempts=0), not vanish"
    (let [res (run-node-eval
               "import worker from './src/worker.js';

                const inserts = [];
                const env = {
                  INFERHUB_BASE_URL: 'https://api.inferhub.net/v1',
                  ROUTING_DB: {
                    prepare: (sql) => ({
                      bind: (...args) => ({
                        run: async () => { if (sql.includes('routing_decisions')) inserts.push(args); },
                        first: async () => null
                      })
                  })
                  }
                };
                const waits = [];
                const ctx = { waitUntil: (p) => waits.push(p) };

                const mkReq = () => new Request('https://edge.infered.ai/v1/chat/completions', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({
                    model: 'infered/glm-budget',
                    temperature: 0,
                    messages: [{ role: 'user', content: 'cache me' }]
                  })
                });

                const r1 = await worker.fetch(mkReq(), env, ctx);
                await r1.json();
                const r2 = await worker.fetch(mkReq(), env, ctx);
                await r2.json();
                await Promise.all(waits);

                console.log(JSON.stringify({
                  firstMiss: r1.headers.get('x-infered-cache'),
                  secondHit: r2.headers.get('x-infered-cache'),
                  insertCount: inserts.length,
                  // bind order: session(0), requested(1), selected(2), provider(3),
                  // escalation(4), attempts(5), latency(6), budget(7), ok(8), error(9)
                  secondAttempts: inserts.length > 1 ? inserts[1][5] : null,
                  secondOk: inserts.length > 1 ? inserts[1][8] : null
                }));")]
      (is (= "MISS" (:firstMiss res)) "first deterministic request is a cache MISS")
      (is (= "HIT" (:secondHit res)) "identical deterministic request hits the cache")
      (is (= 2 (:insertCount res)) "both MISS and HIT record a decision row")
      (is (= 0 (:secondAttempts res)) "cache hit recorded with attempts=0")
      (is (= 1 (:secondOk res)) "cache hit recorded as success"))))

(deftest test-client-disconnect-recorded
  (testing "abandoned streams must record an ok=0 client_disconnected row, not vanish"
    (let [res (run-node-eval
               "import worker from './src/worker.js';

                // undici quirk: cancelling a constructed Response body rejects an
                // internal wrapper read with `undefined` (workerd handles this
                // natively). Absorb exactly that; any real rejection still throws.
                process.on('unhandledRejection', (r) => { if (r !== undefined) throw r; });

                const inserts = [];
                const env = {
                  INFERHUB_BASE_URL: 'https://api.inferhub.net/v1',
                  ROUTING_DB: {
                    prepare: (sql) => ({
                      bind: (...args) => ({
                        run: async () => { if (sql.includes('routing_decisions')) inserts.push(args); },
                        first: async () => null
                      })
                  })
                  }
                };
                const waits = [];
                const ctx = { waitUntil: (p) => waits.push(p) };

                const req = new Request('https://edge.infered.ai/v1/chat/completions', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({
                    model: 'infered/glm-budget',
                    stream: true,
                    messages: [{ role: 'user', content: 'you will leave mid-stream' }]
                  })
                });
                const res = await worker.fetch(req, env, ctx);
                // Simulate the client hanging up before the stream drains.
                await res.body.cancel();
                await Promise.all(waits);

                console.log(JSON.stringify({
                  insertCount: inserts.length,
                  okFlag: inserts.length ? inserts[0][8] : null,
                  error: inserts.length ? inserts[0][9] : null
                }));")]
      (is (= 1 (:insertCount res)) "abandoned stream leaves exactly one decision row")
      (is (= 0 (:okFlag res)) "abandoned stream recorded as failure, not fake success")
      (is (= "client_disconnected" (:error res)) "row carries the client_disconnected error"))))

(deftest test-session-fingerprint-recorded
  (testing "headerless requests derive a stable fp- session id, recorded on decision rows; pins persist to D1"
    (let [res (run-node-eval
               "import worker from './src/worker.js';

                const inserts = [];
                const sqls = [];
                const env = {
                  INFERHUB_BASE_URL: 'https://api.inferhub.net/v1',
                  ROUTING_DB: {
                    prepare: (sql) => ({
                      bind: (...args) => ({
                        run: async () => { sqls.push(sql); if (sql.includes('routing_decisions')) inserts.push(args); },
                        first: async () => null
                      })
                    })
                  }
                };
                const waits = [];
                const ctx = { waitUntil: (p) => waits.push(p) };

                const mkReq = () => new Request('https://edge.infered.ai/v1/chat/completions', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({
                    model: 'infered/glm-budget',
                    messages: [{ role: 'user', content: 'fingerprint me, no session header' }]
                  })
                });

                const r1 = await worker.fetch(mkReq(), env, ctx);
                await r1.json();
                const r2 = await worker.fetch(mkReq(), env, ctx);
                await r2.json();
                await Promise.all(waits);

                console.log(JSON.stringify({
                  insertCount: inserts.length,
                  sessionA: inserts.length ? inserts[0][0] : null,
                  sessionB: inserts.length > 1 ? inserts[1][0] : null,
                  pinUpsertSeen: sqls.some(s => s.includes('session_pins'))
                }));")]
      (is (= 2 (:insertCount res)) "both requests record decision rows")
      (is (some? (:sessionA res)) "no header no longer means NULL session_id")
      (is (clojure.string/starts-with? (:sessionA res) "fp-") "derived ids are visibly fingerprints")
      (is (= (:sessionA res) (:sessionB res)) "same conversation head -> same session across requests")
      (is (:pinUpsertSeen res) "durable pin write reached D1"))))

(deftest test-tier-strong-header
  (testing "X-Infered-Tier: strong starts the cascade at the chain's designated strong link"
    (let [res (run-node-eval
               "import worker from './src/worker.js';

                const env = { INFERHUB_BASE_URL: 'https://api.inferhub.net/v1' };
                const ctx = { waitUntil: () => {} };

                const mkReq = (tier) => new Request('https://edge.infered.ai/v1/chat/completions', {
                  method: 'POST',
                  headers: tier
                    ? { 'Content-Type': 'application/json', 'X-Infered-Tier': tier }
                    : { 'Content-Type': 'application/json' },
                  body: JSON.stringify({
                    model: 'infered/glm-budget',
                    messages: [{ role: 'user', content: 'tier probe ' + (tier || 'default') }]
                  })
                });

                const strongRes = await worker.fetch(mkReq('strong'), env, ctx);
                await strongRes.json();
                const defaultRes = await worker.fetch(mkReq(null), env, ctx);
                await defaultRes.json();
                const bogusRes = await worker.fetch(mkReq('ludicrous'), env, ctx);
                await bogusRes.json();

                console.log(JSON.stringify({
                  strongSelected: strongRes.headers.get('x-infered-selected-model'),
                  defaultSelected: defaultRes.headers.get('x-infered-selected-model'),
                  bogusSelected: bogusRes.headers.get('x-infered-selected-model'),
                  strongStatus: strongRes.status
                }));")]
      (is (= "ali/kimi-k3" (:strongSelected res))
          "glm-budget's strong link is kimi — flash/glm are skipped")
      (is (= "zai/glm-5.3-flash" (:defaultSelected res))
          "no tier header keeps the budget head")
      (is (= "zai/glm-5.3-flash" (:bogusSelected res))
          "unknown tier values are ignored, never an error")
      (is (= 200 (:strongStatus res))))))
