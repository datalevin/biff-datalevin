(ns biff.datalevin.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [biff.datalevin.auth :as auth]
            [biff.datalevin.db :as db]
            [biff.datalevin.test-helpers :refer [with-temp-conn]])
  (:import [java.util UUID Date]))

;; =============================================================================
;; Password Hashing Tests
;; =============================================================================

(deftest hash-password-test
  (testing "hashes password"
    (let [hash (auth/hash-password "mysecret")]
      (is (string? hash))
      (is (not= "mysecret" hash))
      (is (.startsWith hash "bcrypt+sha512$"))))

  (testing "different passwords produce different hashes"
    (let [hash1 (auth/hash-password "password1")
          hash2 (auth/hash-password "password2")]
      (is (not= hash1 hash2))))

  (testing "same password produces different hashes (salted)"
    (let [hash1 (auth/hash-password "samepassword")
          hash2 (auth/hash-password "samepassword")]
      (is (not= hash1 hash2)))))

(deftest verify-password-test
  (testing "verifies correct password"
    (let [hash (auth/hash-password "correctpassword")]
      (is (auth/verify-password "correctpassword" hash))))

  (testing "rejects incorrect password"
    (let [hash (auth/hash-password "correctpassword")]
      (is (not (auth/verify-password "wrongpassword" hash)))))

  (testing "handles nil password"
    (is (not (auth/verify-password nil "somehash"))))

  (testing "handles nil hash"
    (is (not (auth/verify-password "password" nil)))))

;; =============================================================================
;; User Management Tests
;; =============================================================================

(deftest create-user-tx-test
  (testing "creates user transaction with hashed password"
    (let [tx (auth/create-user-tx {:user/email "test@example.com"
                                   :user/username "testuser"
                                   :password "secret123"})]
      (is (contains? tx :user/id))
      (is (= "test@example.com" (:user/email tx)))
      (is (= "testuser" (:user/username tx)))
      (is (contains? tx :user/password-hash))
      (is (not (contains? tx :password)))
      (is (auth/verify-password "secret123" (:user/password-hash tx)))))

  (testing "uses provided user-id"
    (let [user-id (UUID/randomUUID)
          tx (auth/create-user-tx {:user/id user-id
                                   :user/email "custom@example.com"
                                   :password "pass"})]
      (is (= user-id (:user/id tx))))))

(deftest authenticate-user-test
  (testing "authenticates valid credentials"
    (with-temp-conn [conn]
      (let [tx (auth/create-user-tx {:user/email "auth@example.com"
                                     :user/username "authuser"
                                     :password "correctpass"})]
        (db/submit-tx conn [tx])
        (let [user (auth/authenticate-user conn "auth@example.com" "correctpass")]
          (is (some? user))
          (is (= "auth@example.com" (:user/email user)))
          (is (not (contains? user :user/password-hash)))))))

  (testing "rejects invalid password"
    (with-temp-conn [conn]
      (let [tx (auth/create-user-tx {:user/email "auth2@example.com"
                                     :password "correctpass"})]
        (db/submit-tx conn [tx])
        (is (nil? (auth/authenticate-user conn "auth2@example.com" "wrongpass"))))))

  (testing "rejects nonexistent user"
    (with-temp-conn [conn]
      (is (nil? (auth/authenticate-user conn "nope@example.com" "anypass"))))))

(deftest find-user-by-email-test
  (testing "finds existing user"
    (with-temp-conn [conn]
      (let [tx (auth/create-user-tx {:user/email "find@example.com"
                                     :user/username "finduser"
                                     :password "pass"})]
        (db/submit-tx conn [tx])
        (let [user (auth/find-user-by-email conn "find@example.com")]
          (is (some? user))
          (is (= "find@example.com" (:user/email user)))
          (is (not (contains? user :user/password-hash)))))))

  (testing "returns nil for nonexistent user"
    (with-temp-conn [conn]
      (is (nil? (auth/find-user-by-email conn "nope@example.com"))))))

(deftest find-user-by-id-test
  (testing "finds existing user"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)
            tx (auth/create-user-tx {:user/id user-id
                                     :user/email "findid@example.com"
                                     :password "pass"})]
        (db/submit-tx conn [tx])
        (let [user (auth/find-user-by-id conn user-id)]
          (is (some? user))
          (is (= user-id (:user/id user)))))))

  (testing "returns nil for nonexistent user"
    (with-temp-conn [conn]
      (is (nil? (auth/find-user-by-id conn (UUID/randomUUID)))))))

;; =============================================================================
;; OAuth Tests
;; =============================================================================

(deftest github-authorize-url-test
  (testing "generates authorization URL"
    (let [url (auth/github-authorize-url
               {:client-id "test-client-id"
                :redirect-uri "http://localhost:8080/callback"})]
      (is (.contains url "github.com/login/oauth/authorize"))
      (is (.contains url "client_id=test-client-id"))
      (is (.contains url "redirect_uri="))
      (is (.contains url "scope=user%3Aemail"))))

  (testing "includes custom scope"
    (let [url (auth/github-authorize-url
               {:client-id "test"
                :redirect-uri "http://localhost/cb"
                :scope "user:email repo"})]
      (is (.contains url "scope=user%3Aemail+repo"))))

  (testing "includes state parameter"
    (let [url (auth/github-authorize-url
               {:client-id "test"
                :redirect-uri "http://localhost/cb"
                :state "random-state-123"})]
      (is (.contains url "state=random-state-123")))))

