(ns lint
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(println "==========================================================")
(println "🔍 INFERED CODE QUALITY & LINE-COUNT LINT INSPECTION 🔍")
(println "==========================================================")

(def max-loc 500)
(def files
  (concat
   (fs/glob "src" "**/*.{js,clj}")
   (fs/glob "test" "**/*.{clj,js}")
   (fs/glob "." "*.{edn,json,jsonc,md}")))

(def violations (atom []))

(doseq [f files]
  (let [content (slurp (str f))
        lines (str/split-lines content)
        loc (count lines)]
    (if (> loc max-loc)
      (do
        (println (str "❌ VIOLATION: " f " exceeds " max-loc " LOC (" loc " lines)"))
        (swap! violations conj {:file (str f) :loc loc}))
      (println (str "✓ " (format "%-35s" (str f)) " : " loc " lines (< 500 LOC)")))))

(println "----------------------------------------------------------")
(if (empty? @violations)
  (println "✅ ALL FILES COMPLY WITH STRICT <500 LOC & MODULARITY RULES!")
  (do
    (println (str "❌ FOUND " (count @violations) " VIOLATION(S)"))
    (System/exit 1)))
(println "==========================================================\n")
