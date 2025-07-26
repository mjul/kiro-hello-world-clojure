(ns sso-web-app.errors-test
  "Comprehensive tests for error handling and logging functionality."
  (:require [clojure.test :refer :all]
            [sso-web-app.errors :as errors]
            [sso-web-app.templates :as templates]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [ring.mock.request :as mock]
            [clojure.string :as str])
  (:import [java.sql SQLException]
           [java.io IOException]
           [java.net SocketTimeoutException ConnectException]
           [java.time Instant]))

;; Test fixtures and utilities

(def sample-request
  "Sample Ring request for testing."
  {:uri "/test"
   :request-method :get
   :headers {"user-agent" "test-browser/1.0"
             "accept" "text/html"}
   :remote-addr "127.0.0.1"
   :cookies {"session-id" {:value "test-session-123"}}
   :user {:id "user-123" :username "testuser"}})

(def sample-json-request
  "Sample Ring request expecting JSON response."
  (assoc-in sample-request [:headers "accept"] "application/json"))

(defn capture-logs
  "Capture log output during test execution."
  [f]
  (let [log-output (atom [])]
    (with-redefs [log/info (fn [& args] 
                            (swap! log-output conj {:level :info :args args})
                            (apply println "INFO:" args))
                  log/warn (fn [& args] 
                            (swap! log-output conj {:level :warn :args args})
                            (apply println "WARN:" args))
                  log/error (fn [& args] 
                             (swap! log-output conj {:level :error :args args})
                             (apply println "ERROR:" args))
                  log/debug (fn [& args] 
                             (swap! log-output conj {:level :debug :args args})
                             (apply println "DEBUG:" args))]
      (let [result (f)]
        {:result result :logs @log-output}))))

;; Error classification tests

(deftest test-classify-exception
  (testing "Database exceptions"
    (is (= :database (errors/classify-exception (SQLException. "Connection failed")))))
  
  (testing "Network exceptions"
    (is (= :network (errors/classify-exception (SocketTimeoutException. "Timeout"))))
    (is (= :network (errors/classify-exception (ConnectException. "Connection refused"))))
    (is (= :network (errors/classify-exception (IOException. "Network error")))))
  
  (testing "Security exceptions"
    (is (= :authorization (errors/classify-exception (SecurityException. "Access denied")))))
  
  (testing "OAuth exceptions"
    (is (= :oauth (errors/classify-exception (RuntimeException. "OAuth authentication failed"))))
    (is (= :oauth (errors/classify-exception (RuntimeException. "auth provider error")))))
  
  (testing "Validation exceptions"
    (is (= :validation (errors/classify-exception (IllegalArgumentException. "Invalid input"))))
    (is (= :validation (errors/classify-exception (IllegalArgumentException. "Validation failed")))))
  
  (testing "Session exceptions"
    (is (= :session (errors/classify-exception (RuntimeException. "Session expired")))))
  
  (testing "CSRF exceptions"
    (is (= :csrf (errors/classify-exception (RuntimeException. "CSRF token invalid")))))
  
  (testing "Configuration exceptions"
    (is (= :configuration (errors/classify-exception (RuntimeException. "Config error")))))
  
  (testing "Unknown exceptions"
    (is (= :unknown (errors/classify-exception (RuntimeException. "Some random error"))))))

(deftest test-get-error-info
  (testing "Database error info"
    (let [info (errors/get-error-info :database)]
      (is (= "DB_ERROR" (:code info)))
      (is (= 500 (:status info)))
      (is (= :error (:log-level info)))))
  
  (testing "OAuth error info"
    (let [info (errors/get-error-info :oauth)]
      (is (= "OAUTH_ERROR" (:code info)))
      (is (= 401 (:status info)))
      (is (= :warn (:log-level info)))))
  
  (testing "Unknown error defaults"
    (let [info (errors/get-error-info :nonexistent)]
      (is (= "UNKNOWN_ERROR" (:code info)))
      (is (= 500 (:status info)))
      (is (= :error (:log-level info))))))

;; Error context creation tests

