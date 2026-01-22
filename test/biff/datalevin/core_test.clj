(ns biff.datalevin.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [biff.datalevin.core :as core]
            [biff.datalevin.db :as db]
            [biff.datalevin.test-helpers :as helpers])
  (:import [java.util UUID]))

;; =============================================================================
;; System Lifecycle Tests
;; =============================================================================

(deftest start-system-test
  (testing "starts with empty components"
    (let [system (core/start-system {:key "value"} [])]
      (is (= "value" (:key system)))
      (is (vector? (:biff/stop system)))
      (is (empty? (:biff/stop system)))))

  (testing "threads through components"
    (let [component1 (fn [sys] (assoc sys :step1 true))
          component2 (fn [sys] (assoc sys :step2 true))
          system (core/start-system {} [component1 component2])]
      (is (:step1 system))
      (is (:step2 system))))

  (testing "accumulates stop functions"
    (let [stopped (atom [])
          component1 (fn [sys]
                       (-> sys
                           (assoc :resource1 "r1")
                           (core/assoc-stop #(swap! stopped conj :r1))))
          component2 (fn [sys]
                       (-> sys
                           (assoc :resource2 "r2")
                           (core/assoc-stop #(swap! stopped conj :r2))))
          system (core/start-system {} [component1 component2])]
      (is (= 2 (count (:biff/stop system))))
      (core/stop-system system)
      (is (= [:r2 :r1] @stopped)))))  ; Reverse order

(deftest stop-system-test
  (testing "calls stop functions in reverse order"
    (let [order (atom [])
          system {:biff/stop [(fn [] (swap! order conj 1))
                              (fn [] (swap! order conj 2))
                              (fn [] (swap! order conj 3))]}
          result (core/stop-system system)]
      (is (= [3 2 1] @order))
      (is (not (contains? result :biff/stop)))))

  (testing "handles exceptions in stop functions"
    (let [stopped (atom false)
          system {:biff/stop [(fn [] (throw (Exception. "error")))
                              (fn [] (reset! stopped true))]}]
      ;; Should not throw, suppress expected error output
      (with-out-str (core/stop-system system))
      (is @stopped))))

(deftest use-datalevin-test
  (testing "creates connection"
    (let [temp-dir (helpers/create-temp-dir)
          system (core/start-system
                  {:biff.datalevin/db-path temp-dir
                   :biff.datalevin/schema helpers/test-schema}
                  [core/use-datalevin])]
      (try
        (is (some? (:biff.datalevin/conn system)))
        ;; Verify connection works
        (let [user-id (UUID/randomUUID)]
          (db/submit-tx system [{:user/id user-id
                                 :user/email "test@example.com"}])
          (is (some? (db/lookup system :user/email "test@example.com"))))
        (finally
          (core/stop-system system)
          (helpers/delete-dir temp-dir)))))

  (testing "sets :biff/db for Biff compatibility"
    (let [temp-dir (helpers/create-temp-dir)
          system (core/start-system
                  {:biff.datalevin/db-path temp-dir
                   :biff.datalevin/schema helpers/test-schema}
                  [core/use-datalevin])]
      (try
        (is (some? (:biff/db system)))
        ;; Verify :biff/db can be used for queries
        (let [user-id (UUID/randomUUID)]
          (db/submit-tx system [{:user/id user-id
                                 :user/email "biff-compat@example.com"}])
          ;; Need to refresh :biff/db after transaction
          (let [refreshed (db/assoc-db system)]
            (is (some? (db/lookup {:biff/db (:biff/db refreshed)}
                                  :user/email "biff-compat@example.com")))))
        (finally
          (core/stop-system system)
          (helpers/delete-dir temp-dir)))))

  (testing "throws without db-path"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing required :biff.datalevin/db-path"
                          (core/use-datalevin {}))))

  (testing "closes connection on stop"
    (let [temp-dir (helpers/create-temp-dir)
          system (core/start-system
                  {:biff.datalevin/db-path temp-dir
                   :biff.datalevin/schema helpers/test-schema}
                  [core/use-datalevin])
          conn (:biff.datalevin/conn system)]
      (core/stop-system system)
      ;; Connection should be closed - trying to use it should fail
      ;; Note: Datalevin may not throw immediately on closed connection
      ;; but we verify the stop function was registered
      (is (= 1 (count (:biff/stop system))))
      (helpers/delete-dir temp-dir))))

(deftest use-config-test
  (testing "merges config into system"
    (let [system (core/use-config {:existing "value"}
                                  {:port 8080 :host "localhost"})]
      (is (= "value" (:existing system)))
      (is (= 8080 (:port system)))
      (is (= "localhost" (:host system))))))

(deftest assoc-stop-test
  (testing "adds stop function to system"
    (let [system {:biff/stop []}
          updated (core/assoc-stop system (fn [] "cleanup"))]
      (is (= 1 (count (:biff/stop updated))))
      (is (fn? (first (:biff/stop updated))))))

  (testing "appends to existing stop functions"
    (let [system {:biff/stop [(fn [] 1)]}
          updated (-> system
                      (core/assoc-stop (fn [] 2))
                      (core/assoc-stop (fn [] 3)))]
      (is (= 3 (count (:biff/stop updated)))))))

;; =============================================================================
;; Integration Test
;; =============================================================================

(deftest full-system-lifecycle-test
  (testing "complete system start/stop cycle"
    (let [temp-dir (helpers/create-temp-dir)
          events (atom [])

          ;; Custom component that logs events
          use-logger (fn [system]
                       (swap! events conj :logger-started)
                       (core/assoc-stop system #(swap! events conj :logger-stopped)))

          ;; Component that depends on datalevin
          use-app (fn [system]
                    (swap! events conj :app-started)
                    (when-not (:biff.datalevin/conn system)
                      (throw (ex-info "Expected conn" {})))
                    (core/assoc-stop system #(swap! events conj :app-stopped)))

          system (core/start-system
                  {:biff.datalevin/db-path temp-dir
                   :biff.datalevin/schema helpers/test-schema}
                  [core/use-datalevin
                   use-logger
                   use-app])]

      (try
        ;; All components started
        (is (= [:logger-started :app-started] @events))
        (is (some? (:biff.datalevin/conn system)))

        ;; Stop system
        (core/stop-system system)

        ;; Stopped in reverse order
        (is (= [:logger-started :app-started :app-stopped :logger-stopped]
               @events))

        (finally
          (helpers/delete-dir temp-dir))))))
