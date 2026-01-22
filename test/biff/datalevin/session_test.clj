(ns biff.datalevin.session-test
  (:require [clojure.test :refer [deftest is testing]]
            [biff.datalevin.session :as session]
            [biff.datalevin.auth :as auth]
            [biff.datalevin.db :as db]
            [biff.datalevin.test-helpers :refer [with-temp-conn]])
  (:import [java.util UUID Date]))

;; =============================================================================
;; Session Management Tests
;; =============================================================================

(deftest create-session-test
  (testing "creates session with default expiration"
    (let [user-id (UUID/randomUUID)
          {:keys [session-id tx]} (session/create-session user-id)]
      (is (uuid? session-id))
      (is (= session-id (:session/id tx)))
      (is (= [:user/id user-id] (:session/user tx)))
      (is (instance? Date (:session/expires-at tx)))
      ;; Default 7 days expiration
      (let [expires-at (:session/expires-at tx)
            expected-min (Date. (+ (System/currentTimeMillis) (* 6 24 60 60 1000)))]
        (is (.after expires-at expected-min)))))

  (testing "supports custom expiration"
    (let [user-id (UUID/randomUUID)
          {:keys [tx]} (session/create-session user-id :expires-in-hours 1)
          expires-at (:session/expires-at tx)
          expected-max (Date. (+ (System/currentTimeMillis) (* 2 60 60 1000)))]
      (is (.before expires-at expected-max)))))

(deftest get-session-test
  (testing "retrieves valid session with user data"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)
            user-tx (auth/create-user-tx {:user/id user-id
                                          :user/email "session@example.com"
                                          :user/username "sessionuser"
                                          :password "pass"})]
        ;; Create user first (required before creating session with ref)
        (db/submit-tx conn [user-tx])
        (let [{:keys [session-id tx]} (session/create-session user-id)]
          (db/submit-tx conn [tx])
          (let [session (session/get-session conn session-id)]
            (is (some? session))
            (is (= session-id (:session/id session)))
            (is (some? (:session/user session)))
            (is (= user-id (get-in session [:session/user :user/id]))))))))

  (testing "returns nil for expired session"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)
            session-id (UUID/randomUUID)
            user-tx (auth/create-user-tx {:user/id user-id
                                          :user/email "expired-session@example.com"
                                          :password "pass"})]
        ;; Create user first
        (db/submit-tx conn [user-tx])
        (let [expired-tx {:session/id session-id
                          :session/user [:user/id user-id]
                          :session/expires-at (Date. 0)}]
          (db/submit-tx conn [expired-tx])
          (is (nil? (session/get-session conn session-id)))))))

  (testing "returns nil for nonexistent session"
    (with-temp-conn [conn]
      (is (nil? (session/get-session conn (UUID/randomUUID)))))))

(deftest get-session-user-test
  (testing "retrieves user from session"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)
            user-tx (auth/create-user-tx {:user/id user-id
                                          :user/email "getuser@example.com"
                                          :user/username "getuser"
                                          :password "pass"})]
        ;; Create user first
        (db/submit-tx conn [user-tx])
        (let [{:keys [session-id tx]} (session/create-session user-id)]
          (db/submit-tx conn [tx])
          (let [user (session/get-session-user conn session-id)]
            (is (some? user))
            (is (= user-id (:user/id user)))
            (is (= "getuser@example.com" (:user/email user))))))))

  (testing "returns nil for invalid session"
    (with-temp-conn [conn]
      (is (nil? (session/get-session-user conn (UUID/randomUUID)))))))

(deftest delete-session-tx-test
  (testing "deletes session"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)
            user-tx (auth/create-user-tx {:user/id user-id
                                          :user/email "delete-session@example.com"
                                          :password "pass"})]
        ;; Create user first
        (db/submit-tx conn [user-tx])
        (let [{:keys [session-id tx]} (session/create-session user-id)]
          (db/submit-tx conn [tx])
          (is (some? (session/get-session conn session-id)))
          (when-let [delete-tx (session/delete-session-tx conn session-id)]
            (db/submit-tx conn [delete-tx]))
          (is (nil? (session/get-session conn session-id))))))))

