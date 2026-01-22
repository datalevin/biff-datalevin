(ns biff.datalevin.middleware-test
  (:require [clojure.test :refer [deftest is testing]]
            [biff.datalevin.middleware :as mw]
            [biff.datalevin.session :as session]
            [biff.datalevin.auth :as auth]
            [biff.datalevin.db :as db]
            [biff.datalevin.test-helpers :refer [with-temp-conn]])
  (:import [java.util UUID]))

;; =============================================================================
;; Context Injection Tests
;; =============================================================================

(deftest wrap-context-test
  (testing "injects context into request"
    (let [handler (fn [req] {:status 200 :body (:custom-key req)})
          wrapped (mw/wrap-context handler {:custom-key "custom-value"})
          response (wrapped {:uri "/test"})]
      (is (= "custom-value" (:body response)))))

  (testing "preserves existing request keys"
    (let [handler (fn [req] {:status 200 :body [(:uri req) (:injected req)]})
          wrapped (mw/wrap-context handler {:injected "value"})
          response (wrapped {:uri "/path"})]
      (is (= ["/path" "value"] (:body response))))))

;; =============================================================================
;; Authentication Middleware Tests
;; =============================================================================

(deftest wrap-authentication-test
  (testing "authenticates from session"
    (let [user {:user/id (UUID/randomUUID) :user/email "test@example.com"}
          handler (fn [req] {:status 200 :body (:user req)})
          wrapped (mw/wrap-authentication handler {})
          response (wrapped {:session {:user user}})]
      (is (= user (:body response)))))

  (testing "authenticates from JWT header"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)
            secret "test-secret-32-bytes-long!!!!!"
            user-tx (auth/create-user-tx {:user/id user-id
                                          :user/email "jwt@example.com"
                                          :password "pass"})
            {:keys [session-id tx]} (session/create-session user-id)
            _ (db/submit-tx conn [user-tx tx])
            token (session/create-session-token session-id {:secret secret})
            handler (fn [req] {:status 200 :body (:user req)})
            wrapped (mw/wrap-authentication handler {:session-secret secret})
            response (wrapped {:biff.datalevin/conn conn
                               :headers {"authorization" (str "Bearer " token)}})]
        (is (some? (:body response)))
        (is (= user-id (get-in response [:body :user/id]))))))

  (testing "authenticates from cookie"
    (with-temp-conn [conn]
      (let [user-id (UUID/randomUUID)
            secret "test-secret-32-bytes-long!!!!!"
            user-tx (auth/create-user-tx {:user/id user-id
                                          :user/email "cookie@example.com"
                                          :password "pass"})
            {:keys [session-id tx]} (session/create-session user-id)
            _ (db/submit-tx conn [user-tx tx])
            token (session/create-session-token session-id {:secret secret})
            handler (fn [req] {:status 200 :body (:user req)})
            wrapped (mw/wrap-authentication handler {:session-secret secret})
            response (wrapped {:biff.datalevin/conn conn
                               :cookies {"session" {:value token}}})]
        (is (some? (:body response)))
        (is (= user-id (get-in response [:body :user/id]))))))

  (testing "passes through unauthenticated request"
    (let [handler (fn [req] {:status 200 :body (:user req)})
          wrapped (mw/wrap-authentication handler {})
          response (wrapped {})]
      (is (nil? (:body response))))))

(deftest wrap-require-auth-test
  (testing "allows authenticated request"
    (let [handler (fn [_] {:status 200 :body "ok"})
          wrapped (mw/wrap-require-auth handler)
          response (wrapped {:user {:user/id (UUID/randomUUID)}})]
      (is (= 200 (:status response)))))

  (testing "returns 401 for unauthenticated"
    (let [handler (fn [_] {:status 200 :body "ok"})
          wrapped (mw/wrap-require-auth handler)
          response (wrapped {})]
      (is (= 401 (:status response)))))

  (testing "redirects when configured"
    (let [handler (fn [_] {:status 200 :body "ok"})
          wrapped (mw/wrap-require-auth handler {:redirect "/login"})
          response (wrapped {})]
      (is (= 302 (:status response)))
      (is (= "/login" (get-in response [:headers "Location"])))))

  (testing "uses custom message"
    (let [handler (fn [_] {:status 200 :body "ok"})
          wrapped (mw/wrap-require-auth handler {:message "Please log in"})
          response (wrapped {})]
      (is (= 401 (:status response)))
      (is (= "Please log in" (:body response))))))

