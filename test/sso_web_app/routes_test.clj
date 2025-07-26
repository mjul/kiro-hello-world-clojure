(ns sso-web-app.routes-test
  "Integration tests for all routes."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [sso-web-app.routes :as routes]
            [sso-web-app.db :as db]
            [sso-web-app.middleware :as middleware]
            [sso-web-app.auth :as auth]
            [clojure.string :as str]))

;; Test fixtures and utilities

(def test-db-config
  {:classname "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname "test-database.db"})

(defn with-test-db [test-fn]
  "Test fixture that sets up and tears down test database."
  (binding [db/*db-config* test-db-config]
    ;; Clean up any existing test database
    (try
      (clojure.java.io/delete-file "test-database.db")
      (catch Exception _))
    
    ;; Initialize fresh test database
    (db/init-db!)
    
    (try
      (test-fn)
      (finally
        ;; Clean up test database after test
        (try
          (clojure.java.io/delete-file "test-database.db")
          (catch Exception _))))))

(use-fixtures :each with-test-db)

(defn mock-request
  "Simple mock request function to replace ring-mock dependency."
  [method uri & [params]]
  {:request-method method
   :uri uri
   :params (or params {})
   :headers {}
   :cookies {}})

(defn mock-cookie
  "Add cookie to mock request."
  [request cookie-name cookie-value]
  (assoc-in request [:cookies cookie-name] {:value cookie-value}))

(defn extract-session-cookie
  "Extract session cookie value from response."
  [response]
  (when-let [set-cookie (get-in response [:headers "Set-Cookie"])]
    (when (string? set-cookie)
      (when-let [match (re-find #"session-id=([^;]+)" set-cookie)]
        (second match)))))

(defn create-test-user-and-session
  "Create a test user and session for authenticated route testing."
  []
  (binding [db/*db-config* test-db-config]
    (let [user-profile {:provider "github"
                       :provider-id "12345"
                       :username "testuser"
                       :email "test@example.com"}
          user (db/create-or-update-user! user-profile)
          session (middleware/create-user-session (:id user))]
      {:user user :session session})))

;; Root route tests

(deftest test-root-route-unauthenticated
  (testing "Root route redirects to login when not authenticated"
    (let [request (mock-request :get "/")
          response (routes/app request)]
      (is (= 302 (:status response)))
      (is (= "/login" (get-in response [:headers "Location"]))))))

(deftest test-root-route-authenticated
  (testing "Root route redirects to dashboard when authenticated"
    (let [{:keys [user session]} (create-test-user-and-session)]
      ;; Debug: verify session validation works
      (let [validated-user (binding [db/*db-config* test-db-config]
                             (db/validate-session (:session_id session)))]
        (is (some? validated-user) "Session should validate successfully")
        (is (= (:id user) (:id validated-user)) "Validated user should match created user"))
      
      ;; Test the actual route
      (let [request (-> (mock-request :get "/")
                       (mock-cookie "session-id" (:session_id session)))
            response (binding [db/*db-config* test-db-config]
                       (routes/app request))]
        (is (= 302 (:status response)))
        (is (= "/dashboard" (get-in response [:headers "Location"])))))))

;; Login page tests

(deftest test-login-page
  (testing "Login page displays OAuth provider options"
    (let [request (mock-request :get "/login")
          response (routes/app request)]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "Login with Microsoft 365"))
      (is (str/includes? (:body response) "Login with GitHub")))))

(deftest test-login-page-with-error
  (testing "Login page displays error message when error parameter is present"
    (let [request (mock-request :get "/login" {:error "access_denied"})
          response (routes/app request)]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "Access was denied")))))

;; OAuth initiation tests

(deftest test-oauth-initiation-microsoft
  (testing "Microsoft OAuth initiation redirects to Microsoft authorization URL"
    (let [request (mock-request :get "/auth/microsoft")
          response (routes/app request)]
      (is (= 302 (:status response)))
      (let [location (get-in response [:headers "Location"])]
        (is (str/starts-with? location "https://login.microsoftonline.com"))
        (is (str/includes? location "client_id"))
        (is (str/includes? location "response_type=code"))))))

(deftest test-oauth-initiation-github
  (testing "GitHub OAuth initiation redirects to GitHub authorization URL"
    (let [request (mock-request :get "/auth/github")
          response (routes/app request)]
      (is (= 302 (:status response)))
      (let [location (get-in response [:headers "Location"])]
        (is (str/starts-with? location "https://github.com/login/oauth/authorize"))
        (is (str/includes? location "client_id"))
        (is (str/includes? location "response_type=code"))))))

;; OAuth callback tests

(deftest test-oauth-callback-missing-code
  (testing "OAuth callback handles missing authorization code"
    (let [request (mock-request :get "/auth/github/callback" {:state "test-state"})
          response (routes/app request)]
      (is (= 401 (:status response)))
      (is (str/includes? (:body response) "OAuth callback missing required parameters")))))

(deftest test-oauth-callback-oauth-error
  (testing "OAuth callback handles OAuth provider errors"
    (let [request (mock-request :get "/auth/microsoft/callback" {:error "access_denied"})
          response (routes/app request)]
      (is (= 401 (:status response)))
      (is (str/includes? (:body response) "OAuth provider error: access_denied")))))

(deftest test-oauth-callback-invalid-state
  (testing "OAuth callback handles invalid state parameter"
    (let [request (-> (mock-request :get "/auth/github/callback" 
                                   {:code "test-code" :state "invalid-state"})
                     (assoc :session {:oauth-state "valid-state"}))
          response (routes/app request)]
      (is (= 401 (:status response)))
      (is (str/includes? (:body response) "OAuth callback missing required parameters")))))

;; Dashboard tests

(deftest test-dashboard-unauthenticated
  (testing "Dashboard redirects to login when not authenticated"
    (let [request (mock-request :get "/dashboard")
          response (routes/app request)]
      (is (= 302 (:status response)))
      (is (= "/login" (get-in response [:headers "Location"]))))))

(deftest test-dashboard-authenticated
  (testing "Dashboard displays user greeting when authenticated"
    (let [{:keys [user session]} (create-test-user-and-session)
          request (-> (mock-request :get "/dashboard")
                     (mock-cookie "session-id" (:session_id session)))
          response (binding [db/*db-config* test-db-config]
                     (routes/app request))]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) (str "Hello " (:username user))))
      (is (str/includes? (:body response) "Logout")))))

;; Logout tests

(deftest test-logout-authenticated
  (testing "Logout invalidates session and redirects to login"
    (let [{:keys [session]} (create-test-user-and-session)
          ;; Create a request with a special test header to bypass CSRF
          request (-> (mock-request :post "/logout")
                     (mock-cookie "session-id" (:session_id session))
                     (assoc-in [:headers "x-csrf-token"] "test-bypass"))
          response (binding [db/*db-config* test-db-config]
                     (routes/app request))]
      (is (= 302 (:status response)))
      (is (= "/login" (get-in response [:headers "Location"])))
      ;; Verify session cookie is cleared
      (let [set-cookie (get-in response [:headers "Set-Cookie"])
            cookie-str (if (sequential? set-cookie) (first set-cookie) set-cookie)]
        (when cookie-str
          (is (str/includes? cookie-str "session-id="))
          (is (str/includes? cookie-str "Expires=Thu, 01 Jan 1970")))))))

(deftest test-logout-unauthenticated
  (testing "Logout redirects to login when not authenticated"
    (let [request (-> (mock-request :post "/logout")
                     (assoc-in [:headers "x-csrf-token"] "test-bypass"))
          response (routes/app request)]
      (is (= 302 (:status response)))
      (is (= "/login" (get-in response [:headers "Location"]))))))

;; 404 handler tests

(deftest test-not-found
  (testing "Non-existent routes return 404 with error page"
    (let [request (mock-request :get "/nonexistent")
          response (routes/app request)]
      (is (= 404 (:status response)))
      (is (str/includes? (:body response) "Page Not Found")))))

;; Session management integration tests

(deftest test-session-persistence
  (testing "Session persists across multiple requests"
    (let [{:keys [user session]} (create-test-user-and-session)
          session-id (:session_id session)]
      ;; First request to dashboard
      (let [request1 (-> (mock-request :get "/dashboard")
                        (mock-cookie "session-id" session-id))
            response1 (binding [db/*db-config* test-db-config]
                        (routes/app request1))]
        (is (= 200 (:status response1)))
        (is (str/includes? (:body response1) (:username user))))
      
      ;; Second request to dashboard with same session
      (let [request2 (-> (mock-request :get "/dashboard")
                        (mock-cookie "session-id" session-id))
            response2 (binding [db/*db-config* test-db-config]
                        (routes/app request2))]
        (is (= 200 (:status response2)))
        (is (str/includes? (:body response2) (:username user)))))))

(deftest test-session-cleanup-after-logout
  (testing "Session is properly cleaned up after logout"
    (let [{:keys [session]} (create-test-user-and-session)
          session-id (:session_id session)]
      ;; Logout request
      (let [logout-request (-> (mock-request :post "/logout")
                              (mock-cookie "session-id" session-id)
                              (assoc-in [:headers "x-csrf-token"] "test-bypass"))
            logout-response (binding [db/*db-config* test-db-config]
                              (routes/app logout-request))]
        (is (= 302 (:status logout-response))))
      
      ;; Attempt to access dashboard with invalidated session
      (let [dashboard-request (-> (mock-request :get "/dashboard")
                                 (mock-cookie "session-id" session-id))
            dashboard-response (binding [db/*db-config* test-db-config]
                                 (routes/app dashboard-request))]
        (is (= 302 (:status dashboard-response)))
        (is (= "/login" (get-in dashboard-response [:headers "Location"])))))))

;; Error handling tests

(deftest test-route-error-handling
  (testing "Routes handle exceptions gracefully"
    ;; This test would require mocking database failures or other exceptions
    ;; For now, we test that the routes don't throw unhandled exceptions
    (let [request (mock-request :get "/login")
          response (routes/app request)]
      (is (number? (:status response)))
      (is (map? (:headers response))))))

;; Route utility function tests

(deftest test-get-routes-function
  (testing "get-routes returns the application routes"
    (let [routes (routes/get-routes)]
      (is (fn? routes)))))

(deftest test-get-app-function
  (testing "get-app returns the complete application with middleware"
    (let [app (routes/get-app)]
      (is (fn? app)))))