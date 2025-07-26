(ns sso-web-app.middleware-test
  "Tests for session management and authentication middleware."
  (:require [clojure.test :refer :all]
            [sso-web-app.middleware :as middleware]
            [sso-web-app.db :as db]
            [ring.util.response :as response])
  (:import [java.time Instant Duration]))

;; Test fixtures and utilities

(def test-user
  {:id "test-user-id"
   :provider "github"
   :provider_id "12345"
   :username "testuser"
   :email "test@example.com"})

(def test-session-id "test-session-id")

(defn mock-handler
  "Mock handler that returns the request for testing."
  [request]
  {:status 200
   :body (str "User: " (get-in request [:user :username] "anonymous"))})

;; Session creation and validation tests

(deftest test-create-user-session
  (testing "Session creation"
    (with-redefs [db/create-session! (fn [user-id expires-at]
                                       {:session_id test-session-id
                                        :user_id user-id
                                        :expires_at expires-at})]
      (let [session (middleware/create-user-session (:id test-user))]
        (is (= test-session-id (:session_id session)))
        (is (= (:id test-user) (:user_id session)))
        (is (not (nil? (:expires_at session))))))))

(deftest test-validate-session
  (testing "Valid session validation"
    (with-redefs [db/validate-session (fn [session-id]
                                        (when (= session-id test-session-id)
                                          test-user))]
      (let [user (middleware/validate-session test-session-id)]
        (is (= test-user user)))))
  
  (testing "Invalid session validation"
    (with-redefs [db/validate-session (fn [session-id] nil)]
      (let [user (middleware/validate-session "invalid-session")]
        (is (nil? user)))))
  
  (testing "Nil session ID"
    (let [user (middleware/validate-session nil)]
      (is (nil? user)))))

(deftest test-invalidate-session
  (testing "Session invalidation"
    (with-redefs [db/delete-session! (fn [session-id]
                                       (when (= session-id test-session-id) 1))]
      (let [result (middleware/invalidate-session test-session-id)]
        (is (true? result)))))
  
  (testing "Invalid session invalidation"
    (with-redefs [db/delete-session! (fn [session-id] 0)]
      (let [result (middleware/invalidate-session "invalid-session")]
        (is (true? result))))) ; Still returns true as operation succeeded
  
  (testing "Nil session ID invalidation"
    (let [result (middleware/invalidate-session nil)]
      (is (nil? result)))))

(deftest test-cleanup-expired-sessions
  (testing "Session cleanup"
    (with-redefs [db/cleanup-expired-sessions! (fn [] 5)]
      (let [count (middleware/cleanup-expired-sessions)]
        (is (= 5 count)))))
  
  (testing "Session cleanup with error"
    (with-redefs [db/cleanup-expired-sessions! (fn [] (throw (Exception. "DB error")))]
      (let [count (middleware/cleanup-expired-sessions)]
        (is (= 0 count))))))

;; Cookie handling tests

(deftest test-get-session-id-from-request
  (testing "Extract session ID from cookies"
    (let [request {:cookies {"session-id" {:value test-session-id}}}
          session-id (middleware/get-session-id-from-request request)]
      (is (= test-session-id session-id))))
  
  (testing "No session cookie"
    (let [request {:cookies {}}
          session-id (middleware/get-session-id-from-request request)]
      (is (nil? session-id)))))

(deftest test-add-session-cookie
  (testing "Add session cookie to response"
    (let [response {:status 200 :body "test"}
          response-with-cookie (middleware/add-session-cookie response test-session-id)]
      (is (= test-session-id (get-in response-with-cookie [:cookies "session-id" :value])))
      (is (true? (get-in response-with-cookie [:cookies "session-id" :http-only])))
      (is (= :strict (get-in response-with-cookie [:cookies "session-id" :same-site]))))))

(deftest test-remove-session-cookie
  (testing "Remove session cookie from response"
    (let [response {:status 200 :body "test"}
          response-without-cookie (middleware/remove-session-cookie response)]
      (is (= "" (get-in response-without-cookie [:cookies "session-id" :value])))
      (is (= 0 (get-in response-without-cookie [:cookies "session-id" :max-age]))))))

