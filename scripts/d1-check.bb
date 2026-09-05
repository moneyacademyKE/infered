#!/usr/bin/env bb
;; D1 post-stress verification. Env: CF_EMAIL, CF_KEY, CF_ACCT, CF_DB.
(require '[org.httpkit.client :as hc]
         '[cheshire.core :as json])

(def acct  (System/getenv "CF_ACCT"))
(def db    (System/getenv "CF_DB"))
(def email (System/getenv "CF_EMAIL"))
(def key   (System/getenv "CF_KEY"))

(def url (str "https://api.cloudflare.com/client/v4/accounts/" acct "/d1/database/" db "/query"))

(def sql "SELECT ts, requested_model, selected_model, attempts, latency_ms, ok FROM routing_decisions ORDER BY ts DESC LIMIT 60")

(def resp @(hc/post url
                    {:headers {"x-auth-email" email
                               "x-auth-key" key
                               "content-type" "application/json"}
                     :body (json/generate-string {:sql sql})
                     :as :text
                     :timeout 30000}))

(def parsed (try (json/parse-string (:body resp) true)
                 (catch Exception _ {:parse-fail (subs (str (:body resp)) 0 300)})))

(cond
  (:parse-fail parsed)
  (println "PARSE FAIL:" (:parse-fail parsed))

  (not= true (:success parsed))
  (do (println "API ERRORS:" (pr-str (:errors parsed)))
      (println "http status:" (:status resp)))

  :else
  (let [rows (-> (:result parsed) first :results)]
    (println "rows fetched:" (count rows))
    (println "newest ts:" (:ts (first rows)) "| oldest fetched ts:" (:ts (last rows)))
    (println)
    (println "requested_model:" (frequencies (map :requested_model rows)))
    (println "served:" (frequencies (map :selected_model rows)))
    (println "sol served :" (count (filter #(str/includes? (str/lower-case (str (:selected_model %))) "sol") rows)))
    (println "null latency:" (count (filter #(nil? (:latency_ms %)) rows))
             "| ok=1:" (count (filter #(= 1 (long (or (:ok %) 0))) rows)))
    (println "attempts :" (frequencies (map :attempts rows)))
    (println)
    (println "latest 12 rows:")
    (doseq [r (take 12 rows)]
      (println " " (:ts r) "|" (pr-str (:requested_model r)) "->" (pr-str (:selected_model r))
               "| att" (:attempts r) "| ms" (:latency_ms r) "| ok" (:ok r)))))
