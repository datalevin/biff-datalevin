(ns biff.datalevin.adapter
  "Biff 2 database adapter for Datalevin.

   Provides a biff.core module that:

     - starts a Datalevin connection
     - implements biff.core's key-value store interface
       (:biff.core/kv-get, :biff.core/kv-set, :biff.core/kv-list)
     - provides :biff.core/wrap-db-snapshot for consistent reads
     - calls :biff.core/on-tx after transactions
     - generates biff.graph resolvers from a Datalevin schema
     - provides biff.fx handlers for queries and transactions

   Example:

     (def modules
       [(biff.fx/module)
        (biff.graph/module)
        (biff.datalevin.adapter/module
         {:biff.datalevin/db-path \"data/myapp\"
          :biff.datalevin/schema my-schema})])

     (def start-order
       [:biff.datalevin/module])

   See https://github.com/jacobobryant/biff/blob/master/docs/db-adapters.md"
  (:require [biff.datalevin.db :as db]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [com.biffweb.core :as biff.core]
            [com.biffweb.graph :as biff.graph]
            [datalevin.core :as d]))

(biff.core/register
 {:biff.datalevin/conn      :any
  :biff.datalevin/db-path   :string
  :biff.datalevin/opts      [:maybe :map]
  :biff.datalevin/schema    [:map-of :qualified-keyword :map]
  :biff.datalevin/snapshot? :boolean})

;; =============================================================================
;; Key-value store
;; =============================================================================

(def kv-schema
  "Datalevin schema for the biff.core key-value store."
  {:biff.datalevin.kv/id        {:db/valueType :db.type/string
                                 :db/unique    :db.unique/identity}
   :biff.datalevin.kv/namespace {:db/valueType :db.type/string
                                 :db/index     true}
   :biff.datalevin.kv/key       {:db/valueType :db.type/string}
   :biff.datalevin.kv/value     {:db/valueType :db.type/string}})

(defn- kv-id [namespace* key*]
  (str namespace* "\n" key*))

(defn kv-set
  "Implementation of :biff.core/kv-set.

   Sets `key*` in `namespace*` to `value`. A nil `value` deletes the key.
   Values are serialized with pr-str, so they must be readable by
   clojure.edn/read-string."
  [ctx namespace* key* value]
  (biff.core/validate {:biff.core/kv-namespace namespace*
                       :biff.core/kv-key       key*})
  (biff.core/validate ctx {:required [:biff.datalevin/conn]})
  (if (nil? value)
    (when-some [eid (d/q '[:find ?e .
                           :in $ ?id
                           :where [?e :biff.datalevin.kv/id ?id]]
                         (db/get-db ctx)
                         (kv-id namespace* key*))]
      (d/transact! (:biff.datalevin/conn ctx) [[:db/retractEntity eid]]))
    (d/transact! (:biff.datalevin/conn ctx)
                 [{:biff.datalevin.kv/id        (kv-id namespace* key*)
                   :biff.datalevin.kv/namespace (str namespace*)
                   :biff.datalevin.kv/key       key*
                   :biff.datalevin.kv/value     (pr-str value)}]))
  nil)

(defn kv-get
  "Implementation of :biff.core/kv-get.

   Returns the value for `key*` in `namespace*`, or nil if unset."
  [ctx namespace* key*]
  (biff.core/validate {:biff.core/kv-namespace namespace*
                       :biff.core/kv-key       key*})
  (some-> (d/q '[:find ?v .
                 :in $ ?id
                 :where [?e :biff.datalevin.kv/id ?id]
                        [?e :biff.datalevin.kv/value ?v]]
               (db/get-db ctx)
               (kv-id namespace* key*))
          edn/read-string))

(defn kv-list
  "Implementation of :biff.core/kv-list.

   Returns a sorted vector of keys in `namespace*`. If `key-prefix` is set,
   only keys beginning with it are returned."
  ([ctx namespace*]
   (kv-list ctx namespace* nil))
  ([ctx namespace* key-prefix]
   (biff.core/validate {:biff.core/kv-namespace namespace*
                        :biff.core/kv-prefix    key-prefix})
   (->> (d/q '[:find ?k
               :in $ ?ns
               :where [?e :biff.datalevin.kv/namespace ?ns]
                      [?e :biff.datalevin.kv/key ?k]]
             (db/get-db ctx)
             (str namespace*))
        (map first)
        (filter #(or (nil? key-prefix) (str/starts-with? % key-prefix)))
        sort
        vec)))

;; =============================================================================
;; Reads and writes
;; =============================================================================

