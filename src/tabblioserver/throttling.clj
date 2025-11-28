(ns tabblioserver.throttling
  (:require [ring.util.response :refer [response status]]))

;; Rate limiting state: {identifier -> last-access-timestamp}
(def ^:private rate-limit-state (atom {}))

(defn- get-client-ip
  "Extract client IP from request headers or remote-addr"
  [request]
  (or (get-in request [:headers "x-forwarded-for"])
      (get-in request [:headers "x-nf-client-connection-ip"])
      (:remote-addr request)))

(defn- cleanup-old-rate-limits
  "Remove rate limit entries older than 5 minutes to prevent memory growth"
  []
  (let [now (System/currentTimeMillis)
        five-minutes-ago (- now (* 5 60 1000))]
    (swap! rate-limit-state
           (fn [state]
             (into {} (filter (fn [[_ timestamp]] (> timestamp five-minutes-ago)) state))))))

(defn check-rate-limit
  "Check if request is rate limited. Returns nil if allowed, error response if throttled.
   authenticated-limit-ms: minimum time between requests for authenticated users
   unauthenticated-limit-ms: minimum time between requests for unauthenticated users (by IP)
   context: optional string to include in the rate limit key (e.g., file-id, url) for resource-specific limiting"
  ([request authenticated-limit-ms unauthenticated-limit-ms]
   (check-rate-limit request authenticated-limit-ms unauthenticated-limit-ms nil))
  ([request authenticated-limit-ms unauthenticated-limit-ms context]
   (let [user (:user request)
         user-id (:user-id user)
         client-ip (get-client-ip request)
         base-identifier (if user-id user-id client-ip)
         ;; Include context in identifier if provided (e.g., "user123:file.csv" or "192.168.1.1:http://...")
         identifier (if context
                      (str base-identifier ":" context)
                      base-identifier)
         limit-ms (if user-id authenticated-limit-ms unauthenticated-limit-ms)
         now (System/currentTimeMillis)
         last-access (get @rate-limit-state identifier)]

     ;; Cleanup old entries occasionally (1% chance)
     (when (< (rand) 0.01)
       (cleanup-old-rate-limits))

     (if (and last-access (< (- now last-access) limit-ms))
       ;; Rate limited
       (let [seconds-remaining (int (Math/ceil (/ (- limit-ms (- now last-access)) 1000)))
             message (if user-id
                       (format "Please wait %d seconds before trying again" seconds-remaining)
                       (format "Please wait %d seconds before trying again (rate limit for anonymous users)" seconds-remaining))]
         (-> (response {:error message})
             (status 429)))
       ;; Not rate limited, update timestamp and allow
       (do
         (swap! rate-limit-state assoc identifier now)
         nil)))))