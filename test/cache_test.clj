(ns cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-cache-key-and-session-affinity
  (testing "Generates deterministic cache keys and tracks session affinity for prefix caching"
    (let [res (run-node-eval
               "import { createCacheStore, computeRequestKey, getCachedResponse, putCachedResponse, getSessionAffinity, setSessionAffinity } from './src/router/cache.js';
                const store = createCacheStore();

                const reqBody1 = {
                  model: 'infered/sol-budget',
                  messages: [{ role: 'user', content: 'What is 2+2?' }],
                  temperature: 0
                };
                const reqBody2 = {
                  model: 'infered/sol-budget',
                  messages: [{ role: 'user', content: 'What is 2+2?' }],
                  temperature: 0
                };

                const key1 = computeRequestKey(reqBody1);
                const key2 = computeRequestKey(reqBody2);

                // Cache a response
                putCachedResponse(store, key1, { choices: [{ message: { content: '4' } }] });
                const hit = getCachedResponse(store, key2);

                // Track session affinity
                setSessionAffinity(store, 'session-abc-123', 'inferhub-node-3', 'cx/gpt-5.6-sol');
                const affinity = getSessionAffinity(store, 'session-abc-123');

                console.log(JSON.stringify({
                  keysMatch: key1 === key2,
                  hasHit: hit !== null,
                  hitContent: hit?.choices?.[0]?.message?.content,
                  affinityProvider: affinity?.providerId,
                  affinityModel: affinity?.modelId
                }));")]
      (is (:keysMatch res))
      (is (:hasHit res))
      (is (= "4" (:hitContent res)))
      (is (= "inferhub-node-3" (:affinityProvider res)))
      (is (= "cx/gpt-5.6-sol" (:affinityModel res))))))
