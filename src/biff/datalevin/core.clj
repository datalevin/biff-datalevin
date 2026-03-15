(ns biff.datalevin.core
  "Core system lifecycle management for biff-datalevin.

   Provides a simple map-based component system inspired by Biff's architecture.
   Components are functions that take a system map and return a modified system map."
  (:require [biff.datalevin.db :as db]
            [biff.datalevin.session :as session])
  (:import [java.util.concurrent Executors ThreadFactory TimeUnit]))

(declare assoc-stop)

(defn start-system
  "Starts the system by threading the initial system map through each component.

   Components are functions that take a system map and return a modified system map.
   Each component can add a cleanup function under :biff/stop which will be called
   during shutdown (in reverse order).

   Example:
     (start-system {:biff.datalevin/db-path \"data/myapp\"}
                   [use-datalevin
                    my-custom-component])"
  [initial-system components]
  (reduce (fn [system component]
            (component system))
          (assoc initial-system :biff/stop [])
          components))

(defn stop-system
  "Stops the system by calling all cleanup functions in reverse order.

   Cleanup functions are stored under :biff/stop as a vector of no-arg functions."
  [system]
  (doseq [stop-fn (reverse (:biff/stop system))]
    (try
      (stop-fn)
      (catch Exception e
        (println "Error during shutdown:" (.getMessage e)))))
  (dissoc system :biff/stop))

(defn use-datalevin
  "Component that initializes a Datalevin connection.

   Required keys in system map:
     :biff.datalevin/db-path - Path to the Datalevin database directory

   Optional keys:
     :biff.datalevin/schema - Datalevin schema map
     :biff.datalevin/opts   - Additional options passed to d/get-conn

   Adds to system map:
     :biff.datalevin/conn - The Datalevin connection
     :biff/db             - Current database snapshot (for Biff compatibility)"
  [{:biff.datalevin/keys [db-path schema opts] :as system}]
  (when-not db-path
    (throw (ex-info "Missing required :biff.datalevin/db-path" {})))
  (let [conn (db/get-conn db-path schema opts)]
    (-> system
        (assoc :biff.datalevin/conn conn)
        (assoc :biff/db (db/get-db conn))
        (update :biff/stop conj #(db/close-conn conn)))))

(defn use-session-cleanup
  "Component that periodically removes expired sessions.

   Required keys in system map:
     :biff.datalevin/conn - Datalevin connection

   Optional keys:
     :biff.datalevin.session/cleanup-interval-ms   - Interval between runs (default 3600000)
     :biff.datalevin.session/run-cleanup-on-start? - Run once during startup (default true)
     :biff.datalevin.session/cleanup-fn            - Override cleanup function; primarily useful for testing

   Adds to system map:
     :biff.datalevin.session/cleanup-executor - Scheduled executor for cleanup"
  [{:biff.datalevin/keys [conn] :as system}]
  (when-not conn
    (throw (ex-info "Missing required :biff.datalevin/conn" {})))
  (let [interval-ms (get system :biff.datalevin.session/cleanup-interval-ms (* 60 60 1000))
        run-on-start? (get system :biff.datalevin.session/run-cleanup-on-start? true)
        cleanup-fn (get system :biff.datalevin.session/cleanup-fn session/cleanup-expired-sessions!)]
    (when-not (and (integer? interval-ms) (pos? interval-ms))
      (throw (ex-info "Expected :biff.datalevin.session/cleanup-interval-ms to be a positive integer"
                      {:value interval-ms})))
    (let [task (fn []
                 (try
                   (cleanup-fn system)
                   (catch Exception e
                     (println "Error during session cleanup:" (.getMessage e)))))
          runnable (reify Runnable
                     (run [_]
                       (task)))
          executor (Executors/newSingleThreadScheduledExecutor
                    (reify ThreadFactory
                      (newThread [_ runnable]
                        (doto (Thread. runnable "biff-datalevin-session-cleanup")
                          (.setDaemon true)))))]
      (when run-on-start?
        (task))
      (.scheduleWithFixedDelay executor
                               runnable
                               interval-ms
                               interval-ms
                               TimeUnit/MILLISECONDS)
      (-> system
          (assoc :biff.datalevin.session/cleanup-executor executor)
          (assoc-stop #(.shutdownNow executor))))))

(defn use-config
  "Component that merges configuration into the system map.

   Accepts a config map and merges it into the system map.
   Useful for loading configuration from files or environment variables.

   Example:
     (use-config system {:port 8080 :base-url \"http://localhost:8080\"})"
  [system config]
  (merge system config))

(defn assoc-stop
  "Helper to add a stop function to the system map.

   Example:
     (-> system
         (assoc :my-resource (create-resource))
         (assoc-stop #(close-resource (:my-resource system))))"
  [system stop-fn]
  (update system :biff/stop conj stop-fn))
