(ns tabblioserver.clerk
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str])
  (:import [com.clerk.backend_api Clerk]
           [com.clerk.backend_api.models.operations VerifyTokenRequest]
           [com.clerk.backend_api.models.shared Security]
           [com.clerk.backend_api.models.errors ClerkErrors]))

(def ^:private clerk-secret-key (System/getenv "CLERK_SECRET_KEY"))

(defonce ^:private clerk-client
  (delay
    (when clerk-secret-key
      (log/info "Initializing Clerk client")
      (-> (Clerk/builder)
          (.security (Security/builder)
                    (.bearerAuth clerk-secret-key)
                    (.build))
          (.build)))
    (when-not clerk-secret-key
      (log/warn "CLERK_SECRET_KEY environment variable not set"))))

(defn extract-bearer-token [authorization-header]
  (when authorization-header
    (let [parts (str/split authorization-header #" ")]
      (when (and (= 2 (count parts))
                 (= "Bearer" (first parts)))
        (second parts)))))

(defn verify-session-token [token]
  (try
    (when (and @clerk-client token)
      (let [request (-> (VerifyTokenRequest/builder)
                       (.token token)
                       (.build))
            response (.verifyToken @clerk-client request)]
        (when (= 200 (.statusCode response))
          (let [verification (.verifyTokenResponse response)]
            {:valid? true
             :user-id (.userId (.claims verification))
             :session-id (.sessionId (.claims verification))
             :claims (.claims verification)}))))
    (catch Exception e
      (log/error e "Failed to verify Clerk session token")
      {:valid? false :error (.getMessage e)})))

(defn get-user-from-token [token]
  (let [verification (verify-session-token token)]
    (when (:valid? verification)
      {:user-id (:user-id verification)
       :session-id (:session-id verification)})))

(defn wrap-clerk-auth [handler]
  (fn [request]
    (let [auth-header (get-in request [:headers "authorization"])
          token (extract-bearer-token auth-header)]
      (if token
        (let [user-info (get-user-from-token token)]
          (if user-info
            (handler (assoc request :user user-info))
            (handler (assoc request :user nil))))
        (handler (assoc request :user nil))))))