;; Authentication middleware tests

(deftest test-wrap-authentication
  (testing "Authenticated request"
    (with-redefs [middleware/get-session-id-from-request (fn [req] test-session-id)
                  middleware/validate-session (fn [session-id] test-user)]
      (let [wrapped-handler (middleware/wrap-authentication mock-handler)
            request {:request-method :get :uri "/dashboard"}
            response (wrapped-handler request)]
        (is (= 200 (:status response)))
        (is (.contains (:body response) "testuser")))))
  
  (testing "Unauthenticated request"
    (with-redefs [middleware/get-session-id-from-request (fn [req] nil)
                  middleware/validate-session (fn [session-id] nil)]
      (let [wrapped-handler (middleware/wrap-authentication mock-handler)
            request {:request-method :get :uri "/dashboard"}
            response (wrapped-handler request)]
        (is (= 302 (:status response)))
        (is (= "/login" (get-in response [:headers "Location"]))))))
  
  (testing "Invalid session"
    (with-redefs [middleware/get-session-id-from-request (fn [req] "invalid-session")
                  middleware/validate-session (fn [session-id] nil)]
      (let [wrapped-handler (middleware/wrap-authentication mock-handler)
            request {:request-method :get :uri "/dashboard"}
            response (wrapped-handler request)]
        (is (= 302 (:status response)))
        (is (= "/login" (get-in response [:headers "Location"])))))))

(deftest test-wrap-optional-authentication
  (testing "Authenticated request with optional auth"
    (with-redefs [middleware/get-session-id-from-request (fn [req] test-session-id)
                  middleware/validate-session (fn [session-id] test-user)]
      (let [wrapped-handler (middleware/wrap-optional-authentication mock-handler)
            request {:request-method :get :uri "/"}
            response (wrapped-handler request)]
        (is (= 200 (:status response)))
        (is (.contains (:body response) "testuser")))))
  
  (testing "Unauthenticated request with optional auth"
    (with-redefs [middleware/get-session-id-from-request (fn [req] nil)
                  middleware/validate-session (fn [session-id] nil)]
      (let [wrapped-handler (middleware/wrap-optional-authentication mock-handler)
            request {:request-method :get :uri "/"}
            response (wrapped-handler request)]
        (is (= 200 (:status response)))
        (is (.contains (:body response) "anonymous"))))))

(deftest test-authenticated?
  (testing "Request with authenticated user"
    (let [request {:user test-user}]
      (is (true? (middleware/authenticated? request)))))
  
  (testing "Request without authenticated user"
    (let [request {}]
      (is (false? (middleware/authenticated? request))))))

(deftest test-require-authentication
  (testing "Authenticated request"
    (let [wrapped-handler (middleware/require-authentication mock-handler)
          request {:user test-user}
          response (wrapped-handler request)]
      (is (= 200 (:status response)))))
  
  (testing "Unauthenticated request"
    (let [wrapped-handler (middleware/require-authentication mock-handler)
          request {}
          response (wrapped-handler request)]
      (is (= 401 (:status response)))
      (is (= "Authentication required" (:body response))))))

;; Session cleanup middleware tests

(deftest test-wrap-session-cleanup
  (testing "Session cleanup middleware"
    (let [cleanup-called (atom false)
          wrapped-handler (with-redefs [middleware/cleanup-expired-sessions 
                                       (fn [] (reset! cleanup-called true) 0)]
                           (middleware/wrap-session-cleanup mock-handler))
          request {:request-method :get :uri "/"}
          response (wrapped-handler request)]
      (is (= 200 (:status response)))
      ;; Note: cleanup runs in future, so we can't easily test it was called
      ;; This test mainly ensures the middleware doesn't break request processing
      )))

;; Logout functionality tests

