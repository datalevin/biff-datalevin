(ns biff.datalevin.core
  "Core system lifecycle management for biff-datalevin.

   Provides a simple map-based component system inspired by Biff's architecture.
   Components are functions that take a system map and return a modified system map."
  (:require [biff.datalevin.db :as db]))

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
