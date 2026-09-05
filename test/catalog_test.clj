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
      ;; sol removed 2026-09-05: sol-budget resolves to the glm-budget chain,
      ;; and raw-sol requests fall through to the sol-free auto cascade.
      (is (= ["zai/glm-5.3-flash", "zai/glm-5.3", "ali/kimi-k3", "cx/gpt-5.6-terra"]
             (:cascadeModels res)))
      (is (not-any? #(= "cx/gpt-5.6-sol" %) (:solExact res))
          "removed sol must resolve to a sol-free fallback, never to itself"))))

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
      ;; sol removed from catalog: lookup falls to the generic $1/$2 baseline
      (is (= 1 (get-in res [:solPrice :prompt])))
      (is (> (get-in res [:glmFlashPrice :prompt]) 0))
      (is (> (get-in res [:kimiPrice :prompt]) 0))
      (is (> (get-in res [:terraPrice :prompt]) 0)))))

(deftest test-glm-budget-chain
  (testing "glm-budget resolves to the sol-excluded cascade chain and is listed as a virtual model"
    (let [res (run-node-eval
               "import { resolveVirtualModel, VIRTUAL_ALIASES, CASCADE_CHAINS } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  glmBudgetModels: resolveVirtualModel('infered/glm-budget'),
                  isListed: Boolean(VIRTUAL_ALIASES['infered/glm-budget']),
                  cascadeLookup: Boolean(CASCADE_CHAINS['infered/glm-budget']),
                  solBudgetStillListed: Boolean(VIRTUAL_ALIASES['infered/sol-budget'])
                }));")]
      (is (= ["zai/glm-5.3-flash", "zai/glm-5.3", "ali/kimi-k3", "cx/gpt-5.6-terra"]
             (:glmBudgetModels res)))
      (is (not-any? #(= "cx/gpt-5.6-sol" %) (:glmBudgetModels res))
          "glm-budget must never include sol")
      (is (:isListed res) "must appear in VIRTUAL_ALIASES (drives /v1/models)")
      (is (:cascadeLookup res) "must be a registered cascade chain")
      (is (:solBudgetStillListed res) "refactor must not drop sol-budget"))))

(deftest test-astra-budget-chain
  (testing "astra-budget resolves to the astra-headed chain; gm5.3 typo must never appear"
    (let [res (run-node-eval
               "import { resolveVirtualModel, VIRTUAL_ALIASES, CASCADE_CHAINS } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  astraBudgetModels: resolveVirtualModel('infered/astra-budget'),
                  isListed: Boolean(VIRTUAL_ALIASES['infered/astra-budget']),
                  cascadeLookup: Boolean(CASCADE_CHAINS['infered/astra-budget']),
                  glmBudgetStillListed: Boolean(VIRTUAL_ALIASES['infered/glm-budget'])
                }));")]
      (is (= ["cx/gpt-6-astra", "zai/glm-5.3-flash", "ali/kimi-k3"]
             (:astraBudgetModels res)))
      (is (not-any? #(= "zai/gm5.3" %) (:astraBudgetModels res))
          "gm5.3 does not exist on the market; chain must use zai/glm-5.3")
      (is (:isListed res) "must appear in VIRTUAL_ALIASES (drives /v1/models)")
      (is (:cascadeLookup res) "must be a registered cascade chain")
      (is (:glmBudgetStillListed res) "must not disturb glm-budget"))))

(deftest test-unknown-names-reroute
  (testing "unknown model names (typos, removed models) resolve to the default budget chain, never the wide pool"
    (let [res (run-node-eval
               "import { resolveVirtualModel } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  gm53Typo: resolveVirtualModel('zai/gm5.3'),
                  rawSol: resolveVirtualModel('cx/gpt-5.6-sol'),
                  chainMemberDirect: resolveVirtualModel('zai/glm-5.3-flash')
                }));")]
      ;; bind-order note: the default chain is glm-budget's four-model cascade
      (is (= ["zai/glm-5.3-flash" "zai/glm-5.3" "ali/kimi-k3" "cx/gpt-5.6-terra"]
             (:gm53Typo res))
          "the famous gm5.3 typo must land on the budget chain")
      (is (not-any? #(= "cx/gpt-5.6-sol" %) (:rawSol res))
          "raw sol resolves to a sol-free chain")
      (is (= ["zai/glm-5.3-flash"] (:chainMemberDirect res))
          "chain members stay directly addressable (they are the chains)"))))
