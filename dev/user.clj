(ns user
  "Development utilities and REPL helpers."
  (:require [sso-web-app.core :as core]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.repl :refer :all]))

(def system nil)

(defn start
  "Start the development server."
  []
  (alter-var-root #'system (constantly (core/start-server))))

(defn stop
  "Stop the development server."
  []
  (when system
    (core/stop-server system)
    (alter-var-root #'system (constantly nil))))

(defn restart
  "Restart the development server."
  []
  (stop)
  (refresh :after 'user/start))

(println "Development utilities loaded.")
(println "Use (start) to start the server, (stop) to stop it, and (restart) to restart.")