(ns security-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-admin-auth-required
  (testing "admin surfaces require ROUTER_AUTH_TOKEN when configured (401 otherwise)"
    (let [res (run-node-eval
               "import worker from './src/worker.js';
                const env = { INFERHUB_BASE_URL: 'https://api.inferhub.net/v1', ROUTER_AUTH_TOKEN: 'tok-abc' };
                const ctx = { waitUntil: () => {} };
                const noTok = await worker.fetch(new Request('https://edge.infered.ai/v1/admin/sync'), env, ctx);
                const badTok = await worker.fetch(new Request('https://edge.infered.ai/v1/admin/sync', {
                  method: 'POST',
                  headers: { 'Authorization': 'Bearer wrong' }
                }), env, ctx);
                const goodTok = await worker.fetch(new Request('https://edge.infered.ai/v1/admin/sync', {
                  method: 'POST',
                  headers: { 'Authorization': 'Bearer tok-abc' }
                }), env, ctx);
                const metricsNoTok = await worker.fetch(new Request('https://edge.infered.ai/v1/metrics'), env, ctx);
                const metricsGoodTok = await worker.fetch(new Request('https://edge.infered.ai/v1/metrics', {
                  headers: { 'Authorization': 'Bearer tok-abc' }
                }), env, ctx);
                console.log(JSON.stringify({
                  noTok: noTok.status, badTok: badTok.status, goodTok: goodTok.status,
                  metricsNoTok: metricsNoTok.status, metricsGoodTok: metricsGoodTok.status
                }));")]
      (is (= 401 (:noTok res)) "admin/sync without a token is rejected")
      (is (= 401 (:badTok res)) "admin/sync with a wrong token is rejected")
      (is (= 200 (:goodTok res)) "admin/sync with the right token is allowed")
      (is (= 401 (:metricsNoTok res)) "metrics without a token is rejected when auth configured")
      (is (= 200 (:metricsGoodTok res)) "metrics with the right token is allowed"))))

(deftest test-caller-authorization-never-relayed-upstream
  (testing "caller Authorization is never used as the InferHub upstream key"
    (let [res (run-node-eval
               "import worker from './src/worker.js';
                const upstreamAuth = [];
                const realFetch = globalThis.fetch;
                globalThis.fetch = async (url, opts = {}) => {
                  if (String(url).includes('chat/completions')) {
                    upstreamAuth.push(opts.headers ? (opts.headers['Authorization'] || null) : null);
                  }
                  return realFetch(url, opts);
                };
                const env = { INFERHUB_BASE_URL: 'https://api.inferhub.net/v1' };
                const ctx = { waitUntil: () => {} };
                const req = new Request('https://edge.infered.ai/v1/chat/completions', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer sk-caller-secret' },
                  body: JSON.stringify({ model: 'infered/glm-budget', messages: [{ role: 'user', content: 'relay probe' }] })
                });
                const res = await worker.fetch(req, env, ctx);
                await res.json().catch(() => {});
                globalThis.fetch = realFetch;
                const withKeyEnv = { INFERHUB_BASE_URL: 'https://api.inferhub.net/v1', INFERHUB_API_KEY: 'sk-worker-own' };
                const withKeyAuth = [];
                globalThis.fetch = async (url, opts = {}) => {
                  if (String(url).includes('chat/completions')) {
                    withKeyAuth.push(opts.headers ? (opts.headers['Authorization'] || null) : null);
                  }
                  return new Response(JSON.stringify({ id: 'x', choices: [{ message: { role: 'assistant', content: 'ok' }, finish_reason: 'stop' }] }), { status: 200, headers: { 'Content-Type': 'application/json' } });
                };
                const req2 = new Request('https://edge.infered.ai/v1/chat/completions', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer sk-caller-secret' },
                  body: JSON.stringify({ model: 'infered/glm-budget', messages: [{ role: 'user', content: 'relay probe 2' }] })
                });
                const res2 = await worker.fetch(req2, withKeyEnv, ctx);
                await res2.json().catch(() => {});
                globalThis.fetch = realFetch;
                console.log(JSON.stringify({
                  noKeyRealFetchCalls: upstreamAuth.length,
                  withKeyAuth: withKeyAuth.length ? withKeyAuth[0] : 'no-call'
                }));")]
      (is (= 0 (:noKeyRealFetchCalls res))
          "with no INFERHUB_API_KEY the caller's bearer triggers NO real upstream call (mock path)")
      (is (= "Bearer sk-worker-own" (:withKeyAuth res))
          "when the worker has its own key, upstream sees the worker's key — never the caller's"))))

(deftest test-request-body-validation
  (testing "oversized bodies and malformed shapes are rejected before routing"
    (let [res (run-node-eval
               "import worker from './src/worker.js';
                const env = { INFERHUB_BASE_URL: 'https://api.inferhub.net/v1' };
                const ctx = { waitUntil: () => {} };
                const mkReq = (body, headers = {}) => new Request('https://edge.infered.ai/v1/chat/completions', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json', ...headers },
                  body
                });
                const huge = JSON.stringify({ model: 'infered/glm-budget', messages: [{ role: 'user', content: 'x'.repeat(3 * 1024 * 1024) }] });
                const hugeRes = await worker.fetch(mkReq(huge, { 'Content-Length': String(huge.length) }), env, ctx);
                const badShape = await worker.fetch(mkReq(JSON.stringify({ model: 'infered/glm-budget', messages: 'nope' })), env, ctx);
                const badShapeJson = await badShape.json().catch(() => ({}));
                const badMsg = await worker.fetch(mkReq(JSON.stringify({ model: 'infered/glm-budget', messages: [{}] })), env, ctx);
                const badMsgJson = await badMsg.json().catch(() => ({}));
                const manyMsgs = Array.from({ length: 1100 }, () => ({ role: 'user', content: 'hi' }));
                const manyRes = await worker.fetch(mkReq(JSON.stringify({ model: 'infered/glm-budget', messages: manyMsgs })), env, ctx);
                const deepSession = Array.from({ length: 555 }, () => ({ role: 'user', content: 'hi' }));
                const deepRes = await worker.fetch(mkReq(JSON.stringify({ model: 'infered/glm-budget', messages: deepSession })), env, ctx);
                const ok = await worker.fetch(mkReq(JSON.stringify({ model: 'infered/glm-budget', messages: [{ role: 'user', content: 'valid' }] })), env, ctx);
                console.log(JSON.stringify({
                  hugeStatus: hugeRes.status, badShapeStatus: badShape.status,
                  badShapeType: (badShapeJson.error || {}).type,
                  badMsgStatus: badMsg.status, badMsgType: (badMsgJson.error || {}).type,
                  manyStatus: manyRes.status, deepStatus: deepRes.status, okStatus: ok.status
                }));")]
      (is (= 413 (:hugeStatus res)) "bodies over the byte cap get 413, not parsed")
      (is (= 400 (:badShapeStatus res)) "messages must be an array")
      (is (= "invalid_request_error" (:badShapeType res)))
      (is (= 400 (:badMsgStatus res)) "each message needs role and content")
      (is (= "invalid_request_error" (:badMsgType res)))
      (is (= 400 (:manyStatus res)) "message count over the cap is rejected (1100 > 1024)")
      (is (= 200 (:deepStatus res)) "real 555-message agent sessions route (2026-11-09 fallback incident)")
      (is (= 200 (:okStatus res)) "valid minimal requests still route"))))

(deftest test-health-distinguishes-seeded-from-live-market
  (testing "health reports market live/seeded state honestly, not synthetic capacity as truth"
    (let [res (run-node-eval
               "import worker from './src/worker.js';
                const env = { INFERHUB_BASE_URL: 'https://api.inferhub.net/v1' };
                const ctx = { waitUntil: () => {} };
                const h = await (await worker.fetch(new Request('https://edge.infered.ai/v1/health'), env, ctx)).json();
                console.log(JSON.stringify({
                  hasMarket: 'market' in h,
                  marketLive: h.market ? h.market.live : null,
                  seededQuotes: h.market ? h.market.seededQuotes : null,
                  liveQuotes: h.market ? h.market.liveQuotes : null,
                  lastSyncRawNull: h.lastSyncRaw === null,
                  cachedQuotes: h.cachedQuotes
                }));")]
      (is (:hasMarket res) "health carries a market block")
      (is (false? (:marketLive res)) "a fresh isolate with no successful sync is NOT live")
      (is (> (:seededQuotes res) 0) "synthetic seeds are counted as seeded, not as cached capacity")
      (is (= 0 (:liveQuotes res)) "no live quotes until a sync succeeds")
      (is (:lastSyncRawNull res) "lastSyncRaw is null before any sync"))))

(deftest test-analytics-escapes-d1-sourced-values
  (testing "model names and error strings from D1 are HTML-escaped (stored XSS)"
    (let [res (run-node-eval
               "import worker from './src/worker.js';
                import { renderAnalyticsPage } from './src/ui/analytics.js';
                const data = {
                  topModels: [{ model: '<script>alert(1)</script>', reqs: 3, okPct: 100, avgMs: 5, avgAttempts: 1 }],
                  switching: { total: 3, directHits: 3, failovers: 0, escalations: 0, errors: 1, avgAttempts: 1 },
                  flappingSessions: 0, totalSessions: 1, last24h: 3,
                  chains: [{ chain: 'infered/<b>glm</b>', reqs: 3, okPct: 100, directHits: 3, avgAttempts: 1 }],
                  failureClasses: [{ err: '<img src=x onerror=alert(2)>', n: 1 }],
                  failures24h: 1,
                  recentFailures: [{ ts: '2026-11-09', chain: '<script>c</script>', model: '<script>m</script>', error: '<script>e</script>' }]
                };
                const html = renderAnalyticsPage(data, null);
                console.log(JSON.stringify({
                  noScript: !html.includes('<script>alert'),
                  noImgTag: !html.includes('<img src=x'),
                  escapedLt: html.includes('&lt;script&gt;') || !html.includes('script')
                }));")]
      (is (:noScript res) "script tags in model names cannot reach the DOM")
      (is (:noImgTag res) "event-handler payloads in error text are defanged")
      (is (:escapedLt res) "raw < becomes &lt; in output"))))
