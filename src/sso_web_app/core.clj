(ns sso-web-app.core
  "Application entry point and server lifecycle management."
  (:require [ring.adapter.jetty :as jetty]
            [sso-web-app.routes :as routes]
            [sso-web-app.db :as db]
            [sso-web-app.middleware :as middleware]
            [clojure.tools.logging :as log]
            [environ.core :refer [env]]
            [clojure.string :as str])
  (:gen-class))
;; Application configuration

(def default-config
  "Default application configuration values."
  {:port 3000
   :host "0.0.0.0"
   :database-url "jdbc:sqlite:sso_web_app.db"
   :session-secret "change-me-in-production"
   :base-url "http://localhost:3000"
   :join? false  ; Don't block main thread in development
   :max-threads 50
   :min-threads 8
   :max-idle-time 60000})

(defn load-config
  "Load application configuration from environment variables with defaults."
  []
  (let [config (merge default-config
                     {:port (Integer/parseInt (or (env :port) "3000"))
                      :host (or (env :host) "0.0.0.0")
                      :database-url (or (env :database-url) "jdbc:sqlite:sso_web_app.db")
                      :session-secret (or (env :session-secret) "change-me-in-production")
                      :base-url (or (env :base-url) "http://localhost:3000")
                      :microsoft-client-id (env :microsoft-client-id)
                      :microsoft-client-secret (env :microsoft-client-secret)
                      :github-client-id (env :github-client-id)
                      :github-client-secret (env :github-client-secret)
                      :join? (Boolean/parseBoolean (or (env :join) "false"))
                      :max-threads (Integer/parseInt (or (env :max-threads) "50"))
                      :min-threads (Integer/parseInt (or (env :min-threads) "8"))
                      :max-idle-time (Integer/parseInt (or (env :max-idle-time) "60000"))})]
    (log/info "Loaded configuration:" (dissoc config :session-secret :microsoft-client-secret :github-client-secret))
    config))

