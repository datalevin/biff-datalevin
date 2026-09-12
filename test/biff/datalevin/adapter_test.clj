(ns biff.datalevin.adapter-test
  (:require [biff.datalevin.adapter :as dl]
            [biff.datalevin.test-helpers :as helpers]
            [clojure.test :refer [deftest is testing]]
            [com.biffweb.core :as biff.core]
            [com.biffweb.fx :as biff.fx]
            [com.biffweb.graph :as biff.graph]
            [datalevin.core :as d])
  (:import [java.util Date UUID]))

(def ^:private modules nil)

(defn- start-test-system
  [{:keys [db-path schema snapshot? initial-system extra-modules]}]
  (alter-var-root #'modules
                  (constantly
                   (vec (concat [(biff.fx/module)
                                 (biff.graph/module)
                                 (dl/module
                                  (cond-> {:biff.datalevin/db-path db-path
                                           :biff.datalevin/schema (or schema helpers/test-schema)}
                                    (some? snapshot?)
                                    (assoc :biff.datalevin/snapshot? snapshot?)))]
                                extra-modules))))
  (biff.core/start (or initial-system {}) #'modules [:biff.datalevin/module]))

(defn- stop-test-system [system temp-dir]
  (biff.core/stop system)
  (helpers/delete-dir temp-dir))

(deftest module-lifecycle-test
  (testing "starts and stops a Datalevin connection"
    (let [temp-dir (helpers/create-temp-dir)
          system   (start-test-system {:db-path temp-dir})
          conn     (:biff.datalevin/conn system)]
      (try
        (is (some? conn))
        (is (not (d/closed? conn)))
        (is (fn? (:biff.core/kv-get system)))
        (is (fn? (:biff.core/kv-set system)))
        (is (fn? (:biff.core/kv-list system)))
        (is (fn? (:biff.core/wrap-db-snapshot system)))
        (finally
          (stop-test-system system temp-dir)))
      (is (d/closed? conn))))

  (testing "throws without db-path"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing required :biff.datalevin/db-path"
                          (start-test-system {:db-path nil})))))

(deftest kv-store-test
  (let [temp-dir (helpers/create-temp-dir)
        system   (start-test-system {:db-path temp-dir})]
    (try
      (testing "round-trips EDN values"
        (let [value {:colors #{"red" "blue"}
                     :when   (Date. 0)
                     :id     (UUID/randomUUID)}]
          ((:biff.core/kv-set system) system :app/settings "theme" value)
          (is (= value ((:biff.core/kv-get system) system :app/settings "theme")))))

      (testing "nil value deletes the key"
        ((:biff.core/kv-set system) system :app/settings "theme" nil)
        (is (nil? ((:biff.core/kv-get system) system :app/settings "theme"))))

      (testing "lists keys sorted, with optional prefix"
        (doseq [k ["b" "a" "ab"]]
          ((:biff.core/kv-set system) system :app/settings k k))
        (is (= ["a" "ab" "b"] ((:biff.core/kv-list system) system :app/settings)))
        (is (= ["a" "ab"] ((:biff.core/kv-list system) system :app/settings "a"))))

      (testing "namespaces are isolated"
        ((:biff.core/kv-set system) system :app/other "a" 1)
        (is (= ["a"] ((:biff.core/kv-list system) system :app/other)))
        (is (= ["a" "ab" "b"] ((:biff.core/kv-list system) system :app/settings))))
      (finally
        (stop-test-system system temp-dir)))))

(deftest on-tx-test
  (let [temp-dir (helpers/create-temp-dir)
        calls    (atom 0)
        system   (start-test-system
                  {:db-path        temp-dir
                   :initial-system {:biff.core/on-tx (fn [_] (swap! calls inc))}})]
    (try
      (testing "called after execute-tx"
        (dl/execute-tx system [{:user/id    (UUID/randomUUID)
                                :user/email "ontx@example.com"}])
        (is (= 1 @calls)))

      (testing "called after kv-set"
        ((:biff.core/kv-set system) system :app/settings "k" "v")
        (is (= 2 @calls)))

      (testing "called after direct Datalevin transactions"
        (d/transact! (:biff.datalevin/conn system)
                     [{:user/id (UUID/randomUUID) :user/email "raw@example.com"}])
        (is (= 3 @calls)))
      (finally
        (stop-test-system system temp-dir)))))

(deftest wrap-db-snapshot-test
  (let [temp-dir (helpers/create-temp-dir)
        system   (start-test-system {:db-path temp-dir})
        results  (atom [])
        find-after '[:find ?e .
                     :where [?e :user/email "after@example.com"]]
        writer   (future
                   (Thread/sleep 50)
                   (dl/execute-tx system [{:user/id    (UUID/randomUUID)
                                           :user/email "after@example.com"}]))
        wrapped  ((:biff.core/wrap-db-snapshot system)
                  (fn [ctx]
                    (swap! results conj (dl/q ctx find-after))
                    (Thread/sleep 200)
                    (swap! results conj (dl/q ctx find-after))))]
    (try
      (wrapped system)
      (is (= [nil nil] @results))
      @writer
      (is (some? (dl/q system find-after)))
      (finally
        (stop-test-system system temp-dir)))))

