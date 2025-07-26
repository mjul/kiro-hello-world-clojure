(ns sso-web-app.routes
  "HTTP route definitions and request routing."
  (:require [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [ring.util.response :as response]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [sso-web-app.auth :as auth]
            [sso-web-app.templates :as templates]
            [sso-web-app.middleware :as middleware]
            [sso-web-app.db :as db]
            [sso-web-app.errors :as errors]
            [clojure.tools.logging :as log]))

;; Route handlers

(defn root-handler
  "Root route with authentication check and appropriate redirects.
   If authenticated, redirect to dashboard. If not, redirect to login."
  [request]
  (if (middleware/authenticated? request)
    (response/redirect "/dashboard")
    (response/redirect "/login")))

(defn login-page-handler
  "Login page route that displays OAuth provider options."
  [request]
  (let [error (get-in request [:params :error])]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (templates/login-page :error error)}))

(defn oauth-initiation-handler
  "OAuth initiation routes for Microsoft 365 and GitHub.
   Initiates OAuth flow and redirects to provider's authorization endpoint."
  [provider]
  (fn [request]
    (try
      (errors/log-auth-event :oauth-initiated request {:provider provider})
      
      (when-not (auth/supported-provider? (keyword provider))
        (throw (IllegalArgumentException. (str "Unsupported OAuth provider: " provider))))
      
      (let [oauth-data (auth/initiate-oauth (keyword provider))]
        (when-not oauth-data
          (throw (RuntimeException. (str "Failed to initiate OAuth for provider: " provider))))
        
        (let [response (response/redirect (:auth-url oauth-data))]
          ;; Store OAuth state in session for validation
          (assoc-in response [:session :oauth-state] (:state oauth-data))))
      
      (catch Exception e
        (errors/log-auth-event :oauth-failure request 
                              {:provider provider :error (.getMessage e)})
        (throw e)))))

;; OAuth callback handlers

(defn oauth-callback-handler
  "OAuth callback routes for both providers with error handling.
   Handles the OAuth callback, validates state, exchanges code for token,
   creates or updates user, and creates session."
  [provider]
  (fn [request]
    (let [params (:params request)
          code (:code params)
          state (:state params)
          error (:error params)
          session-state (get-in request [:session :oauth-state])
          provider-kw (keyword provider)]
      
      (try
        (errors/log-auth-event :oauth-callback request 
                              {:provider provider :has-code (boolean code) 
                               :has-state (boolean state) :error error})
        
        (cond
          ;; Handle OAuth errors from provider
          error
          (do
            (errors/log-auth-event :oauth-failure request 
                                  {:provider provider :oauth-error error})
            {:status 401
             :headers {"Content-Type" "text/html"}
             :body (templates/error-page "OAuth Authentication Failed" 
                                       (str "OAuth provider error: " error))})
          
          ;; Handle successful callback
          (and code state)
          (let [callback-result (if (= provider-kw :github)
                                 (auth/handle-github-callback code state session-state)
                                 (auth/handle-oauth-callback provider-kw code state session-state))]
            (if (:success callback-result)
              (let [user-profile (:user callback-result)]
                ;; Create or update user in database
                (let [user (db/create-or-update-user! user-profile)
                      session (middleware/create-user-session (:id user))]
                  
                  (errors/log-auth-event :oauth-success request 
                                        {:provider provider :user-id (:id user) 
                                         :username (:username user)})
                  (errors/log-auth-event :session-created request 
                                        {:user-id (:id user) :session-id (:session_id session)})
                  
                  ;; Redirect to dashboard with session cookie
                  (-> (response/redirect "/dashboard")
                      (middleware/add-session-cookie (:session_id session))
                      ;; Clear OAuth state from session
                      (assoc :session {}))))
              (do
                (errors/log-auth-event :oauth-failure request 
                                      {:provider provider :error (:error callback-result)})
                {:status 401
                 :headers {"Content-Type" "text/html"}
                 :body (templates/error-page "OAuth Authentication Failed" 
                                           (str "OAuth callback failed: " (:error callback-result)))})))
          
          ;; Missing required parameters
          :else
          (do
            (errors/log-auth-event :oauth-failure request 
                                  {:provider provider :error "OAuth callback missing required parameters"})
            {:status 401
             :headers {"Content-Type" "text/html"}
             :body (templates/error-page "OAuth Authentication Failed" 
                                       "OAuth callback missing required parameters")}))
        
        (catch Exception e
          (errors/log-auth-event :oauth-failure request 
                                {:provider provider :error (.getMessage e)})
          {:status 500
           :headers {"Content-Type" "text/html"}
           :body (templates/error-page "OAuth Authentication Error" 
                                     (str "An error occurred during authentication: " (.getMessage e)))})))))

(defn dashboard-handler
  "Protected dashboard route with user greeting display.
   Shows personalized dashboard with user information and logout option."
  [request]
  (let [user (:user request)
        csrf-token (get-in request [:session :csrf-token])]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (templates/dashboard-page user :csrf-token csrf-token)}))

(defn logout-handler
  "Logout route with session invalidation and redirect.
   Invalidates the user session and redirects to login page."
  [request]
  (try
    (let [username (middleware/get-username request)
          user-id (middleware/get-user-id request)]
      (log/info "User logout requested for user:" username)
      (errors/log-auth-event :logout request {:username username :user-id user-id})
      (middleware/logout-user request))
    (catch Exception e
      (log/error e "Error during logout")
      (throw e))))

;; Complete route definitions
(defroutes app-routes
  ;; Root route - redirect based on authentication status
  (GET "/" [] (middleware/wrap-optional-authentication root-handler))
  
  ;; Login page - show OAuth provider options
  (GET "/login" [] login-page-handler)
  
  ;; OAuth initiation routes
  (GET "/auth/microsoft" [] (oauth-initiation-handler "microsoft"))
  (GET "/auth/github" [] (oauth-initiation-handler "github"))
  
  ;; OAuth callback routes
  (GET "/auth/microsoft/callback" [] (oauth-callback-handler "microsoft"))
  (GET "/auth/github/callback" [] (oauth-callback-handler "github"))
  
  ;; Protected dashboard route
  (GET "/dashboard" [] (middleware/wrap-authentication dashboard-handler))
  
  ;; Logout route
  (POST "/logout" [] (middleware/wrap-authentication logout-handler))
  
  ;; 404 handler
  (route/not-found {:status 404
                    :headers {"Content-Type" "text/html"}
                    :body (templates/error-page "Page Not Found" 
                                               "The requested page could not be found.")}))

;; Application middleware configuration

(def app
  "Main application with middleware stack."
  (-> app-routes
      ;; Global error handling middleware (must be first to catch all errors)
      errors/wrap-error-handling
      ;; Session cleanup middleware
      middleware/wrap-session-cleanup
      ;; Session and cookie handling
      wrap-session
      wrap-cookies
      ;; Default Ring middleware (without anti-forgery and security headers since we handle them ourselves)
      (wrap-defaults (-> site-defaults
                        ;; Disable built-in anti-forgery since we have custom CSRF protection
                        (assoc-in [:security :anti-forgery] false)
                        ;; Disable built-in security headers since we have custom ones
                        (assoc-in [:security :frame-options] false)
                        (assoc-in [:security :content-type-options] false)
                        (assoc-in [:security :xss-protection] false)))
      ;; Security middleware (CSRF, headers, input validation) - applied last to override defaults
      middleware/wrap-security))

;; Utility functions for route testing and development

(defn get-routes
  "Get the application routes for testing."
  []
  app-routes)

(defn get-app
  "Get the complete application with middleware for testing."
  []
  app)