(defn validate-config
  "Validate application configuration and return validation results."
  [config]
  (let [errors (atom [])]
    
    ;; Validate required OAuth credentials
    (when (str/blank? (:microsoft-client-id config))
      (swap! errors conj "Microsoft OAuth client ID is required"))
    (when (str/blank? (:microsoft-client-secret config))
      (swap! errors conj "Microsoft OAuth client secret is required"))
    (when (str/blank? (:github-client-id config))
      (swap! errors conj "GitHub OAuth client ID is required"))
    (when (str/blank? (:github-client-secret config))
      (swap! errors conj "GitHub OAuth client secret is required"))
    
    ;; Validate session secret
    (when (or (str/blank? (:session-secret config))
              (= (:session-secret config) "change-me-in-production")
              (< (count (:session-secret config)) 32))
      (swap! errors conj "Session secret must be at least 32 characters and not use default value"))
    
    ;; Validate port range
    (when (or (< (:port config) 1) (> (:port config) 65535))
      (swap! errors conj "Port must be between 1 and 65535"))
    
    ;; Validate thread pool settings
    (when (< (:max-threads config) (:min-threads config))
      (swap! errors conj "Max threads must be greater than or equal to min threads"))
    
    ;; Validate base URL format
    (when-not (re-matches #"^https?://[^/]+.*" (:base-url config))
      (swap! errors conj "Base URL must be a valid HTTP/HTTPS URL"))
    
    {:valid? (empty? @errors)
     :errors @errors
     :config config}))

;; Server lifecycle management

(defonce server-state (atom {:server nil :config nil :running? false}))

(defn start-server
  "Initialize and start the web server with the given configuration."
  [config]
  (try
    (log/info "Starting SSO Web Application server...")
    
    ;; Initialize database
    (log/info "Initializing database...")
    (db/setup-db!)
    (log/info "Database initialization completed")
    
    ;; Create Jetty server configuration
    (let [jetty-options {:port (:port config)
                        :host (:host config)
                        :join? (:join? config)
                        :max-threads (:max-threads config)
                        :min-threads (:min-threads config)
                        :max-idle-time (:max-idle-time config)
                        :send-server-version? false
                        :send-date-header? false}
          
          ;; Start the server
          server (jetty/run-jetty routes/app jetty-options)]
      
      ;; Update server state
      (swap! server-state assoc 
             :server server 
             :config config 
             :running? true)
      
      (log/info "Server started successfully on" (:host config) ":" (:port config))
      (log/info "Application available at:" (:base-url config))
      
      server)
    
    (catch Exception e
      (log/error e "Failed to start server")
      (swap! server-state assoc :running? false)
      (throw e))))

(defn stop-server
  "Gracefully shutdown the server and clean up resources."
  []
  (try
    (log/info "Shutting down SSO Web Application server...")
    
    (when-let [server (:server @server-state)]
      (log/info "Stopping Jetty server...")
      (.stop server)
      (log/info "Jetty server stopped"))
    
    ;; Clean up expired sessions
    (log/info "Cleaning up expired sessions...")
    (middleware/cleanup-expired-sessions)
    
    ;; Update server state
    (swap! server-state assoc :server nil :running? false)
    
    (log/info "Server shutdown completed successfully")
    true
    
    (catch Exception e
      (log/error e "Error during server shutdown")
      false)))

(defn restart-server
  "Restart the server with current or new configuration."
  ([]
   (restart-server (:config @server-state)))
  ([config]
   (log/info "Restarting server...")
   (stop-server)
   (Thread/sleep 1000) ; Brief pause to ensure clean shutdown
   (start-server config)))

(defn server-running?
  "Check if the server is currently running."
  []
  (:running? @server-state))

(defn get-server-info
  "Get current server information."
  []
  (let [state @server-state]
    {:running? (:running? state)
     :config (when (:config state)
               (dissoc (:config state) :session-secret :microsoft-client-secret :github-client-secret))
     :server-class (when (:server state)
                     (class (:server state)))}))

;; Application initialization

(defn init-app
  "Initialize application components and validate configuration."
  []
  (try
    (log/info "Initializing SSO Web Application...")
    
    ;; Load and validate configuration
    (let [config (load-config)
          validation (validate-config config)]
      
      (if (:valid? validation)
        (do
          (log/info "Configuration validation passed")
          config)
        (do
          (log/error "Configuration validation failed:")
          (doseq [error (:errors validation)]
            (log/error "  -" error))
          (throw (ex-info "Invalid configuration" 
                         {:errors (:errors validation)
                          :config (:config validation)})))))
    
    (catch Exception e
      (log/error e "Application initialization failed")
      (throw e))))

;; Shutdown hook for graceful termination

(defn add-shutdown-hook
  "Add JVM shutdown hook for graceful server termination."
  []
  (.addShutdownHook 
    (Runtime/getRuntime)
    (Thread. 
      (fn []
        (log/info "Received shutdown signal")
        (stop-server)))))

;; Health check endpoint

(defn health-check
  "Perform application health check."
  []
  (try
    (let [db-healthy? (try
                        (db/cleanup-expired-sessions!)
                        true
                        (catch Exception e
                          (log/error e "Database health check failed")
                          false))
          server-healthy? (server-running?)]
      
      {:healthy? (and db-healthy? server-healthy?)
       :database db-healthy?
       :server server-healthy?
       :timestamp (java.time.Instant/now)})
    
    (catch Exception e
      (log/error e "Health check failed")
      {:healthy? false
       :error (.getMessage e)
       :timestamp (java.time.Instant/now)})))

;; Main application entry point

(defn -main
  "Main application entry point."
  [& args]
  (try
    (log/info "Starting SSO Web Application...")
    
    ;; Initialize application
    (let [config (init-app)]
      
      ;; Add shutdown hook for graceful termination
      (add-shutdown-hook)
      
      ;; Start the server
      (start-server config)
      
      ;; If join? is true, block main thread (for production)
      (when (:join? config)
        (log/info "Server running in blocking mode")
        (.join (:server @server-state))))
    
    (catch Exception e
      (log/error e "Application startup failed")
      (System/exit 1))))

;; Development utilities

(defn dev-start
  "Start server in development mode (non-blocking)."
  []
  (let [config (assoc (init-app) :join? false)]
    (start-server config)))

(defn dev-stop
  "Stop development server."
  []
  (stop-server))

(defn dev-restart
  "Restart development server."
  []
  (restart-server))

(defn dev-status
  "Get development server status."
  []
  (get-server-info))