(deftest test-create-error-context
  (testing "Error context creation with full request"
    (let [exception (RuntimeException. "Test error")
          context (errors/create-error-context sample-request exception :database)]
      (is (string? (:timestamp context)))
      (is (= "database" (:error-type context)))
      (is (= "DB_ERROR" (:error-code context)))
      (is (= "java.lang.RuntimeException" (:exception-class context)))
      (is (= "Test error" (:exception-message context)))
      (is (= "/test" (:request-uri context)))
      (is (= "get" (:request-method context)))
      (is (= "test-browser/1.0" (:user-agent context)))
      (is (= "127.0.0.1" (:remote-addr context)))
      (is (= "test-session-123" (:session-id context)))
      (is (= "user-123" (:user-id context)))))
  
  (testing "Error context with minimal request"
    (let [exception (RuntimeException. "Test error")
          minimal-request {:uri "/test" :request-method :post}
          context (errors/create-error-context minimal-request exception :oauth)]
      (is (= "/test" (:request-uri context)))
      (is (= "post" (:request-method context)))
      (is (nil? (:user-agent context)))
      (is (nil? (:session-id context)))
      (is (nil? (:user-id context)))))
  
  (testing "Stack trace included for unknown errors"
    (let [exception (RuntimeException. "Unknown error")
          context (errors/create-error-context sample-request exception :unknown)]
      (is (contains? context :stack-trace))
      ;; Stack trace might be empty in test environment, so just check it exists
      (is (or (nil? (:stack-trace context)) (string? (:stack-trace context)))))))

;; Structured logging tests

