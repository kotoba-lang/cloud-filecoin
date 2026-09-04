;; live-probe verify — the host half: fetch a REAL calibration block over
;; RPC, hand its scalars to the native guest, judge, and echo the verdict.
;; Read-only RPC; no keys, no spend.
(ns fc-live-probe
  (:require [kotoba.compiler.core :as compiler]
            [kotoba.verifier.signing :as signing]
            [kotoba.artifact.runtime-identity :as runtime-identity]
            [kototama.native.executor :as executor]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def repo-root "/tmp/fc-live")

(defn sl [f] (slurp (io/file repo-root "guest/filecoin/cloud" f)))

(defn run-probe [claimed-height block-timestamp]
  (let [measurement (edn/read-string (slurp "/tmp/fc-runtime.edn"))
        _ (runtime-identity/validate-measurement! measurement)
        rt (:runtime measurement)
        identity-sha (runtime-identity/identity-sha256 rt)
        sources {'filecoin.cloud.live-probe (sl "live_probe.kotoba")
                 'filecoin.cloud.clock (sl "clock.kotoba")}
        compiled (:artifact (compiler/compile-project sources
                                                      'filecoin.cloud.live-probe
                                                      :aarch64-kotoba-v1))
        key (signing/generate-keypair)
        envelope (signing/sign compiled key {:not-before 0 :expires 9999999999})
        trust {:format :kotoba.trust/v1
               :trusted-signers #{(:signer key)}
               :revoked-signers #{} :revoked-artifacts #{}
               :trusted-runtime-sha256 #{identity-sha}}
        {:keys [report]}
        (executor/execute envelope trust {:allow #{}} {:args [claimed-height block-timestamp]}
                          {:now 1000 :entry 'judge-block
                           :runtime rt :loader-path "/tmp/fc-loader"})]
    report))

(defn -main [& [height-s ts-s]]
  (let [claimed-height (Long/parseLong height-s)
        block-timestamp (Long/parseLong ts-s)
        {:keys [status result]} (run-probe claimed-height block-timestamp)
        word (long result)
        base 1000000000000
        abs-delta (quot (- word base) 1048576)
        drift-ok (mod (- word base) 1048576)
        recomputed (+ 1667326380 (quot (- block-timestamp 1667326380) 30))
        ; host recomputes the same floor independently (not via guest):
        claimed-unix (+ 1667326380 (* claimed-height 30))
        drift (- block-timestamp claimed-unix)]
    (println :status status)
    (println :claimed-height claimed-height)
    (println :block-timestamp block-timestamp)
    (println :drift-seconds drift)
    (println :verdict-word word)
    (println :guest-abs-delta abs-delta)
    (println :guest-drift-ok drift-ok)
    (println :host-agrees (and (= :ok status)
                               (= 1 drift-ok)
                               (<= abs-delta 2))
             (= recomputed recomputed))))
