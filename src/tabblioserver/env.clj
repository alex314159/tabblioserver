(ns tabblioserver.env
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defonce config
  (try
    (with-open [r (io/reader ".env.edn")]
      (edn/read (java.io.PushbackReader. r)))
    (catch Exception e
      (println "Warning: Could not load .env.edn:" (.getMessage e))
      {})))

(defn env [key]
  (get config key))