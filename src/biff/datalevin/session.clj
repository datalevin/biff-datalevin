(ns biff.datalevin.session
  "Session management backed by Datalevin.

   Provides both direct session management functions and a Ring-compatible
   session store implementation."
  (:require [biff.datalevin.db :as db]
            [jsonista.core :as json]
            [ring.middleware.session.store :as store])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Base64 UUID Date]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:private jwt-header {:alg "HS256" :typ "JWT"})
(def ^:private jwt-json-mapper json/keyword-keys-object-mapper)

(defn- utf8-bytes
  [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- base64url-encode
  [bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- base64url-decode
  [^String s]
  (.decode (Base64/getUrlDecoder) s))

(defn- hmac-sha256
  [secret signing-input]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (utf8-bytes secret) "HmacSHA256"))
    (.doFinal mac (utf8-bytes signing-input))))

(defn- create-jwt
  [claims secret]
  (let [header (base64url-encode (json/write-value-as-bytes jwt-header jwt-json-mapper))
        payload (base64url-encode (json/write-value-as-bytes claims jwt-json-mapper))
        signing-input (str header "." payload)
        signature (base64url-encode (hmac-sha256 secret signing-input))]
    (str signing-input "." signature)))

(defn- parse-jwt-json
  [segment]
  (json/read-value (base64url-decode segment) jwt-json-mapper))

(defn- verify-jwt
  [token secret]
  (let [[header payload signature & more] (clojure.string/split token #"\.")]
    (when (and header payload signature (nil? more))
      (let [signing-input (str header "." payload)
            expected (hmac-sha256 secret signing-input)
            actual (base64url-decode signature)
            header-map (parse-jwt-json header)
            claims (parse-jwt-json payload)
            now (quot (System/currentTimeMillis) 1000)]
        (when (and (MessageDigest/isEqual actual expected)
                   (= "HS256" (:alg header-map))
                   (= "JWT" (:typ header-map))
                   (number? (:exp claims))
                   (< now (long (:exp claims))))
          claims)))))

;; =============================================================================
;; Session Management
;; =============================================================================

(defn create-session
  "Creates a new session for a user.

   The user-ref can be either:
   - A UUID (will be converted to lookup ref [:user/id uuid])
   - A lookup ref like [:user/id uuid]
   - A numeric entity ID

   Options:
     :expires-in-hours - Session lifetime in hours (default 168 = 7 days)

   Returns a map with :session-id and :tx (transaction data).

   Note: The user must exist before creating a session."
  [user-ref & {:keys [expires-in-hours] :or {expires-in-hours 168}}]
  (let [session-id (UUID/randomUUID)
        expires-at (Date. (+ (System/currentTimeMillis)
                             (* expires-in-hours 60 60 1000)))
        user-lookup (if (uuid? user-ref)
                      [:user/id user-ref]
                      user-ref)]
    {:session-id session-id
     :tx {:session/id session-id
          :session/user user-lookup
          :session/expires-at expires-at}}))

(defn get-session
  "Retrieves a session by ID.

   Returns the session entity with user data if valid and not expired,
   nil otherwise."
  [db-or-system session-id]
  (when-let [session (db/lookup db-or-system :session/id session-id
                                '[:session/id :session/expires-at
                                  {:session/user [:user/id :user/email :user/username
                                                  :user/role :user/github-username]}])]
    (let [expires-at (:session/expires-at session)
          now (Date.)]
      (when (.before now expires-at)
        session))))

(defn get-session-user
  "Retrieves the user associated with a session.

   Returns the user map if session is valid, nil otherwise."
  [db-or-system session-id]
  (when-let [session (get-session db-or-system session-id)]
    (:session/user session)))

(defn delete-session-tx
  "Creates a transaction to delete a session.

   For Datalevin, this returns a retraction using the lookup ref.
   The session must exist for this to work."
  [db-or-conn-or-system session-id]
  (when-let [eid (db/lookup-id db-or-conn-or-system :session/id session-id)]
    [:db/retractEntity eid]))

(defn delete-user-sessions-tx
  "Creates transactions to delete all sessions for a user.

   Returns a vector of retraction transactions."
  [db-or-conn-or-system user-id]
  (let [db (db/get-db db-or-conn-or-system)
        ;; Get the actual entity IDs for sessions
        session-eids (db/q '[:find [?s ...]
                             :in $ ?uid
                             :where
                             [?s :session/user ?u]
                             [?u :user/id ?uid]]
                           db user-id)]
    (mapv (fn [eid] [:db/retractEntity eid]) session-eids)))

