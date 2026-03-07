(ns tabblioserver.core
  (:require [org.httpkit.server :as server]
            [tabblioserver.api :as api]
            [clojure.tools.logging :as log]
            [tabblioserver.env :refer [env]]
            [tabblioserver.telegram :as telegram])
  (:import [java.net Socket]))

(defonce server-state (atom {:stop-fn nil :port nil}))
(defonce watchdog-state (atom {:running? false}))

(defn- server-alive? [port]
  (try
    (with-open [_ (Socket. "localhost" (int port))] true)
    (catch Exception _ false)))

(defn start-server!
  ([] (start-server! (:port @server-state)))
  ([port]
   (when-let [stop-fn (:stop-fn @server-state)]
     (try (stop-fn) (catch Exception _)))
   (log/info "Starting server on port" port)
   (let [stop-fn (server/run-server api/app {:port port})]
     (reset! server-state {:stop-fn stop-fn :port port})
     stop-fn)))

(defn start-watchdog! []
  (swap! watchdog-state assoc :running? true)
  (future
    (log/info "Watchdog started")
    (loop []
      (when (:running? @watchdog-state)
        (Thread/sleep 10000)
        (let [port (:port @server-state)]
          (when (and port (not (server-alive? port)))
            (log/error "Server unresponsive on port" port "- restarting...")
            (start-server! port)
            (telegram/notify-server-restarted port)))
        (recur)))
    (log/info "Watchdog stopped")))

(defn stop-watchdog! []
  (swap! watchdog-state assoc :running? false))

(defn -main [& args]
  (let [port (or (env :port) 8082)]
    (start-server! port)
    (start-watchdog!)
    (log/info "Server started on port" port)
    (telegram/notify-startup)))