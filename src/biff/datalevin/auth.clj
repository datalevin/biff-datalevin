(ns biff.datalevin.auth
  "Authentication utilities including password hashing and OAuth support.

   Provides patterns for:
   - Password-based authentication with bcrypt
   - OAuth flows (GitHub, extensible to other providers)"
  (:require [buddy.hashers :as hashers]
            [clj-http.client :as http]
            [jsonista.core :as json]
            [biff.datalevin.db :as db])
  (:import [java.util UUID Date]))

;; =============================================================================
;; Password Hashing
;; =============================================================================

(defn hash-password
  "Hashes a password using bcrypt.

   Options:
     :iterations - bcrypt work factor (default 12)"
  ([password]
   (hash-password password {}))
  ([password {:keys [iterations] :or {iterations 12}}]
   (hashers/derive password {:alg :bcrypt+sha512
                             :iterations iterations})))

(defn verify-password
  "Verifies a password against a hash.

   Returns true if the password matches, false otherwise."
  [password hash]
  (when (and password hash)
    (:valid (hashers/verify password hash))))

;; =============================================================================
;; User Management Helpers
;; =============================================================================

(defn create-user-tx
  "Creates a transaction map for a new user with password.

   Required:
     :user/email - User's email address
     :password   - Plain text password (will be hashed)

   Optional:
     :user/username - Username
     :user/id       - UUID (generated if not provided)
     Any other user attributes

   Returns a transaction map ready for submit-tx.

   Note: The :user/id attribute must be unique in the schema for lookups to work."
  [{:keys [password] :as user-data}]
  (let [user-id (or (:user/id user-data) (UUID/randomUUID))]
    (-> user-data
        (dissoc :password)
        (assoc :user/id user-id
               :user/password-hash (hash-password password)
               :user/created-at :db/now))))

