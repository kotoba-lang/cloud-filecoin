;; host/verify.clj — run the filecoin.cloud guest golden vectors natively.
;;
;; Three measured steps, the screen-library route:
;;   1. compile the closed module graph (4 modules) with
;;      kotoba.compiler/compile-project, target :aarch64-kotoba-v1
;;   2. sign the artifact with a fresh Ed25519 keypair
;;   3. execute entry `run` through kototama.native.executor/execute,
;;      against a measured runtime (amu measure-runtime) whose identity
;;      sha256 is computed from the measurement itself and pinned in the
;;      trust policy (native execution is never trust-on-first-use)
;;
;; The expected total (32) is a literal in guest/filecoin/cloud/test.kotoba,
;; whose expectations quote the SDK-generated vectors in
;; test/filecoin/cloud/vectors.cljc (cross-checked against the .cljc oracle
;; under nbb before this run).
;;
;; usage (JVM is the test driver only — the guest itself is JVM-free):
;;   cd <amu> && java -cp "/tmp:$(clojure -Spath -M:native-run)" \
;;     clojure.main -e "(require 'filecoin-cloud-verify) (filecoin-cloud-verify/-main)" \
;;     /tmp/fc-runtime.edn /tmp/fc-loader
;; (the driver file must live on the classpath as
;;  /tmp/filecoin_cloud_verify.clj)

(ns filecoin-cloud-verify
  (:require [kotoba.compiler.core :as compiler]
            [kotoba.verifier.signing :as signing]
            [kotoba.artifact.runtime-identity :as runtime-identity]
            [kototama.native.executor :as executor]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def repo-root
  (or (System/getenv "FC_GUEST_ROOT")
      "/Users/junkawasaki/github/wt/cloud-filecoin-kotoba-guest"))

(def modules ["piece_plan" "clock" "pay_allowance" "test"])

(defn sources []
  (into {}
        (map (fn [f]
               [(symbol (str "filecoin.cloud."
                             (clojure.string/replace f "_" "-")))
                (slurp (io/file repo-root "guest/filecoin/cloud" (str f ".kotoba")))]))
        modules))

(defn expected-total [] 32)

(defn run-native [runtime-path loader-path]
  (let [measurement (edn/read-string (slurp runtime-path))
        _ (runtime-identity/validate-measurement! measurement)
        rt (:runtime measurement)
        identity-sha (runtime-identity/identity-sha256 rt)
        compiled (:artifact (compiler/compile-project (sources)
                                                      'filecoin.cloud.test
                                                      :aarch64-kotoba-v1))
        key (signing/generate-keypair)
        envelope (signing/sign compiled key {:not-before 0 :expires 9999999999})
        trust {:format :kotoba.trust/v1
               :trusted-signers #{(:signer key)}
               :revoked-signers #{}
               :revoked-artifacts #{}
               :trusted-runtime-sha256 #{identity-sha}}]
    (println :runtime-identity identity-sha)
    (executor/execute envelope trust {:allow #{}} {:args []}
                      {:now 1000 :entry 'run
                       :runtime rt :loader-path loader-path})))

(defn -main [& args]
  (let [runtime (or (first args) "/tmp/fc-runtime.edn")
        loader (or (second args) "/tmp/fc-loader")
        {:keys [report]} (run-native runtime loader)]
    (println :status (:status report))
    (println :result (:result report))
    (println :expected (expected-total))
    (if (and (= :ok (:status report))
             (= (expected-total) (:result report)))
      (println :verify :pass)
      (do (println :verify :fail) (System/exit 1)))))