(deftest wrap-require-role-test
  (testing "allows user with required role"
    (let [handler (fn [_] {:status 200 :body "ok"})
          wrapped (mw/wrap-require-role handler {:role :admin})
          response (wrapped {:user {:user/id (UUID/randomUUID) :user/role :admin}})]
      (is (= 200 (:status response)))))

  (testing "allows user with one of required roles"
    (let [handler (fn [_] {:status 200 :body "ok"})
          wrapped (mw/wrap-require-role handler {:roles #{:admin :moderator}})
          response (wrapped {:user {:user/id (UUID/randomUUID) :user/role :moderator}})]
      (is (= 200 (:status response)))))

  (testing "returns 403 for wrong role"
    (let [handler (fn [_] {:status 200 :body "ok"})
          wrapped (mw/wrap-require-role handler {:role :admin})
          response (wrapped {:user {:user/id (UUID/randomUUID) :user/role :user}})]
      (is (= 403 (:status response)))))

  (testing "returns 403 for unauthenticated"
    (let [handler (fn [_] {:status 200 :body "ok"})
          wrapped (mw/wrap-require-role handler {:role :admin})
          response (wrapped {})]
      (is (= 403 (:status response)))))

  (testing "redirects when configured"
    (let [handler (fn [_] {:status 200 :body "ok"})
          wrapped (mw/wrap-require-role handler {:role :admin :redirect "/forbidden"})
          response (wrapped {:user {:user/role :user}})]
      (is (= 302 (:status response)))
      (is (= "/forbidden" (get-in response [:headers "Location"]))))))

;; =============================================================================
;; CSRF Tests
;; =============================================================================

(deftest csrf-input-test
  (testing "generates hidden input"
    ;; Note: csrf-token returns nil outside of request context
    ;; This test just verifies the function structure
    (let [input (mw/csrf-input)]
      (is (vector? input))
      (is (= :input (first input)))
      (is (= "hidden" (get-in input [1 :type])))
      (is (= "__anti-forgery-token" (get-in input [1 :name]))))))

;; =============================================================================
;; Composed Middleware Tests
;; =============================================================================

(deftest wrap-base-defaults-test
  (testing "parses params"
    (let [handler (fn [req] {:status 200 :body (:params req)})
          wrapped (mw/wrap-base-defaults handler {})
          response (wrapped {:query-string "foo=bar&baz=qux"})]
      (is (= {:foo "bar" :baz "qux"} (:body response)))))

  (testing "injects context"
    (let [handler (fn [req] {:status 200 :body (:db req)})
          wrapped (mw/wrap-base-defaults handler {:context {:db "connection"}})
          response (wrapped {})]
      (is (= "connection" (:body response))))))

(deftest wrap-site-defaults-test
  (testing "includes base defaults"
    (let [handler (fn [req] {:status 200 :body (:params req)})
          wrapped (mw/wrap-site-defaults handler {:session-secret "0123456789abcdef"
                                                   :csrf? false
                                                   :auth? false})
          response (wrapped {:query-string "key=value"})]
      (is (= {:key "value"} (:body response))))))

(deftest wrap-api-defaults-test
  (testing "includes base defaults without CSRF"
    (let [handler (fn [req] {:status 200 :body (:params req)})
          wrapped (mw/wrap-api-defaults handler {:auth? false})
          response (wrapped {:query-string "api=true"})]
      (is (= {:api "true"} (:body response))))))

;; =============================================================================
;; Utility Middleware Tests
;; =============================================================================

(deftest wrap-catch-exceptions-test
  (testing "catches exceptions"
    (let [handler (fn [_] (throw (Exception. "boom")))
          wrapped (mw/wrap-catch-exceptions handler)
          response (wrapped {})]
      (is (= 500 (:status response)))))

  (testing "calls on-error handler"
    (let [error-atom (atom nil)
          handler (fn [_] (throw (Exception. "boom")))
          wrapped (mw/wrap-catch-exceptions handler
                    {:on-error (fn [_ e] (reset! error-atom (.getMessage e)))})
          _ (wrapped {})]
      (is (= "boom" @error-atom))))

  (testing "uses custom response"
    (let [handler (fn [_] (throw (Exception. "boom")))
          wrapped (mw/wrap-catch-exceptions handler
                    {:response {:status 500 :body "Custom error"}})
          response (wrapped {})]
      (is (= "Custom error" (:body response)))))

  (testing "passes through successful response"
    (let [handler (fn [_] {:status 200 :body "ok"})
          wrapped (mw/wrap-catch-exceptions handler)
          response (wrapped {})]
      (is (= 200 (:status response)))
      (is (= "ok" (:body response))))))

(deftest wrap-logging-test
  (testing "logs requests"
    (let [log-atom (atom nil)
          handler (fn [_] {:status 200 :body "ok"})
          wrapped (mw/wrap-logging handler {:log-fn #(reset! log-atom %)})
          _ (wrapped {:request-method :get :uri "/test"})]
      (is (some? @log-atom))
      (is (.contains @log-atom "GET"))
      (is (.contains @log-atom "/test"))
      (is (.contains @log-atom "200")))))
