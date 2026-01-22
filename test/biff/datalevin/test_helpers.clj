(ns biff.datalevin.test-helpers
  "Test utilities and fixtures for biff-datalevin tests."
  (:require [biff.datalevin.db :as db]
            [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def test-schema
  "Schema used for testing."
  {:user/id {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :user/email {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :user/username {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :user/password-hash {:db/valueType :db.type/string}
   :user/github-id {:db/valueType :db.type/long :db/unique :db.unique/identity}
   :user/github-username {:db/valueType :db.type/string}
   :user/avatar-url {:db/valueType :db.type/string}
   :user/role {:db/valueType :db.type/keyword}
   :user/email-verified? {:db/valueType :db.type/boolean}
   :user/created-at {:db/valueType :db.type/instant}

   :session/id {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :session/user {:db/valueType :db.type/ref}
   :session/expires-at {:db/valueType :db.type/instant}

   :verification-token/token {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :verification-token/user {:db/valueType :db.type/ref}
   :verification-token/expires-at {:db/valueType :db.type/instant}})

(defn create-temp-dir
  "Creates a temporary directory for testing."
  []
  (str (Files/createTempDirectory "biff-datalevin-test"
                                  (into-array FileAttribute []))))

(defn delete-dir
  "Recursively deletes a directory."
  [path]
  (let [dir (io/file path)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))]
        (.delete f)))))

(defmacro with-temp-conn
  "Creates a temporary Datalevin connection for testing.

   Usage:
     (with-temp-conn [conn]
       (db/submit-tx conn [...])
       (is (= expected (db/lookup conn ...))))"
  [[conn-sym & {:keys [schema]}] & body]
  `(let [temp-dir# (create-temp-dir)
         ~conn-sym (db/get-conn temp-dir# (or ~schema test-schema))]
     (try
       ~@body
       (finally
         (db/close-conn ~conn-sym)
         (delete-dir temp-dir#)))))

(defmacro with-temp-system
  "Creates a temporary system with Datalevin for testing.

   Usage:
     (with-temp-system [system]
       (db/submit-tx system [...])
       (is (= expected (db/lookup system ...))))"
  [[system-sym & {:keys [schema]}] & body]
  `(let [temp-dir# (create-temp-dir)
         conn# (db/get-conn temp-dir# (or ~schema test-schema))
         ~system-sym {:biff.datalevin/conn conn#}]
     (try
       ~@body
       (finally
         (db/close-conn conn#)
         (delete-dir temp-dir#)))))
