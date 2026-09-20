#!/usr/bin/env bb
;; E2E time-to-first-byte stress for the infered virtual router.
;; Measures what the CLIENT sees: the worker ledger can record ok=1 even
;; when the client stared at dead silence (bk-4f77 lesson), so streaming
;; health is measured from the outside, per probe.
;;
;; Usage:
;;   ROUTER_KEY=<key> bb scripts/stress-ttfb.bb [seq-runs-per-chain=3] [burst-total=12]
;;
;; Phases:
;;   1. sequential — N probes per chain (easy prompt + reasoning bait)
;;   2. burst      — concurrent probes, default chain + worst-case chain
;;
;; Verdict: PASS iff every probe is HTTP 200, carries [DONE], and no
;; sequential TTFB exceeds MAX_TTFB (default 20s = 15s cutoff + first byte).

(require '[clojure.java.shell :as sh]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def url (or (System/getenv "ROUTER_URL")
             "https://infered-virtual-router.moneyacad.workers.dev"))
(def api-key (or (System/getenv "ROUTER_KEY")
                 (throw (ex-info "Set ROUTER_KEY to the router API key" {}))))
(def cli-args *command-line-args*)
(def seq-runs (or (some-> cli-args first parse-long) 3))
(def burst-total (or (some-> cli-args second parse-long) 12))
(def max-ttfb (or (some-> (System/getenv "MAX_TTFB") parse-double) 20.0))

(def chains ["infered/glm-budget" "infered/astra-budget" "infered/astra-terra"
             "infered/terra-kimi" "infered/kimi-glm"])

(def easy "Reply with exactly: OK")
(def hard "In one short sentence: why does time-to-first-byte matter for streaming LLM APIs?")

(defn probe [model content tag]
  (let [tmp (str "/tmp/ttfb-" (System/currentTimeMillis) "-" (rand-int 100000) ".json")
        body (json/generate-string {:model model :stream true
                                    :messages [{:role "user" :content content}]})
        {:keys [out err]} (sh/sh "curl" "-N" "-sS" "-o" tmp
                                 "-w" "%{http_code}|%{time_starttransfer}|%{time_total}"
                                 "--max-time" "150" "-X" "POST"
                                 (str url "/v1/chat/completions")
                                 "-H" (str "Authorization: Bearer " api-key)
                                 "-H" "Content-Type: application/json" "-d" body)
        parts (str/split (str/trim out) #"\|")
        [code ttfb total] (if (= 3 (count parts)) parts ["0" "0" "0"])
        text (slurp tmp)]
    (.. (java.io.File. tmp) delete)
    {:model model :tag tag :code (parse-long code)
     :ttfb (parse-double ttfb) :total (parse-double total)
     :done? (str/includes? text "[DONE]") :chars (count text) :err err}))

(defn fmt [r]
  (format "%-24s %-3s code=%s ttfb=%5.1fs total=%5.1fs done=%s chars=%d"
          (:model r) (:tag r) (:code r) (:ttfb r) (:total r)
          (if (:done? r) "y" "N") (:chars r)))

(defn summarize [rs label]
  (let [ok (filter #(= 200 (:code %)) rs)
        bad (remove #(= 200 (:code %)) rs)
        max-t (if (seq ok) (->> ok (map :ttfb) (apply max)) 0.0)
        avg-t (if (seq ok) (/ (reduce + (map :ttfb ok)) (count ok)) 0.0)]
    (println)
    (println (format "%s: %d/%d ok | ttfb avg=%.1fs max=%.1fs | missing [DONE]=%d | failures=%d"
                     label (count ok) (count rs) avg-t max-t
                     (count (remove :done? ok)) (count bad)))
    (doseq [r bad]
      (println "  FAIL:" (fmt r))
      (when (seq (:err r)) (println "        curl:" (:err r))))))

(println "=== phase 1: sequential —" seq-runs "runs x" (count chains) "chains ===")
(def seq-jobs (for [m chains i (range seq-runs)]
                {:model m :content (if (even? i) easy hard) :tag (str "s" i)}))
(def seq-results
  (doall (for [{:keys [model content tag]} seq-jobs]
           (let [r (probe model content tag)]
             (println (fmt r))
             r))))

(defn burst-jobs [n]
  (concat (repeat (quot n 3) {:model "infered/glm-budget" :content easy :tag "b"})
          (repeat (- n (quot n 3)) {:model "infered/kimi-glm" :content hard :tag "b"})))

(println)
(println "=== phase 2: burst —" burst-total "concurrent (glm-budget + kimi-glm) ===")
(def burst-results
  (doall (pmap (fn [{:keys [model content tag]}] (probe model content tag))
               (burst-jobs burst-total))))
(doseq [r (sort-by :ttfb burst-results)] (println (fmt r)))

(summarize seq-results "phase 1 sequential")
(summarize burst-results "phase 2 burst")

(let [all (concat seq-results burst-results)
      max-seq (->> seq-results (map :ttfb) (apply max))
      pass? (and (every? #(= 200 (:code %)) all)
                 (every? :done? all)
                 (<= max-seq max-ttfb))]
  (println)
  (if pass?
    (println (str "VERDICT: PASS (max sequential ttfb " max-seq "s <= " max-ttfb "s)"))
    (do (println (str "VERDICT: FAIL (max sequential ttfb " max-seq "s, threshold " max-ttfb "s)"))
        (System/exit 1))))