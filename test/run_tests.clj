(ns run-tests
  (:require [clojure.test :refer [run-tests]]))

(println "==========================================================")
(println "⚡ RUNNING INFERED CLOUDFLARE VIRTUAL ROUTER TEST SUITE ⚡")
(println "==========================================================")

(load-file "test/catalog_test.clj")
(load-file "test/metrics_test.clj")
(load-file "test/pricing_test.clj")
(load-file "test/pareto_test.clj")
(load-file "test/client_test.clj")
(load-file "test/cache_test.clj")
(load-file "test/healer_test.clj")
(load-file "test/worker_test.clj")

(let [catalog-res (run-tests 'catalog-test)
      metrics-res (run-tests 'metrics-test)
      pricing-res (run-tests 'pricing-test)
      pareto-res  (run-tests 'pareto-test)
      client-res  (run-tests 'client-test)
      cache-res   (run-tests 'cache-test)
      healer-res  (run-tests 'healer-test)
      worker-res  (run-tests 'worker-test)
      total-fail  (+ (:fail catalog-res) (:fail metrics-res) (:fail pricing-res)
                     (:fail pareto-res) (:fail client-res) (:fail cache-res)
                     (:fail healer-res) (:fail worker-res))
      total-err   (+ (:error catalog-res) (:error metrics-res) (:error pricing-res)
                     (:error pareto-res) (:error client-res) (:error cache-res)
                     (:error healer-res) (:error worker-res))]
  (println "\n----------------------------------------------------------")
  (if (and (zero? total-fail) (zero? total-err))
    (do
      (println "✅ ALL TESTS PASSED SUCCESSFULLY! (Rich Hickey Quality Certified)")
      (println "----------------------------------------------------------\n"))
    (do
      (println (str "❌ TEST SUITE FAILED: " total-fail " failures, " total-err " errors"))
      (println "----------------------------------------------------------\n")
      (System/exit 1))))
