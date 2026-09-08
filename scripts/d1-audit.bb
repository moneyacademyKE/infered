#!/usr/bin/env bb
;; D1 aggregate audit since a timestamp. Env: CF_EMAIL, CF_KEY, CF_ACCT, CF_DB, SINCE (optional, default deploy of 2026-09-05).
(require '[org.httpkit.client :as hc]
         '[cheshire.core :as json])

(def acct  (System/getenv "CF_ACCT"))
(def db    (System/getenv "CF_DB"))
(def email (System/getenv "CF_EMAIL"))
(def key   (System/getenv "CF_KEY"))
(def since (or (System/getenv "SINCE") "2026-09-05 17:40:00"))

(def url (str "https://api.cloudflare.com/client/v4/accounts/" acct "/d1/database/" db "/query"))

(def sql (str
  "SELECT ts FROM routing_decisions ORDER BY ts DESC LIMIT 3;"
  "SELECT COUNT(*) n, SUM(ok) ok_n, AVG(attempts) avg_att, MAX(latency_ms) max_ms,"
    " SUM(CASE WHEN latency_ms IS NULL THEN 1 ELSE 0 END) null_ms,"
    " SUM(CASE WHEN attempts=0 THEN 1 ELSE 0 END) cache_rows,"
    " SUM(CASE WHEN error LIKE '%disconnect%' THEN 1 ELSE 0 END) abort_rows"
    " FROM routing_decisions WHERE ts >= '" since "';"
  "SELECT COALESCE(requested_model,'(null)') req, COUNT(*) n, SUM(ok) ok_n FROM routing_decisions"
    " WHERE ts >= '" since "' GROUP BY req ORDER BY n DESC;"
  "SELECT COALESCE(selected_model,'(null)') sel, COUNT(*) n, SUM(ok) ok_n, AVG(latency_ms) avg_ms"
    " FROM routing_decisions WHERE ts >= '" since "' GROUP BY sel ORDER BY n DESC;"
  "SELECT COALESCE(error,'(ok)') err, COUNT(*) n FROM routing_decisions"
    " WHERE ts >= '" since "' AND ok=0 GROUP BY err;"))

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
  (let [[peek totals reqs serves errs] (-> (:result parsed) first :results)]
    (println "newest ts:" (mapv :ts peek))
    (println "window:" since "→ now")
    (println "totals:" totals)
    (println)
    (println "by requested:")
    (doseq [r reqs]  (println "  " (:req r) "n=" (:n r) "ok=" (:ok_n r)))
    (println "by served:")
    (doseq [r serves] (println "  " (:sel r) "n=" (:n r) "ok=" (:ok_n r) "avg_ms=" (int (or (:avg_ms r) 0))))
    (println "failures:")
    (doseq [r errs]  (println "  " (:err r) "n=" (:n r))))))
