(ns test-simple
  (:require [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [sso-web-app.routes :as routes]
            [sso-web-app.auth :as auth]
            [ring.util.codec :as codec]
            [environ.core :refer [env]]))

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
   :github-client-secret "test-github-client-secret"})

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

(deftest test-oauth-state-extraction
  (testing "OAuth state extraction from redirect URL"
    (with-redefs [environ.core/env (merge environ.core/env test-config)]
      (let [oauth-init-response (routes/app (mock/request :get "/auth/microsoft"))]
        (is (= 302 (:status oauth-init-response)))
        (let [location (get-in oauth-init-response [:headers "Location"])
              ring-session-cookie (extract-ring-session-cookie oauth-init-response)]
          (println "Location:" location)
          (println "Ring session cookie:" ring-session-cookie)
          (is (some? location))
          (is (some? ring-session-cookie))
          
          ;; Extract state from the redirect URL
          (let [state-param (codec/url-decode (second (re-find #"state=([^&]+)" location)))]
            (println "State param:" state-param)
            (is (some? state-param))))))))