(defn authenticate-user
  "Authenticates a user by email and password.

   Returns the user entity if authentication succeeds, nil otherwise.

   Example:
     (authenticate-user ctx \"user@example.com\" \"password123\")"
  [db-or-system email password]
  (when-let [user (db/lookup db-or-system :user/email email)]
    (when (verify-password password (:user/password-hash user))
      (dissoc user :user/password-hash))))

(defn find-user-by-email
  "Finds a user by email address.

   Returns the user entity without password hash, or nil if not found."
  [db-or-system email]
  (when-let [user (db/lookup db-or-system :user/email email)]
    (dissoc user :user/password-hash)))

(defn find-user-by-id
  "Finds a user by their UUID.

   Returns the user entity without password hash, or nil if not found."
  [db-or-system user-id]
  (when-let [user (db/lookup db-or-system :user/id user-id)]
    (dissoc user :user/password-hash)))

;; =============================================================================
;; OAuth - GitHub
;; =============================================================================

(def ^:private github-authorize-endpoint "https://github.com/login/oauth/authorize")
(def ^:private github-token-endpoint "https://github.com/login/oauth/access_token")
(def ^:private github-user-endpoint "https://api.github.com/user")

(defn github-authorize-url
  "Generates the GitHub OAuth authorization URL.

   Options:
     :client-id    - GitHub OAuth app client ID (required)
     :redirect-uri - Callback URL (required)
     :scope        - OAuth scopes (default \"user:email\")
     :state        - CSRF state parameter (recommended)"
  [{:keys [client-id redirect-uri scope state]
    :or {scope "user:email"}}]
  (str github-authorize-endpoint
       "?client_id=" client-id
       "&redirect_uri=" (java.net.URLEncoder/encode redirect-uri "UTF-8")
       "&scope=" (java.net.URLEncoder/encode scope "UTF-8")
       (when state (str "&state=" state))))

(defn github-exchange-code
  "Exchanges an OAuth authorization code for an access token.

   Returns a map with :access_token or throws on error."
  [{:keys [client-id client-secret code redirect-uri]}]
  (let [response (http/post github-token-endpoint
                            {:form-params {:client_id client-id
                                           :client_secret client-secret
                                           :code code
                                           :redirect_uri redirect-uri}
                             :headers {"Accept" "application/json"}
                             :as :json})]
    (:body response)))

(defn github-get-user
  "Fetches the authenticated user's profile from GitHub.

   Returns a map with GitHub user data including :id, :login, :email, :avatar_url, etc."
  [access-token]
  (let [response (http/get github-user-endpoint
                           {:headers {"Authorization" (str "Bearer " access-token)
                                      "Accept" "application/json"}
                            :as :json})]
    (:body response)))

(defn github-find-or-create-user-tx
  "Creates transaction data for creating a user from GitHub profile.

   Takes GitHub user data and returns a transaction map.
   Uses :user/github-id as the unique identifier.

   Example:
     (let [gh-user (github-get-user access-token)]
       (submit-tx conn [(github-find-or-create-user-tx gh-user)]))"
  [github-user]
  (let [user-id (UUID/randomUUID)]
    {:user/id user-id
     :user/github-id (:id github-user)
     :user/github-username (:login github-user)
     :user/email (:email github-user)
     :user/avatar-url (:avatar_url github-user)
     :user/created-at :db/now}))

(defn find-user-by-github-id
  "Finds a user by their GitHub ID.

   Returns the user entity or nil if not found."
  [db-or-system github-id]
  (db/lookup db-or-system :user/github-id github-id))

;; =============================================================================
;; OAuth - Generic Provider Support
;; =============================================================================

(defn oauth-authorize-url
  "Generates an OAuth authorization URL for any provider.

   Options:
     :authorize-url - Provider's authorization endpoint (required)
     :client-id     - OAuth app client ID (required)
     :redirect-uri  - Callback URL (required)
     :scope         - OAuth scopes (optional)
     :state         - CSRF state parameter (recommended)
     :extra-params  - Map of additional query params"
  [{:keys [authorize-url client-id redirect-uri scope state extra-params]}]
  (let [params (cond-> {:client_id client-id
                        :redirect_uri redirect-uri
                        :response_type "code"}
                 scope (assoc :scope scope)
                 state (assoc :state state)
                 extra-params (merge extra-params))
        query-string (->> params
                          (map (fn [[k v]]
                                 (str (name k) "="
                                      (java.net.URLEncoder/encode (str v) "UTF-8"))))
                          (clojure.string/join "&"))]
    (str authorize-url "?" query-string)))

(defn oauth-exchange-code
  "Exchanges an OAuth code for tokens with any provider.

   Options:
     :token-url     - Provider's token endpoint (required)
     :client-id     - OAuth app client ID (required)
     :client-secret - OAuth app client secret (required)
     :code          - Authorization code (required)
     :redirect-uri  - Callback URL (required)
     :extra-params  - Map of additional form params

   Returns the parsed JSON response body."
  [{:keys [token-url client-id client-secret code redirect-uri extra-params]}]
  (let [params (cond-> {:client_id client-id
                        :client_secret client-secret
                        :code code
                        :redirect_uri redirect-uri
                        :grant_type "authorization_code"}
                 extra-params (merge extra-params))
        response (http/post token-url
                            {:form-params params
                             :headers {"Accept" "application/json"}
                             :as :json})]
    (:body response)))

;; =============================================================================
;; Email Verification
;; =============================================================================

(defn create-verification-token
  "Creates a verification token for email confirmation.

   The user-ref can be a UUID (converted to lookup ref) or a lookup ref.
   Note: The user must exist before creating a verification token.

   Returns a map with :token and transaction data."
  [user-ref & {:keys [expires-in-hours] :or {expires-in-hours 24}}]
  (let [token (str (UUID/randomUUID))
        expires-at (Date. (+ (System/currentTimeMillis)
                             (* expires-in-hours 60 60 1000)))
        user-lookup (if (uuid? user-ref)
                      [:user/id user-ref]
                      user-ref)]
    {:token token
     :tx {:verification-token/token token
          :verification-token/user user-lookup
          :verification-token/expires-at expires-at}}))

(defn verify-token
  "Verifies an email verification token.

   Returns the user ID if valid and not expired, nil otherwise."
  [db-or-conn-or-system token]
  (when-let [token-entity (db/lookup db-or-conn-or-system :verification-token/token token
                                     '[:verification-token/token
                                       :verification-token/expires-at
                                       {:verification-token/user [:user/id]}])]
    (let [expires-at (:verification-token/expires-at token-entity)
          now (Date.)]
      (when (.before now expires-at)
        (get-in token-entity [:verification-token/user :user/id])))))

(defn delete-verification-token-tx
  "Creates a transaction to delete a verification token.

   For Datalevin, this returns a retraction using the entity ID.
   The token must exist for this to work."
  [db-or-conn-or-system token]
  (when-let [eid (db/lookup-id db-or-conn-or-system :verification-token/token token)]
    [:db/retractEntity eid]))
