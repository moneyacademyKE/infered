#!/usr/bin/env bb
;; astra-budget stress test — concurrent waves, stickiness, edges, mid-stream abort.
;; Usage: bb scripts/stress-astra.bb
(require '[org.httpkit.client :as hc]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def base "https://infered-virtual-router.moneyacad.workers.dev")

(defn hdr [headers k]
  (some (fn [[kk v]]
          (when (= (str/lower-case (if (keyword? kk) (name kk) (str kk))) k) v))
        headers))

(defn post-json [payload session]
  (let [start (System/currentTimeMillis)
        {:keys [status headers body]}
        @(hc/post (str base "/v1/chat/completions")
                  {:headers (cond-> {"content-type" "application/json"}
                              (seq session) (assoc "x-session-id" session))
                   :body (json/generate-string payload)
                   :as :text
                   :timeout 90000})
        ms (- (System/currentTimeMillis) start)]
    {:status status
     :ms ms
     :ok? (= 200 status)
     :sel (hdr headers "x-infered-selected-model")
     :att (hdr headers "x-infered-attempts")
     :esc (hdr headers "x-infered-escalation-level")
     :err (when (not= 200 status)
            (try (-> (json/parse-string body true) :error :message)
                 (catch Exception _ (str/trim (str body)))))}))

(defn req
  [& [{:keys [model budget session max-tokens]}]]
  (post-json (cond-> {:model (or model "infered/astra-budget")
                      :messages [{:role "user" :content "ping"}]
                      :max_tokens (or max-tokens 5)}
               budget (assoc :max_price budget))
             session))

(defn pct [xs p]
  (let [xs (sort xs) n (count xs)]
    (when (pos? n) (nth xs (min (dec n) (long (* p n)))))))

(defn summarize [name rs]
  (println (str "\n=== " name " ==="))
  (println "n =" (count rs) "| ok =" (count (filter :ok? rs))
           "| failures:" (mapv :status (remove :ok? rs)))
  (let [lat (map :ms rs)]
    (println "latency ms  p50 =" (pct lat 0.5) " p95 =" (pct lat 0.95) " max =" (when (seq lat) (apply max lat))))
  (println "served   :" (frequencies (map :sel rs)))
  (println "attempts :" (frequencies (map :att rs)))
  (doseq [r (take 3 (remove :ok? rs))]
    (println "  fail sample:" (:status r) (pr-str (subs (str (:err r)) 0 (min 120 (count (str (:err r)))))))))

;; ---- Wave 1: 20 concurrent plain requests
(println "== WAVE 1: astra-budget x20 concurrent ==")
(def w1 (doall (pmap (fn [_] (req)) (range 20))))
(summarize "wave1 plain x20" w1)

;; ---- Wave 2: tight budget cap under load
(println "\n== WAVE 2: max_price 0.00001 x10 concurrent ==")
(def w2 (doall (pmap (fn [_] (req {:budget 0.00001})) (range 10))))
(summarize "wave2 tight-budget x10" w2)

;; ---- Wave 3: session stickiness under load
(println "\n== WAVE 3: one session id x10 concurrent ==")
(def w3 (doall (pmap (fn [_] (req {:session "stress-fixed"})) (range 10))))
(summarize "wave3 sticky x10" w3)
(println "distinct served models in session:" (count (distinct (map :sel w3))))

;; ---- Wave 4: edges (sequential)
(println "\n== WAVE 4: edge cases ==")
(doseq [[label spec]
        [["explicit sol name (expect reroute, never sol)" {:model "cx/gpt-5.6-sol"}]
         ["unknown zai/gm5.3 (expect default chain)"      {:model "zai/gm5.3"}]
         ["empty model name (expect default chain)"       {:model ""}]
         ["kept alias sol-budget (expect glm chain)"      {:model "infered/sol-budget"}]
         ["kept alias cascade (expect glm chain)"         {:model "infered/cascade"}]]]
  (let [r (req spec)]
    (println label)
    (println "   ->" (:status r) "served" (pr-str (:sel r)) "att" (:att r) "esc" (:esc r) "ms" (:ms r)
             (when (:err r) (str "ERR: " (:err r))))))

;; ---- Abort: open a stream, read one chunk, slam it shut
(println "\n== ABORT: stream open -> close after first chunk ==")
(let [p (promise)
      _ (hc/post (str base "/v1/chat/completions")
                 {:headers {"content-type" "application/json"}
                  :body (json/generate-string
                         {:model "infered/astra-budget"
                          :stream true
                          :max_tokens 300
                          :messages [{:role "user" :content "Count from 1 to 100 slowly, one number per line."}]})
                  :as :stream
                  :timeout 90000}
                 (fn [resp] (deliver p resp)))
      {:keys [status headers body]} (deref p 30000 {:status :timeout})
      t0 (System/currentTimeMillis)]
  (println "stream opened:" status "served" (pr-str (hdr headers "x-infered-selected-model")))
  (if (instance? java.io.InputStream body)
    (do (let [buf (byte-array 128)] (.read body buf))
        (println "first chunk received; closing connection NOW")
        (.close body)
        (println "connection closed after" (- (System/currentTimeMillis) t0) "ms — upstream should stop being billed"))
    (println "no stream body (got" (type body) ") — abort test inconclusive")))

;; ---- Verdict line
(def all-rs (concat w1 w2 w3))
(println "\n== VERDICT ==")
(println "total load requests:" (count all-rs)
         "| ok:" (count (filter :ok? all-rs))
         "| sol-served:" (count (filter #(str/includes? (str/lower-case (str (:sel %))) "sol") all-rs)))