(deftest test-log-error
  (testing "Error logging with different levels"
    (let [exception (RuntimeException. "Test error")]
      
      ;; Test error level logging
      (let [{:keys [logs]} (capture-logs 
                            #(errors/log-error sample-request exception :database))]
        (is (= 1 (count logs)))
        (is (= :error (:level (first logs)))))
      
      ;; Test warn level logging
      (let [{:keys [logs]} (capture-logs 
                            #(errors/log-error sample-request exception :oauth))]
        (is (= 1 (count logs)))
        (is (= :warn (:level (first logs)))))
      
      ;; Test info level logging
      (let [{:keys [logs]} (capture-logs 
                            #(errors/log-error sample-request exception :not-found))]
        (is (= 1 (count logs)))
        (is (= :info (:level (first logs)))))))
  
  (testing "Additional context in logging"
    (let [exception (RuntimeException. "Test error")
          additional-context {:custom-field "custom-value"}
          {:keys [logs]} (capture-logs 
                          #(errors/log-error sample-request exception :database additional-context))]
      (is (= 1 (count logs)))
      ;; The JSON string should contain the additional context
      (let [log-message (second (:args (first logs)))]
        (is (str/includes? log-message "custom-field"))
        (is (str/includes? log-message "custom-value"))))))

(deftest test-log-auth-event
  (testing "Authentication event logging"
    (let [test-cases [[:login-attempt :info]
                      [:login-success :info]
                      [:login-failure :warn]
                      [:logout :info]
                      [:session-created :info]
                      [:session-expired :info]
                      [:session-invalid :warn]
                      [:oauth-initiated :info]
                      [:oauth-callback :info]
                      [:oauth-success :info]
                      [:oauth-failure :warn]
                      [:csrf-violation :warn]
                      [:unauthorized-access :warn]]]
      
      (doseq [[event-type expected-level] test-cases]
        (let [{:keys [logs]} (capture-logs 
                              #(errors/log-auth-event event-type sample-request {:test "data"}))]
          (is (= 1 (count logs)) (str "Failed for event: " event-type))
          (is (= expected-level (:level (first logs))) (str "Wrong log level for event: " event-type))
          
          ;; Check that the log message contains structured data
          (let [log-args (:args (first logs))
                log-message (if (> (count log-args) 1) (second log-args) (str log-args))]
            (when log-message
              (is (str/includes? (str log-message) (name event-type)))
              (is (str/includes? (str log-message) "test"))
              (is (str/includes? (str log-message) "data"))))))))
  
  (testing "Auth event with additional data"
    (let [additional-data {:provider "github" :error "access_denied"}
          {:keys [logs]} (capture-logs 
                          #(errors/log-auth-event :oauth-failure sample-request additional-data))]
      (is (= 1 (count logs)))
      (let [log-args (:args (first logs))
            log-message (if (> (count log-args) 1) (second log-args) (str log-args))]
        (when log-message
          (is (str/includes? (str log-message) "github"))
          (is (str/includes? (str log-message) "access_denied")))))))

;; Error response generation tests

(deftest test-create-error-response
  (testing "HTML error response"
    (with-redefs [templates/error-page (fn [title message] (str "<html>" title ":" message "</html>"))]
      (let [exception (RuntimeException. "Test error")
            response (errors/create-error-response sample-request exception :database)]
        (is (= 500 (:status response)))
        (is (= "text/html" (get-in response [:headers "Content-Type"])))
        (is (str/includes? (:body response) "Database operation failed")))))
  
  (testing "JSON error response"
    (let [exception (RuntimeException. "Test error")
          response (errors/create-error-response sample-json-request exception :oauth)]
      (is (= 401 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (let [body (json/parse-string (:body response) true)]
        (is (= "OAUTH_ERROR" (:error body)))
        (is (= "OAuth authentication failed" (:message body)))
        (is (string? (:timestamp body))))))
  
  (testing "Error response with additional context"
    (let [exception (RuntimeException. "Test error")
          additional-context {:custom-field "custom-value"}
          response (errors/create-error-response sample-json-request exception :validation additional-context)]
      (is (= 400 (:status response)))
      (let [body (json/parse-string (:body response) true)]
        (is (= "VALIDATION_ERROR" (:error body)))
        (is (= {:custom-field "custom-value"} (:details body))))))
  
  (testing "Different error types produce appropriate messages"
    (with-redefs [templates/error-page (fn [title message] message)]
      (let [exception (RuntimeException. "Test error")
            test-cases [[:database "We're experiencing technical difficulties. Please try again later."]
                        [:oauth "Authentication failed. Please try logging in again."]
                        [:network "Unable to connect to external services. Please try again later."]
                        [:validation "The information provided is invalid. Please check and try again."]
                        [:authorization "You don't have permission to access this resource."]
                        [:not-found "The page you're looking for doesn't exist."]
                        [:session "Your session has expired. Please log in again."]
                        [:csrf "Security validation failed. Please refresh the page and try again."]
                        [:configuration "The application is misconfigured. Please contact support."]
                        [:unknown "An unexpected error occurred. Please try again later."]]]
        
        (doseq [[error-type expected-message] test-cases]
          (let [response (errors/create-error-response sample-request exception error-type)]
            (is (= expected-message (:body response)) (str "Failed for error type: " error-type))))))))

;; Specific error handler tests

(deftest test-specific-error-handlers
  (testing "Database error handler"
    (let [exception (SQLException. "Connection failed")
          {:keys [result logs]} (capture-logs 
                                 #(errors/handle-database-error sample-request exception))]
      (is (= 500 (:status result)))
      (is (= 1 (count logs)))
      (is (= :error (:level (first logs))))))
  
  (testing "OAuth error handler"
    (let [exception (RuntimeException. "OAuth failed")
          {:keys [result logs]} (capture-logs 
                                 #(errors/handle-oauth-error sample-request exception))]
      (is (= 401 (:status result)))
      ;; Should have both error log and auth event log
      (is (= 2 (count logs)))
      (is (some #(= :warn (:level %)) logs))))
  
  (testing "Session error handler"
    (let [exception (RuntimeException. "Session expired")
          {:keys [result logs]} (capture-logs 
                                 #(errors/handle-session-error sample-request exception))]
      (is (= 401 (:status result)))
      ;; Should have both error log and auth event log
      (is (= 2 (count logs)))))
  
  (testing "CSRF error handler"
    (let [exception (SecurityException. "CSRF token invalid")
          {:keys [result logs]} (capture-logs 
                                 #(errors/handle-csrf-error sample-request exception))]
      (is (= 403 (:status result)))
      ;; Should have both error log and auth event log
      (is (= 2 (count logs))))))

;; Global error handling middleware tests

(deftest test-wrap-error-handling
  (testing "Successful request passes through"
    (let [handler (fn [request] {:status 200 :body "OK"})
          wrapped-handler (errors/wrap-error-handling handler)
          response (wrapped-handler sample-request)]
      (is (= 200 (:status response)))
      (is (= "OK" (:body response)))))
  
  (testing "Database exception handling"
    (let [handler (fn [request] (throw (SQLException. "DB connection failed")))
          wrapped-handler (errors/wrap-error-handling handler)
          {:keys [result logs]} (capture-logs #(wrapped-handler sample-request))]
      (is (= 500 (:status result)))
      (is (= 1 (count logs)))
      (is (= :error (:level (first logs))))))
  
  (testing "OAuth exception handling"
    (let [handler (fn [request] (throw (RuntimeException. "OAuth authentication failed")))
          wrapped-handler (errors/wrap-error-handling handler)
          {:keys [result logs]} (capture-logs #(wrapped-handler sample-request))]
      (is (= 401 (:status result)))
      ;; Should have both error log and auth event log
      (is (= 2 (count logs)))))
  
  (testing "Network exception handling"
    (let [handler (fn [request] (throw (SocketTimeoutException. "Connection timeout")))
          wrapped-handler (errors/wrap-error-handling handler)
          {:keys [result logs]} (capture-logs #(wrapped-handler sample-request))]
      (is (= 503 (:status result)))
      (is (= 1 (count logs)))
      (is (= :error (:level (first logs))))))
  
  (testing "Unknown exception handling"
    (let [handler (fn [request] (throw (RuntimeException. "Something went wrong")))
          wrapped-handler (errors/wrap-error-handling handler)
          {:keys [result logs]} (capture-logs #(wrapped-handler sample-request))]
      (is (= 500 (:status result)))
      (is (= 1 (count logs)))
      (is (= :error (:level (first logs)))))))

;; Error recovery mechanism tests

(deftest test-with-retry
  (testing "Successful execution on first try"
    (let [call-count (atom 0)
          f (fn [] (swap! call-count inc) "success")
          result (errors/with-retry f 3 100)]
      (is (= "success" result))
      (is (= 1 @call-count))))
  
  (testing "Retry on transient network error"
    (let [call-count (atom 0)
          f (fn [] 
              (swap! call-count inc)
              (if (< @call-count 3)
                (throw (SocketTimeoutException. "Timeout"))
                "success"))
          {:keys [result logs]} (capture-logs #(errors/with-retry f 3 10))]
      (is (= "success" result))
      (is (= 3 @call-count))
      ;; Should have retry warning logs
      (is (>= (count logs) 2))))
  
  (testing "Exhausted retries throw original exception"
    (let [call-count (atom 0)
          f (fn [] 
              (swap! call-count inc)
              (throw (SocketTimeoutException. "Persistent timeout")))
          {:keys [logs]} (capture-logs 
                          #(try
                             (errors/with-retry f 2 10)
                             (catch Exception e
                               (.getMessage e))))]
      (is (= 2 @call-count))
      (is (>= (count logs) 1))))
  
  (testing "Non-retryable exceptions are not retried"
    (let [call-count (atom 0)
          f (fn [] 
              (swap! call-count inc)
              (throw (IllegalArgumentException. "Bad argument")))
          exception-thrown? (try
                             (errors/with-retry f 3 10)
                             false
                             (catch IllegalArgumentException e
                               true))]
      (is exception-thrown?)
      (is (= 1 @call-count))))
  
  (testing "Custom retry predicate"
    (let [call-count (atom 0)
          f (fn [] 
              (swap! call-count inc)
              (throw (RuntimeException. "Custom retryable error")))
          custom-predicate (fn [e] (str/includes? (.getMessage e) "retryable"))
          {:keys [result logs]} (capture-logs 
                                 #(try
                                    (errors/with-retry f 2 10 custom-predicate)
                                    (catch Exception e
                                      "caught")))]
      (is (= "caught" result))
      (is (= 2 @call-count)))))

(deftest test-with-circuit-breaker
  (testing "Circuit breaker allows successful calls"
    (let [f (fn [] "success")
          circuit-breaker-f (errors/with-circuit-breaker f 3 1000)
          result (circuit-breaker-f)]
      (is (= "success" result))))
  
  (testing "Circuit breaker opens after failure threshold"
    (let [call-count (atom 0)
          f (fn [] 
              (swap! call-count inc)
              (throw (RuntimeException. "Service failure")))
          circuit-breaker-f (errors/with-circuit-breaker f 2 1000)]
      
      ;; First two calls should fail normally
      (is (thrown? RuntimeException (circuit-breaker-f)))
      (is (thrown? RuntimeException (circuit-breaker-f)))
      (is (= 2 @call-count))
      
      ;; Third call should fail with circuit breaker open
      (is (thrown-with-msg? RuntimeException #"Circuit breaker is open" (circuit-breaker-f)))
      (is (= 2 @call-count)))) ; Call count shouldn't increase
  
  (testing "Circuit breaker resets after timeout"
    (let [call-count (atom 0)
          success-after-failures (atom false)
          f (fn [] 
              (swap! call-count inc)
              (if @success-after-failures
                "success"
                (throw (RuntimeException. "Service failure"))))
          circuit-breaker-f (errors/with-circuit-breaker f 2 50)] ; Short timeout for testing
      
      ;; Trigger circuit breaker to open
      (is (thrown? RuntimeException (circuit-breaker-f)))
      (is (thrown? RuntimeException (circuit-breaker-f)))
      
      ;; Wait for reset timeout
      (Thread/sleep 60)
      
      ;; Enable success
      (reset! success-after-failures true)
      
      ;; Should succeed and close circuit
      (is (= "success" (circuit-breaker-f)))
      (is (= 3 @call-count)))))

;; Health check tests

(deftest test-create-health-check
  (testing "All components healthy"
    (let [db-check (fn [] true)
          external-checks {:service1 (fn [] true)
                          :service2 (fn [] true)}
          health-check-fn (errors/create-health-check db-check external-checks)
          result (health-check-fn)]
      (is (= :healthy (:overall result)))
      (is (= :healthy (get-in result [:checks :database :status])))
      (is (= :healthy (get-in result [:checks :service1 :status])))
      (is (= :healthy (get-in result [:checks :service2 :status])))
      (is (string? (:timestamp result)))))
  
  (testing "Database unhealthy affects overall status"
    (let [db-check (fn [] (throw (SQLException. "DB down")))
          external-checks {}
          health-check-fn (errors/create-health-check db-check external-checks)
          result (health-check-fn)]
      (is (= :unhealthy (:overall result)))
      (is (= :unhealthy (get-in result [:checks :database :status])))
      (is (= "DB down" (get-in result [:checks :database :error])))))
  
  (testing "External service failure affects overall status"
    (let [db-check (fn [] true)
          external-checks {:failing-service (fn [] (throw (IOException. "Service down")))}
          health-check-fn (errors/create-health-check db-check external-checks)
          result (health-check-fn)]
      (is (= :unhealthy (:overall result)))
      (is (= :healthy (get-in result [:checks :database :status])))
      (is (= :unhealthy (get-in result [:checks :failing-service :status])))
      (is (= "Service down" (get-in result [:checks :failing-service :error]))))))

;; Utility function tests

(deftest test-safe-execute
  (testing "Successful execution"
    (let [f (fn [] "success")
          result (errors/safe-execute f)]
      (is (:success result))
      (is (= "success" (:result result)))))
  
  (testing "Failed execution"
    (let [f (fn [] (throw (RuntimeException. "Error")))
          result (errors/safe-execute f {:context "test"})]
      (is (not (:success result)))
      (is (= :unknown (get-in result [:error :type])))
      (is (= "Error" (get-in result [:error :message])))
      (is (= {:context "test"} (get-in result [:error :context])))))
  
  (testing "Database error classification in safe-execute"
    (let [f (fn [] (throw (SQLException. "DB Error")))
          result (errors/safe-execute f)]
      (is (not (:success result)))
      (is (= :database (get-in result [:error :type]))))))

(deftest test-log-and-rethrow
  (testing "Exception is logged and rethrown"
    (let [exception (RuntimeException. "Test error")
          context {:test "context"}
          {:keys [logs]} (capture-logs 
                          #(try
                             (errors/log-and-rethrow exception context)
                             (catch RuntimeException e
                               (.getMessage e))))]
      (is (= 1 (count logs)))
      (is (= :error (:level (first logs))))
      (let [log-args (:args (first logs))]
        (is (= exception (first log-args)))
        (is (str/includes? (str (second log-args)) "context"))))))

;; Integration tests for error scenarios

(deftest test-error-handling-integration
  (testing "Complete error handling flow"
    (let [handler (fn [request] 
                    (case (:uri request)
                      "/db-error" (throw (SQLException. "Database connection failed"))
                      "/oauth-error" (throw (RuntimeException. "OAuth authentication failed"))
                      "/network-error" (throw (SocketTimeoutException. "Network timeout"))
                      "/success" {:status 200 :body "OK"}
                      {:status 404 :body "Not found"}))
          wrapped-handler (errors/wrap-error-handling handler)]
      
      ;; Test successful request
      (let [response (wrapped-handler (assoc sample-request :uri "/success"))]
        (is (= 200 (:status response))))
      
      ;; Test database error
      (let [{:keys [result logs]} (capture-logs 
                                   #(wrapped-handler (assoc sample-request :uri "/db-error")))]
        (is (= 500 (:status result)))
        (is (some #(= :error (:level %)) logs)))
      
      ;; Test OAuth error
      (let [{:keys [result logs]} (capture-logs 
                                   #(wrapped-handler (assoc sample-request :uri "/oauth-error")))]
        (is (= 401 (:status result)))
        (is (some #(= :warn (:level %)) logs)))
      
      ;; Test network error
      (let [{:keys [result logs]} (capture-logs 
                                   #(wrapped-handler (assoc sample-request :uri "/network-error")))]
        (is (= 503 (:status result)))
        (is (some #(= :error (:level %)) logs))))))

;; Performance and edge case tests

(deftest test-error-handling-performance
  (testing "Error handling doesn't significantly impact performance"
    (let [handler (fn [request] {:status 200 :body "OK"})
          wrapped-handler (errors/wrap-error-handling handler)
          start-time (System/nanoTime)]
      
      ;; Execute multiple requests
      (dotimes [_ 1000]
        (wrapped-handler sample-request))
      
      (let [elapsed-time (/ (- (System/nanoTime) start-time) 1000000.0)] ; Convert to milliseconds
        ;; Should complete 1000 requests in reasonable time (less than 1 second)
        (is (< elapsed-time 1000) (str "Performance test took " elapsed-time "ms")))))
  
  (testing "Error context creation with large request"
    (let [large-request (assoc sample-request 
                              :headers (into {} (map #(vector (str "header-" %) (str "value-" %)) (range 100)))
                              :params (into {} (map #(vector (str "param-" %) (str "value-" %)) (range 100))))
          exception (RuntimeException. "Test error")
          context (errors/create-error-context large-request exception :database)]
      ;; Should handle large requests without issues
      (is (string? (:timestamp context)))
      (is (= "database" (:error-type context)))))
  
  (testing "Concurrent error handling"
    (let [handler (fn [request] (throw (RuntimeException. "Concurrent error")))
          wrapped-handler (errors/wrap-error-handling handler)
          futures (doall (map (fn [_] 
                               (future 
                                 (try
                                   (wrapped-handler sample-request)
                                   (catch Exception e
                                     {:error (.getMessage e)}))))
                             (range 10)))]
      
      ;; Wait for all futures to complete
      (let [results (map deref futures)]
        ;; All should complete without hanging
        (is (= 10 (count results)))
        ;; All should be error responses
        (is (every? #(= 500 (:status %)) results))))))

(deftest test-edge-cases
  (testing "Null and empty values in error context"
    (let [minimal-request {}
          exception (RuntimeException. "Test error")
          context (errors/create-error-context minimal-request exception :database)]
      (is (string? (:timestamp context)))
      (is (= "database" (:error-type context)))))
  
  (testing "Very long error messages"
    (let [long-message (apply str (repeat 10000 "x"))
          exception (RuntimeException. long-message)
          context (errors/create-error-context sample-request exception :database)]
      (is (= long-message (:exception-message context)))))
  
  (testing "Special characters in error messages"
    (let [special-message "Error with special chars: <>&\"'\n\t\r"
          exception (RuntimeException. special-message)
          context (errors/create-error-context sample-request exception :database)]
      (is (= special-message (:exception-message context)))))
  
  (testing "Nested exception handling"
    (let [cause (SQLException. "Root cause")
          wrapper (RuntimeException. "Wrapper exception" cause)
          error-type (errors/classify-exception wrapper)]
      ;; Should classify based on the wrapper, not the cause
      (is (= :unknown error-type))))
  
  (testing "Error handling with malformed JSON in logs"
    (let [exception (RuntimeException. "Error with \"quotes\" and {braces}")
          {:keys [logs]} (capture-logs 
                          #(errors/log-error sample-request exception :database))]
      (is (= 1 (count logs)))
      ;; Should not throw JSON parsing errors
      (let [log-message (second (:args (first logs)))]
        (is (string? log-message))))))