(ns tabblioserver.env
  (:require [clojure.edn :as edn]))

(defonce config
  (try
    (edn/read-string (slurp ".env.edn"))
    (catch Exception e
      (println "Warning: Could not load .env.edn:" (.getMessage e))
      {})))

(defn env [key]
  (get config key))