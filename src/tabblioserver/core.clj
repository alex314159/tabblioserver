(ns tabblioserver.core
  (:require [org.httpkit.server :as server]
            [tabblioserver.api :as api]
            [clojure.tools.logging :as log]
            [tabblioserver.env :refer [env]]))

(defn start-server [port]
  (log/info "Starting server on port" port)
  (server/run-server api/app {:port port}))

(defn -main [& args]
  (let [port (or (env :port) 8082)]
    (start-server port)
    (log/info "Server started on port" port)))
