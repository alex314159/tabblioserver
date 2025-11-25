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
    (log/info "=== CLERK AUTHENTICATE START ===")
    (log/info "clerk-auth: Has clerk-secret-key:" (boolean clerk-secret-key))
    (when clerk-secret-key
      (log/info "clerk-auth: Request URI:" (:uri ring-req))
      (log/info "clerk-auth: Request headers keys:" (keys (:headers ring-req)))
      (let [origin (get-in ring-req [:headers "origin"])
            auth-header (get-in ring-req [:headers "authorization"])]
        (log/info "clerk-auth: Origin header:" origin)
        (log/info "clerk-auth: Authorization header present:" (boolean auth-header))
        (log/info "clerk-auth: Converting headers to Java map...")
        (let [headers-map (ring-headers-to-java-map (:headers ring-req))]
          (log/info "clerk-auth: Headers converted successfully")
          (log/info "clerk-auth: Building authentication options with origin:" origin)
          (let [options-builder (AuthenticateRequestOptions/secretKey clerk-secret-key)
                options-with-party (if origin
                                    (.authorizedParty options-builder origin)
                                    options-builder)
                options (.build options-with-party)]
            (log/info "clerk-auth: Options built, calling authenticateRequest...")
            (let [request-state (AuthenticateRequest/authenticateRequest headers-map options)]
              (log/info "clerk-auth: Request state received")
              (log/info "clerk-auth: Request signed in:" (.isSignedIn request-state))
              (if (.isSignedIn request-state)
                (let [claims-optional (.claims request-state)]
                  (log/info "clerk-auth: Claims present:" (.isPresent claims-optional))
                  (if (.isPresent claims-optional)
                    (let [claims (.get claims-optional)
                          user-id (.getSubject claims)
                          session-id (.get claims "sid" String)]
                      (log/info "clerk-auth: Authentication successful, user-id:" user-id "session-id:" session-id)
                      (log/info "=== CLERK AUTHENTICATE END (SUCCESS) ===")
                      {:user-id user-id
                       :session-id session-id})
                    (do
                      (log/warn "clerk-auth: Claims not present in request state")
                      (log/warn "=== CLERK AUTHENTICATE END (NO CLAIMS) ===")
                      nil)))
                (do
                  (log/warn "clerk-auth: Authentication failed, reason:" (.reason request-state))
                  (log/warn "=== CLERK AUTHENTICATE END (NOT SIGNED IN) ===")
                  nil)))))))
    (catch Exception e
      (log/error e "clerk-auth: Failed to authenticate request with Clerk")
      (log/error "clerk-auth: Exception class:" (class e))
      (log/error "clerk-auth: Exception message:" (.getMessage e))
      (log/error "=== CLERK AUTHENTICATE END (EXCEPTION) ===")
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

(defn- hmac-sha256 [secret-bytes message]
  "Compute HMAC-SHA256 and return as base64 string
   secret-bytes should be raw bytes, not a string"
  (let [mac (Mac/getInstance "HmacSHA256")
        secret-key (SecretKeySpec. secret-bytes "HmacSHA256")]
    (.init mac secret-key)
    (let [hash (.doFinal mac (.getBytes message "UTF-8"))]
      ;; Return base64-encoded signature to match Svix format
      (.encodeToString (Base64/getEncoder) hash))))

(defn verify-webhook-signature [payload headers]
  (log/info "=== WEBHOOK SIGNATURE VERIFICATION START ===")
  (log/info "webhook-verify: Has clerk-webhook-secret:" (boolean clerk-webhook-secret))
  (log/info "webhook-verify: Payload available:" (boolean payload))
  (log/info "webhook-verify: Payload length:" (when payload (count payload)))
  (when clerk-webhook-secret
    (let [svix-id (get headers "svix-id")
          svix-timestamp (get headers "svix-timestamp")
          svix-signature (get headers "svix-signature")]
      (log/info "webhook-verify: svix-id:" svix-id)
      (log/info "webhook-verify: svix-timestamp:" svix-timestamp)
      (log/info "webhook-verify: svix-signature present:" (boolean svix-signature))
      (if (and svix-id svix-timestamp svix-signature)
        (do
          (log/info "webhook-verify: All required headers present, decoding secret...")
          (let [webhook-secret-bytes (.decode (Base64/getDecoder)
                                             (str/replace clerk-webhook-secret #"^whsec_" ""))]
            (log/info "webhook-verify: Secret decoded successfully")
            ;; Validate timestamp (within 5 minutes)
            (let [current-time (quot (System/currentTimeMillis) 1000)
                  webhook-time (Long/parseLong svix-timestamp)
                  time-diff (Math/abs (long (- current-time webhook-time)))]
              (log/info "webhook-verify: Timestamp check - current:" current-time "webhook:" webhook-time "diff:" time-diff)
              (if (> time-diff 300) ; 5 minutes = 300 seconds
                (do
                  (log/warn "webhook-verify: Timestamp too old or too new (diff:" time-diff "seconds)")
                  (log/warn "=== WEBHOOK SIGNATURE VERIFICATION END (TIMESTAMP) ===")
                  nil)
                (let [signed-payload (str svix-id "." svix-timestamp "." payload)]
                  (log/info "webhook-verify: Signed payload constructed, length:" (count signed-payload))
                  (log/info "webhook-verify: Computing HMAC signature...")
                  (let [expected-signature (hmac-sha256 webhook-secret-bytes signed-payload)]
                    (log/info "webhook-verify: Expected signature computed:" expected-signature)
                    (let [signatures (-> svix-signature
                                       (str/split #",")
                                       (->> (map #(str/trim (str/replace % #"^v1=" "")))))]
                      (log/info "webhook-verify: Provided signatures:" signatures)
                      (let [expected-sig-trimmed (str/trim expected-signature)
                            match? (some #(= expected-sig-trimmed %) signatures)]
                        (log/info "webhook-verify: Signature match:" (boolean match?))
                        (log/info "=== WEBHOOK SIGNATURE VERIFICATION END ===")
                        match?))))))))
        (do
          (log/warn "webhook-verify: Missing required headers")
          (log/warn "=== WEBHOOK SIGNATURE VERIFICATION END (MISSING HEADERS) ===")
          nil)))))

(defn handle-webhook-event [event-data]
  (log/info "=== HANDLE WEBHOOK EVENT START ===")
  (log/info "handle-event: Event data available:" (boolean event-data))
  (let [event-type (:type event-data)
        event-object (:data event-data)]
    (log/info "handle-event: Event type:" event-type)
    (log/info "handle-event: Event object available:" (boolean event-object))
    (log/info "handle-event: Event object keys:" (when event-object (keys event-object)))
    (case event-type
      :user.created
      (let [user-id (:id event-object)]
        (log/info "handle-event: User created event, user-id:" user-id)
        (log/info "handle-event: Calling sql/create-user...")
        (let [result (sql/create-user user-id)]
          (log/info "handle-event: User created successfully, result:" result)
          (log/info "=== HANDLE WEBHOOK EVENT END (USER CREATED) ===")
          {:status "success" :message "User created"}))

      :session.created
      (let [user-id (:user_id event-object)
            user-obj (:user event-object)
            actual-user-id (or user-id (:id user-obj))]
        (log/info "handle-event: Session created event, user-id:" actual-user-id)
        (log/info "handle-event: Calling sql/update-user-login...")
        (let [result (sql/update-user-login actual-user-id)]
          (log/info "handle-event: Login recorded successfully, result:" result)
          (log/info "=== HANDLE WEBHOOK EVENT END (SESSION CREATED) ===")
          {:status "success" :message "Login recorded"}))

      :user.deleted
      (let [user-id (:id event-object)]
        (log/info "handle-event: User deleted event, user-id:" user-id)
        (log/info "handle-event: Calling sql/delete-user...")
        (let [result (sql/delete-user user-id)]
          (log/info "handle-event: User deleted successfully, result:" result)
          (log/info "=== HANDLE WEBHOOK EVENT END (USER DELETED) ===")
          {:status "success" :message "User deleted"}))

      (do
        (log/info "handle-event: Unhandled webhook event type:" event-type)
        (log/info "=== HANDLE WEBHOOK EVENT END (UNHANDLED) ===")
        {:status "ignored" :message "Event type not handled"}))))
