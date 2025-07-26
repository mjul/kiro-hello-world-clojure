(ns sso-web-app.auth
  "Authentication and OAuth2 integration."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [ring.util.codec :as codec]
            [sso-web-app.errors :as errors]
            [clojure.tools.logging :as log]
            [environ.core :refer [env]])
  (:import [java.security SecureRandom]
           [java.util Base64]))

;; OAuth2 Provider Configurations
(def oauth-providers
  {:microsoft {:client-id (env :microsoft-client-id)
               :client-secret (env :microsoft-client-secret)
               :auth-url "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
               :token-url "https://login.microsoftonline.com/common/oauth2/v2.0/token"
               :user-info-url "https://graph.microsoft.com/v1.0/me"
               :scopes "openid profile email"
               :redirect-uri (str (env :base-url "http://localhost:3000") "/auth/microsoft/callback")}
   :github {:client-id (env :github-client-id)
            :client-secret (env :github-client-secret)
            :auth-url "https://github.com/login/oauth/authorize"
            :token-url "https://github.com/login/oauth/access_token"
            :user-info-url "https://api.github.com/user"
            :scopes "user:email"
            :redirect-uri (str (env :base-url "http://localhost:3000") "/auth/github/callback")}})

;; OAuth2 State Management
(def ^:private secure-random (SecureRandom.))

