(ns biff.datalevin.middleware
  "Ring middleware for authentication, CSRF protection, and context injection.

   Provides Biff-style middleware composition for web applications."
  (:require [biff.datalevin.session :as session]
            [biff.datalevin.db :as db]
            [clojure.string :as str]
            [ring.middleware.anti-forgery :as anti-forgery]
            [ring.middleware.session :as ring-session]
            [ring.middleware.session.cookie :as cookie]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cookies :refer [wrap-cookies]]))

;; =============================================================================
;; Context Injection
;; =============================================================================

(defn wrap-context
  "Injects the system context into each request.

   The context map is merged into the request, providing handlers access
   to system components like the database connection.

   Example:
     (wrap-context handler {:biff.datalevin/conn conn})"
  [handler context]
  (fn [request]
    (handler (merge context request))))

;; =============================================================================
;; Authentication Middleware
;; =============================================================================

(defn wrap-authentication
  "Authenticates requests using session cookies or JWT tokens.

   Checks for:
   1. Ring session with :user key (from wrap-session)
   2. Authorization header with Bearer token (JWT)
   3. Session cookie with JWT token

   Adds :user to the request if authenticated.

   Options:
     :session-secret  - Secret for JWT verification (required for token auth)
     :cookie-name     - Name of session cookie (default \"session\")
     :header-name     - Name of auth header (default \"authorization\")"
  [handler {:keys [session-secret cookie-name header-name]
            :or {cookie-name "session"
                 header-name "authorization"}}]
  (fn [{:keys [cookies headers] :as request}]
    (let [;; Try Ring session first
          session-user (:user (:session request))
          ;; Try Authorization header
          auth-header (get headers header-name)
          header-token (when (and auth-header
                                  (.startsWith auth-header "Bearer "))
                         (subs auth-header 7))
          ;; Try cookie
          cookie-token (get-in cookies [cookie-name :value])
          ;; Verify token if present
          token (or header-token cookie-token)
          token-session-id (when (and token session-secret)
                             (session/verify-session-token token session-secret))
          token-user (when token-session-id
                       (session/get-session-user request token-session-id))
          ;; Use session user or token user
          user (or session-user token-user)]
      (handler (cond-> request
                 user (assoc :user user)
                 token-session-id (assoc :session-id token-session-id))))))

(defn wrap-require-auth
  "Middleware that requires authentication.

   Returns 401 Unauthorized if :user is not present in the request.

   Options:
     :redirect - URL to redirect to instead of 401 (for web apps)
     :message  - Custom error message"
  ([handler]
   (wrap-require-auth handler {}))
  ([handler {:keys [redirect message] :or {message "Unauthorized"}}]
   (fn [request]
     (if (:user request)
       (handler request)
       (if redirect
         {:status 302
          :headers {"Location" redirect}
          :body ""}
         {:status 401
          :headers {"Content-Type" "text/plain"}
          :body message})))))

(defn wrap-require-role
  "Middleware that requires a specific user role.

   Returns 403 Forbidden if user doesn't have the required role.

   Options:
     :role     - Required role keyword (e.g., :admin)
     :roles    - Set of acceptable roles
     :redirect - URL to redirect to instead of 403
     :message  - Custom error message"
  [handler {:keys [role roles redirect message]
            :or {message "Forbidden"}}]
  (let [allowed-roles (or roles (when role #{role}))]
    (fn [request]
      (let [user-role (get-in request [:user :user/role])]
        (if (and (:user request) (contains? allowed-roles user-role))
          (handler request)
          (if redirect
            {:status 302
             :headers {"Location" redirect}
             :body ""}
            {:status 403
             :headers {"Content-Type" "text/plain"}
             :body message}))))))

;; =============================================================================
;; CSRF Protection
;; =============================================================================

(defn wrap-csrf
  "CSRF protection middleware.

   Wraps ring-anti-forgery with sensible defaults.

   Options:
     :error-response - Custom error response for CSRF failures
     :read-token     - Function to extract token from request
     :token-name     - Form field name for token (default \"__anti-forgery-token\")"
  ([handler]
   (wrap-csrf handler {}))
  ([handler opts]
   (anti-forgery/wrap-anti-forgery handler opts)))

(defn csrf-token
  "Returns the current CSRF token for use in forms.

   Must be called within a request handled by wrap-csrf."
  []
  anti-forgery/*anti-forgery-token*)

(defn csrf-input
  "Returns a hidden input field hiccup for CSRF token.

   Example:
     [:form {:method \"post\"}
      (csrf-input)
      [:button \"Submit\"]]"
  []
  [:input {:type "hidden"
           :name "__anti-forgery-token"
           :value (csrf-token)}])

;; =============================================================================
;; Composed Middleware
;; =============================================================================

(defn wrap-base-defaults
  "Base middleware stack for all routes.

   Includes:
   - Parameter parsing (query string and form)
   - Keyword parameter keys
   - Cookie handling
   - Context injection

   Options:
     :context - System context to inject into requests"
  [handler {:keys [context] :as opts}]
  (cond-> handler
    true wrap-keyword-params
    true wrap-params
    true wrap-cookies
    context (wrap-context context)))

(defn wrap-site-defaults
  "Middleware stack for website routes (with sessions and CSRF).

   Includes everything from wrap-base-defaults plus:
   - Session handling (cookie or Datalevin store)
   - CSRF protection
   - Authentication

   Options:
     :context        - System context to inject
     :session-secret - Secret for session cookies
     :session-store  - Ring session store (defaults to cookie store)
     :csrf?          - Enable CSRF protection (default true)
     :auth?          - Enable authentication middleware (default true)"
  [handler {:keys [context session-secret session-store csrf? auth?]
            :or {csrf? true auth? true}
            :as opts}]
  (let [store (or session-store
                  (when session-secret
                    (cookie/cookie-store {:key (.getBytes session-secret)})))]
    (cond-> handler
      auth? (wrap-authentication {:session-secret session-secret})
      csrf? wrap-csrf
      store (ring-session/wrap-session {:store store})
      true (wrap-base-defaults opts))))

(defn wrap-api-defaults
  "Middleware stack for API routes (no sessions or CSRF).

   Includes everything from wrap-base-defaults plus:
   - JWT authentication (via Authorization header)

   Options:
     :context        - System context to inject
     :session-secret - Secret for JWT verification
     :auth?          - Enable authentication middleware (default true)"
  [handler {:keys [context session-secret auth?]
            :or {auth? true}
            :as opts}]
  (cond-> handler
    auth? (wrap-authentication {:session-secret session-secret})
    true (wrap-base-defaults opts)))

;; =============================================================================
;; Utility Middleware
;; =============================================================================

(defn wrap-catch-exceptions
  "Catches exceptions and returns a 500 response.

   Options:
     :on-error - Function called with request and exception
     :response - Custom error response map"
  ([handler]
   (wrap-catch-exceptions handler {}))
  ([handler {:keys [on-error response]}]
   (fn [request]
     (try
       (handler request)
       (catch Exception e
         (when on-error (on-error request e))
         (or response
             {:status 500
              :headers {"Content-Type" "text/plain"}
              :body "Internal Server Error"}))))))

(defn wrap-logging
  "Logs request method, URI, and response status.

   Options:
     :log-fn - Custom logging function (default println)"
  ([handler]
   (wrap-logging handler {}))
  ([handler {:keys [log-fn] :or {log-fn println}}]
   (fn [request]
     (let [start (System/currentTimeMillis)
           response (handler request)
           duration (- (System/currentTimeMillis) start)]
       (log-fn (format "%s %s %d (%dms)"
                       (-> request :request-method name str/upper-case)
                       (:uri request)
                       (:status response)
                       duration))
       response))))
