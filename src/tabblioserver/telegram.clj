(ns tabblioserver.telegram
  (:require [telegrambot-lib.core :as tbot]
            [tabblioserver.env :refer [env]]
            [clojure.tools.logging :as log]))

(def ^:private me (env :telegram-recipent))
(def ^:private bot (tbot/create (env :telegram-token)))

(defn- notify [text]
  (future
    (try
      (tbot/send-message bot {:chat_id me :text text})
      (catch Exception e
        (log/warn "Failed to send Telegram notification:" (.getMessage e))))))

(defn notify-startup []
  (let [host (or (env :host) "unknown host")]
    (notify (str "tabblio server started on " host))))

(defn notify-user-created [user-id]
  (notify (str "New user: " user-id)))

(defn notify-user-login [user-id]
  (notify (str "User logged in: " user-id)))

(defn notify-user-logout [user-id]
  (notify (str "User logged out: " user-id)))

(defn notify-template-saved [user-id]
  (notify (str "Template saved by " (or user-id "anonymous"))))

(defn notify-template-loaded [template-id user-id]
  (notify (str "Template loaded: " template-id " by " (or user-id "anonymous"))))

(defn notify-server-restarted [port]
  (notify (str "WARNING: tabblio server was unresponsive on port " port " and has been restarted")))