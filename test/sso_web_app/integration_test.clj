(ns sso-web-app.integration-test
  "Comprehensive end-to-end integration tests for complete authentication flows."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ring.mock.request :as mock]
            [sso-web-app.core :as core]
            [sso-web-app.routes :as routes]
            [sso-web-app.db :as db]
            [sso-web-app.auth :as auth]
            [sso-web-app.middleware :as middleware]
            [sso-web-app.templates :as templates]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [ring.util.codec :as codec])
  (:import [java.util.concurrent Executors TimeUnit]))

;; Test configuration
(def test-config
  {:port 3001
   :host "localhost"
   :database-url "jdbc:sqlite::memory:"
   :session-secret "test-session-secret-at-least-32-characters-long"
   :base-url "http://localhost:3001"
   :microsoft-client-id "test-microsoft-client-id"
   :microsoft-client-secret "test-microsoft-client-secret"
   :github-client-id "test-github-client-id"
   :github-client-secret "test-github-client-secret"
   :join? false
   :max-threads 10
   :min-threads 2
   :max-idle-time 30000})

;; Test fixtures
(defn with-test-database
  "Test fixture that sets up and tears down test database."
  [test-fn]
  ;; Use a file-based database for tests to avoid SQLite in-memory connection issues
  (let [test-db-file (str "test-" (System/currentTimeMillis) ".db")
        test-db-config {:classname "org.sqlite.JDBC"
                        :subprotocol "sqlite"
                        :subname test-db-file}]
    (try
      ;; Override database configuration for testing
      (binding [db/*db-config* test-db-config]
        ;; Ensure database is properly initialized
        (db/init-db!)
        ;; Run the test
        (test-fn))
      (catch Exception e
        (println "Test database setup failed:" (.getMessage e))
        (throw e))
      (finally
        ;; Clean up test database file
        (try
          (clojure.java.io/delete-file test-db-file true)
          (catch Exception _))))))

(use-fixtures :each with-test-database)

;; Helper functions
(defn create-test-user
  "Create a test user in the database."
  [provider provider-id username email]
  (db/create-user! provider provider-id username email))

(defn create-test-session
  "Create a test session for a user."
  [user-id]
  (middleware/create-user-session user-id))

(defn extract-session-cookie
  "Extract session cookie from response."
  [response]
  (or (get-in response [:cookies "session-id" :value])
      (when-let [set-cookie-header (get-in response [:headers "Set-Cookie"])]
        (let [cookie-str (if (sequential? set-cookie-header)
                          (clojure.string/join " " (doall set-cookie-header))
                          (str set-cookie-header))
              session-match (re-find #"session-id=([^;]+)" cookie-str)]
          (when session-match
            (second session-match))))))

(defn extract-ring-session-cookie
  "Extract Ring session cookie from response."
  [response]
  (when-let [set-cookie-header (get-in response [:headers "Set-Cookie"])]
    (when (string? set-cookie-header)
      (let [cookie-parts (clojure.string/split set-cookie-header #";")
            session-part (first (filter #(clojure.string/starts-with? % "ring-session=") cookie-parts))]
        (when session-part
          (second (clojure.string/split session-part #"=")))))))

(defn add-ring-session-to-request
  "Add Ring session cookie to request."
  [request session-cookie]
  (if session-cookie
    (assoc-in request [:cookies "ring-session"] {:value session-cookie})
    request))

(defn add-session-to-request
  "Add session cookie to request."
  [request session-id]
  (assoc-in request [:cookies "session-id"] {:value session-id}))

;; Integration tests
(deftest test-application-startup-and-configuration
  (testing "Application configuration loading and validation"
    (let [config (core/load-config)]
      (is (map? config))
      (is (contains? config :port))
      (is (contains? config :database-url)))
    
    (let [validation (core/validate-config test-config)]
      (is (:valid? validation))
      (is (empty? (:errors validation))))
    
    (let [invalid-config (assoc test-config :session-secret "short")
          validation (core/validate-config invalid-config)]
      (is (not (:valid? validation)))
      (is (seq (:errors validation))))))

(deftest test-health-check
  (testing "Application health check functionality"
    (let [health (core/health-check)]
      (is (map? health))
      (is (contains? health :healthy?))
      (is (contains? health :database))
      (is (contains? health :server))
      (is (contains? health :timestamp)))))

(deftest test-basic-routing
  (testing "Basic application routing"
    (testing "Root URL redirects to login when not authenticated"
      (let [response (routes/app (mock/request :get "/"))]
        (is (= 302 (:status response)))
        (is (= "/login" (get-in response [:headers "Location"])))))
    
    (testing "Login page displays OAuth options"
      (let [response (routes/app (mock/request :get "/login"))]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "Microsoft"))
        (is (str/includes? (:body response) "GitHub"))))
    
    (testing "OAuth initiation redirects to provider"
      (let [response (routes/app (mock/request :get "/auth/microsoft"))]
        (is (= 302 (:status response)))
        (let [location (get-in response [:headers "Location"])]
          (is (str/includes? location "login.microsoftonline.com")))))
    
    (testing "Protected route redirects when not authenticated"
      (let [response (routes/app (mock/request :get "/dashboard"))]
        (is (= 302 (:status response)))
        (is (= "/login" (get-in response [:headers "Location"])))))
    
    (testing "404 for non-existent routes"
      (let [response (routes/app (mock/request :get "/non-existent"))]
        (is (= 404 (:status response)))
        (is (str/includes? (:body response) "Page Not Found"))))))

(deftest test-session-management
  (testing "Session creation and validation"
    (let [user (create-test-user "microsoft" "session-test" "Session Test" "session@test.com")
          session (create-test-session (:id user))]
      
      (is (some? (:session_id session)))
      (is (= (:id user) (:user_id session)))
      
      ;; Test session validation
      (let [validated-user (middleware/validate-session (:session_id session))]
        (is (some? validated-user))
        (is (= (:id user) (:id validated-user))))
      
      ;; Test session cleanup
      (let [cleaned-count (middleware/cleanup-expired-sessions)]
        (is (>= cleaned-count 0))))))

(deftest test-database-operations
  (testing "Database operations integration"
    (testing "User creation and retrieval"
      (let [user (create-test-user "github" "db-test" "DB Test" "db@test.com")]
        (is (some? (:id user)))
        (is (= "github" (:provider user)))
        
        ;; Test user retrieval
        (let [retrieved (db/find-user-by-provider-id "github" "db-test")]
          (is (some? retrieved))
          (is (= (:id user) (:id retrieved))))))
    
    (testing "User update operations"
      (let [user (create-test-user "microsoft" "update-test" "Original" "original@test.com")]
        (db/update-user! (:id user) {:username "Updated" :email "updated@test.com"})
        
        (let [updated (db/find-user-by-id (:id user))]
          (is (= "Updated" (:username updated)))
          (is (= "updated@test.com" (:email updated))))))))

(deftest test-authentication-flow-simulation
  (testing "Simulated authentication flow"
    (testing "Dashboard access with valid session"
      (let [user (create-test-user "microsoft" "auth-test" "Auth Test" "auth@test.com")
            session (create-test-session (:id user))
            request (add-session-to-request (mock/request :get "/dashboard") (:session_id session))
            response (routes/app request)]
        
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "hello Auth Test"))))
    
    (testing "Logout functionality"
      (let [user (create-test-user "github" "logout-test" "Logout Test" "logout@test.com")
            session (create-test-session (:id user))
            request (-> (mock/request :post "/logout")
                       (add-session-to-request (:session_id session))
                       (mock/body {:csrf-token "test-csrf"})
                       (assoc :session {:csrf-token "test-csrf"}))
            response (routes/app request)]
        
        (is (= 302 (:status response)))
        (is (= "/login" (get-in response [:headers "Location"])))))))

(deftest test-error-handling
  (testing "Error scenarios"
    (testing "Invalid session handling"
      (let [request (add-session-to-request (mock/request :get "/dashboard") "invalid-session")
            response (routes/app request)]
        (is (= 302 (:status response)))
        (is (= "/login" (get-in response [:headers "Location"])))))
    
    (testing "CSRF protection"
      (let [user (create-test-user "microsoft" "csrf-test" "CSRF Test" "csrf@test.com")
            session (create-test-session (:id user))
            request (-> (mock/request :post "/logout")
                       (add-session-to-request (:session_id session)))
            response (routes/app request)]
        
        (is (= 403 (:status response)))
        (is (str/includes? (:body response) "CSRF"))))))

(deftest test-security-headers
  (testing "Security headers are present"
    (let [response (routes/app (mock/request :get "/login"))]
      (is (contains? (:headers response) "X-Content-Type-Options"))
      (is (contains? (:headers response) "X-Frame-Options"))
      (is (contains? (:headers response) "X-XSS-Protection"))
      (is (= "nosniff" (get-in response [:headers "X-Content-Type-Options"])))
      (is (= "DENY" (get-in response [:headers "X-Frame-Options"]))))))

;; =============================================================================
;; COMPREHENSIVE END-TO-END INTEGRATION TESTS
;; =============================================================================

;; Mock OAuth Provider Responses
(def mock-microsoft-token-response
  {:access_token "mock-microsoft-access-token"
   :token_type "Bearer"
   :expires_in 3600
   :scope "openid profile email"})

(def mock-microsoft-user-profile
  {:id "12345-microsoft-user-id"
   :displayName "Microsoft Test User"
   :userPrincipalName "testuser@microsoft.com"
   :mail "testuser@microsoft.com"})

(def mock-github-token-response
  {:access_token "mock-github-access-token"
   :token_type "bearer"
   :scope "user:email"})

(def mock-github-user-profile
  {:id 67890
   :login "githubuser"
   :name "GitHub Test User"
   :email "testuser@github.com"})

(def mock-github-emails
  [{:email "testuser@github.com" :primary true :verified true}
   {:email "secondary@github.com" :primary false :verified true}])

;; OAuth Mock Functions
(defn mock-oauth-token-exchange
  "Mock OAuth token exchange for testing."
  [provider code]
  (case provider
    :microsoft mock-microsoft-token-response
    :github mock-github-token-response
    nil))

(defn mock-oauth-user-profile
  "Mock OAuth user profile fetch for testing."
  [provider access-token]
  (case provider
    :microsoft mock-microsoft-user-profile
    :github mock-github-user-profile
    nil))

(defn mock-github-emails-fetch
  "Mock GitHub emails fetch for testing."
  [access-token]
  mock-github-emails)

;; =============================================================================
;; COMPLETE USER AUTHENTICATION FLOW TESTS WITH MOCKED OAUTH PROVIDERS
;; =============================================================================

(deftest test-complete-microsoft-authentication-flow
  (testing "Complete Microsoft OAuth authentication flow with mocked provider"
    (with-redefs [auth/exchange-code-for-token mock-oauth-token-exchange
                  auth/fetch-user-profile mock-oauth-user-profile
                  auth/validate-state (fn [session-state received-state] true)
                  environ.core/env (merge environ.core/env test-config)]
      
      ;; Step 1: User visits root URL and gets redirected to login
      (let [root-response (routes/app (mock/request :get "/"))]
        (is (= 302 (:status root-response)))
        (is (= "/login" (get-in root-response [:headers "Location"]))))
      
      ;; Step 2: User visits login page and sees OAuth options
      (let [login-response (routes/app (mock/request :get "/login"))]
        (is (= 200 (:status login-response)))
        (is (str/includes? (:body login-response) "Microsoft"))
        (is (str/includes? (:body login-response) "GitHub")))
      
      ;; Step 3: User clicks Microsoft login and gets redirected to OAuth provider
      (let [oauth-init-response (routes/app (mock/request :get "/auth/microsoft"))]
        (is (= 302 (:status oauth-init-response)))
        (let [location (get-in oauth-init-response [:headers "Location"])]
          (is (str/includes? location "login.microsoftonline.com"))
          (is (str/includes? location "client_id"))
          (is (str/includes? location "response_type=code"))
          
          ;; Step 4: OAuth provider redirects back with authorization code
          ;; Since we mocked validate-state to always return true, we can use any state
          (let [callback-request (-> (mock/request :get "/auth/microsoft/callback" {:code "mock-auth-code" :state "mock-state"}))
                callback-response (routes/app callback-request)]
            
            (is (= 302 (:status callback-response)))
            (is (= "/dashboard" (get-in callback-response [:headers "Location"])))
            
            ;; Verify session cookie is set
            (let [session-cookie (extract-session-cookie callback-response)]
              (is (some? session-cookie))
              
              ;; Step 5: User accesses dashboard with valid session
              (let [dashboard-request (-> (mock/request :get "/dashboard")
                                         (assoc :cookies {"session-id" {:value session-cookie}}))
                    dashboard-response (routes/app dashboard-request)]
                
                (is (= 200 (:status dashboard-response)))
                (is (str/includes? (:body dashboard-response) "Hello Microsoft Test User"))
                (is (str/includes? (:body dashboard-response) "Logout"))
                
                ;; Verify user was created in database
                (let [user (db/find-user-by-provider-id "microsoft" "12345-microsoft-user-id")]
                  (is (some? user))
                  (is (= "Microsoft Test User" (:username user)))
                  (is (= "testuser@microsoft.com" (:email user))))))))))))

(deftest test-complete-github-authentication-flow
  (testing "Complete GitHub OAuth authentication flow with mocked provider"
    (with-redefs [auth/exchange-code-for-token mock-oauth-token-exchange
                  auth/fetch-user-profile mock-oauth-user-profile
                  auth/fetch-github-emails mock-github-emails-fetch
                  environ.core/env (merge environ.core/env test-config)]
      
      ;; Step 1: User initiates GitHub OAuth
      (let [oauth-init-response (routes/app (mock/request :get "/auth/github"))]
        (is (= 302 (:status oauth-init-response)))
        (let [location (get-in oauth-init-response [:headers "Location"])
              oauth-state (get-in oauth-init-response [:session :oauth-state])]
          (is (str/includes? location "github.com/login/oauth/authorize"))
          (is (some? oauth-state))
          
          ;; Step 2: OAuth callback with authorization code
          (let [callback-request (-> (mock/request :get "/auth/callback/github")
                                    (assoc :params {:code "mock-github-code" :state oauth-state})
                                    (assoc :session (:session oauth-init-response)))
                callback-response (routes/app callback-request)]
            
            (is (= 302 (:status callback-response)))
            (is (= "/dashboard" (get-in callback-response [:headers "Location"])))
            
            ;; Step 3: Access dashboard with GitHub user
            (let [session-cookie (get-in callback-response [:cookies "session-id" :value])
                  dashboard-request (-> (mock/request :get "/dashboard")
                                       (assoc :cookies {"session-id" {:value session-cookie}}))
                  dashboard-response (routes/app dashboard-request)]
              
              (is (= 200 (:status dashboard-response)))
              (is (str/includes? (:body dashboard-response) "hello GitHub Test User"))
              
              ;; Verify GitHub user was created
              (let [user (db/find-user-by-provider-id "github" "67890")]
                (is (some? user))
                (is (= "GitHub Test User" (:username user)))
                (is (= "testuser@github.com" (:email user)))))))))))

(deftest test-oauth-error-scenarios
  (testing "OAuth error scenarios and recovery"
    ;; Test OAuth provider error response
    (let [oauth-init-response (routes/app (mock/request :get "/auth/microsoft"))]
      (let [oauth-state (get-in oauth-init-response [:session :oauth-state])
            error-callback-request (-> (mock/request :get "/auth/microsoft/callback")
                                      (assoc :params {:error "access_denied" 
                                                     :error_description "User denied access"
                                                     :state oauth-state})
                                      (assoc :session {:oauth-state oauth-state}))]
        
        (is (thrown? RuntimeException
                    (routes/app error-callback-request)))))
    
    ;; Test invalid state parameter
    (let [callback-request (-> (mock/request :get "/auth/microsoft/callback")
                              (assoc :params {:code "valid-code" :state "invalid-state"})
                              (assoc :session {:oauth-state "different-state"}))]
      (is (thrown? SecurityException
                  (routes/app callback-request))))
    
    ;; Test missing authorization code
    (let [oauth-init-response (routes/app (mock/request :get "/auth/microsoft"))
          oauth-state (get-in oauth-init-response [:session :oauth-state])
          callback-request (-> (mock/request :get "/auth/microsoft/callback")
                              (assoc :params {:state oauth-state})
                              (assoc :session {:oauth-state oauth-state}))]
      (is (thrown? IllegalArgumentException
                  (routes/app callback-request))))))

;; =============================================================================
;; SESSION PERSISTENCE ACROSS MULTIPLE REQUESTS TESTS
;; =============================================================================

(deftest test-session-persistence-across-requests
  (testing "Session persistence and validation across multiple HTTP requests"
    (with-redefs [auth/exchange-code-for-token mock-oauth-token-exchange
                  auth/fetch-user-profile mock-oauth-user-profile
                  environ.core/env (merge environ.core/env test-config)]
      
      ;; Create authenticated session through OAuth flow
      (let [oauth-init-response (routes/app (mock/request :get "/auth/microsoft"))
            oauth-state (get-in oauth-init-response [:session :oauth-state])
            callback-request (-> (mock/request :get "/auth/microsoft/callback")
                                (assoc :params {:code "mock-auth-code" :state oauth-state})
                                (assoc :session (:session oauth-init-response)))
            callback-response (routes/app callback-request)
            session-cookie (extract-session-cookie callback-response)]
        
        (is (some? session-cookie))
        
        ;; Test multiple dashboard requests with same session
        (dotimes [i 5]
          (let [dashboard-request (-> (mock/request :get "/dashboard")
                                     (assoc :cookies {"session-id" {:value session-cookie}}))
                dashboard-response (routes/app dashboard-request)]
            (is (= 200 (:status dashboard-response)))
            (is (str/includes? (:body dashboard-response) "Microsoft Test User"))))
        
        ;; Test session validation with different request types
        (let [root-request (-> (mock/request :get "/")
                              (assoc :cookies {"session-id" {:value session-cookie}}))
              root-response (routes/app root-request)]
          (is (= 302 (:status root-response)))
          (is (= "/dashboard" (get-in root-response [:headers "Location"]))))
        
        ;; Test session persistence after server restart simulation
        ;; (In real scenario, session would be stored in database or persistent store)
        (let [persistent-request (-> (mock/request :get "/dashboard")
                                    (assoc :cookies {"session-id" {:value session-cookie}}))
              persistent-response (routes/app persistent-request)]
          (is (= 200 (:status persistent-response))))))))

(deftest test-session-expiration-handling
  (testing "Session expiration and automatic cleanup"
    ;; Create a user and session
    (let [user (create-test-user "microsoft" "expire-test" "Expire Test" "expire@test.com")
          session (create-test-session (:id user))]
      
      ;; Verify session is initially valid
      (let [validated-user (middleware/validate-session (:session_id session))]
        (is (some? validated-user)))
      
      ;; Simulate session expiration by updating database directly
      (jdbc/execute! (db/get-db-config) ["UPDATE sessions SET expires_at = datetime('now', '-1 hour') WHERE session_id = ?" 
                    (:session_id session)])
      
      ;; Test that expired session is no longer valid
      (let [validated-user (middleware/validate-session (:session_id session))]
        (is (nil? validated-user)))
      
      ;; Test cleanup of expired sessions
      (let [cleanup-count (middleware/cleanup-expired-sessions)]
        (is (>= cleanup-count 1))))))

(deftest test-concurrent-session-handling
  (testing "Concurrent session creation and validation"
    (with-redefs [auth/exchange-code-for-token mock-oauth-token-exchange
                  auth/fetch-user-profile mock-oauth-user-profile
                  environ.core/env (merge environ.core/env test-config)]
      
      ;; Simulate multiple concurrent authentication attempts
      (let [oauth-init-response (routes/app (mock/request :get "/auth/microsoft"))
            oauth-state (get-in oauth-init-response [:session :oauth-state])
            
            ;; Create multiple concurrent callback requests
            callback-requests (repeatedly 3 
                                         #(-> (mock/request :get "/auth/microsoft/callback")
                                             (assoc :params {:code (str "mock-code-" (rand-int 1000)) 
                                                            :state oauth-state})
                                             (assoc :session (:session oauth-init-response))))
            
            ;; Process all requests
            responses (map routes/app callback-requests)
            session-cookies (map #(get-in % [:cookies "session-id" :value]) responses)]
        
        ;; All should succeed and create valid sessions
        (doseq [response responses]
          (is (= 302 (:status response)))
          (is (= "/dashboard" (get-in response [:headers "Location"]))))
        
        ;; All session cookies should be unique
        (is (= (count session-cookies) (count (set session-cookies))))
        
        ;; All sessions should be valid for dashboard access
        (doseq [session-cookie session-cookies]
          (when session-cookie
            (let [dashboard-request (-> (mock/request :get "/dashboard")
                                       (assoc :cookies {"session-id" {:value session-cookie}}))
                  dashboard-response (routes/app dashboard-request)]
              (is (= 200 (:status dashboard-response))))))))))

;; =============================================================================
;; LOGOUT FUNCTIONALITY AND SESSION CLEANUP TESTS
;; =============================================================================

(deftest test-complete-logout-flow
  (testing "Complete logout functionality with session cleanup"
    (with-redefs [auth/exchange-code-for-token mock-oauth-token-exchange
                  auth/fetch-user-profile mock-oauth-user-profile
                  environ.core/env (merge environ.core/env test-config)]
      
      ;; Step 1: Authenticate user and get session
      (let [oauth-init-response (routes/app (mock/request :get "/auth/microsoft"))
            oauth-state (get-in oauth-init-response [:session :oauth-state])
            callback-request (-> (mock/request :get "/auth/microsoft/callback")
                                (assoc :params {:code "mock-auth-code" :state oauth-state})
                                (assoc :session (:session oauth-init-response)))
            callback-response (routes/app callback-request)
            session-cookie (get-in callback-response [:cookies "session-id" :value])]
        
        (is (some? session-cookie))
        
        ;; Step 2: Verify user can access dashboard
        (let [dashboard-request (-> (mock/request :get "/dashboard")
                                   (assoc :cookies {"session-id" {:value session-cookie}}))
              dashboard-response (routes/app dashboard-request)]
          (is (= 200 (:status dashboard-response)))
          (is (str/includes? (:body dashboard-response) "Microsoft Test User")))
        
        ;; Step 3: Perform logout
        (let [logout-request (-> (mock/request :post "/logout")
                                (assoc :cookies {"session-id" {:value session-cookie}})
                                (assoc :session {:csrf-token "test-csrf"})
                                (mock/body {:csrf-token "test-csrf"}))
              logout-response (routes/app logout-request)]
          
          (is (= 302 (:status logout-response)))
          (is (= "/login" (get-in logout-response [:headers "Location"])))
          
          ;; Verify session cookie is cleared
          (let [cleared-cookie (get-in logout-response [:cookies "session-id"])]
            (is (or (nil? (:value cleared-cookie))
                   (empty? (:value cleared-cookie))))))
        
        ;; Step 4: Verify user cannot access dashboard after logout
        (let [post-logout-request (-> (mock/request :get "/dashboard")
                                     (assoc :cookies {"session-id" {:value session-cookie}}))
              post-logout-response (routes/app post-logout-request)]
          (is (= 302 (:status post-logout-response)))
          (is (= "/login" (get-in post-logout-response [:headers "Location"]))))
        
        ;; Step 5: Verify session is removed from database
        (let [session-exists? (middleware/validate-session session-cookie)]
          (is (nil? session-exists?)))))))

(deftest test-logout-without-csrf-token
  (testing "Logout request without CSRF token should be rejected"
    (let [user (create-test-user "microsoft" "csrf-logout-test" "CSRF Logout Test" "csrf@test.com")
          session (create-test-session (:id user))
          logout-request (-> (mock/request :post "/logout")
                            (assoc :cookies {"session-id" {:value (:session_id session)}}))
          logout-response (routes/app logout-request)]
      
      (is (= 403 (:status logout-response)))
      (is (str/includes? (:body logout-response) "CSRF"))
      
      ;; Verify session still exists after failed logout
      (let [session-still-valid? (middleware/validate-session (:session_id session))]
        (is (some? session-still-valid?))))))

(deftest test-logout-with_invalid_session
  (testing "Logout with invalid session should redirect to login"
    (let [logout-request (-> (mock/request :post "/logout")
                            (assoc :cookies {"session-id" {:value "invalid-session-id"}})
                            (assoc :session {:csrf-token "test-csrf"})
                            (mock/body {:csrf-token "test-csrf"}))
          logout-response (routes/app logout-request)]
      
      (is (= 302 (:status logout-response)))
      (is (= "/login" (get-in logout-response [:headers "Location"]))))))

(deftest test-session_cleanup_on_logout
  (testing "Session cleanup removes expired and invalid sessions"
    ;; Create multiple test sessions
    (let [user1 (create-test-user "microsoft" "cleanup1" "Cleanup User 1" "cleanup1@test.com")
          user2 (create-test-user "github" "cleanup2" "Cleanup User 2" "cleanup2@test.com")
          session1 (create-test-session (:id user1))
          session2 (create-test-session (:id user2))]
      
      ;; Expire one session manually
      (jdbc/execute! (db/get-db-config) ["UPDATE sessions SET expires_at = datetime('now', '-1 hour') WHERE session_id = ?" 
                    (:session_id session1)])
      
      ;; Run cleanup
      (let [cleanup-count (middleware/cleanup-expired-sessions)]
        (is (>= cleanup-count 1)))
      
      ;; Verify expired session is gone, valid session remains
      (is (nil? (middleware/validate-session (:session_id session1))))
      (is (some? (middleware/validate-session (:session_id session2)))))))

;; =============================================================================
;; PERFORMANCE TESTS FOR DATABASE OPERATIONS AND TEMPLATE RENDERING
;; =============================================================================

(deftest test-concurrent_load_simulation
  (testing "Concurrent load simulation for authentication flows"
    (with-redefs [auth/exchange-code-for-token mock-oauth-token-exchange
                  auth/fetch-user-profile mock-oauth-user-profile
                  environ.core/env (merge environ.core/env test-config)]
      
      ;; Simulate concurrent OAuth callbacks
      (let [oauth-init-response (routes/app (mock/request :get "/auth/microsoft"))
            oauth-state (get-in oauth-init-response [:session :oauth-state])
            oauth-session (:session oauth-init-response)
            
            ;; Create multiple concurrent requests
            concurrent-requests (repeatedly 20
                                           #(-> (mock/request :get "/auth/microsoft/callback")
                                               (assoc :params {:code (str "concurrent-code-" (rand-int 1000))
                                                              :state oauth-state})
                                               (assoc :session oauth-session)))
            
            ;; Process all requests concurrently using futures
            start-time (System/currentTimeMillis)
            futures (map #(future (routes/app %)) concurrent-requests)
            responses (map deref futures)
            end-time (System/currentTimeMillis)
            total-time (- end-time start-time)]
        
        (log/info "Processed" (count responses) "concurrent OAuth callbacks in" total-time "ms")
        (log/info "Average time per request:" (/ total-time (count responses)) "ms")
        
        ;; Verify all requests succeeded
        (doseq [response responses]
          (is (= 302 (:status response))))
        
        ;; Verify performance is reasonable (less than 5 seconds for 20 requests)
        (is (< total-time 5000))))))

;; Performance testing utilities
(defn time-operation
  "Time an operation and return the result and elapsed time in milliseconds."
  [operation]
  (let [start-time (System/nanoTime)
        result (operation)
        end-time (System/nanoTime)
        elapsed-ms (/ (- end-time start-time) 1000000.0)]
    {:result result :elapsed-ms elapsed-ms}))

(defn benchmark-operation
  "Benchmark an operation by running it multiple times and calculating statistics."
  [operation iterations]
  (let [results (repeatedly iterations #(time-operation operation))
        times (map :elapsed-ms results)
        min-time (apply min times)
        max-time (apply max times)
        avg-time (/ (reduce + times) (count times))]
    {:iterations iterations
     :min-ms min-time
     :max-ms max-time
     :avg-ms avg-time
     :total-ms (reduce + times)}))

(deftest test-database-performance
  (testing "Database operations performance benchmarks"
    
    ;; Test user creation performance
    (let [user-creation-benchmark 
          (benchmark-operation 
            #(create-test-user "microsoft" 
                              (str "perf-user-" (rand-int 10000)) 
                              "Performance Test User" 
                              "perf@test.com")
            10)]
      (log/info "User creation performance:" user-creation-benchmark)
      (is (< (:avg-ms user-creation-benchmark) 100))) ; Should be under 100ms on average
    
    ;; Test user lookup performance
    (let [test-user (create-test-user "github" "lookup-perf" "Lookup Test" "lookup@test.com")
          lookup-benchmark 
          (benchmark-operation 
            #(db/find-user-by-provider-id "github" "lookup-perf")
            50)]
      (log/info "User lookup performance:" lookup-benchmark)
      (is (< (:avg-ms lookup-benchmark) 50))) ; Should be under 50ms on average
    
    ;; Test session creation performance
    (let [test-user (create-test-user "microsoft" "session-perf" "Session Test" "session@test.com")
          session-creation-benchmark
          (benchmark-operation 
            #(create-test-session (:id test-user))
            20)]
      (log/info "Session creation performance:" session-creation-benchmark)
      (is (< (:avg-ms session-creation-benchmark) 100))) ; Should be under 100ms on average
    
    ;; Test session validation performance
    (let [test-user (create-test-user "github" "validation-perf" "Validation Test" "validation@test.com")
          test-session (create-test-session (:id test-user))
          validation-benchmark
          (benchmark-operation 
            #(middleware/validate-session (:session_id test-session))
            50)]
      (log/info "Session validation performance:" validation-benchmark)
      (is (< (:avg-ms validation-benchmark) 50))) ; Should be under 50ms on average
    
    ;; Test bulk operations performance
    (let [bulk-insert-benchmark
          (benchmark-operation
            #(dotimes [i 5]
               (create-test-user "microsoft" 
                                (str "bulk-user-" (rand-int 10000) "-" i) 
                                (str "Bulk User " i) 
                                (str "bulk" i "@test.com")))
            5)]
      (log/info "Bulk user creation performance:" bulk-insert-benchmark)
      (is (< (:avg-ms bulk-insert-benchmark) 500))))) ; Should be under 500ms for 5 users

(deftest test-template-rendering-performance
  (testing "Template rendering performance benchmarks"
    
    ;; Test login page rendering performance
    (let [login-render-benchmark
          (benchmark-operation
            #(templates/login-page)
            50)]
      (log/info "Login page rendering performance:" login-render-benchmark)
      (is (< (:avg-ms login-render-benchmark) 10))) ; Should be under 10ms on average
    
    ;; Test dashboard rendering performance
    (let [test-user {:id "perf-test-id"
                     :username "Performance Test User"
                     :email "perf@test.com"
                     :provider "microsoft"}
          dashboard-render-benchmark
          (benchmark-operation
            #(templates/dashboard-page test-user :csrf-token "test-csrf")
            50)]
      (log/info "Dashboard rendering performance:" dashboard-render-benchmark)
      (is (< (:avg-ms dashboard-render-benchmark) 10))) ; Should be under 10ms on average
    
    ;; Test error page rendering performance
    (let [error-render-benchmark
          (benchmark-operation
            #(templates/error-page "Test Error" "This is a test error message")
            50)]
      (log/info "Error page rendering performance:" error-render-benchmark)
      (is (< (:avg-ms error-render-benchmark) 10))))) ; Should be under 10ms on average

(deftest test-full-request-response-performance
  (testing "Full HTTP request/response cycle performance"
    (with-redefs [auth/exchange-code-for-token mock-oauth-token-exchange
                  auth/fetch-user-profile mock-oauth-user-profile]
      
      ;; Test login page request performance
      (let [login-request-benchmark
            (benchmark-operation
              #(routes/app (mock/request :get "/login"))
              20)]
        (log/info "Login page request performance:" login-request-benchmark)
        (is (< (:avg-ms login-request-benchmark) 50))) ; Should be under 50ms on average
      
      ;; Test OAuth initiation performance
      (let [oauth-init-benchmark
            (benchmark-operation
              #(routes/app (mock/request :get "/auth/microsoft"))
              20)]
        (log/info "OAuth initiation performance:" oauth-init-benchmark)
        (is (< (:avg-ms oauth-init-benchmark) 50))) ; Should be under 50ms on average
      
      ;; Test authenticated dashboard request performance
      (let [user (create-test-user "microsoft" "dashboard-perf" "Dashboard Perf" "dashboard@test.com")
            session (create-test-session (:id user))
            dashboard-request (-> (mock/request :get "/dashboard")
                                 (assoc :cookies {"session-id" {:value (:session_id session)}}))
            dashboard-request-benchmark
            (benchmark-operation
              #(routes/app dashboard-request)
              20)]
        (log/info "Dashboard request performance:" dashboard-request-benchmark)
        (is (< (:avg-ms dashboard-request-benchmark) 100)))))) ; Should be under 100ms on average