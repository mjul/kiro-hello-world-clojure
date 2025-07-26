(ns sso-web-app.errors
  "Comprehensive error handling and logging for the SSO web application."
  (:require [clojure.tools.logging :as log]
            [ring.util.response :as response]
            [sso-web-app.templates :as templates]
            [cheshire.core :as json])
  (:import [java.sql SQLException]
           [java.io IOException]
           [java.net SocketTimeoutException ConnectException]
           [java.time Instant]))

;; Error types and classifications

(def error-types
  "Classification of different error types for structured handling."
  {:database {:code "DB_ERROR"
              :message "Database operation failed"
              :status 500
              :log-level :error}
   :oauth {:code "OAUTH_ERROR"
           :message "OAuth authentication failed"
           :status 401
           :log-level :warn}
   :network {:code "NETWORK_ERROR"
             :message "Network communication failed"
             :status 503
             :log-level :error}
   :validation {:code "VALIDATION_ERROR"
                :message "Input validation failed"
                :status 400
                :log-level :warn}
   :authorization {:code "AUTHORIZATION_ERROR"
                   :message "Access denied"
                   :status 403
                   :log-level :warn}
   :not-found {:code "NOT_FOUND"
               :message "Resource not found"
               :status 404
               :log-level :info}
   :session {:code "SESSION_ERROR"
             :message "Session management failed"
             :status 401
             :log-level :warn}
   :csrf {:code "CSRF_ERROR"
          :message "CSRF token validation failed"
          :status 403
          :log-level :warn}
   :configuration {:code "CONFIG_ERROR"
                   :message "Configuration error"
                   :status 500
                   :log-level :error}
   :unknown {:code "UNKNOWN_ERROR"
             :message "An unexpected error occurred"
             :status 500
             :log-level :error}})

;; Error classification functions

