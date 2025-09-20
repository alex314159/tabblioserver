(ns tabblioserver.clerk
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [cheshire.core :as json]
            [tabblioserver.sql :as sql]
            [environ.core :refer [env]])
  (:import [com.clerk.backend_api Clerk]
           [com.clerk.backend_api.models.operations VerifyTokenRequest]
           [com.clerk.backend_api.models.shared Security]
           [com.clerk.backend_api.models.errors ClerkErrors]
           [javax.crypto.Mac]
           [javax.crypto.spec.SecretKeySpec]
           [java.security.MessageDigest]
           [java.util.Base64]))

(def ^:private clerk-secret-key (env :clerk-secret-key))
(def ^:private clerk-webhook-secret (env :clerk-webhook-secret))

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
            (do
              ;; Update user login when they make authenticated requests
              (sql/update-user-login (:user-id user-info))
              (handler (assoc request :user user-info)))
            (handler (assoc request :user nil))))
        (handler (assoc request :user nil))))))

(defn- hmac-sha256 [secret message]
  (let [mac (Mac/getInstance "HmacSHA256")
        secret-key (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256")]
    (.init mac secret-key)
    (let [hash (.doFinal mac (.getBytes message "UTF-8"))]
      (apply str (map #(format "%02x" %) hash)))))

(defn verify-webhook-signature [payload headers]
  (when clerk-webhook-secret
    (let [svix-id (get headers "svix-id")
          svix-timestamp (get headers "svix-timestamp")
          svix-signature (get headers "svix-signature")]
      (when (and svix-id svix-timestamp svix-signature)
        (let [webhook-secret-bytes (.decode (Base64/getDecoder)
                                           (str/replace clerk-webhook-secret #"^whsec_" ""))
              webhook-secret (String. ^bytes webhook-secret-bytes "UTF-8")
              signed-payload (str svix-id "." svix-timestamp "." payload)
              expected-signature (hmac-sha256 webhook-secret signed-payload)
              signatures (-> svix-signature
                           (str/split #",")
                           (->> (map #(str/replace % #"^v1=" ""))))]
          (some #(= expected-signature %) signatures))))))

(defn handle-webhook-event [event-data]
  (let [event-type (:type event-data)
        event-object (:data event-data)]
    (log/info "Processing Clerk webhook event:" event-type)
    (case event-type
      "user.created"
      (let [user-id (:id event-object)]
        (log/info "User created:" user-id)
        (sql/create-user user-id)
        {:status "success" :message "User created"})

      "session.created"
      (let [user-id (:user_id event-object)]
        (log/info "Session created for user:" user-id)
        (sql/update-user-login user-id)
        {:status "success" :message "Login recorded"})

      "user.deleted"
      (let [user-id (:id event-object)]
        (log/info "User deleted:" user-id)
        (sql/delete-user user-id)
        {:status "success" :message "User deleted"})

      (do
        (log/info "Unhandled webhook event type:" event-type)
        {:status "ignored" :message "Event type not handled"}))))
