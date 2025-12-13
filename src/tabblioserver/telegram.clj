(ns tabblioserver.telegram
  (:require [telegrambot-lib.core :as tbot]
            [tabblioserver.env :refer [env]]))


(def me (env :telegram-recipent))
(def bot (tbot/create (env :telegram-token)))
(defn send-to-user [id txt] (tbot/send-message bot {:chat_id id :text txt}))
