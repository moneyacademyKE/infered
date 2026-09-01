(ns catalog-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]))

(defn run-node-eval [js-code]
  (let [{:keys [exit out err]} (sh "node" "--input-type=module" "-e" js-code)]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "Node eval error: " err) {:err err :out out})))))

(deftest test-catalog-tiers
  (testing "Virtual models resolve to appropriate candidate models and tiers"
    (let [res (run-node-eval
               "import { resolveVirtualModel, MODEL_TIERS, VIRTUAL_ALIASES } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  autoModels: resolveVirtualModel('infered/auto'),
                  fastModels: resolveVirtualModel('infered/fast'),
                  smartModels: resolveVirtualModel('infered/smart'),
                  reasoningModels: resolveVirtualModel('infered/reasoning'),
                  cascadeModels: resolveVirtualModel('infered/sol-budget'),
                  solExact: resolveVirtualModel('cx/gpt-5.6-sol'),
                  hasAliases: Object.keys(VIRTUAL_ALIASES).length > 0
                }));")]
      (is (:hasAliases res))
      (is (vector? (:autoModels res)))
      (is (> (count (:autoModels res)) 0))
      (is (= ["cx/gpt-5.6-sol", "zai/glm-5.3-flash", "zai/glm-5.3", "ali/kimi-k3", "cx/gpt-5.6-terra"]
             (:cascadeModels res)))
      (is (= ["cx/gpt-5.6-sol"] (:solExact res))))))

(deftest test-official-pricing
  (testing "Official pricing provides baseline for all models including 2026 frontier models"
    (let [res (run-node-eval
               "import { getOfficialPrice, OFFICIAL_PRICES } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  solPrice: getOfficialPrice('cx/gpt-5.6-sol'),
                  glmFlashPrice: getOfficialPrice('zai/glm-5.3-flash'),
                  kimiPrice: getOfficialPrice('ali/kimi-k3'),
                  terraPrice: getOfficialPrice('cx/gpt-5.6-terra'),
                  hasPrices: Object.keys(OFFICIAL_PRICES).length >= 10
                }));")]
      (is (:hasPrices res))
      (is (> (get-in res [:solPrice :prompt]) 0))
      (is (> (get-in res [:glmFlashPrice :prompt]) 0))
      (is (> (get-in res [:kimiPrice :prompt]) 0))
      (is (> (get-in res [:terraPrice :prompt]) 0)))))
