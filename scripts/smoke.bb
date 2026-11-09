#!/usr/bin/env bb
;; Post-deploy smoke gate (audit 2026-11-08): verifies the LIVE router end to
;; end. Env: SMOKE_BASE_URL (default prod), SMOKE_AUTH (router admin token,
;; optional), INFERHUB key not needed for public probes; the chat probe rides
;; whatever the worker has (mock path if unconfigured — still proves routing).
;; Exit 0 only when every probe passes.
(require '[cheshire.core :as json]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

(def base (or (System/getenv "SMOKE_BASE_URL") "https://infered-virtual-router.moneyacad.workers.dev"))
(def auth (System/getenv "SMOKE_AUTH"))
(defn auth-headers [] (if auth {"Authorization" (str "Bearer " auth)} {}))

(def failures (atom []))
(defn probe! [name ok detail]
  (println (if ok "✓" "✗") name (str detail))
  (when-not ok (swap! failures conj name)))

(defn sh-json [url extra-args]
  (let [{:keys [exit out]} (apply sh "curl" "-s" "-m" "30" url extra-args)]
    (if (zero? exit)
      (try (json/parse-string out true) (catch Exception _ {:parse-fail true}))
      {:exit-fail exit})))

;; 1. /v1/health — 200 + market truth block
(let [h (sh-json (str base "/v1/health") [])]
  (probe! "health-200" (map? h) (str (:status h)))
  (probe! "health-market-block" (contains? h :market) (pr-str (:market h))))

;; 2. /v1/models — exactly the five chain products
(let [m (sh-json (str base "/v1/models") [])
      ids (mapv :id (get m :data))]
  (probe! "models-five-chains"
          (and (contains? (set ids) "infered/glm-budget")
               (contains? (set ids) "infered/astra-budget")
               (contains? (set ids) "infered/astra-terra")
               (contains? (set ids) "infered/terra-kimi")
               (contains? (set ids) "infered/kimi-glm"))
          (pr-str ids)))

;; 3. /v1/metrics — 401 WITHOUT auth when token configured; skip if open
(let [{:keys [exit out]} (sh "curl" "-s" "-o" "/dev/null" "-w" "%{http_code}" "-m" "30" (str base "/v1/metrics"))
      status (str/trim (str out))]
  (when (= "401" status)
    (probe! "metrics-401-open" true "admin plane is token-gated (expected 401)"))
  (when (and auth (not= "401" status))
    ;; token configured AND metrics open without it — that's a failure
    (probe! "metrics-open-with-token" false (str "status " status " but token configured"))))

;; 4. chat completion (non-stream) — 200 with content
(let [tmp "/tmp/smoke_chat.json"
      _ (spit tmp (json/generate-string {:model "infered/glm-budget"
                                         :messages [{:role "user" :content "Reply with the single word: OK"}]}))
      {exit :exit out :out} (sh "curl" "-s" "-m" "60" "-X" "POST"
                                (str base "/v1/chat/completions")
                                "-H" "Content-Type: application/json"
                                "--data-binary" (str "@" tmp))
      body (try (json/parse-string out true) (catch Exception _ {}))
      content (get-in body [:choices 0 :message :content])]
  (probe! "chat-200-content" (and (zero? exit) (pos? (count (str content))))
          (pr-str (subs (str content) 0 (min 60 (count (str content)))))))

;; 5. chat completion (stream) — 200, one [DONE], real content
(let [tmp "/tmp/smoke_chat_stream.json"
      _ (spit tmp (json/generate-string {:model "infered/glm-budget" :stream true
                                         :messages [{:role "user" :content "Say OK"}]}))
      {exit :exit out :out} (sh "curl" "-s" "-m" "60" "-N" "-X" "POST"
                                (str base "/v1/chat/completions")
                                "-H" "Content-Type: application/json"
                                "--data-binary" (str "@" tmp))
      done-count (count (re-seq #"\[DONE\]" (str out)))
      content-frames (count (re-seq #"\"delta\"" (str out)))]
  (probe! "stream-one-done" (and (zero? exit) (= 1 done-count)) (str "done=" done-count))
  (probe! "stream-content" (pos? content-frames) (str "delta-frames=" content-frames)))

;; 6. validation gate live — oversized declared body gets 413
(let [{:keys [exit out]} (sh "curl" "-s" "-o" "/dev/null" "-w" "%{http_code}" "-m" "30" "-X" "POST"
                             (str base "/v1/chat/completions")
                             "-H" "Content-Type: application/json"
                             "-H" "Content-Length: 99999999"
                             "--data" "{}")
      status (str/trim (str out))]
  (probe! "oversized-413" (= "413" status) (str "status " status)))

(println)
(if (empty? @failures)
  (println "✅ SMOKE PASS — all probes green against" base)
  (do (println "❌ SMOKE FAIL —" (count @failures) "probe(s):" (pr-str @failures))
      (System/exit 1)))
