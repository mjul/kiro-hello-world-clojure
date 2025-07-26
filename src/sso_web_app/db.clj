(ns sso-web-app.db
  "Database operations and user management."
  (:require [clojure.java.jdbc :as jdbc]
            [sso-web-app.errors :as errors]
            [clojure.tools.logging :as log]
            [environ.core :refer [env]])
  (:import [java.util UUID]
           [java.sql SQLException]
           [java.time Instant]))

;; Database configuration
(def ^:dynamic *db-config*
  {:classname "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname (or (env :database-url) "sso_web_app.db")})

(defn get-db-config
  "Get the current database configuration."
  []
  *db-config*)

;; Database schema definitions
(def users-table-ddl
  "CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    provider TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    username TEXT NOT NULL,
    email TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(provider, provider_id)
  )")

(def sessions-table-ddl
  "CREATE TABLE IF NOT EXISTS sessions (
    session_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id)
  )")

(def users-provider-index-ddl
  "CREATE INDEX IF NOT EXISTS idx_users_provider ON users(provider, provider_id)")

(def sessions-expires-index-ddl
  "CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at)")

(defn create-connection
  "Create a database connection."
  []
  (try
    (jdbc/get-connection (get-db-config))
    (catch SQLException e
      (log/error e "Failed to create database connection")
      (throw e))))

(defn execute-ddl!
  "Execute a DDL statement."
  [ddl-statement]
  (try
    (jdbc/execute! (get-db-config) [ddl-statement])
    (log/info "Successfully executed DDL:" ddl-statement)
    (catch SQLException e
      (log/error e "Failed to execute DDL:" ddl-statement)
      (throw e))))

(defn init-db!
  "Initialize the database with required tables and indexes."
  []
  (log/info "Initializing database...")
  (try
    (jdbc/with-db-transaction [tx (get-db-config)]
      ;; Create tables
      (jdbc/execute! tx [users-table-ddl])
      (log/info "Successfully executed DDL:" users-table-ddl)
      
      (jdbc/execute! tx [sessions-table-ddl])
      (log/info "Successfully executed DDL:" sessions-table-ddl)
      
      ;; Create indexes
      (jdbc/execute! tx [users-provider-index-ddl])
      (log/info "Successfully executed DDL:" users-provider-index-ddl)
      
      (jdbc/execute! tx [sessions-expires-index-ddl])
      (log/info "Successfully executed DDL:" sessions-expires-index-ddl))
    
    (log/info "Database initialization completed successfully")
    true
    (catch Exception e
      (log/error e "Database initialization failed")
      (throw e))))

(defn migrate-db!
  "Run database migrations. Currently just calls init-db! but can be extended."
  []
  (log/info "Running database migrations...")
  (init-db!)
  true)

(defn setup-db!
  "Complete database setup including initialization and migrations."
  []
  (log/info "Setting up database...")
  (migrate-db!)
  true)

;; User CRUD operations

(defn generate-uuid
  "Generate a UUID string."
  []
  (str (UUID/randomUUID)))

(defn create-user!
  "Create a new user record."
  ([provider provider-id username email]
   (create-user! provider provider-id username email (get-db-config)))
  ([provider provider-id username email db-config]
   (let [user-id (generate-uuid)
         user {:id user-id
               :provider provider
               :provider_id provider-id
               :username username
               :email email}]
     (try
       (jdbc/insert! db-config :users user)
       (log/info "Created user:" user-id "for provider:" provider)
       user
       (catch SQLException e
         (log/error e "Failed to create user for provider:" provider "provider-id:" provider-id)
         (throw (RuntimeException. "Database error: Failed to create user" e)))))))

(defn find-user-by-provider-id
  "Find a user by provider and provider ID."
  ([provider provider-id]
   (find-user-by-provider-id provider provider-id (get-db-config)))
  ([provider provider-id db-config]
   (try
     (let [users (jdbc/query db-config 
                            ["SELECT * FROM users WHERE provider = ? AND provider_id = ?" 
                             provider provider-id])]
       (first users))
     (catch SQLException e
       (log/error e "Failed to find user by provider:" provider "provider-id:" provider-id)
       (throw (RuntimeException. "Database error: Failed to find user" e))))))

(defn find-user-by-id
  "Find a user by their ID."
  ([user-id]
   (find-user-by-id user-id (get-db-config)))
  ([user-id db-config]
   (try
     (let [users (jdbc/query db-config 
                            ["SELECT * FROM users WHERE id = ?" user-id])]
       (first users))
     (catch SQLException e
       (log/error e "Failed to find user by id:" user-id)
       (throw e)))))

(defn update-user!
  "Update user information."
  [user-id updates]
  (try
    (let [update-map (assoc updates :updated_at (java.time.Instant/now))
          result (jdbc/update! (get-db-config) :users update-map ["id = ?" user-id])]
      (log/info "Updated user:" user-id)
      (first result))
    (catch SQLException e
      (log/error e "Failed to update user:" user-id)
      (throw e))))

;; Session management functions

(defn create-session!
  "Create a new session for a user."
  ([user-id expires-at]
   (create-session! user-id expires-at (get-db-config)))
  ([user-id expires-at db-config]
   (let [session-id (generate-uuid)
         session {:session_id session-id
                  :user_id user-id
                  :expires_at expires-at}]
     (try
       (jdbc/insert! db-config :sessions session)
       (log/info "Created session:" session-id "for user:" user-id)
       session
       (catch SQLException e
         (log/error e "Failed to create session for user:" user-id)
         (throw (RuntimeException. "Database error: Failed to create session" e)))))))

(defn find-session
  "Find a session by session ID."
  ([session-id]
   (find-session session-id (get-db-config)))
  ([session-id db-config]
   (try
     (let [sessions (jdbc/query db-config 
                               ["SELECT * FROM sessions WHERE session_id = ?" session-id])]
       (first sessions))
     (catch SQLException e
       (log/error e "Failed to find session:" session-id)
       (throw e)))))

(defn delete-session!
  "Delete a session."
  ([session-id]
   (delete-session! session-id (get-db-config)))
  ([session-id db-config]
   (try
     (let [result (jdbc/delete! db-config :sessions ["session_id = ?" session-id])]
       (log/info "Deleted session:" session-id)
       (first result))
     (catch SQLException e
       (log/error e "Failed to delete session:" session-id)
       (throw e)))))

(defn validate-session
  "Validate a session and return the associated user if valid."
  ([session-id]
   (validate-session session-id (get-db-config)))
  ([session-id db-config]
   (try
     (let [session (find-session session-id db-config)]
       (when session
         (let [expires-at (java.time.Instant/parse (:expires_at session))
               now (java.time.Instant/now)]
           (if (.isBefore now expires-at)
             (find-user-by-id (:user_id session) db-config)
             (do
               (log/info "Session expired:" session-id)
               (delete-session! session-id db-config)
               nil)))))
     (catch Exception e
       (log/error e "Failed to validate session:" session-id)
       nil))))

(defn cleanup-expired-sessions!
  "Remove all expired sessions."
  []
  (try
    (let [now (java.time.Instant/now)
          result (jdbc/delete! (get-db-config) :sessions ["expires_at < ?" (.toString now)])]
      (log/info "Cleaned up expired sessions, removed:" (first result))
      (first result))
    (catch SQLException e
      (log/error e "Failed to cleanup expired sessions")
      (throw e))))

;; Database transaction utilities

(defn with-transaction
  "Execute a function within a database transaction."
  [f]
  (try
    (jdbc/with-db-transaction [tx (get-db-config)]
      (f tx))
    (catch Exception e
      (log/error e "Transaction failed")
      (throw e))))

(defn execute-in-transaction!
  "Execute multiple database operations in a single transaction."
  [operations]
  (with-transaction
    (fn [tx]
      (doall (map #(% tx) operations)))))

;; Combined user operations

(defn create-or-update-user!
  "Create a new user or update existing user based on provider and provider-id.
   Takes a user profile map with :provider, :provider-id, :username, and :email."
  ([user-profile]
   (create-or-update-user! user-profile (get-db-config)))
  ([user-profile db-config]
   (let [{:keys [provider provider-id username email]} user-profile]
     (try
       (if-let [existing-user (find-user-by-provider-id provider provider-id db-config)]
         ;; Update existing user
         (do
           (update-user! (:id existing-user) {:username username :email email})
           (log/info "Updated existing user:" (:id existing-user) "for provider:" provider)
           (find-user-by-id (:id existing-user)))
         ;; Create new user
         (do
           (log/info "Creating new user for provider:" provider "provider-id:" provider-id)
           (create-user! provider provider-id username email db-config)))
       (catch Exception e
         (log/error e "Failed to create or update user for provider:" provider)
         (throw e))))))