(deftest github-find-or-create-user-tx-test
  (testing "creates transaction from GitHub user data"
    (let [tx (auth/github-find-or-create-user-tx
              {:id 12345
               :login "octocat"
               :email "octocat@github.com"
               :avatar_url "https://github.com/images/octocat.png"})]
      (is (= 12345 (:user/github-id tx)))
      (is (= "octocat" (:user/github-username tx)))
      (is (= "octocat@github.com" (:user/email tx)))
      (is (= "https://github.com/images/octocat.png" (:user/avatar-url tx)))
      (is (contains? tx :user/id))
      (is (= :db/now (:user/created-at tx))))))

(deftest find-user-by-github-id-test
  (testing "finds user by GitHub ID"
    (with-temp-conn [conn]
      (let [tx (auth/github-find-or-create-user-tx
                {:id 99999
                 :login "testgh"
                 :email "gh@example.com"
                 :avatar_url "http://example.com/avatar.png"})]
        (db/submit-tx conn [tx])
        (let [user (auth/find-user-by-github-id conn 99999)]
          (is (some? user))
          (is (= 99999 (:user/github-id user)))
          (is (= "testgh" (:user/github-username user)))))))

  (testing "returns nil for nonexistent GitHub ID"
    (with-temp-conn [conn]
      (is (nil? (auth/find-user-by-github-id conn 1))))))

;; =============================================================================
;; Generic OAuth Tests
;; =============================================================================

(deftest oauth-authorize-url-test
  (testing "generates generic OAuth URL"
    (let [url (auth/oauth-authorize-url
               {:authorize-url "https://provider.com/oauth/authorize"
                :client-id "my-client"
                :redirect-uri "http://localhost/callback"
                :scope "read write"
                :state "csrf-token"})]
      (is (.startsWith url "https://provider.com/oauth/authorize?"))
      (is (.contains url "client_id=my-client"))
      (is (.contains url "redirect_uri="))
      (is (.contains url "response_type=code"))
      (is (.contains url "scope=read+write"))
      (is (.contains url "state=csrf-token"))))

  (testing "supports extra params"
    (let [url (auth/oauth-authorize-url
               {:authorize-url "https://provider.com/auth"
                :client-id "client"
                :redirect-uri "http://localhost/cb"
                :extra-params {:prompt "consent"}})]
      (is (.contains url "prompt=consent")))))

;; =============================================================================
;; Email Verification Tests
;; =============================================================================

(deftest create-verification-token-test
  (testing "creates token and transaction"
    (let [user-id (UUID/randomUUID)
          {:keys [token tx]} (auth/create-verification-token user-id)]
      (is (string? token))
      (is (= 36 (count token)))  ; UUID string length
      (is (= token (:verification-token/token tx)))
      (is (= [:user/id user-id] (:verification-token/user tx)))
      (is (instance? Date (:verification-token/expires-at tx)))))

  (testing "supports custom expiration"
    (let [user-id (UUID/randomUUID)
          {:keys [tx]} (auth/create-verification-token user-id :expires-in-hours 1)
          expires-at (:verification-token/expires-at tx)
          expected-max (Date. (+ (System/currentTimeMillis) (* 2 60 60 1000)))]
      (is (.before expires-at expected-max)))))

(deftest verify-token-test
  (testing "verifies valid token"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)
            user-tx (auth/create-user-tx {:user/id user-id
                                          :user/email "verify@example.com"
                                          :password "pass"})]
        ;; Create user first
        (db/submit-tx conn [user-tx])
        (let [{:keys [token tx]} (auth/create-verification-token user-id)]
          (db/submit-tx conn [tx])
          (is (= user-id (auth/verify-token conn token)))))))

  (testing "returns nil for expired token"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)
            user-tx (auth/create-user-tx {:user/id user-id
                                          :user/email "expired@example.com"
                                          :password "pass"})]
        ;; Create user first
        (db/submit-tx conn [user-tx])
        (let [token (str (UUID/randomUUID))
              expired-tx {:verification-token/token token
                          :verification-token/user [:user/id user-id]
                          :verification-token/expires-at (Date. 0)}]
          (db/submit-tx conn [expired-tx])
          (is (nil? (auth/verify-token conn token)))))))

  (testing "returns nil for nonexistent token"
    (with-temp-conn [conn]
      (is (nil? (auth/verify-token conn "nonexistent-token"))))))

(deftest delete-verification-token-tx-test
  (testing "deletes verification token"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)
            user-tx (auth/create-user-tx {:user/id user-id
                                          :user/email "delete-token@example.com"
                                          :password "pass"})]
        ;; Create user first
        (db/submit-tx conn [user-tx])
        (let [{:keys [token tx]} (auth/create-verification-token user-id)]
          (db/submit-tx conn [tx])
          ;; Token exists
          (is (some? (db/lookup conn :verification-token/token token)))
          ;; Delete it
          (when-let [delete-tx (auth/delete-verification-token-tx conn token)]
            (db/submit-tx conn [delete-tx]))
          ;; Token gone
          (is (nil? (db/lookup conn :verification-token/token token))))))))
