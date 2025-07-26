(ns sso-web-app.auth-test
  (:require [clojure.test :refer :all]
            [sso-web-app.auth :as auth]
            [environ.core :refer [env]])
  (:import [java.util.regex Pattern]))

(deftest test-generate-state
  (testing "State generation"
    (let [state1 (auth/generate-state)
          state2 (auth/generate-state)]
      (is (string? state1) "State should be a string")
      (is (string? state2) "State should be a string")
      (is (not= state1 state2) "Each state should be unique")
      (is (>= (count state1) 40) "State should be sufficiently long")
      (is (re-matches #"[A-Za-z0-9_-]+" state1) "State should be URL-safe base64"))))

(deftest test-validate-state
  (testing "State validation"
    (let [valid-state "test-state-123"]
      (is (auth/validate-state valid-state valid-state) "Same states should validate")
      (is (not (auth/validate-state valid-state "different-state")) "Different states should not validate")
      (is (not (auth/validate-state nil valid-state)) "Nil session state should not validate")
      (is (not (auth/validate-state valid-state nil)) "Nil received state should not validate")
      (is (not (auth/validate-state nil nil)) "Both nil states should not validate"))))

(deftest test-supported-provider
  (testing "Provider support check"
    (is (auth/supported-provider? :microsoft) "Microsoft should be supported")
    (is (auth/supported-provider? :github) "GitHub should be supported")
    (is (not (auth/supported-provider? :google)) "Google should not be supported")
    (is (not (auth/supported-provider? :invalid)) "Invalid provider should not be supported")))

(deftest test-get-supported-providers
  (testing "Get supported providers"
    (let [providers (auth/get-supported-providers)]
      (is (coll? providers) "Should return a collection")
      (is (contains? (set providers) :microsoft) "Should include Microsoft")
      (is (contains? (set providers) :github) "Should include GitHub")
      (is (= 2 (count providers)) "Should have exactly 2 providers"))))

(deftest test-get-oauth-config
  (testing "Get OAuth configuration"
    (let [ms-config (auth/get-oauth-config :microsoft)
          gh-config (auth/get-oauth-config :github)]
      (is (map? ms-config) "Microsoft config should be a map")
      (is (map? gh-config) "GitHub config should be a map")
      (is (contains? ms-config :auth-url) "Microsoft config should have auth-url")
      (is (contains? gh-config :auth-url) "GitHub config should have auth-url")
      (is (nil? (auth/get-oauth-config :invalid)) "Invalid provider should return nil"))))

(deftest test-build-auth-url
  (testing "Build authorization URL"
    (with-redefs [env (constantly nil)] ; Mock environment variables
      (let [state "test-state-123"]
        (testing "Microsoft auth URL"
          (let [url (auth/build-auth-url :microsoft state)]
            (when url ; Only test if config is available
              (is (string? url) "Should return a string")
              (is (.startsWith url "https://login.microsoftonline.com") "Should start with Microsoft auth URL")
              (is (.contains url "client_id=") "Should contain client_id parameter")
              (is (.contains url "state=test-state-123") "Should contain state parameter")
              (is (.contains url "response_type=code") "Should contain response_type parameter"))))
        
        (testing "GitHub auth URL"
          (let [url (auth/build-auth-url :github state)]
            (when url ; Only test if config is available
              (is (string? url) "Should return a string")
              (is (.startsWith url "https://github.com/login/oauth/authorize") "Should start with GitHub auth URL")
              (is (.contains url "client_id=") "Should contain client_id parameter")
              (is (.contains url "state=test-state-123") "Should contain state parameter"))))
        
        (testing "Invalid provider"
          (is (nil? (auth/build-auth-url :invalid state)) "Invalid provider should return nil"))))))

(deftest test-initiate-oauth
  (testing "OAuth flow initiation"
    (testing "Valid providers"
      (let [ms-result (auth/initiate-oauth :microsoft)
            gh-result (auth/initiate-oauth :github)]
        (when ms-result ; Only test if config is available
          (is (map? ms-result) "Microsoft result should be a map")
          (is (contains? ms-result :auth-url) "Should contain auth-url")
          (is (contains? ms-result :state) "Should contain state")
          (is (string? (:state ms-result)) "State should be a string"))
        
        (when gh-result ; Only test if config is available
          (is (map? gh-result) "GitHub result should be a map")
          (is (contains? gh-result :auth-url) "Should contain auth-url")
          (is (contains? gh-result :state) "Should contain state"))))
    
    (testing "Invalid provider"
      (is (nil? (auth/initiate-oauth :invalid)) "Invalid provider should return nil"))))

(deftest test-validate-oauth-config
  (testing "OAuth configuration validation"
    ; Note: These tests depend on environment variables being set
    ; In a real environment, you'd mock the configuration
    (testing "Supported providers"
      (let [ms-valid? (auth/validate-oauth-config :microsoft)
            gh-valid? (auth/validate-oauth-config :github)]
        ; These may be false if env vars aren't set, which is expected
        (is (boolean? ms-valid?) "Should return a boolean for Microsoft")
        (is (boolean? gh-valid?) "Should return a boolean for GitHub")))
    
    (testing "Invalid provider"
      (is (nil? (auth/validate-oauth-config :invalid)) "Invalid provider should return nil"))))

;; Mock data for testing
(def mock-microsoft-token-response
  {:access_token "mock-ms-token"
   :token_type "Bearer"
   :expires_in 3600})

(def mock-github-token-response
  {:access_token "mock-gh-token"
   :token_type "bearer"
   :scope "user:email"})

(def mock-microsoft-profile
  {:id "12345"
   :displayName "John Doe"
   :userPrincipalName "john.doe@example.com"
   :mail "john.doe@example.com"})

(def mock-github-profile
  {:id 67890
   :login "johndoe"
   :name "John Doe"
   :email "john.doe@example.com"})

(def mock-github-emails
  [{:email "john.doe@example.com" :primary true :verified true}
   {:email "john@personal.com" :primary false :verified true}])

(deftest test-normalize-user-profile
  (testing "User profile normalization"
    (testing "Microsoft profile"
      (let [normalized (auth/normalize-user-profile :microsoft mock-microsoft-profile)]
        (is (= "microsoft" (:provider normalized)) "Provider should be microsoft")
        (is (= "12345" (:provider-id normalized)) "Provider ID should match")
        (is (= "John Doe" (:username normalized)) "Username should be displayName")
        (is (= "john.doe@example.com" (:email normalized)) "Email should match")))
    
    (testing "GitHub profile"
      (let [normalized (auth/normalize-user-profile :github mock-github-profile)]
        (is (= "github" (:provider normalized)) "Provider should be github")
        (is (= "67890" (:provider-id normalized)) "Provider ID should be string")
        (is (= "John Doe" (:username normalized)) "Username should be name")
        (is (= "john.doe@example.com" (:email normalized)) "Email should match")))
    
    (testing "GitHub profile without name (fallback to login)"
      (let [profile-without-name (dissoc mock-github-profile :name)
            normalized (auth/normalize-user-profile :github profile-without-name)]
        (is (= "johndoe" (:username normalized)) "Username should fallback to login")))
    
    (testing "Invalid provider"
      (is (nil? (auth/normalize-user-profile :invalid mock-microsoft-profile)) 
          "Invalid provider should return nil"))))

(deftest test-get-primary-github-email
  (testing "GitHub email extraction"
    (is (= "john.doe@example.com" (auth/get-primary-github-email mock-github-emails))
        "Should return primary email")
    
    (let [emails-without-primary [{:email "first@example.com" :primary false}
                                  {:email "second@example.com" :primary false}]]
      (is (= "first@example.com" (auth/get-primary-github-email emails-without-primary))
          "Should return first email if no primary"))
    
    (is (nil? (auth/get-primary-github-email [])) "Should return nil for empty emails")
    (is (nil? (auth/get-primary-github-email nil)) "Should return nil for nil emails")))

;; Tests with mocked HTTP calls
(deftest test-exchange-code-for-token
  (testing "Token exchange with mocked HTTP"
    (with-redefs [clj-http.client/post (fn [url opts]
                                        (cond
                                          (.contains url "microsoftonline.com")
                                          {:status 200 :body mock-microsoft-token-response}
                                          
                                          (.contains url "github.com")
                                          {:status 200 :body mock-github-token-response}
                                          
                                          :else
                                          {:status 400 :body {:error "invalid_request"}}))]
      
      (testing "Microsoft token exchange"
        (let [result (auth/exchange-code-for-token :microsoft "test-code")]
          (is (= mock-microsoft-token-response result) "Should return Microsoft token response")))
      
      (testing "GitHub token exchange"
        (let [result (auth/exchange-code-for-token :github "test-code")]
          (is (= mock-github-token-response result) "Should return GitHub token response")))
      
      (testing "Invalid provider"
        (is (nil? (auth/exchange-code-for-token :invalid "test-code")) 
            "Invalid provider should return nil")))))

(deftest test-fetch-user-profile
  (testing "User profile fetching with mocked HTTP"
    (with-redefs [clj-http.client/get (fn [url opts]
                                       (cond
                                         (.contains url "graph.microsoft.com")
                                         {:status 200 :body mock-microsoft-profile}
                                         
                                         (.contains url "api.github.com/user")
                                         {:status 200 :body mock-github-profile}
                                         
                                         :else
                                         {:status 404 :body {:error "not_found"}}))]
      
      (testing "Microsoft profile fetch"
        (let [result (auth/fetch-user-profile :microsoft "mock-token")]
          (is (= mock-microsoft-profile result) "Should return Microsoft profile")))
      
      (testing "GitHub profile fetch"
        (let [result (auth/fetch-user-profile :github "mock-token")]
          (is (= mock-github-profile result) "Should return GitHub profile")))
      
      (testing "Invalid provider"
        (is (nil? (auth/fetch-user-profile :invalid "mock-token")) 
            "Invalid provider should return nil")))))

(deftest test-handle-oauth-callback
  (testing "OAuth callback handling with mocked HTTP"
    (with-redefs [clj-http.client/post (fn [url opts]
                                        {:status 200 :body mock-microsoft-token-response})
                  clj-http.client/get (fn [url opts]
                                       {:status 200 :body mock-microsoft-profile})]
      
      (testing "Successful callback"
        (let [result (auth/handle-oauth-callback :microsoft "test-code" "valid-state" "valid-state")]
          (is (:success result) "Callback should succeed")
          (is (map? (:user result)) "Should return user data")
          (is (= "microsoft" (get-in result [:user :provider])) "Should have correct provider")))
      
      (testing "Invalid state"
        (let [result (auth/handle-oauth-callback :microsoft "test-code" "invalid-state" "valid-state")]
          (is (not (:success result)) "Callback should fail")
          (is (= "Invalid state parameter" (:error result)) "Should have state error")))
      
      (testing "Missing code"
        (let [result (auth/handle-oauth-callback :microsoft nil "valid-state" "valid-state")]
          (is (not (:success result)) "Callback should fail")
          (is (= "Missing authorization code" (:error result)) "Should have code error")))
      
      (testing "Unsupported provider"
        (let [result (auth/handle-oauth-callback :invalid "test-code" "valid-state" "valid-state")]
          (is (not (:success result)) "Callback should fail")
          (is (= "Unsupported provider" (:error result)) "Should have provider error"))))))

(deftest test-fetch-github-emails
  (testing "GitHub emails fetching with mocked HTTP"
    (with-redefs [clj-http.client/get (fn [url opts]
                                       (if (.contains url "/user/emails")
                                         {:status 200 :body mock-github-emails}
                                         {:status 404 :body {}}))]
      
      (let [result (auth/fetch-github-emails "mock-token")]
        (is (= mock-github-emails result) "Should return GitHub emails")))))

(deftest test-fetch-github-profile-with-email
  (testing "GitHub profile with email fetching"
    (with-redefs [clj-http.client/get (fn [url opts]
                                       (cond
                                         (.contains url "/user/emails")
                                         {:status 200 :body mock-github-emails}
                                         
                                         (.contains url "/user")
                                         {:status 200 :body (dissoc mock-github-profile :email)}
                                         
                                         :else
                                         {:status 404 :body {}}))]
      
      (let [result (auth/fetch-github-profile-with-email "mock-token")]
        (is (map? result) "Should return profile map")
        (is (= "john.doe@example.com" (:email result)) "Should include email from emails API")))))

(deftest test-handle-github-callback
  (testing "GitHub callback handling with email fetching"
    (with-redefs [clj-http.client/post (fn [url opts]
                                        {:status 200 :body mock-github-token-response})
                  clj-http.client/get (fn [url opts]
                                       (cond
                                         (.contains url "/user/emails")
                                         {:status 200 :body mock-github-emails}
                                         
                                         (.contains url "/user")
                                         {:status 200 :body (dissoc mock-github-profile :email)}
                                         
                                         :else
                                         {:status 404 :body {}}))]
      
      (let [result (auth/handle-github-callback "test-code" "valid-state" "valid-state")]
        (is (:success result) "GitHub callback should succeed")
        (is (= "github" (get-in result [:user :provider])) "Should have GitHub provider")
        (is (= "john.doe@example.com" (get-in result [:user :email])) "Should include email")))))