(defn cleanup-expired-sessions-tx
  "Creates transactions to delete all expired sessions.

   Returns a vector of retraction transactions."
  [db-or-conn-or-system]
  (let [db (db/get-db db-or-conn-or-system)
        now (Date.)
        ;; Get the actual entity IDs for expired sessions
        expired-eids (db/q '[:find [?s ...]
                             :in $ ?now
                             :where
                             [?s :session/expires-at ?exp]
                             [(< ?exp ?now)]]
                           db now)]
    (mapv (fn [eid] [:db/retractEntity eid]) expired-eids)))

(defn cleanup-expired-sessions!
  "Deletes expired sessions and returns the number removed.

   Accepts a Datalevin connection or system map."
  [conn-or-system]
  (let [txs (cleanup-expired-sessions-tx conn-or-system)]
    (when (seq txs)
      (db/submit-tx conn-or-system txs))
    (count txs)))

;; =============================================================================
;; Cookie-Based Sessions with JWT
;; =============================================================================

(defn create-session-token
  "Creates a signed JWT token containing session data.

  Options:
     :secret         - Signing secret (required)
     :expires-in-sec - Token lifetime in seconds (default 604800 = 7 days)"
  [session-id {:keys [secret expires-in-sec] :or {expires-in-sec 604800}}]
  (create-jwt {:session-id (str session-id)
               :exp (+ (quot (System/currentTimeMillis) 1000) expires-in-sec)}
              secret))

(defn verify-session-token
  "Verifies and decodes a JWT session token.

   Returns the session-id as UUID if valid, nil otherwise."
  [token secret]
  (try
    (let [claims (verify-jwt token secret)]
      (UUID/fromString (:session-id claims)))
    (catch Exception _
      nil)))

;; =============================================================================
;; Ring Session Store
;; =============================================================================

(deftype DatalevinSessionStore [^{:volatile-mutable true} conn opts]
  store/SessionStore

  (read-session [this session-id]
    (when session-id
      (try
        (let [uuid (if (uuid? session-id)
                     session-id
                     (UUID/fromString session-id))]
          (when-let [session (get-session (.-conn this) uuid)]
            {:user (:session/user session)
             :session-id uuid}))
        (catch Exception _
          nil))))

  (write-session [this session-id data]
    (let [new-id (or (when session-id
                       (try (if (uuid? session-id)
                              session-id
                              (UUID/fromString session-id))
                            (catch Exception _ nil)))
                     (UUID/randomUUID))
          user-id (get-in data [:user :user/id])
          expires-hours (or (:expires-in-hours opts) 168)]
      (when user-id
        (let [{:keys [tx]} (create-session user-id :expires-in-hours expires-hours)]
          ;; Override the session-id with the one we want
          (db/submit-tx (.-conn this) [(assoc tx :session/id new-id)])))
      (str new-id)))

  (delete-session [this session-id]
    (when session-id
      (try
        (let [uuid (if (uuid? session-id)
                     session-id
                     (UUID/fromString session-id))]
          (when-let [delete-tx (delete-session-tx (.-conn this) uuid)]
            (db/submit-tx (.-conn this) [delete-tx])))
        (catch Exception _
          nil)))
    nil))

(defn datalevin-session-store
  "Creates a Ring session store backed by Datalevin.

   Options:
     :expires-in-hours - Session lifetime in hours (default 168 = 7 days)

   Usage:
     (wrap-session handler {:store (datalevin-session-store conn)})"
  ([conn]
   (datalevin-session-store conn {}))
  ([conn opts]
   (->DatalevinSessionStore conn opts)))

(def ^:private session-store-conn-field
  (doto (.getDeclaredField DatalevinSessionStore "conn")
    (.setAccessible true)))

(defn update-session-store-conn
  "Updates the connection used by a session store.

   Useful when the connection changes during development."
  [store conn]
  (.set ^java.lang.reflect.Field session-store-conn-field store conn)
  store)
