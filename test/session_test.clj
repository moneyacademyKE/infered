(ns session-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out err})))))

(deftest test-fingerprint-determinism
  (testing "conversation-head fingerprint is stable, content-sensitive, and null-safe"
    (let [res (run-node-eval
               "import { fingerprintSessionId } from './src/router/session.js';

                const a = fingerprintSessionId([{ role: 'user', content: 'Design an agentic pipeline' }]);
                const a2 = fingerprintSessionId([{ role: 'user', content: 'Design an agentic pipeline' }]);
                const b = fingerprintSessionId([{ role: 'user', content: 'Write a haiku about crabs' }]);
                const whitespace = fingerprintSessionId([{ role: 'user', content: '  Design   an  agentic\\npipeline  ' }]);
                const parts = fingerprintSessionId([{ role: 'user', content: [{ type: 'text', text: 'Design an agentic pipeline' }] }]);
                const empty = fingerprintSessionId([]);
                const noUser = fingerprintSessionId([{ role: 'system', content: 'be brief' }]);
                const missing = fingerprintSessionId(undefined);

                console.log(JSON.stringify({ a, a2, b, whitespace, parts, empty, noUser, missing }));")]
      (is (some? (:a res)) "a real conversation head produces an id")
      (is (= (:a res) (:a2 res)) "same head -> same id (the whole point)")
      (is (not= (:a res) (:b res)) "different heads -> different ids")
      (is (= (:a res) (:whitespace res)) "whitespace normalization keeps turns stable")
      (is (= (:a res) (:parts res)) "content-parts arrays fingerprint like plain strings")
      (is (nil? (:empty res)) "no messages -> no session, never a junk pin")
      (is (nil? (:noUser res)) "no user message -> no fingerprint")
      (is (nil? (:missing res)) "undefined messages -> null, not a crash")
      (is (clojure.string/starts-with? (:a res) "fp-") "fingerprint ids are distinguishable from caller ids"))))

(deftest test-resolve-session-id-precedence
  (testing "explicit X-Session-ID wins; fingerprint is the fallback, not the override"
    (let [res (run-node-eval
               "import { resolveSessionId } from './src/router/session.js';

                const body = { messages: [{ role: 'user', content: 'Design an agentic pipeline' }] };
                const withHeader = resolveSessionId(new Request('https://x', { headers: { 'X-Session-ID': 'caller-123' } }), body);
                const legacyHeader = resolveSessionId(new Request('https://x', { headers: { 'X-Session-Affinity': 'legacy-9' } }), body);
                const derived = resolveSessionId(new Request('https://x'), body);
                const nothing = resolveSessionId(new Request('https://x'), {});

                console.log(JSON.stringify({ withHeader, legacyHeader, derived, nothing }));")]
      (is (= "caller-123" (:withHeader res)) "caller identity is sacred")
      (is (= "legacy-9" (:legacyHeader res)) "legacy affinity header still honored")
      (is (clojure.string/starts-with? (:derived res) "fp-") "headerless request derives a fingerprint")
      (is (nil? (:nothing res)) "no header and no head -> no session id"))))

(deftest test-get-session-pin-d1-fallthrough
  (testing "memory hit skips D1; memory miss rehydrates from D1; stale/broken D1 degrades to null"
    (let [res (run-node-eval
               "import { getSessionPin } from './src/router/session.js';
                import { createCacheStore, setSessionAffinity } from './src/router/cache.js';

                const store = createCacheStore();
                let d1Reads = 0;

                const mkEnv = (row) => ({ ROUTING_DB: {
                  prepare: (sql) => ({ bind: (...args) => ({
                    first: async () => { d1Reads++; return row; },
                    run: async () => ({})
                  }) })
                }});

                // 1. Memory hit: D1 must not be touched
                setSessionAffinity(store, 'mem-1', 'node-a', 'zai/glm-5.3-flash');
                const memHit = await getSessionPin(store, 'mem-1', mkEnv(null));
                const readsAfterMemHit = d1Reads;

                // 2. Memory miss, fresh D1 row: returns pin AND rehydrates L1
                const fresh = await getSessionPin(store, 'd1-1', mkEnv({ provider: 'node-b', model: 'ali/kimi-k3', updated_at: Date.now() - 60000 }));
                const rehydrated = await getSessionPin(store, 'd1-1', mkEnv(null));

                // 3. Stale D1 row (2h old, past the 60min pin TTL): treated as absent
                const stale = await getSessionPin(store, 'd1-2', mkEnv({ provider: 'node-b', model: 'ali/kimi-k3', updated_at: Date.now() - 7200000 }));

                // 4. Broken D1: routing must never break on observability
                const broken = await getSessionPin(store, 'd1-3', { ROUTING_DB: { prepare: () => { throw new Error('d1 down'); } } });

                // 5. No D1 binding at all
                const noBinding = await getSessionPin(store, 'd1-4', {});

                console.log(JSON.stringify({
                  memHit, readsAfterMemHit, fresh,
                  rehydratedFromL1: rehydrated && rehydrated.modelId,
                  stale, broken, noBinding
                }));")]
      (is (= "zai/glm-5.3-flash" (get-in res [:memHit :modelId])))
      (is (= 0 (:readsAfterMemHit res)) "warm L1 never pays the D1 read")
      (is (= "ali/kimi-k3" (get-in res [:fresh :modelId])) "cold L1 falls through to D1")
      (is (= "ali/kimi-k3" (:rehydratedFromL1 res)) "D1 hit rehydrates the in-memory pin")
      (is (nil? (:stale res)) "stale pins expire on read")
      (is (nil? (:broken res)) "D1 outage degrades to unpinned routing, never an error")
      (is (nil? (:noBinding res)) "missing binding is a miss, not a crash"))))

(deftest test-record-session-pin-write-dedup
  (testing "pin writes hit D1 only when the pinned model CHANGES; memory always refreshes"
    (let [res (run-node-eval
               "import { recordSessionPin, getSessionPin } from './src/router/session.js';
                import { createCacheStore } from './src/router/cache.js';

                const store = createCacheStore();
                const writes = [];
                const env = { ROUTING_DB: {
                  prepare: (sql) => ({ bind: (...args) => ({
                    run: async () => { writes.push({ sql, args }); return {}; },
                    first: async () => null
                  }) })
                }};
                const ctx = { waitUntil: (p) => p };

                recordSessionPin(store, env, ctx, 's-1', 'node-a', 'zai/glm-5.3-flash');
                const afterFirst = writes.length;
                recordSessionPin(store, env, ctx, 's-1', 'node-a', 'zai/glm-5.3-flash');
                const afterSame = writes.length;
                recordSessionPin(store, env, ctx, 's-1', 'node-b', 'ali/kimi-k3');
                const afterChange = writes.length;
                const upsertSql = writes.length ? writes[writes.length - 1].sql : '';
                const pin = await getSessionPin(store, 's-1', {});

                recordSessionPin(store, {}, ctx, 's-nodb', 'node-a', 'zai/glm-5.3-flash');

                console.log(JSON.stringify({ afterFirst, afterSame, afterChange, upsertSql, pinnedModel: pin && pin.modelId }));")]
      (is (= 1 (:afterFirst res)) "first pin persists to D1")
      (is (= 1 (:afterSame res)) "unchanged pin skips the D1 write (write volume stays tiny)")
      (is (= 2 (:afterChange res)) "a model switch writes exactly once more")
      (is (clojure.string/includes? (:upsertSql res) "ON CONFLICT") "upsert, not blind insert")
      (is (= "ali/kimi-k3" (:pinnedModel res)) "L1 reflects the latest pin"))))
