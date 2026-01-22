(ns biff.datalevin.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [biff.datalevin.db :as db]
            [biff.datalevin.test-helpers :refer [with-temp-conn with-temp-system]])
  (:import [java.util UUID Date]))

;; =============================================================================
;; Connection Tests
;; =============================================================================

(deftest get-conn-test
  (testing "creates connection without schema"
    (with-temp-conn [conn :schema {}]
      (is (some? conn))))

  (testing "creates connection with schema"
    (with-temp-conn [conn]
      (is (some? conn)))))

(deftest get-db-test
  (testing "gets db from connection"
    (with-temp-conn [conn]
      (is (some? (db/get-db conn)))))

  (testing "gets db from system map"
    (with-temp-system [system]
      (is (some? (db/get-db system)))))

  (testing "gets db from :biff/db key (Biff compatibility)"
    (with-temp-conn [conn]
      (let [db (db/get-db conn)
            ctx {:biff/db db}]
        (is (= db (db/get-db ctx)))))))

(deftest assoc-db-test
  (testing "refreshes :biff/db from connection"
    (with-temp-system [system]
      (let [user-id (UUID/randomUUID)]
        ;; Initial db snapshot
        (let [db-before (:biff/db system)]
          ;; Add a user
          (db/submit-tx system [{:user/id user-id
                                 :user/email "assoc-db@example.com"}])
          ;; Refresh the db
          (let [refreshed (db/assoc-db system)]
            ;; The refreshed db should see the new user
            (is (some? (db/lookup refreshed :user/email "assoc-db@example.com"))))))))

  (testing "returns ctx unchanged if no connection"
    (let [ctx {:some "data"}]
      (is (= ctx (db/assoc-db ctx))))))

;; =============================================================================
;; Transaction Tests
;; =============================================================================

(deftest submit-tx-test
  (testing "submits basic transaction"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)]
        (db/submit-tx conn [{:user/id user-id
                             :user/email "test@example.com"}])
        (is (some? (db/lookup conn :user/email "test@example.com"))))))

  (testing "resolves :db/now to current date"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)
            before (Date.)]
        (db/submit-tx conn [{:user/id user-id
                             :user/email "now-test@example.com"
                             :user/created-at :db/now}])
        (let [user (db/lookup conn :user/email "now-test@example.com")
              created-at (:user/created-at user)]
          (is (instance? Date created-at))
          (is (not (.before created-at before)))))))

  (testing "resolves :db/uuid to random UUID"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)]
        (db/submit-tx conn [{:user/id user-id
                             :user/email "uuid-test@example.com"}])
        (is (some? (db/lookup conn :user/email "uuid-test@example.com"))))))

  (testing "works with system map"
    (with-temp-system [system]
      (let [user-id (UUID/randomUUID)]
        (db/submit-tx system [{:user/id user-id
                               :user/email "system-test@example.com"}])
        (is (some? (db/lookup system :user/email "system-test@example.com")))))))

(deftest merge-tx-test
  (testing "creates merge transaction"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)]
        (db/submit-tx conn [{:user/id user-id
                             :user/email "merge@example.com"
                             :user/username "original"}])
        ;; Use the unique attribute as db/id for updates
        (db/submit-tx conn [(db/merge-tx [:user/id user-id]
                                         {:user/username "updated"})])
        (let [user (db/lookup conn :user/email "merge@example.com")]
          (is (= "updated" (:user/username user)))
          (is (= "merge@example.com" (:user/email user))))))))

(deftest delete-tx-test
  (testing "deletes entity"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)]
        (db/submit-tx conn [{:user/id user-id
                             :user/email "delete@example.com"}])
        (is (some? (db/lookup conn :user/email "delete@example.com")))
        (db/submit-tx conn [(db/delete-tx [:user/id user-id])])
        (is (nil? (db/lookup conn :user/email "delete@example.com")))))))

;; =============================================================================
;; Query Tests
;; =============================================================================

(deftest q-test
  (testing "runs query with connection"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)]
        (db/submit-tx conn [{:user/id user-id
                             :user/email "query@example.com"}])
        (let [result (db/q '[:find ?e .
                             :where [?e :user/email "query@example.com"]]
                           (db/get-db conn))]
          (is (some? result))))))

  (testing "runs query with system map"
    (with-temp-system [system]
      (let [user-id (UUID/randomUUID)]
        (db/submit-tx system [{:user/id user-id
                               :user/email "query-system@example.com"}])
        (let [result (db/q '[:find ?e .
                             :where [?e :user/email "query-system@example.com"]]
                           system)]
          (is (some? result)))))))

(deftest lookup-test
  (testing "finds entity by attribute"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)]
        (db/submit-tx conn [{:user/id user-id
                             :user/email "lookup@example.com"
                             :user/username "lookupuser"}])
        (let [user (db/lookup conn :user/email "lookup@example.com")]
          (is (= "lookup@example.com" (:user/email user)))
          (is (= "lookupuser" (:user/username user)))))))

  (testing "returns nil when not found"
    (with-temp-conn [conn]
      (is (nil? (db/lookup conn :user/email "nonexistent@example.com")))))

  (testing "supports custom pull expression"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)]
        (db/submit-tx conn [{:user/id user-id
                             :user/email "pull@example.com"
                             :user/username "pulluser"}])
        (let [user (db/lookup conn :user/email "pull@example.com"
                              [:user/email])]
          (is (= "pull@example.com" (:user/email user)))
          (is (nil? (:user/username user))))))))

(deftest lookup-all-test
  (testing "finds all matching entities"
    (with-temp-conn [conn]
      (let [id1 (UUID/randomUUID)
            id2 (UUID/randomUUID)]
        (db/submit-tx conn [{:user/id id1
                             :user/email "admin1@example.com"
                             :user/role :admin}
                            {:user/id id2
                             :user/email "admin2@example.com"
                             :user/role :admin}])
        (let [admins (db/lookup-all conn :user/role :admin)]
          (is (= 2 (count admins)))
          (is (every? #(= :admin (:user/role %)) admins))))))

  (testing "returns empty vector when none found"
    (with-temp-conn [conn]
      (is (= [] (db/lookup-all conn :user/role :superadmin))))))

(deftest lookup-id-test
  (testing "returns entity id"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)]
        (db/submit-tx conn [{:user/id user-id
                             :user/email "lookup-id@example.com"}])
        (is (some? (db/lookup-id conn :user/email "lookup-id@example.com"))))))

  (testing "returns nil when not found"
    (with-temp-conn [conn]
      (is (nil? (db/lookup-id conn :user/email "nope@example.com"))))))

(deftest entity-exists-test
  (testing "returns true when entity exists"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)]
        (db/submit-tx conn [{:user/id user-id
                             :user/email "exists@example.com"}])
        (is (db/entity-exists? conn :user/email "exists@example.com")))))

  (testing "returns false when entity doesn't exist"
    (with-temp-conn [conn]
      (is (not (db/entity-exists? conn :user/email "nope@example.com"))))))

(deftest pull-test
  (testing "pulls entity attributes"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)]
        (db/submit-tx conn [{:user/id user-id
                             :user/email "pull-test@example.com"
                             :user/username "pulltest"}])
        (let [user (db/pull conn [:user/email :user/username] [:user/id user-id])]
          (is (= "pull-test@example.com" (:user/email user)))
          (is (= "pulltest" (:user/username user))))))))