(defn generate-state
  "Generate a cryptographically secure random state parameter for OAuth2 flow."
  []
  (let [bytes (byte-array 32)]
    (.nextBytes secure-random bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn validate-state
  "Validate OAuth2 state parameter against session state."
  [session-state received-state]
  (and session-state
       received-state
       (= session-state received-state)))

;; OAuth2 Flow Initiation
(defn build-auth-url
  "Build OAuth2 authorization URL for the specified provider."
  [provider state]
  (when-let [config (get oauth-providers provider)]
    (let [params {"client_id" (:client-id config)
                  "response_type" "code"
                  "redirect_uri" (:redirect-uri config)
                  "scope" (:scopes config)
                  "state" state}
          query-string (codec/form-encode params)]
      (str (:auth-url config) "?" query-string))))

(defn initiate-oauth
  "Initiate OAuth2 flow for the specified provider.
   Returns a map with :auth-url and :state for the OAuth flow."
  [provider]
  (when (contains? oauth-providers provider)
    (let [state (generate-state)
          auth-url (build-auth-url provider state)]
      (if auth-url
        {:auth-url auth-url :state state}
        (do
          (log/error "Failed to build auth URL for provider:" provider)
          nil)))))

;; OAuth2 Configuration Validation
(defn validate-oauth-config
  "Validate that OAuth2 configuration is complete for a provider."
  [provider]
  (when-let [config (get oauth-providers provider)]
    (boolean (and (:client-id config)
                  (:client-secret config)
                  (:auth-url config)
                  (:token-url config)
                  (:user-info-url config)
                  (:redirect-uri config)))))

(defn get-oauth-config
  "Get OAuth2 configuration for a provider."
  [provider]
  (get oauth-providers provider))

;; Utility functions for OAuth2 flow
(defn supported-provider?
  "Check if the provider is supported."
  [provider]
  (contains? oauth-providers provider))

(defn get-supported-providers
  "Get list of supported OAuth2 providers."
  []
  (keys oauth-providers))
;; OAuth2 Token Exchange
(defn exchange-code-for-token
  "Exchange authorization code for access token."
  [provider code]
  (when-let [config (get oauth-providers provider)]
    (errors/with-retry
      (fn []
        (let [response (http/post (:token-url config)
                                 {:form-params {"client_id" (:client-id config)
                                               "client_secret" (:client-secret config)
                                               "code" code
                                               "grant_type" "authorization_code"
                                               "redirect_uri" (:redirect-uri config)}
                                  :accept :json
                                  :as :json
                                  :socket-timeout 10000
                                  :connection-timeout 5000
                                  :throw-exceptions false})]
          (if (= 200 (:status response))
            (:body response)
            (throw (RuntimeException. 
                   (str "OAuth token exchange failed for " provider 
                        " with status " (:status response)
                        " and body " (:body response)))))))
      3 1000)))

;; User Profile Retrieval
(defn fetch-user-profile
  "Fetch user profile from OAuth provider using access token."
  [provider access-token]
  (when-let [config (get oauth-providers provider)]
    (errors/with-retry
      (fn []
        (let [headers (case provider
                        :microsoft {"Authorization" (str "Bearer " access-token)}
                        :github {"Authorization" (str "token " access-token)
                                "User-Agent" "sso-web-app"})
              response (http/get (:user-info-url config)
                                {:headers headers
                                 :accept :json
                                 :as :json
                                 :socket-timeout 10000
                                 :connection-timeout 5000
                                 :throw-exceptions false})]
          (if (= 200 (:status response))
            (:body response)
            (throw (RuntimeException. 
                   (str "User profile fetch failed for " provider 
                        " with status " (:status response)
                        " and body " (:body response)))))))
      3 1000)))

;; User Profile Normalization
(defn normalize-user-profile
  "Normalize user profile data from different OAuth providers into a common format."
  [provider raw-profile]
  (case provider
    :microsoft {:provider "microsoft"
                :provider-id (:id raw-profile)
                :username (or (:displayName raw-profile) (:userPrincipalName raw-profile))
                :email (:mail raw-profile (:userPrincipalName raw-profile))}
    :github {:provider "github"
             :provider-id (str (:id raw-profile))
             :username (or (:name raw-profile) (:login raw-profile))
             :email (:email raw-profile)}
    nil))

;; OAuth2 Callback Handling
(defn handle-oauth-callback
  "Handle OAuth2 callback and return normalized user profile.
   Returns a map with :success, :user (if successful), and :error (if failed)."
  [provider code state session-state]
  (try
    (cond
      (not (supported-provider? provider))
      (throw (IllegalArgumentException. "Unsupported OAuth provider"))
      
      (not (validate-state session-state state))
      (throw (SecurityException. "Invalid OAuth state parameter"))
      
      (not code)
      (throw (IllegalArgumentException. "Missing OAuth authorization code"))
      
      :else
      (let [token-response (exchange-code-for-token provider code)]
        (when-not token-response
          (throw (RuntimeException. "Failed to exchange authorization code for token")))
        
        (let [access-token (:access_token token-response)]
          (when-not access-token
            (throw (RuntimeException. "No access token in OAuth response")))
          
          (let [raw-profile (fetch-user-profile provider access-token)]
            (when-not raw-profile
              (throw (RuntimeException. "Failed to fetch user profile from OAuth provider")))
            
            (let [normalized-profile (normalize-user-profile provider raw-profile)]
              (when-not normalized-profile
                (throw (RuntimeException. "Failed to normalize user profile data")))
              
              {:success true :user normalized-profile})))))
    
    (catch Exception e
      (log/error e "OAuth callback handling failed for provider:" provider)
      {:success false :error (.getMessage e)})))

;; GitHub Email Fetching (GitHub API may not return email in user profile)
(defn fetch-github-emails
  "Fetch user emails from GitHub API (needed because primary email might not be in user profile)."
  [access-token]
  (try
    (let [response (http/get "https://api.github.com/user/emails"
                            {:headers {"Authorization" (str "token " access-token)
                                      "User-Agent" "sso-web-app"}
                             :accept :json
                             :as :json
                             :throw-exceptions false})]
      (if (= 200 (:status response))
        (:body response)
        nil))
    (catch Exception e
      (log/error e "Exception during GitHub email fetch")
      nil)))

(defn get-primary-github-email
  "Get primary email from GitHub emails response."
  [emails]
  (when (seq emails)
    (or (:email (first (filter :primary emails)))
        (:email (first emails)))))

;; Enhanced GitHub profile handling
(defn fetch-github-profile-with-email
  "Fetch GitHub user profile and ensure email is included."
  [access-token]
  (when-let [profile (fetch-user-profile :github access-token)]
    (if (:email profile)
      profile
      (if-let [emails (fetch-github-emails access-token)]
        (assoc profile :email (get-primary-github-email emails))
        profile))))

;; Enhanced callback handler for GitHub
(defn handle-github-callback
  "Enhanced GitHub callback handler that ensures email is fetched."
  [code state session-state]
  (try
    (cond
      (not (validate-state session-state state))
      (throw (SecurityException. "Invalid OAuth state parameter"))
      
      (not code)
      (throw (IllegalArgumentException. "Missing OAuth authorization code"))
      
      :else
      (let [token-response (exchange-code-for-token :github code)]
        (when-not token-response
          (throw (RuntimeException. "Failed to exchange authorization code for token")))
        
        (let [access-token (:access_token token-response)]
          (when-not access-token
            (throw (RuntimeException. "No access token in OAuth response")))
          
          (let [raw-profile (fetch-github-profile-with-email access-token)]
            (when-not raw-profile
              (throw (RuntimeException. "Failed to fetch GitHub user profile")))
            
            (let [normalized-profile (normalize-user-profile :github raw-profile)]
              (when-not normalized-profile
                (throw (RuntimeException. "Failed to normalize GitHub user profile")))
              
              {:success true :user normalized-profile})))))
    
    (catch Exception e
      (log/error e "GitHub OAuth callback handling failed")
      {:success false :error (.getMessage e)})))