#!/usr/bin/env bb
(require '[cheshire.core :as json])
(let [d (json/parse-string (slurp "/tmp/health_raw2.json") true)
      s (:lastSyncRaw d)]
  (if s
    (do (println "syncedAt:" (:at s))
        (println "total:" (:total s) "withPricing:" (:withPricing s))
        (println "astraEntry:" (str (:astraEntry s)))
        (let [ids (:ids s)]
          (println "ids-count:" (count ids))
          (println "astra-ish:" (vec (filter #(re-find #"(?i)astra" (str %)) ids)))
          (println "cx-ids:" (vec (filter #(re-find #"^cx/" (str %)) ids)))))
    (println "lastSyncRaw nil — isolate synced before deploy or sync failed")))