(deftest snapshot-opt-out-test
  (let [temp-dir (helpers/create-temp-dir)
        system   (start-test-system {:db-path   temp-dir
                                     :snapshot? false})]
    (try
      (is (not (contains? system :biff.core/wrap-db-snapshot)))
      (dl/execute-tx system [{:user/id    (UUID/randomUUID)
                              :user/email "no-snapshot@example.com"}])
      (is (some? (dl/q system '[:find ?e .
                                :where [?e :user/email "no-snapshot@example.com"]])))
      (finally
        (stop-test-system system temp-dir)))))

(deftest graph-resolvers-test
  (let [temp-dir   (helpers/create-temp-dir)
        system     (start-test-system {:db-path temp-dir})
        user-id    (UUID/randomUUID)
        session-id (UUID/randomUUID)]
    (try
      (dl/execute-tx system [{:user/id    user-id
                              :user/email "graph@example.com"
                              :user/role  :admin}
                             {:session/id         session-id
                              :session/user       [:user/id user-id]
                              :session/expires-at (Date.)}])
      (testing "scalar attributes"
        (is (= {:user/email "graph@example.com"
                :user/role  :admin}
               (biff.graph/query system {:user/id user-id}
                                 [:user/email :user/role]))))
      (testing "ref attributes are joins"
        (is (= {:session/user {:user/email "graph@example.com"}}
               (biff.graph/query system {:session/id session-id}
                                 [{:session/user [:user/email]}]))))
      (finally
        (stop-test-system system temp-dir)))))

(def pet-schema
  (merge helpers/test-schema
         {:pet/id      {:db/valueType :db.type/uuid
                        :db/unique    :db.unique/identity}
          :pet/name    {:db/valueType :db.type/string}
          :user/pet-id {:db/valueType :db.type/ref}}))

(deftest ref-with-id-suffix-test
  (let [temp-dir (helpers/create-temp-dir)
        system   (start-test-system {:db-path temp-dir
                                     :schema  pet-schema})
        user-id  (UUID/randomUUID)
        pet-id   (UUID/randomUUID)]
    (try
      (dl/execute-tx system [{:pet/id   pet-id
                              :pet/name "Rex"}
                             {:user/id     user-id
                              :user/email  "pet-owner@example.com"
                              :user/pet-id [:pet/id pet-id]}])
      (is (= {:user/pet-id pet-id
              :user/pet    {:pet/name "Rex"}}
             (biff.graph/query system {:user/id user-id}
                               [:user/pet-id {:user/pet [:pet/name]}])))
      (finally
        (stop-test-system system temp-dir)))))

(def group-schema
  (merge helpers/test-schema
         {:group/id      {:db/valueType :db.type/uuid
                          :db/unique    :db.unique/identity}
          :group/name    {:db/valueType :db.type/string}
          :group/members {:db/valueType       :db.type/ref
                          :db/cardinality     :db.cardinality/many
                          :biff.datalevin/ref :user/id}}))

(deftest many-ref-test
  (let [temp-dir (helpers/create-temp-dir)
        system   (start-test-system {:db-path temp-dir
                                     :schema  group-schema})
        group-id (UUID/randomUUID)
        alice    (UUID/randomUUID)
        bob      (UUID/randomUUID)]
    (try
      (dl/execute-tx system [{:user/id    alice
                              :user/email "alice@example.com"}
                             {:user/id    bob
                              :user/email "bob@example.com"}
                             {:group/id      group-id
                              :group/name    "Admins"
                              :group/members [[:user/id alice]
                                              [:user/id bob]]}])
      (let [{:group/keys [members]}
            (biff.graph/query system {:group/id group-id}
                              [{:group/members [:user/email]}])]
        (is (= #{{:user/email "alice@example.com"}
                 {:user/email "bob@example.com"}}
               (set members))))
      (finally
        (stop-test-system system temp-dir)))))

(deftest schema-from-other-modules-test
  (let [temp-dir (helpers/create-temp-dir)
        system   (start-test-system
                  {:db-path       temp-dir
                   :extra-modules [{:biff.datalevin/schema
                                    {:widget/id   {:db/valueType :db.type/uuid
                                                   :db/unique    :db.unique/identity}
                                     :widget/name {:db/valueType :db.type/string}}}]})]
    (try
      (is (contains? (:biff.datalevin/schema system) :widget/id))
      (dl/execute-tx system [{:widget/id   (UUID/randomUUID)
                              :widget/name "Widget"}])
      (is (some? (dl/q system '[:find ?e .
                                :where [?e :widget/name "Widget"]])))
      (finally
        (stop-test-system system temp-dir)))))

(deftest fx-handlers-test
  (let [temp-dir (helpers/create-temp-dir)
        system   (start-test-system {:db-path temp-dir})
        handlers ((:biff.fx/get-handlers system))]
    (try
      (is (fn? (:biff.datalevin.fx/execute-tx handlers)))
      (is (fn? (:biff.datalevin.fx/q handlers)))
      (let [user-id (UUID/randomUUID)]
        ((:biff.datalevin.fx/execute-tx handlers)
         system
         [{:user/id user-id :user/email "fx@example.com"}])
        (is (= #{[user-id]}
               ((:biff.datalevin.fx/q handlers)
                system
                '[:find ?id :where [?e :user/id ?id]]))))
      (finally
        (stop-test-system system temp-dir)))))
