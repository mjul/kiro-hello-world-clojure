(defproject sso-web-app "0.1.0-SNAPSHOT"
  :description "SSO Web Application with Microsoft 365 and GitHub authentication"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 ;; Web framework dependencies
                 [ring/ring-core "1.10.0"]
                 [ring/ring-jetty-adapter "1.10.0"]
                 [ring/ring-defaults "0.4.0"]
                 [compojure "1.7.0"]
                 
                 ;; Database dependencies
                 [org.xerial/sqlite-jdbc "3.42.0.0"]
                 [org.clojure/java.jdbc "0.7.12"]
                 
                 ;; OAuth2 and authentication
                 [ring-oauth2 "0.2.0"]
                 [clj-http "3.12.3"]
                 [cheshire "5.11.0"]
                 
                 ;; Templating
                 [hiccup "1.0.5"]
                 
                 ;; Utilities
                 [org.clojure/tools.logging "1.2.4"]
                 [ch.qos.logback/logback-classic "1.4.7"]
                 [environ "1.2.0"]
                 
                 ;; Testing utilities
                 [ring/ring-mock "0.4.0"]]
  
  :plugins [[lein-environ "1.2.0"]]
  
  :profiles {:dev {:dependencies [[ring/ring-mock "0.4.0"]
                                  [org.clojure/test.check "1.1.1"]]
                   :env {:dev true}}
             :test {:dependencies [[ring/ring-mock "0.4.0"]
                                   [org.clojure/test.check "1.1.1"]]
                    :env {:test true}}}
  
  :main ^:skip-aot sso-web-app.core
  :target-path "target/%s"
  :uberjar-name "sso-web-app.jar"
  :min-lein-version "2.0.0")