(defn classify-exception
  "Classify an exception into an error type based on its class and message."
  [exception]
  (cond
    (instance? SQLException exception) :database
    (instance? SocketTimeoutException exception) :network
    (instance? ConnectException exception) :network
    (instance? IOException exception) :network
    (instance? SecurityException exception) :authorization
    (and (instance? RuntimeException exception)
         (re-find #"(?i)(oauth|auth)" (.getMessage exception))) :oauth
    (and (instance? IllegalArgumentException exception)
         (re-find #"(?i)(validation|invalid)" (.getMessage exception))) :validation
    (and (instance? RuntimeException exception)
         (re-find #"(?i)(session)" (.getMessage exception))) :session
    (and (instance? RuntimeException exception)
         (re-find #"(?i)(csrf)" (.getMessage exception))) :csrf
    (and (instance? RuntimeException exception)
         (re-find #"(?i)(config)" (.getMessage exception))) :configuration
    :else :unknown))

(defn get-error-info
  "Get error information for a given error type."
  [error-type]
  (get error-types error-type (:unknown error-types)))

;; Structured logging functions

(defn create-error-context
  "Create structured error context for logging."
  [request exception error-type]
  {:timestamp (.toString (Instant/now))
   :error-type (name error-type)
   :error-code (:code (get-error-info error-type))
   :exception-class (.getName (class exception))
   :exception-message (.getMessage exception)
   :request-uri (:uri request)
   :request-method (name (:request-method request))
   :user-agent (get-in request [:headers "user-agent"])
   :remote-addr (:remote-addr request)
   :session-id (get-in request [:cookies "session-id" :value])
   :user-id (get-in request [:user :id])
   :stack-trace (when (= error-type :unknown)
                  (with-out-str (.printStackTrace exception)))})

(defn log-error
  "Log an error with structured context."
  [request exception error-type & [additional-context]]
  (let [error-info (get-error-info error-type)
        context (merge (create-error-context request exception error-type)
                      additional-context)]
    (case (:log-level error-info)
      :error (log/error exception "Error occurred:" (json/generate-string context))
      :warn (log/warn exception "Warning:" (json/generate-string context))
      :info (log/info "Info:" (json/generate-string context))
      :debug (log/debug "Debug:" (json/generate-string context)))))

;; Authentication event logging

(defn log-auth-event
  "Log authentication-related events with structured data."
  [event-type request & [additional-data]]
  (let [context {:timestamp (.toString (Instant/now))
                 :event-type (name event-type)
                 :request-uri (:uri request)
                 :request-method (name (:request-method request))
                 :user-agent (get-in request [:headers "user-agent"])
                 :remote-addr (:remote-addr request)
                 :session-id (get-in request [:cookies "session-id" :value])
                 :user-id (get-in request [:user :id])
                 :additional-data additional-data}]
    (case event-type
      :login-attempt (log/info "Login attempt:" (json/generate-string context))
      :login-success (log/info "Login successful:" (json/generate-string context))
      :login-failure (log/warn "Login failed:" (json/generate-string context))
      :logout (log/info "User logout:" (json/generate-string context))
      :session-created (log/info "Session created:" (json/generate-string context))
      :session-expired (log/info "Session expired:" (json/generate-string context))
      :session-invalid (log/warn "Invalid session:" (json/generate-string context))
      :oauth-initiated (log/info "OAuth flow initiated:" (json/generate-string context))
      :oauth-callback (log/info "OAuth callback received:" (json/generate-string context))
      :oauth-success (log/info "OAuth authentication successful:" (json/generate-string context))
      :oauth-failure (log/warn "OAuth authentication failed:" (json/generate-string context))
      :csrf-violation (log/warn "CSRF token violation:" (json/generate-string context))
      :unauthorized-access (log/warn "Unauthorized access attempt:" (json/generate-string context))
      (log/info "Authentication event:" (json/generate-string context)))))

;; Error response generation

(defn create-error-response
  "Create an appropriate error response based on error type and request."
  [request exception error-type & [additional-context]]
  (let [error-info (get-error-info error-type)
        accepts (get-in request [:headers "accept"] "text/html")]
    (cond
      ;; JSON API request
      (re-find #"application/json" accepts)
      {:status (:status error-info)
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string
              {:error (:code error-info)
               :message (:message error-info)
               :timestamp (.toString (Instant/now))
               :details additional-context})}
      
      ;; HTML request
      :else
      {:status (:status error-info)
       :headers {"Content-Type" "text/html"}
       :body (templates/error-page 
              (:message error-info)
              (case error-type
                :database "We're experiencing technical difficulties. Please try again later."
                :oauth "Authentication failed. Please try logging in again."
                :network "Unable to connect to external services. Please try again later."
                :validation "The information provided is invalid. Please check and try again."
                :authorization "You don't have permission to access this resource."
                :not-found "The page you're looking for doesn't exist."
                :session "Your session has expired. Please log in again."
                :csrf "Security validation failed. Please refresh the page and try again."
                :configuration "The application is misconfigured. Please contact support."
                "An unexpected error occurred. Please try again later."))})))

;; Specific error handlers

(defn handle-database-error
  "Handle database-specific errors with appropriate logging and response."
  [request exception & [context]]
  (log-error request exception :database context)
  (create-error-response request exception :database context))

(defn handle-oauth-error
  "Handle OAuth-specific errors with appropriate logging and response."
  [request exception & [context]]
  (log-error request exception :oauth context)
  (log-auth-event :oauth-failure request {:error (.getMessage exception)})
  (create-error-response request exception :oauth context))

(defn handle-network-error
  "Handle network-related errors with appropriate logging and response."
  [request exception & [context]]
  (log-error request exception :network context)
  (create-error-response request exception :network context))

(defn handle-validation-error
  "Handle input validation errors with appropriate logging and response."
  [request exception & [context]]
  (log-error request exception :validation context)
  (create-error-response request exception :validation context))

(defn handle-session-error
  "Handle session-related errors with appropriate logging and response."
  [request exception & [context]]
  (log-error request exception :session context)
  (log-auth-event :session-invalid request {:error (.getMessage exception)})
  (create-error-response request exception :session context))

(defn handle-csrf-error
  "Handle CSRF token validation errors with appropriate logging and response."
  [request exception & [context]]
  (log-error request exception :csrf context)
  (log-auth-event :csrf-violation request {:error (.getMessage exception)})
  (create-error-response request exception :csrf context))

;; Global error handling middleware

(defn wrap-error-handling
  "Global error handling middleware for all error types.
   Catches exceptions, classifies them, logs appropriately, and returns user-friendly responses."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception exception
        (let [error-type (classify-exception exception)]
          ;; Log the error with structured context
          (log-error request exception error-type)
          
          ;; Handle specific error types with specialized handlers
          (case error-type
            :database (handle-database-error request exception)
            :oauth (handle-oauth-error request exception)
            :network (handle-network-error request exception)
            :validation (handle-validation-error request exception)
            :session (handle-session-error request exception)
            :csrf (handle-csrf-error request exception)
            ;; Default handling for other error types
            (create-error-response request exception error-type)))))))

;; Error recovery mechanisms

(defn with-retry
  "Execute a function with retry logic for transient errors."
  [f max-retries delay-ms & [retry-predicate]]
  (let [retry-pred (or retry-predicate 
                      #(or (instance? SocketTimeoutException %)
                           (instance? ConnectException %)))]
    (loop [attempt 1]
      (let [result (try
                     {:success true :result (f)}
                     (catch Exception e
                       {:success false :exception e}))]
        (if (:success result)
          (:result result)
          (let [e (:exception result)]
            (if (and (< attempt max-retries) (retry-pred e))
              (do
                (log/warn "Attempt" attempt "failed, retrying in" delay-ms "ms:" (.getMessage e))
                (Thread/sleep delay-ms)
                (recur (inc attempt)))
              (throw e))))))))

(defn with-circuit-breaker
  "Simple circuit breaker implementation for external service calls."
  [f failure-threshold reset-timeout-ms]
  (let [state (atom {:failures 0 :last-failure nil :state :closed})]
    (fn [& args]
      (let [current-state @state
            now (System/currentTimeMillis)]
        (cond
          ;; Circuit is open - check if we should try again
          (= (:state current-state) :open)
          (if (> (- now (:last-failure current-state)) reset-timeout-ms)
            (do
              (swap! state assoc :state :half-open)
              (try
                (let [result (apply f args)]
                  (swap! state assoc :failures 0 :state :closed)
                  result)
                (catch Exception e
                  (swap! state assoc :state :open :last-failure now)
                  (throw e))))
            (throw (RuntimeException. "Circuit breaker is open")))
          
          ;; Circuit is closed or half-open - try the operation
          :else
          (try
            (let [result (apply f args)]
              (when (= (:state current-state) :half-open)
                (swap! state assoc :failures 0 :state :closed))
              result)
            (catch Exception e
              (let [new-failures (inc (:failures current-state))]
                (if (>= new-failures failure-threshold)
                  (swap! state assoc :failures new-failures :state :open :last-failure now)
                  (swap! state assoc :failures new-failures))
                (throw e)))))))))

;; Health check and monitoring

(defn create-health-check
  "Create a health check function that tests critical system components."
  [db-check-fn external-service-checks]
  (fn []
    (let [checks (atom {:overall :healthy :timestamp (.toString (Instant/now)) :checks {}})
          check-component (fn [name check-fn]
                           (try
                             (check-fn)
                             (swap! checks assoc-in [:checks name] {:status :healthy})
                             (catch Exception e
                               (swap! checks assoc-in [:checks name] 
                                     {:status :unhealthy :error (.getMessage e)})
                               (swap! checks assoc :overall :unhealthy))))]
      
      ;; Check database
      (check-component :database db-check-fn)
      
      ;; Check external services
      (doseq [[service-name check-fn] external-service-checks]
        (check-component service-name check-fn))
      
      @checks)))

;; Utility functions for error handling

(defn safe-execute
  "Safely execute a function and return either the result or an error map."
  [f & [error-context]]
  (try
    {:success true :result (f)}
    (catch Exception e
      {:success false 
       :error {:type (classify-exception e)
               :message (.getMessage e)
               :context error-context}})))

(defn log-and-rethrow
  "Log an exception and rethrow it (useful for debugging)."
  [exception context]
  (log/error exception "Exception in context:" context)
  (throw exception))