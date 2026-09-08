#!/usr/bin/env bb
;; D1 decision rows for the astra stress window (last 25 min)
(require '[cheshire.core :as json]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

(def hist-line (->> (slurp (str (System/getProperty "user.home") "/.zsh_history"))
                    clojure.string/split-lines
                    (filter #(and (re-find #"c3acded8" %) (re-find #"CLOUDFLARE_API_KEY" %)))
                    last))
(def email (second (re-find #"CLOUDFLARE_EMAIL=(\S+)" hist-line)))
(def key (second (re-find #"CLOUDFLARE_API_KEY=\"([^\"]+)\"" hist-line)))
(def acct (second (re-find #"CLOUDFLARE_ACCOUNT_ID=\"([^\"]+)\"" hist-line)))

(def db-id "b8c91497-e416-4315-8dba-256f9f5652b0")
(def payload (json/generate-string
              {:sql "SELECT ts, requested_model, selected_model, ok, error, attempts, latency_ms, escalation_level FROM routing_decisions WHERE ts > datetime('now', '-25 minutes') ORDER BY ts ASC"}))

(def tmp "/tmp/d1_stress_query.json")
(spit tmp payload)

(def res (sh "curl" "-s" "-m" "60" "-X" "POST"
             (str "https://api.cloudflare.com/client/v4/accounts/" acct "/d1/database/" db-id "/query")
             "-H" (str "X-Auth-Email: " email)
             "-H" (str "X-Auth-Key: " key)
             "-H" "Content-Type: application/json"
             "--data-binary" (str "@" tmp)))

(try
  (let [r (json/parse-string (:out res) true)
        rows (get-in r [:result :results])]
    (if (seq rows)
      (do (println "rows:" (count rows))
          (doseq [g (group-by :requested_model rows)]
            (let [rs (val g)
                  oks (count (filter #(= 1 (:ok %)) rs))
                  fails (count (filter #(not= 1 (:ok %)) rs))
                  served (frequencies (map :selected_model rs))
                  atts (frequencies (map :attempts rs))
                  errs (frequencies (map :error rs))]
              (println "---" (key g) ":" (count rs) "reqs | ok:" oks "fail:" fails)
              (println "   served:" served)
              (println "   attempts:" atts)
              (println "   errors:" errs)))
          (println "== failure rows detail ==")
          (doseq [r (filter #(not= 1 (:ok %)) rows)]
            (println (:ts r) "|" (:requested_model r) "|" (:selected_model r) "| att:" (:attempts r) "| err:" (str (:error r)) "| lat:" (:latency_ms r))))
      (println "no rows or error:" (str (:out res)))))
  (catch Exception e
    (println "parse fail:" (.getMessage e))
    (println (subs (str (:out res)) 0 (min 800 (count (str (:out res))))))))
