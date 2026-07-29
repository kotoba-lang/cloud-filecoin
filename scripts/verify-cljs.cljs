#!/usr/bin/env nbb
;; Run the suite on the ClojureScript side.
;;
;;   nbb --classpath "$(clojure -A:cljs -Spath)" scripts/verify-cljs.cljs
(ns verify-cljs
  (:require [clojure.test :as t]
            [filecoin.cloud.contracts-test]
            [filecoin.cloud.evm-test]
            [filecoin.cloud.piece-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (println)
  (if (t/successful? m)
    (println "all checks passed on the ClojureScript path")
    (do (println "FAILED on the ClojureScript path")
        (js/process.exit 1))))

(t/run-tests 'filecoin.cloud.contracts-test
             'filecoin.cloud.evm-test
             'filecoin.cloud.piece-test)