(defn q
  "Queries the Datalevin database.

   `ctx` is a system map containing :biff.datalevin/conn (or a Datalevin
   connection or database value).

   Example:
     (q ctx '[:find ?e :where [?e :user/email \"a@b.com\"]])"
  [ctx query & args]
  (apply d/q query (db/get-db ctx) args))

(defn execute-tx
  "Executes a Datalevin transaction, returning the transaction report.

   Supports the special values :db/now and :db/uuid. :biff.core/on-tx is
   called after the transaction (via a connection listener)."
  [ctx tx-data]
  (biff.core/validate ctx {:required [:biff.datalevin/conn]})
  (db/submit-tx ctx tx-data))

(defn submit-tx
  "Alias for execute-tx."
  [ctx tx-data]
  (execute-tx ctx tx-data))

(def fx-handlers
  "A biff.fx handlers map. Contains :biff.datalevin.fx/q and
   :biff.datalevin.fx/execute-tx."
  {:biff.datalevin.fx/q          q
   :biff.datalevin.fx/execute-tx execute-tx})

;; =============================================================================
;; System lifecycle
;; =============================================================================

(defn- wrap-db-snapshot
  "Wraps `f` so that all queries inside it see a consistent view of the
   database. Datalevin provides this via a read/write transaction, which
   serializes concurrent graph queries and blocks writes for the duration of
   `f`. Disable with :biff.datalevin/snapshot? false if you prefer concurrent
   reads over a consistent view."
  [f]
  (fn [ctx]
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (d/with-transaction [tx-conn (:biff.datalevin/conn ctx)]
      (f (assoc ctx :biff.datalevin/conn tx-conn)))))

(defn- on-tx-listener [ctx]
  (fn [_report]
    (when-let [on-tx (:biff.core/on-tx ctx)]
      (on-tx ctx))))

(defn- start
  [{:biff.datalevin/keys [db-path schema opts] :as ctx}]
  (when-not db-path
    (throw (ex-info "Missing required :biff.datalevin/db-path" {})))
  (let [conn (d/get-conn db-path (merge kv-schema schema) opts)
        listener-key (d/listen! conn (on-tx-listener ctx))]
    (cond-> (assoc ctx
                   :biff.datalevin/conn conn
                   :biff.core/kv-get kv-get
                   :biff.core/kv-set kv-set
                   :biff.core/kv-list kv-list
                   ::listener-key listener-key)
      (get ctx :biff.datalevin/snapshot? true)
      (assoc :biff.core/wrap-db-snapshot wrap-db-snapshot))))

(defn- stop
  [{:biff.datalevin/keys [conn] ::keys [listener-key]}]
  (when conn
    (when listener-key
      (d/unlisten! conn listener-key))
    (d/close conn)))

;; =============================================================================
;; biff.graph resolvers
;; =============================================================================

(defn- unique-attrs [schema ns-str]
  (->> schema
       (keep (fn [[attr opts]]
               (when (and (= ns-str (namespace attr))
                          (#{:db.unique/identity :db.unique/value}
                           (:db/unique opts)))
                 attr)))
       sort))

(defn- primary-key
  "Returns the primary key attribute for the entity type in namespace `ns-str`,
   or nil if none can be determined. Prefers :<ns>/id, then any unique attr."
  [schema ns-str]
  (let [id-attr (keyword ns-str "id")]
    (if (contains? schema id-attr)
      id-attr
      (first (unique-attrs schema ns-str)))))

(defn- ref-target-key
  "Returns the attribute that holds the primary key of the entity referenced by
   `attr`.

   If the schema entry has a :biff.datalevin/ref key, that is used. Otherwise
   the target entity type is inferred from the attribute name: :session/user
   refers to the :user entity type, and :user/pet-id refers to :pet."
  [schema attr opts]
  (or (:biff.datalevin/ref opts)
      (let [n         (name attr)
            target-ns (if (str/ends-with? n "-id")
                        (subs n 0 (- (count n) 3))
                        n)
            id-attr   (keyword target-ns "id")]
        (or (when (contains? schema id-attr) id-attr)
            (first (unique-attrs schema target-ns))
            id-attr))))

(defn- output-mappings [schema attr opts]
  (if (= :db.type/ref (:db/valueType opts))
    (let [many?  (= :db.cardinality/many (:db/cardinality opts))
          target (ref-target-key schema attr opts)]
      (if (str/ends-with? (name attr) "-id")
        [{:source-key attr
          :output-key attr
          :kind       :ref-id
          :target     target
          :many?      many?}
         {:source-key attr
          :output-key (keyword (namespace attr)
                               (str/replace (name attr) #"-id$" ""))
          :kind       :join
          :target     target
          :many?      many?}]
        [{:source-key attr
          :output-key attr
          :kind       :join
          :target     target
          :many?      many?}]))
    [{:source-key attr
      :output-key attr
      :kind       :scalar}]))

(defn- output-query [mappings]
  (mapv (fn [{:keys [output-key kind target]}]
          (case kind
            :scalar output-key
            :ref-id output-key
            :join   {output-key [target]}))
        mappings))

(defn- pull-pattern
  "Returns a Datalevin pull pattern that fetches the primary key and all
   output attributes. Ref attributes are pulled as nested maps so that the
   referenced entity's primary key value is returned directly."
  [pk mappings]
  (let [join-targets (into {}
                           (comp (filter #(= :join (:kind %)))
                                 (map (juxt :source-key :target)))
                           mappings)
        attrs        (distinct (map :source-key mappings))]
    (vec (cons pk (map (fn [attr]
                         (if-some [target (get join-targets attr)]
                           {attr [target]}
                           attr))
                       attrs)))))

(defn- process-row [row mappings]
  (into {}
        (keep (fn [{:keys [source-key output-key kind target many?]}]
                (let [value (get row source-key)]
                  (when (some? value)
                    (case kind
                      :scalar [output-key value]
                      :join   [output-key (if many?
                                            (mapv #(or % {}) value)
                                            value)]
                      :ref-id (if many?
                                [output-key (mapv #(get % target) value)]
                                [output-key (get value target)])))))
        mappings)))

(defn- make-resolver [schema ns-str attrs]
  (let [pk       (primary-key schema ns-str)
        attrs    (remove #(= pk %) attrs)
        mappings (vec (mapcat #(output-mappings schema % (get schema %)) attrs))]
    (biff.graph/resolver
     {:id     (keyword "biff.datalevin" (str (name ns-str) "-resolver"))
      :input  [pk]
      :output (output-query mappings)
      :batch  true

      :resolve-fn
      (fn [ctx inputs]
        (let [db       (db/get-db ctx)
              pks      (mapv #(get % pk) inputs)
              pk->eid  (into {}
                             (d/q '[:find ?pk ?e
                                    :in $ ?attr [?pk ...]
                                    :where [?e ?attr ?pk]]
                                  db pk pks))
              indexed  (keep-indexed (fn [i pk]
                                       (when-some [eid (pk->eid pk)]
                                         [i eid]))
                                     pks)
              eids     (mapv second indexed)
              rows     (if (seq eids)
                         (d/pull-many db (pull-pattern pk mappings) eids)
                         [])
              idx->row (zipmap (map first indexed) rows)]
          (mapv (fn [i]
                  (if-some [row (get idx->row i)]
                    (process-row row mappings)
                    {}))
                (range (count inputs)))))})))

(defn make-resolvers
  "Returns a sequence of biff.graph resolvers, one for each entity type in
   `schema` (a Datalevin schema map).

   Entity types are determined by attribute namespaces, e.g. all :user/* attrs
   belong to the :user entity type. The primary key is :<ns>/id if it exists,
   otherwise the first unique attribute in the namespace.

   Ref attributes are returned as joins. The target entity type is inferred
   from the attribute name: :session/user refers to :user/id and :user/pet-id
   refers to :pet/id. For refs whose names don't imply their target (e.g.
   :group/members), set :biff.datalevin/ref in the schema entry:

     :group/members {:db/valueType      :db.type/ref
                     :db/cardinality    :db.cardinality/many
                     :biff.datalevin/ref :user/id}

   If a ref attribute ends in -id, that attribute is also returned as a scalar
   containing the referenced primary key value."
  [schema]
  (let [schema (or schema {})]
    (biff.core/validate {:biff.datalevin/schema schema})
    (vec
     (for [[ns-str attrs] (sort-by key (group-by (comp namespace key) schema))
           :when ns-str
           :let [pk (primary-key schema ns-str)]
           :when pk]
       (make-resolver schema ns-str (map key (sort-by key attrs)))))))

;; =============================================================================
;; Module
;; =============================================================================

(defn module
  "Returns a biff.core module for Datalevin. Module ID is
   :biff.datalevin/module.

   Options:
     :biff.datalevin/db-path   - Path to the Datalevin database directory (required)
     :biff.datalevin/schema    - Datalevin schema map
     :biff.datalevin/opts      - Additional options passed to d/get-conn
     :biff.datalevin/snapshot? - Set to false to skip :biff.core/wrap-db-snapshot
                                 (default true)

   Other modules may contribute schema by including a :biff.datalevin/schema
   key in their module map.

   On start, opens a connection and adds to the system map:

     :biff.datalevin/conn          - The Datalevin connection
     :biff.core/kv-get             - biff.core key-value store
     :biff.core/kv-set
     :biff.core/kv-list
     :biff.core/wrap-db-snapshot   - Provides consistent reads (unless
                                     :biff.datalevin/snapshot? is false)"
  [{:biff.datalevin/keys [db-path schema opts snapshot?]}]
  {:biff.core/id         :biff.datalevin/module
   :biff.datalevin/schema (merge kv-schema schema)
   :biff.core/start      start
   :biff.core/stop       stop
   :biff.fx/handlers     fx-handlers
   :biff.graph/resolvers (make-resolvers schema)

   :biff.core/init
   (fn [modules-var]
     (cond-> {:biff.datalevin/schema
              (into {} (mapcat :biff.datalevin/schema) @modules-var)
              :biff.datalevin/snapshot?
              (if (some? snapshot?) snapshot? true)}
       db-path (assoc :biff.datalevin/db-path db-path)
       opts    (assoc :biff.datalevin/opts opts)))})
