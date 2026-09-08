#!/usr/bin/env bb
;; Parse D1 query result file: newest rows + summary
(require '[cheshire.core :as json])
(let [rows (get-in (json/parse-string (slurp (first *command-line-args*)) true) [:result 0 :results])]
  (println "rows:" (count rows))
  (when (seq rows)
    (println "newest ts:" (:ts (first rows)) " oldest ts:" (:ts (last rows)))
    (doseq [g (group-by :requested_model rows)]
      (let [rs (val g)
            oks (count (filter #(= 1 (:ok %)) rs))]
        (println "---" (key g) ":" (count rs) "reqs | ok:" oks "fail:" (- (count rs) oks))
        (println "    served:" (vec (frequencies (map :selected_model rs))))
        (println "    atts:" (vec (frequencies (map :attempts rs))))
        (println "    errs:" (vec (frequencies (map :error rs))))))
    (println "== failure rows ==")
    (doseq [r (take 12 (filter #(not= 1 (:ok %)) rows))]
      (println (:ts r) "|" (:requested_model r) "|" (:selected_model r) "| att:" (:attempts r) "| err:" (str (:error r)) "| lat:" (:latency_ms r)))))
