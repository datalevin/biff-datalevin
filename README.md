# biff-datalevin

A Clojure library that adapts [Biff](https://biffweb.com/) Web framework to use [Datalevin](https://github.com/juji-io/datalevin) as the database.

## Features

- **System lifecycle management** - Simple map-based component system inspired by Biff
- **Database utilities** - Connection management, transaction helpers, and query utilities
- **Authentication** - Password hashing with bcrypt, OAuth support (GitHub and generic providers)
- **Session management** - Datalevin-backed sessions with Ring session store
- **Middleware** - Authentication, CSRF protection, and request handling

## Installation

Add to your `deps.edn`:

```clojure
{:deps {io.github.datalevin/biff-datalevin {:git/tag "v0.1.0" :git/sha "..."}}}
```

## Biff Integration

This library is designed to work as a drop-in Datalevin component for Biff applications:

```clojure
(ns myapp.core
  (:require [com.biffweb :as biff]
            [biff.datalevin.core :as dl]
            [biff.datalevin.db :as db]))

;; Use with Biff's start-system
(def initial-system
  {:biff.datalevin/db-path "data/myapp"
   :biff.datalevin/schema my-schema
   ;; ... other Biff config
   })

(def components
  [dl/use-datalevin  ;; Adds :biff.datalevin/conn and :biff/db
   ;; ... other Biff components
   ])

;; use-datalevin sets both:
;;   :biff.datalevin/conn - The Datalevin connection
;;   :biff/db             - Database snapshot (Biff compatibility)
```

After transactions, refresh `:biff/db` to see new data:

```clojure
(db/submit-tx ctx [{:user/id (UUID/randomUUID) :user/email "new@example.com"}])
(let [ctx (db/assoc-db ctx)]  ;; Refresh :biff/db
  (db/lookup ctx :user/email "new@example.com"))
```

## Quick Start

```clojure
(ns myapp.core
  (:require [biff.datalevin.core :as core]
            [biff.datalevin.db :as db]
            [biff.datalevin.auth :as auth]
            [biff.datalevin.middleware :as mw]))

;; Define your schema
(def schema
  {:user/id {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :user/email {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :user/password-hash {:db/valueType :db.type/string}})

;; Start the system
(def system
  (core/start-system
    {:biff.datalevin/db-path "data/myapp"
     :biff.datalevin/schema schema}
    [core/use-datalevin]))

;; Create a user
(let [user-tx (auth/create-user-tx {:user/email "user@example.com"
                                     :password "secret123"})]
  (db/submit-tx system [user-tx]))

;; Query users
(db/lookup system :user/email "user@example.com")

;; Stop the system
(core/stop-system system)
```

## Modules

### Core (`biff.datalevin.core`)

System lifecycle management:

```clojure
;; Start a system with components
(def system
  (core/start-system
    {:biff.datalevin/db-path "data/myapp"
     :biff.datalevin/schema my-schema
     :port 8080}
    [core/use-datalevin
     my-custom-component]))

;; Stop the system (calls cleanup functions in reverse order)
(core/stop-system system)

;; Add cleanup functions to a component
(defn my-component [system]
  (let [resource (create-resource)]
    (-> system
        (assoc :my-resource resource)
        (core/assoc-stop #(close-resource resource)))))
```

### Database (`biff.datalevin.db`)

Connection and query utilities:

```clojure
;; Submit transactions with special values
(db/submit-tx system [{:user/id (java.util.UUID/randomUUID)
                       :user/email "new@example.com"
                       :user/created-at :db/now}])  ; :db/now -> current Date

;; Lookup single entity
(db/lookup system :user/email "user@example.com")
;; => {:user/id #uuid "...", :user/email "user@example.com", ...}

;; Lookup with custom pull expression
(db/lookup system :user/email "user@example.com" [:user/id :user/email])

;; Lookup all matching entities
(db/lookup-all system :user/role :admin)

;; Check existence
(db/entity-exists? system :user/email "user@example.com")

;; Run queries
(db/q '[:find ?e
        :where [?e :user/role :admin]]
      system)

;; Update entities
(db/submit-tx system [(db/merge-tx [:user/id user-id]
                                    {:user/name "New Name"})])

;; Delete entities
(db/submit-tx system [(db/delete-tx [:user/id user-id])])
```

### Authentication (`biff.datalevin.auth`)

Password and OAuth authentication:

```clojure
;; Password hashing
(auth/hash-password "secret")
(auth/verify-password "secret" hash)

;; Create user with password
(let [user-tx (auth/create-user-tx {:user/email "user@example.com"
                                     :user/username "myuser"
                                     :password "secret123"})]
  (db/submit-tx system [user-tx]))

;; Authenticate user
(auth/authenticate-user system "user@example.com" "secret123")
;; => {:user/id #uuid "...", :user/email "user@example.com", ...} or nil

;; GitHub OAuth
(auth/github-authorize-url
  {:client-id "your-client-id"
   :redirect-uri "http://localhost:8080/auth/github/callback"
   :state "csrf-token"})

;; Exchange code for token
(let [token-response (auth/github-exchange-code
                       {:client-id "..."
                        :client-secret "..."
                        :code code
                        :redirect-uri "..."})]
  (auth/github-get-user (:access_token token-response)))

;; Email verification tokens
(let [{:keys [token tx]} (auth/create-verification-token user-id)]
  (db/submit-tx system [tx])
  ;; Send token to user via email...
  )

;; Verify token
(auth/verify-token system token)
;; => user-id or nil
```

### Sessions (`biff.datalevin.session`)

Datalevin-backed session management:

```clojure
;; Create a session
(let [{:keys [session-id tx]} (session/create-session user-id)]
  (db/submit-tx system [tx])
  session-id)

;; Get session with user data
(session/get-session system session-id)
;; => {:session/id ..., :session/user {:user/id ..., ...}, :session/expires-at ...}

;; Get just the user
(session/get-session-user system session-id)

;; Delete session
(when-let [delete-tx (session/delete-session-tx system session-id)]
  (db/submit-tx system [delete-tx]))

;; JWT tokens for stateless auth
(def secret "your-32-byte-secret-key-here!!!")
(session/create-session-token session-id {:secret secret})
(session/verify-session-token token secret)

;; Ring session store
(require '[ring.middleware.session :refer [wrap-session]])

(-> handler
    (wrap-session {:store (session/datalevin-session-store conn)}))
```

### Middleware (`biff.datalevin.middleware`)

Ring middleware stack:

```clojure
;; Full site middleware (sessions, CSRF, auth)
(def handler
  (-> my-routes
      (mw/wrap-site-defaults
        {:context {:biff.datalevin/conn conn}
         :session-secret "your-32-byte-secret!!!!"
         :csrf? true
         :auth? true})))

;; API middleware (JWT auth, no CSRF)
(def api-handler
  (-> my-api-routes
      (mw/wrap-api-defaults
        {:context {:biff.datalevin/conn conn}
         :session-secret "your-32-byte-secret!!!!"})))

;; Require authentication
(-> handler
    (mw/wrap-require-auth {:redirect "/login"}))

;; Require specific role
(-> handler
    (mw/wrap-require-role {:role :admin :redirect "/forbidden"}))

;; CSRF token in forms
[:form {:method "post"}
 (mw/csrf-input)
 [:button "Submit"]]
```

## Schema Reference

Recommended schema for common entities:

```clojure
(def schema
  {;; Users
   :user/id           {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :user/email        {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :user/username     {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :user/password-hash {:db/valueType :db.type/string}
   :user/github-id    {:db/valueType :db.type/long :db/unique :db.unique/identity}
   :user/github-username {:db/valueType :db.type/string}
   :user/avatar-url   {:db/valueType :db.type/string}
   :user/role         {:db/valueType :db.type/keyword}
   :user/created-at   {:db/valueType :db.type/instant}

   ;; Sessions
   :session/id        {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :session/user      {:db/valueType :db.type/ref}
   :session/expires-at {:db/valueType :db.type/instant}

   ;; Verification tokens
   :verification-token/token {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :verification-token/user {:db/valueType :db.type/ref}
   :verification-token/expires-at {:db/valueType :db.type/instant}})
```

## Important Notes

### Datalevin vs. Datomic/XTDB

This library is designed for Datalevin, which has some differences from Datomic/XTDB:

1. **Entity creation**: Don't use lookup refs as `:db/id` for new entities. Just include the unique attribute:
   ```clojure
   ;; Correct
   {:user/id (UUID/randomUUID) :user/email "user@example.com"}

   ;; Incorrect (won't work)
   {:db/id [:user/id some-uuid] :user/email "user@example.com"}
   ```

2. **Entity updates**: Use lookup refs as `:db/id` for updating existing entities:
   ```clojure
   {:db/id [:user/id existing-uuid] :user/name "New Name"}
   ```

3. **References**: Lookup refs like `[:user/id uuid]` can be used for `:db/valueType :db.type/ref` attributes, but the referenced entity must exist first.

4. **Retractions**: Use entity IDs (numbers) for `:db/retractEntity`, not lookup refs. The helper functions handle this automatically.

## Development

### Testing

```bash
clj -M:test
```

### Building

```bash
# Build JAR only
clj -T:build jar

# Clean target directory
clj -T:build clean
```

### Deploying to Clojars

```bash
# Set credentials (use a deploy token from https://clojars.org/tokens)
export CLOJARS_USERNAME=your-username
export CLOJARS_PASSWORD=CLOJARS_xxxxxxxxx

# Build and deploy
clj -T:build deploy
```

## License

MIT License
