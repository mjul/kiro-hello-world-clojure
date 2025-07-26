(ns sso-web-app.templates
  "HTML template generation using Hiccup."
  (:require [hiccup.core :refer [html]]
            [hiccup.page :refer [html5 include-css include-js]]
            [hiccup.form :as form]
            [clojure.tools.logging :as log]))

;; Common layout template with HTML structure
(defn layout
  "Common page layout wrapper with HTML5 structure, CSS, and navigation."
  [title & content]
  (html5
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title (str "SSO Web App" (when title (str " - " title)))]
     [:style "
       body { 
         font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
         max-width: 800px; 
         margin: 0 auto; 
         padding: 20px;
         background-color: #f5f5f5;
       }
       .container { 
         background: white; 
         padding: 40px; 
         border-radius: 8px; 
         box-shadow: 0 2px 10px rgba(0,0,0,0.1);
         text-align: center;
       }
       .oauth-button { 
         display: inline-block;
         padding: 12px 24px; 
         margin: 10px; 
         background: #0078d4; 
         color: white; 
         text-decoration: none; 
         border-radius: 4px;
         font-weight: 500;
         transition: background-color 0.2s;
       }
       .oauth-button:hover { 
         background: #106ebe; 
       }
       .oauth-button.github { 
         background: #333; 
       }
       .oauth-button.github:hover { 
         background: #24292e; 
       }
       .logout-button {
         background: #d73a49;
         border: none;
         color: white;
         padding: 10px 20px;
         border-radius: 4px;
         cursor: pointer;
         font-size: 14px;
         margin-top: 20px;
       }
       .logout-button:hover {
         background: #cb2431;
       }
       .greeting {
         font-size: 24px;
         margin-bottom: 20px;
         color: #333;
       }
       h1 { color: #333; margin-bottom: 30px; }
       .error { color: #d73a49; margin: 10px 0; }
     "]]
    [:body
     [:div.container
      content]]))

;; Login page template with OAuth provider buttons
(defn login-page
  "Generate login page HTML with Microsoft 365 and GitHub OAuth provider options."
  [& {:keys [error]}]
  (layout "Login"
    [:h1 "Welcome to SSO Web App"]
    [:p "Please choose your login provider:"]
    
    (when error
      [:div.error 
       (case error
         "access_denied" "Access was denied. Please try again."
         "invalid_request" "Invalid request. Please try again."
         "An error occurred during authentication. Please try again.")])
    
    [:div
     [:a.oauth-button {:href "/auth/microsoft"} 
      "Login with Microsoft 365"]
     [:a.oauth-button.github {:href "/auth/github"} 
      "Login with GitHub"]]))

;; Dashboard page template with user greeting and logout
(defn dashboard-page
  "Generate user dashboard HTML with greeting message and logout functionality."
  [user & {:keys [csrf-token]}]
  (layout "Dashboard"
    [:div.greeting 
     (str "Hello " (:username user) "!")]
    
    [:p "Welcome to your personal dashboard."]
    
    [:div
     [:p (str "Logged in via: " (clojure.string/capitalize (:provider user)))]
     (when (:email user)
       [:p (str "Email: " (:email user))])]
    
    (form/form-to [:post "/logout"]
      (when csrf-token
        (form/hidden-field "csrf-token" csrf-token))
      [:button.logout-button {:type "submit"} "Logout"])))

;; Template error handling and optimization

(def ^:private template-cache (atom {}))

;; Error page template (defined first to avoid forward reference issues)
(defn error-page
  "Generate a basic error page when template rendering fails."
  [title message]
  (try
    (html5
      [:head
       [:meta {:charset "utf-8"}]
       [:title "SSO Web App - Error"]]
      [:body
       [:div {:style "max-width: 600px; margin: 50px auto; padding: 20px; text-align: center; font-family: Arial, sans-serif;"}
        [:h1 {:style "color: #d73a49;"} title]
        [:p message]
        [:a {:href "/" :style "color: #0078d4; text-decoration: none;"} "Return to Home"]]])
    (catch Exception e
      (log/error e "Even error page template failed")
      "<html><body><h1>Critical Error</h1><p>Unable to render any content</p></body></html>")))

(defn- cache-key
  "Generate a cache key for template caching."
  [template-name & args]
  (str template-name "-" (hash args)))

(defn- get-cached-template
  "Retrieve a cached template if available."
  [cache-key]
  (get @template-cache cache-key))

(defn- cache-template!
  "Cache a rendered template."
  [cache-key rendered-html]
  (swap! template-cache assoc cache-key rendered-html)
  rendered-html)

(defn clear-template-cache!
  "Clear the template cache. Useful for development or memory management."
  []
  (reset! template-cache {}))

(defn- safe-render
  "Safely render a template with error handling and fallback mechanisms."
  [template-fn fallback-fn & args]
  (try
    (apply template-fn args)
    (catch Exception e
      (log/error e "Template rendering failed, using fallback")
      (if fallback-fn
        (try
          (fallback-fn e)
          (catch Exception fallback-error
            (log/error fallback-error "Fallback template also failed")
            (error-page "Template Error" "Unable to render page content")))
        (error-page "Template Error" "Unable to render page content")))))

(defn- cached-render
  "Render a template with caching support."
  [cache-key template-fn fallback-fn & args]
  (if-let [cached (get-cached-template cache-key)]
    cached
    (let [rendered (apply safe-render template-fn fallback-fn args)]
      (cache-template! cache-key rendered))))

;; Fallback templates for error scenarios
(defn- login-page-fallback
  "Fallback login page when main template fails."
  [error]
  (layout "Login - Error"
    [:h1 "Login"]
    [:p "There was an error loading the login page."]
    [:div
     [:a {:href "/auth/microsoft" :style "display: block; margin: 10px 0; padding: 10px; background: #0078d4; color: white; text-decoration: none;"} 
      "Login with Microsoft 365"]
     [:a {:href "/auth/github" :style "display: block; margin: 10px 0; padding: 10px; background: #333; color: white; text-decoration: none;"} 
      "Login with GitHub"]]))

(defn- dashboard-page-fallback
  "Fallback dashboard page when main template fails."
  [error]
  (layout "Dashboard - Error"
    [:h1 "Dashboard"]
    [:p "There was an error loading your dashboard."]
    [:form {:method "post" :action "/logout"}
     [:input {:type "hidden" :name "csrf-token" :value ""}]
     [:button {:type "submit" :style "padding: 10px 20px; background: #d73a49; color: white; border: none; cursor: pointer;"} 
      "Logout"]]))

;; Optimized template functions with caching and error handling

(defn login-page-cached
  "Generate login page HTML with caching and error handling."
  [& {:keys [error] :as opts}]
  (let [cache-key (cache-key "login" opts)]
    (cached-render cache-key login-page login-page-fallback opts)))

(defn dashboard-page-cached
  "Generate user dashboard HTML with caching and error handling."
  [user & {:keys [csrf-token] :as opts}]
  ;; Don't cache pages with CSRF tokens since they're unique per session
  (if csrf-token
    (safe-render dashboard-page dashboard-page-fallback user :csrf-token csrf-token)
    (let [cache-key (cache-key "dashboard" (:id user) (:username user) (:provider user))]
      (cached-render cache-key dashboard-page dashboard-page-fallback user))))

;; Template performance monitoring
(defn template-stats
  "Get template cache statistics for monitoring."
  []
  {:cache-size (count @template-cache)
   :cached-templates (keys @template-cache)})