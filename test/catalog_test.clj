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
                  hasPrices: Object.keys(OFFICIAL_PRICES).length >= 4
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
                  solBudgetStillReroutes: resolveVirtualModel('infered/sol-budget')
                }));")]
      (is (= ["zai/glm-5.3-flash", "zai/glm-5.3", "ali/kimi-k3", "cx/gpt-5.6-terra"]
             (:glmBudgetModels res)))
      (is (not-any? #(= "cx/gpt-5.6-sol" %) (:glmBudgetModels res))
          "glm-budget must never include sol")
      (is (:isListed res) "must appear in VIRTUAL_ALIASES (drives /v1/models)")
      (is (:cascadeLookup res) "must be a registered cascade chain")
      ;; slimmed 2026-09-05: sol names left the listing but old callers still
      ;; reroute to the glm-budget chain instead of erroring
      (is (= ["zai/glm-5.3-flash", "zai/glm-5.3", "ali/kimi-k3", "cx/gpt-5.6-terra"]
             (:solBudgetStillReroutes res))
          "legacy sol-budget callers reroute to the glm-budget chain"))))

(deftest test-astra-budget-chain
  (testing "astra-budget resolves to the astra-headed chain; gm5.3 typo must never appear"
    (let [res (run-node-eval
               "import { resolveVirtualModel, VIRTUAL_ALIASES, CASCADE_CHAINS, CEILING_EXEMPT_MODELS } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  astraBudgetModels: resolveVirtualModel('infered/astra-budget'),
                  isListed: Boolean(VIRTUAL_ALIASES['infered/astra-budget']),
                  cascadeLookup: Boolean(CASCADE_CHAINS['infered/astra-budget']),
                  glmBudgetStillListed: Boolean(VIRTUAL_ALIASES['infered/glm-budget']),
                  ceilingExempt: CEILING_EXEMPT_MODELS
                }));")]
      (is (= ["cx/gpt-6-astra", "zai/glm-5.3-flash", "ali/kimi-k3"]
             (:astraBudgetModels res)))
      (is (= ["cx/gpt-6-astra"] (:ceilingExempt res))
          "astra head is price-exempt: no output ceiling can disqualify it")
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
                  removedPinned: resolveVirtualModel('infered/claude-3.5-sonnet'),
                  chainMemberDirect: resolveVirtualModel('zai/glm-5.3-flash')
                }));")]
      ;; bind-order note: the default chain is glm-budget's four-model cascade
      (is (= ["zai/glm-5.3-flash" "zai/glm-5.3" "ali/kimi-k3" "cx/gpt-5.6-terra"]
             (:gm53Typo res))
          "the famous gm5.3 typo must land on the budget chain")
      (is (not-any? #(= "cx/gpt-5.6-sol" %) (:rawSol res))
          "raw sol resolves to a sol-free chain")
      (is (not-any? #(= "claude-3.5-sonnet" %) (:removedPinned res))
          "removed pinned virtuals reroute to the budget chain")
      (is (= ["zai/glm-5.3-flash"] (:chainMemberDirect res))
          "chain members stay directly addressable (they are the chains)"))))

(deftest test-three-new-chains
  (testing "astra-terra, terra-kimi, kimi-glm resolve to their exact ordered chains"
    (let [res (run-node-eval
               "import { resolveVirtualModel, CASCADE_CHAINS, VIRTUAL_ALIASES } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  astraTerra: resolveVirtualModel('infered/astra-terra'),
                  terraKimi: resolveVirtualModel('infered/terra-kimi'),
                  kimiGlm: resolveVirtualModel('infered/kimi-glm'),
                  registered: ['infered/astra-terra','infered/terra-kimi','infered/kimi-glm'].every(k => Boolean(CASCADE_CHAINS[k] && VIRTUAL_ALIASES[k]))
                }));")]
      (is (= ["cx/gpt-6-astra" "cx/gpt-5.6-terra" "ali/kimi-k3" "zai/glm-5.3-flash"]
             (:astraTerra res)))
      (is (= ["cx/gpt-5.6-terra" "ali/kimi-k3" "zai/glm-5.3-flash"]
             (:terraKimi res)))
      (is (= ["ali/kimi-k3" "zai/glm-5.3-flash" "ali/qwen3.8-max"]
             (:kimiGlm res)) "qwen3.8-max is the verified market id for the kimi-glm tail")
      (is (:registered res) "all three registered in CASCADE_CHAINS and VIRTUAL_ALIASES"))))

(deftest test-strong-links
  (testing "each chain names a designated strong link for X-Infered-Tier: strong; unknown names fall to the default chain's"
    (let [res (run-node-eval
               "import { resolveStrongLink } from './src/router/catalog.js';
                console.log(JSON.stringify({
                  glmBudget: resolveStrongLink('infered/glm-budget'),
                  astraBudget: resolveStrongLink('infered/astra-budget'),
                  astraTerra: resolveStrongLink('infered/astra-terra'),
                  terraKimi: resolveStrongLink('infered/terra-kimi'),
                  kimiGlm: resolveStrongLink('infered/kimi-glm'),
                  unknownName: resolveStrongLink('zai/gm5.3'),
                  empty: resolveStrongLink('')
                }));")]
      (is (= "ali/kimi-k3" (:glmBudget res)) "highest-quality catalog model carries glm-budget's strong link")
      (is (= "cx/gpt-6-astra" (:astraBudget res)) "the premium head IS astra-budget's strong link")
      (is (= "cx/gpt-6-astra" (:astraTerra res)))
      (is (= "ali/kimi-k3" (:terraKimi res)))
      (is (= "ali/qwen3.8-max" (:kimiGlm res)) "the big-gun tail is kimi-glm's strong link")
      (is (= "ali/kimi-k3" (:unknownName res)) "unrecognized names inherit the default chain's strong link")
      (is (= "ali/kimi-k3" (:empty res))))))