(deftest delete-user-sessions-tx-test
  (testing "deletes all user sessions"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)
            user-tx (auth/create-user-tx {:user/id user-id
                                          :user/email "multi-session@example.com"
                                          :password "pass"})]
        ;; Create user first
        (db/submit-tx conn [user-tx])
        (let [s1 (session/create-session user-id)
              s2 (session/create-session user-id)
              s3 (session/create-session user-id)]
          (db/submit-tx conn [(:tx s1) (:tx s2) (:tx s3)])
          (is (some? (session/get-session conn (:session-id s1))))
          (is (some? (session/get-session conn (:session-id s2))))
          (is (some? (session/get-session conn (:session-id s3))))
          (let [delete-txs (session/delete-user-sessions-tx conn user-id)]
            (is (= 3 (count delete-txs)))
            (db/submit-tx conn delete-txs))
          (is (nil? (session/get-session conn (:session-id s1))))
          (is (nil? (session/get-session conn (:session-id s2))))
          (is (nil? (session/get-session conn (:session-id s3)))))))))

(deftest cleanup-expired-sessions-tx-test
  (testing "cleans up expired sessions only"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)
            user-tx (auth/create-user-tx {:user/id user-id
                                          :user/email "cleanup@example.com"
                                          :password "pass"})]
        ;; Create user first
        (db/submit-tx conn [user-tx])
        (let [;; Valid session
              valid-session (session/create-session user-id)
              ;; Expired session
              expired-id (UUID/randomUUID)
              expired-tx {:session/id expired-id
                          :session/user [:user/id user-id]
                          :session/expires-at (Date. 0)}]
          (db/submit-tx conn [(:tx valid-session) expired-tx])
          (let [cleanup-txs (session/cleanup-expired-sessions-tx conn)]
            (is (= 1 (count cleanup-txs)))
            (db/submit-tx conn cleanup-txs))
          ;; Valid session still exists
          (is (some? (session/get-session conn (:session-id valid-session))))
          ;; Expired session removed (but was already returning nil due to expiration check)
          (is (nil? (db/lookup conn :session/id expired-id))))))))

;; =============================================================================
;; JWT Token Tests
;; =============================================================================

(deftest create-session-token-test
  (testing "creates JWT token"
    (let [session-id (UUID/randomUUID)
          secret "test-secret-key-32-bytes-long!!"
          token (session/create-session-token session-id {:secret secret})]
      (is (string? token))
      (is (.contains token ".")))))  ; JWT has dots

(deftest verify-session-token-test
  (testing "verifies valid token"
    (let [session-id (UUID/randomUUID)
          secret "test-secret-key-32-bytes-long!!"
          token (session/create-session-token session-id {:secret secret})]
      (is (= session-id (session/verify-session-token token secret)))))

  (testing "returns nil for invalid token"
    (is (nil? (session/verify-session-token "invalid.token.here" "secret"))))

  (testing "returns nil for wrong secret"
    (let [session-id (UUID/randomUUID)
          token (session/create-session-token session-id {:secret "secret1"})]
      (is (nil? (session/verify-session-token token "secret2"))))))

;; =============================================================================
;; Ring Session Store Tests
;; =============================================================================

(deftest datalevin-session-store-test
  (testing "reads and writes sessions"
    (with-temp-conn [conn]
      (let [store (session/datalevin-session-store conn)
            user-id (UUID/randomUUID)
            user-tx (auth/create-user-tx {:user/id user-id
                                          :user/email "store@example.com"
                                          :password "pass"})]
        ;; Create user first
        (db/submit-tx conn [user-tx])

        ;; Write session
        (let [session-id (.write-session store nil {:user {:user/id user-id}})]
          (is (string? session-id))

          ;; Read session - note: the store returns the session user, not the full user
          (let [session (.read-session store session-id)]
            (is (some? session))
            (is (some? (:user session))))

          ;; Delete session
          (.delete-session store session-id)
          (is (nil? (.read-session store session-id)))))))

  (testing "returns nil for invalid session id"
    (with-temp-conn [conn]
      (let [store (session/datalevin-session-store conn)]
        (is (nil? (.read-session store "invalid-uuid")))
        (is (nil? (.read-session store nil)))))))