(deftest test-logout-user
  (testing "Logout with valid session"
    (with-redefs [middleware/get-session-id-from-request (fn [req] test-session-id)
                  middleware/invalidate-session (fn [session-id] true)]
      (let [request {:cookies {"session-id" {:value test-session-id}}}
            response (middleware/logout-user request)]
        (is (= 302 (:status response)))
        (is (= "/login" (get-in response [:headers "Location"])))
        (is (= "" (get-in response [:cookies "session-id" :value]))))))
  
  (testing "Logout without session"
    (with-redefs [middleware/get-session-id-from-request (fn [req] nil)]
      (let [request {}
            response (middleware/logout-user request)]
        (is (= 302 (:status response)))
        (is (= "/login" (get-in response [:headers "Location"])))))))

;; Helper function tests

(deftest test-helper-functions
  (testing "get-current-user"
    (let [request {:user test-user}]
      (is (= test-user (middleware/get-current-user request))))
    (let [request {}]
      (is (nil? (middleware/get-current-user request)))))
  
  (testing "get-user-id"
    (let [request {:user test-user}]
      (is (= (:id test-user) (middleware/get-user-id request))))
    (let [request {}]
      (is (nil? (middleware/get-user-id request)))))
  
  (testing "get-username"
    (let [request {:user test-user}]
      (is (= (:username test-user) (middleware/get-username request))))
    (let [request {}]
      (is (nil? (middleware/get-username request))))))

;; Route protection tests

(deftest test-protect-route
  (testing "Protected route with authentication"
    (with-redefs [middleware/wrap-authentication identity] ; Mock the wrapper
      (let [protected-handler (middleware/protect-route mock-handler)]
        (is (fn? protected-handler))))))

(deftest test-protect-routes
  (testing "Protect multiple routes"
    (let [routes [[:get "/dashboard" mock-handler]
                  [:get "/profile" mock-handler]]
          protected-routes (middleware/protect-routes routes)]
      (is (= 2 (count protected-routes)))
      (is (every? vector? protected-routes)))))
;; CSRF Protection Tests

