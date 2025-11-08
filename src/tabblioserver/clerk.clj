(ns tabblioserver.clerk
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [cheshire.core :as json]
            [tabblioserver.sql :as sql]
            [tabblioserver.env :refer [env]])
  (:import [com.clerk.backend_api Clerk]
           [com.clerk.backend_api.helpers.security AuthenticateRequest]
           [com.clerk.backend_api.helpers.security.models AuthenticateRequestOptions]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.security MessageDigest]
           [java.util ArrayList Base64 HashMap]
           [java.net.http HttpRequest HttpRequest$BodyPublishers]
           [java.net URI]))

(def ^:private clerk-secret-key (env :clerk-secret-key))
(def ^:private clerk-webhook-secret (env :clerk-webhook-secret))

(defonce ^:private clerk-client
  (delay
    (if clerk-secret-key
      (do
        (log/info "Initializing Clerk client")
        (-> (Clerk/builder)
            (.bearerAuth clerk-secret-key)
            (.build)))
      (do
        (log/warn "CLERK_SECRET_KEY environment variable not set")
        nil))))

(defn ring-headers-to-java-map [ring-headers]
  "Convert Ring headers map to Java Map<String, List<String>>"
  (let [java-map (HashMap.)]
    (doseq [[header-name header-value] ring-headers]
      (.put java-map header-name (ArrayList. [header-value])))
    java-map))

(defn authenticate-request [ring-req]
  (try
    (when clerk-secret-key
      (log/info "Authenticating request with Clerk...")
      (let [headers-map (ring-headers-to-java-map (:headers ring-req))
            origin (get-in ring-req [:headers "origin"])
            options (-> (AuthenticateRequestOptions/secretKey clerk-secret-key)
                       (.authorizedParty origin)
                       (.build))
            request-state (AuthenticateRequest/authenticateRequest headers-map options)]
        (log/info "Request signed in:" (.isSignedIn request-state))
        (if (.isSignedIn request-state)
          (let [claims-optional (.claims request-state)]
            (if (.isPresent claims-optional)
              (let [claims (.get claims-optional)
                    user-id (.getSubject claims)
                    session-id (.get claims "sid" String)]
                (log/info "Authentication successful, user-id:" user-id "session-id:" session-id)
                {:user-id user-id
                 :session-id session-id})
              (do
                (log/warn "Claims not present in request state")
                nil)))
          (do
            (log/warn "Authentication failed, reason:" (.reason request-state))
            nil))))
    (catch Exception e
      (log/error e "Failed to authenticate request with Clerk")
      (log/error "Exception class:" (class e))
      (log/error "Exception message:" (.getMessage e))
      nil)))

(defn wrap-clerk-auth [handler]
  (fn [request]
    (let [user-info (authenticate-request request)]
      (if user-info
        (do
          ;; Update user login when they make authenticated requests
          (sql/update-user-login (:user-id user-info))
          (handler (assoc request :user user-info)))
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
