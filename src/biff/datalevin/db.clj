(ns biff.datalevin.db
  "Database connection management, transaction helpers, and query utilities.

   Provides Biff-style transaction semantics adapted for Datalevin."
  (:require [datalevin.core :as d])
  (:import [java.util Date UUID]))

;; =============================================================================
;; Connection Management
;; =============================================================================

(defn get-conn
  "Opens a Datalevin connection.

   Args:
     db-path - Path to the database directory
     schema  - Optional Datalevin schema map
     opts    - Optional map of additional options for d/get-conn

   Returns the connection."
  ([db-path]
   (get-conn db-path nil nil))
  ([db-path schema]
   (get-conn db-path schema nil))
  ([db-path schema opts]
   (d/get-conn db-path schema opts)))

(defn close-conn
  "Closes a Datalevin connection."
  [conn]
  (d/close conn))

(defn conn? [x] (d/conn? x))

(defn get-db
  "Returns the current database value from a connection, db, or system map.

   Accepts:
     - A Datalevin connection (Atom) -> calls (d/db conn)
     - A Datalevin database value -> returns as-is
     - A system/context map with :biff.datalevin/conn -> extracts and converts
     - A system/context map with :biff/db -> returns the db value (Biff compatibility)"
  [conn-or-db-or-system]
  (cond
    ;; System map with connection - get fresh db snapshot
    (and (map? conn-or-db-or-system)
         (:biff.datalevin/conn conn-or-db-or-system))
    (d/db (:biff.datalevin/conn conn-or-db-or-system))

    ;; System map with :biff/db (Biff compatibility)
    (and (map? conn-or-db-or-system)
         (:biff/db conn-or-db-or-system))
    (:biff/db conn-or-db-or-system)

    ;; Datalevin connection (Atom) - need to get db from it
    (conn? conn-or-db-or-system)
    (d/db conn-or-db-or-system)

    ;; Assume it's already a db value
    :else
    conn-or-db-or-system))

(defn assoc-db
  "Sets :biff/db on the context map to the current database snapshot.

   Use this in middleware to ensure handlers have a consistent db view,
   or after transactions to refresh the snapshot.

   This matches Biff's assoc-db pattern for compatibility."
  [ctx]
  (if-let [conn (:biff.datalevin/conn ctx)]
    (assoc ctx :biff/db (d/db conn))
    ctx))

;; =============================================================================
;; Transaction Helpers
;; =============================================================================

(defn- resolve-special-values
  "Resolves special values in a transaction item:
   - :db/now -> current Date
   - :db/uuid -> random UUID

   Handles both map transactions and vector transactions (like retractions)."
  [tx-item now]
  (if (map? tx-item)
    (into {}
          (map (fn [[k v]]
                 [k (cond
                      (= v :db/now) now
                      (= v :db/uuid) (UUID/randomUUID)
                      :else v)]))
          tx-item)
    ;; Pass through vectors (retractions, etc.) unchanged
    tx-item))

(defn- ensure-id
  "Ensures the transaction map has a :db/id.
   If not present, generates a random UUID using the provided id-attr."
  [tx-map id-attr]
  (if (:db/id tx-map)
    tx-map
    (assoc tx-map :db/id [id-attr (UUID/randomUUID)])))

(defn submit-tx
  "Submits a transaction to Datalevin.

   Accepts a connection (or system map) and a vector of transaction maps.

   Transaction maps support special values:
     :db/now  - Replaced with current Date
     :db/uuid - Replaced with random UUID

   For new entities, include the unique identity attribute(s).
   For updates, use a lookup ref as :db/id.

   Example:
     ;; Create new entity (unique attr is :user/id)
     (submit-tx conn [{:user/id user-id
                       :user/email \"test@example.com\"
                       :user/created-at :db/now}])

     ;; Update existing entity
     (submit-tx conn [{:db/id [:user/id user-id]
                       :user/name \"New Name\"}])"
  [conn-or-system txs]
  (let [conn (if (map? conn-or-system)
               (:biff.datalevin/conn conn-or-system)
               conn-or-system)
        now (Date.)
        resolved-txs (mapv #(resolve-special-values % now) txs)]
    (d/transact! conn resolved-txs)))

(defn merge-tx
  "Creates a transaction that merges attributes into an existing entity.
   Only updates provided attributes, leaving others unchanged.

   Example:
     (submit-tx conn [(merge-tx [:user/id user-id]
                                {:user/name \"New Name\"})])"
  [id attrs]
  (assoc attrs :db/id id))

(defn delete-tx
  "Creates a retraction transaction for an entity.

   Example:
     (submit-tx conn [(delete-tx [:user/id user-id])])"
  [id]
  [:db/retractEntity id])

;; =============================================================================
;; Query Helpers
;; =============================================================================

(defn q
  "Convenience wrapper for d/q.

   Automatically extracts the database from connection/system if provided.
   Supports both positional and keyword argument styles.

   Examples:
     (q '[:find ?e :where [?e :user/email]] db)
     (q {:query '[:find ?e :where [?e :user/email]]
         :args [db]})"
  ([query db-or-conn-or-system & args]
   (let [db (get-db db-or-conn-or-system)]
     (apply d/q query db args))))

(defn lookup
  "Finds a single entity by attribute value.

   Returns the entity map or nil if not found.

   Example:
     (lookup db :user/email \"test@example.com\")"
  ([db-or-conn-or-system attr value]
   (lookup db-or-conn-or-system attr value '[*]))
  ([db-or-conn-or-system attr value pull-expr]
   (let [db (get-db db-or-conn-or-system)]
     (d/q `[:find (~'pull ~'?e ~pull-expr) .
            :in ~'$ ~'?v
            :where [~'?e ~attr ~'?v]]
          db value))))

(defn lookup-all
  "Finds all entities matching an attribute value.

   Returns a vector of entity maps.

   Example:
     (lookup-all db :user/role :admin)"
  ([db-or-conn-or-system attr value]
   (lookup-all db-or-conn-or-system attr value '[*]))
  ([db-or-conn-or-system attr value pull-expr]
   (let [db (get-db db-or-conn-or-system)]
     (d/q `[:find [(~'pull ~'?e ~pull-expr) ...]
            :in ~'$ ~'?v
            :where [~'?e ~attr ~'?v]]
          db value))))

(defn lookup-id
  "Returns the entity id (:db/id) for an entity with the given attribute value.

   Example:
     (lookup-id db :user/email \"test@example.com\")
     ;; => 12345"
  [db-or-conn-or-system attr value]
  (let [db (get-db db-or-conn-or-system)]
    (d/q '[:find ?e .
           :in $ ?attr ?v
           :where [?e ?attr ?v]]
         db attr value)))

(defn entity-exists?
  "Checks if an entity with the given attribute value exists.

   Example:
     (entity-exists? db :user/email \"test@example.com\")"
  [db-or-conn-or-system attr value]
  (some? (lookup-id db-or-conn-or-system attr value)))

(defn pull
  "Convenience wrapper for d/pull.

   Example:
     (pull db [:user/email :user/name] [:user/id user-id])"
  [db-or-conn-or-system pull-expr eid]
  (let [db (get-db db-or-conn-or-system)]
    (d/pull db pull-expr eid)))

(defn pull-many
  "Convenience wrapper for d/pull-many.

   Example:
     (pull-many db [:user/email :user/name] [[:user/id id1] [:user/id id2]])"
  [db-or-conn-or-system pull-expr eids]
  (let [db (get-db db-or-conn-or-system)]
    (d/pull-many db pull-expr eids)))