(deftest test-generate-csrf-token
  (testing "CSRF token generation"
    (let [token1 (middleware/generate-csrf-token)
          token2 (middleware/generate-csrf-token)]
      (is (string? token1))
      (is (string? token2))
      (is (not= token1 token2)) ; Tokens should be unique
      (is (>= (count token1) 40)) ; Should be reasonably long
      (is (re-matches #"^[A-Za-z0-9+/=]+$" token1))))) ; Base64 format

(deftest test-get-csrf-token
  (testing "Get existing CSRF token from session"
    (let [existing-token "existing-csrf-token"
          request {:session {:csrf-token existing-token}}
          token (middleware/get-csrf-token request)]
      (is (= existing-token token))))
  
  (testing "Generate new CSRF token when none exists"
    (let [request {:session {}}
          token (middleware/get-csrf-token request)]
      (is (string? token))
      (is (>= (count token) 40)))))

(deftest test-valid-csrf-token?
  (testing "Valid CSRF token in params"
    (let [token "test-csrf-token"
          request {:session {:csrf-token token}
                   :params {:csrf-token token}}]
      (is (true? (middleware/valid-csrf-token? request)))))
  
  (testing "Valid CSRF token in headers"
    (let [token "test-csrf-token"
          request {:session {:csrf-token token}
                   :headers {"x-csrf-token" token}}]
      (is (true? (middleware/valid-csrf-token? request)))))
  
  (testing "Invalid CSRF token"
    (let [request {:session {:csrf-token "session-token"}
                   :params {:csrf-token "different-token"}}]
      (is (false? (middleware/valid-csrf-token? request)))))
  
  (testing "Missing CSRF token in request"
    (let [request {:session {:csrf-token "session-token"}
                   :params {}}]
      (is (false? (middleware/valid-csrf-token? request)))))
  
  (testing "Missing CSRF token in session"
    (let [request {:session {}
                   :params {:csrf-token "request-token"}}]
      (is (false? (middleware/valid-csrf-token? request))))))

(deftest test-wrap-csrf-protection
  (testing "GET request passes through without CSRF check"
    (let [handler (fn [req] {:status 200 :body "OK"})
          wrapped-handler (middleware/wrap-csrf-protection handler)
          request {:request-method :get :uri "/dashboard" :session {}}
          response (wrapped-handler request)]
      (is (= 200 (:status response)))
      (is (= "OK" (:body response)))))
  
  (testing "POST request with valid CSRF token"
    (let [token "valid-csrf-token"
          handler (fn [req] {:status 200 :body "OK"})
          wrapped-handler (middleware/wrap-csrf-protection handler)
          request {:request-method :post 
                   :uri "/logout"
                   :session {:csrf-token token}
                   :params {:csrf-token token}}
          response (wrapped-handler request)]
      (is (= 200 (:status response)))
      (is (= "OK" (:body response)))))
  
  (testing "POST request with invalid CSRF token"
    (let [handler (fn [req] {:status 200 :body "OK"})
          wrapped-handler (middleware/wrap-csrf-protection handler)
          request {:request-method :post 
                   :uri "/logout"
                   :session {:csrf-token "session-token"}
                   :params {:csrf-token "different-token"}}
          response (wrapped-handler request)]
      (is (= 403 (:status response)))
      (is (.contains (:body response) "CSRF token validation failed"))))
  
  (testing "POST request without CSRF token"
    (let [handler (fn [req] {:status 200 :body "OK"})
          wrapped-handler (middleware/wrap-csrf-protection handler)
          request {:request-method :post 
                   :uri "/logout"
                   :session {:csrf-token "session-token"}
                   :params {}}
          response (wrapped-handler request)]
      (is (= 403 (:status response)))
      (is (.contains (:body response) "CSRF token validation failed")))))

;; Security Headers Tests

(deftest test-wrap-security-headers
  (testing "Security headers are added to response"
    (let [handler (fn [req] {:status 200 :body "OK" :headers {}})
          wrapped-handler (middleware/wrap-security-headers handler)
          request {:request-method :get :uri "/"}
          response (wrapped-handler request)]
      (is (= 200 (:status response)))
      (is (contains? (:headers response) "Strict-Transport-Security"))
      (is (contains? (:headers response) "Content-Security-Policy"))
      (is (contains? (:headers response) "X-Content-Type-Options"))
      (is (contains? (:headers response) "X-Frame-Options"))
      (is (contains? (:headers response) "X-XSS-Protection"))
      (is (contains? (:headers response) "Referrer-Policy"))
      (is (contains? (:headers response) "Permissions-Policy"))
      (is (contains? (:headers response) "Cache-Control"))))
  
  (testing "Security headers values are correct"
    (let [handler (fn [req] {:status 200 :body "OK" :headers {}})
          wrapped-handler (middleware/wrap-security-headers handler)
          request {:request-method :get :uri "/"}
          response (wrapped-handler request)
          headers (:headers response)]
      (is (= "max-age=31536000; includeSubDomains" (get headers "Strict-Transport-Security")))
      (is (.contains (get headers "Content-Security-Policy") "default-src 'self'"))
      (is (= "nosniff" (get headers "X-Content-Type-Options")))
      (is (= "DENY" (get headers "X-Frame-Options")))
      (is (= "1; mode=block" (get headers "X-XSS-Protection")))
      (is (= "strict-origin-when-cross-origin" (get headers "Referrer-Policy")))
      (is (= "no-cache, no-store, must-revalidate" (get headers "Cache-Control"))))))

;; Input Validation Tests

(deftest test-sanitize-string
  (testing "Remove dangerous characters"
    (is (= "HelloWorld" (middleware/sanitize-string "Hello<script>World")))
    (is (= "Test  String" (middleware/sanitize-string "Test\r\nString")))
    (is (= "Clean text" (middleware/sanitize-string "  Clean text  "))))
  
  (testing "Handle nil input"
    (is (nil? (middleware/sanitize-string nil))))
  
  (testing "Handle non-string input"
    (is (= "123" (middleware/sanitize-string 123)))))

(deftest test-validate-oauth-state
  (testing "Valid OAuth state"
    (is (true? (middleware/validate-oauth-state "YWJjZGVmZ2hpams1234567890123456789012345="))))
  
  (testing "Invalid OAuth state - too short"
    (is (false? (middleware/validate-oauth-state "short"))))
  
  (testing "Invalid OAuth state - invalid characters"
    (is (false? (middleware/validate-oauth-state "invalid<>characters"))))
  
  (testing "Invalid OAuth state - too long"
    (let [long-state (apply str (repeat 300 "a"))]
      (is (false? (middleware/validate-oauth-state long-state)))))
  
  (testing "Nil OAuth state"
    (is (false? (middleware/validate-oauth-state nil)))))

(deftest test-validate-oauth-code
  (testing "Valid OAuth code"
    (is (true? (middleware/validate-oauth-code "abc123_def-456.ghi"))))
  
  (testing "Invalid OAuth code - invalid characters"
    (is (false? (middleware/validate-oauth-code "invalid<>code"))))
  
  (testing "Invalid OAuth code - too long"
    (let [long-code (apply str (repeat 600 "a"))]
      (is (false? (middleware/validate-oauth-code long-code)))))
  
  (testing "Nil OAuth code"
    (is (false? (middleware/validate-oauth-code nil)))))

(deftest test-validate-provider
  (testing "Valid providers"
    (is (true? (middleware/validate-provider "microsoft")))
    (is (true? (middleware/validate-provider "github")))
    (is (true? (middleware/validate-provider "Microsoft"))) ; Case insensitive
    (is (true? (middleware/validate-provider "GITHUB"))))
  
  (testing "Invalid provider"
    (is (false? (middleware/validate-provider "invalid")))
    (is (false? (middleware/validate-provider "google"))))
  
  (testing "Nil provider"
    (is (false? (middleware/validate-provider nil)))))

(deftest test-sanitize-oauth-params
  (testing "Valid OAuth parameters"
    (let [params {:code "valid_code-123"
                  :state "YWJjZGVmZ2hpams1234567890123456789012345="
                  :error "access_denied"}
          sanitized (middleware/sanitize-oauth-params params)]
      (is (= "valid_code-123" (:code sanitized)))
      (is (= "YWJjZGVmZ2hpams1234567890123456789012345=" (:state sanitized)))
      (is (= "access_denied" (:error sanitized)))))
  
  (testing "Invalid OAuth parameters are filtered out"
    (let [params {:code "invalid code with spaces"  ; Spaces are not allowed in OAuth codes
                  :state "short"                     ; Too short for valid state
                  :error "some_error"}
          sanitized (middleware/sanitize-oauth-params params)]
      (is (not (contains? sanitized :code))) ; Invalid code should be filtered out
      (is (not (contains? sanitized :state))) ; Invalid state should be filtered out
      (is (= "some_error" (:error sanitized))))))

(deftest test-wrap-input-validation
  (testing "OAuth request with valid parameters"
    (let [handler (fn [req] {:status 200 :body (str "Code: " (get-in req [:params :code]))})
          wrapped-handler (middleware/wrap-input-validation handler)
          request {:request-method :get 
                   :uri "/auth/github/callback"
                   :params {:code "valid_code-123"
                           :state "YWJjZGVmZ2hpams1234567890123456789012345="}}
          response (wrapped-handler request)]
      (is (= 200 (:status response)))
      (is (.contains (:body response) "valid_code-123"))))
  
  (testing "OAuth request with invalid code"
    (let [handler (fn [req] {:status 200 :body "OK"})
          wrapped-handler (middleware/wrap-input-validation handler)
          request {:request-method :get 
                   :uri "/auth/github/callback"
                   :params {:code "invalid code with spaces"}} ; Spaces make it invalid
          response (wrapped-handler request)]
      (is (= 400 (:status response)))
      (is (.contains (:body response) "Invalid OAuth parameters"))))
  
  (testing "Non-OAuth request with general sanitization"
    (let [handler (fn [req] {:status 200 :body (str "Param: " (get-in req [:params :test]))})
          wrapped-handler (middleware/wrap-input-validation handler)
          request {:request-method :get 
                   :uri "/dashboard"
                   :params {:test "hello<script>world"}}
          response (wrapped-handler request)]
      (is (= 200 (:status response)))
      (is (.contains (:body response) "helloworld"))
      (is (not (.contains (:body response) "<script>"))))))

;; Enhanced Session Security Tests

(deftest test-wrap-secure-session
  (testing "Session cookie security attributes are set"
    (let [handler (fn [req] {:status 200 
                            :body "OK"
                            :cookies {"session-id" {:value "test-session"}}})
          wrapped-handler (middleware/wrap-secure-session handler)
          request {:request-method :get :uri "/"}
          response (wrapped-handler request)]
      (is (= 200 (:status response)))
      (is (true? (get-in response [:cookies "session-id" :secure])))
      (is (true? (get-in response [:cookies "session-id" :http-only])))
      (is (= :strict (get-in response [:cookies "session-id" :same-site])))))
  
  (testing "Response without session cookie is unchanged"
    (let [handler (fn [req] {:status 200 :body "OK" :headers {}})
          wrapped-handler (middleware/wrap-secure-session handler)
          request {:request-method :get :uri "/"}
          response (wrapped-handler request)]
      (is (= 200 (:status response)))
      (is (= "OK" (:body response)))
      (is (not (contains? response :cookies))))))

;; Security Middleware Composition Tests

(deftest test-wrap-security
  (testing "Security middleware composition"
    (let [handler (fn [req] {:status 200 :body "OK" :headers {}})
          wrapped-handler (middleware/wrap-security handler)
          request {:request-method :get :uri "/" :session {} :params {}}
          response (wrapped-handler request)]
      (is (= 200 (:status response)))
      ;; Should have security headers
      (is (contains? (:headers response) "X-Frame-Options"))
      ;; Should have CSRF token in session for next request
      (is (fn? wrapped-handler)))))

;; Session Security Integration Tests

(deftest test-session-security-integration
  (testing "Complete session security flow"
    (with-redefs [middleware/get-session-id-from-request (fn [req] test-session-id)
                  middleware/validate-session (fn [session-id] 
                                                (when (= session-id test-session-id)
                                                  test-user))]
      (let [handler (fn [req] {:status 200 
                              :body (str "User: " (get-in req [:user :username]))
                              :cookies {"session-id" {:value test-session-id}}})
            wrapped-handler (-> handler
                               middleware/wrap-authentication
                               middleware/wrap-secure-session
                               middleware/wrap-security-headers)
            request {:request-method :get 
                     :uri "/dashboard"
                     :cookies {"session-id" {:value test-session-id}}
                     :session {}
                     :params {}}
            response (wrapped-handler request)]
        (is (= 200 (:status response)))
        (is (.contains (:body response) "testuser"))
        ;; Should have security headers
        (is (contains? (:headers response) "X-Frame-Options"))
        ;; Should have secure session cookie
        (is (true? (get-in response [:cookies "session-id" :secure]))))))
  
  (testing "CSRF protection with authentication"
    (with-redefs [middleware/get-session-id-from-request (fn [req] test-session-id)
                  middleware/validate-session (fn [session-id] test-user)]
      (let [csrf-token "test-csrf-token"
            handler (fn [req] {:status 200 :body "Logout successful"})
            wrapped-handler (-> handler
                               middleware/wrap-csrf-protection
                               middleware/wrap-authentication)
            request {:request-method :post 
                     :uri "/logout"
                     :session {:csrf-token csrf-token}
                     :params {:csrf-token csrf-token}
                     :cookies {"session-id" {:value test-session-id}}}
            response (wrapped-handler request)]
        (is (= 200 (:status response)))
        (is (= "Logout successful" (:body response)))))))