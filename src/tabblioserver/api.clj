(ns tabblioserver.api
  (:require [reitit.ring :as ring]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.util.response :refer [response status]]
            [clj-simple-stats.core]
            [tabblioserver.sql :as sql]
            [tabblioserver.clerk :as clerk]
            [tabblioserver.throttling :as throttle]
            [tabblioserver.telegram :as telegram]
            [tabblioserver.ai :as ai]
            [clojure.tools.logging :as log]
            [clojure.java.io :as jio]
            [cheshire.core]))

(defn wrap-raw-body
  "Middleware to capture raw body as string before JSON parsing.
   Needed for webhook signature verification."
  [handler]
  (fn [request]
    (log/info "wrap-raw-body: Processing request for URI:" (:uri request))
    (if-let [body (:body request)]
      (do
        (log/info "wrap-raw-body: Body present, type:" (type body))
        (if (instance? java.io.InputStream body)
          (do
            (log/info "wrap-raw-body: Body is InputStream, reading...")
            (let [raw-body (slurp body)
                  ;; Create new InputStream for next middleware
                  new-body (java.io.ByteArrayInputStream. (.getBytes raw-body "UTF-8"))]
              (log/info "wrap-raw-body: Raw body captured, length:" (count raw-body))
              (handler (assoc request
                              :raw-body raw-body
                              :body new-body))))
          ;; Body already processed, pass through
          (do
            (log/info "wrap-raw-body: Body not an InputStream, passing through")
            (handler request))))
      ;; No body, pass through
      (do
        (log/info "wrap-raw-body: No body present")
        (handler request)))))

(defn log-requests [handler]
  (fn [request]
    (let [method (name (:request-method request))
          uri (:uri request)
          start-time (System/currentTimeMillis)]
      (log/info (str "[" method "] " uri " - Request started"))
      (let [response (handler request)
            duration (- (System/currentTimeMillis) start-time)]
        (log/info (str "[" method "] " uri " - " (:status response 200) " (" duration "ms)"))
        response))))

(defn require-auth [handler]
  (fn [request]
    (if (:user request)
      (handler request)
      (-> (response {:error "Authentication required"})
          (status 401)))))

(defn save-template [request]
  (if-let [rate-limit-response (throttle/check-rate-limit request 1000 3000)]
    rate-limit-response
    (let [user-id (get-in request [:user :user-id])
          template-data (clojure.edn/read-string (:body request))
          enhanced-data (assoc template-data :username (or user-id "") :nickname "")]
      (telegram/notify-template-saved user-id)
      (response (sql/save-template enhanced-data)))))

(defn load-template [request]
  (if-let [rate-limit-response (throttle/check-rate-limit request 1000 3000)]
    rate-limit-response
    (let [user-id (get-in request [:user :user-id])
          template-id (get-in request [:query-params "uuid"])
          template-data (sql/load-template template-id)]
      (telegram/notify-template-loaded template-id user-id)
      (if template-data
        (response template-data)
        (-> (response {:error "Template not found"})
            (status 404))))))

(defn link-template [request]
  (if-let [user (:user request)]
    (let [user-id (:user-id user)
          body (:body request)
          uuid (:uuid body)
          nickname (:nickname body)]
      (log/info user-id body uuid nickname)
      (if uuid
        (response (sql/link-template user-id uuid nickname))
        (-> (response {:error "UUID is required"})
            (status 400))))
    (-> (response {:error "User needs to be logged in to link templates to their account"})
        (status 401))))

(defn unlink-template [request]
  (if-let [user (:user request)]
    (let [user-id (:user-id user)
          body (:body request)
          uuid (:uuid body)]
      (if uuid
        (response (sql/unlink-template user-id uuid))
        (-> (response {:error "UUID is required"})
            (status 400))))
    (-> (response {:error "User needs to be logged in to unlink templates from their account"})
        (status 401))))

(defn user-templates [request]
  (if-let [user (:user request)]
    (let [user-id (:user-id user)
          templates (sql/get-user-templates user-id)]
      (response {:templates templates}))
    (-> (response {:error "User needs to be logged in to view their templates"})
        (status 401))))

(defn clerk-webhook [request]
  (log/info "=== CLERK WEBHOOK START ===")
  (log/info "clerk-webhook: Request URI:" (:uri request))
  (log/info "clerk-webhook: Request method:" (:request-method request))
  (let [raw-body (:raw-body request)
        headers (:headers request)
        body (:body request)]
    (log/info "clerk-webhook: Raw body available:" (boolean raw-body))
    (log/info "clerk-webhook: Raw body length:" (when raw-body (count raw-body)))
    (log/info "clerk-webhook: Headers:" (select-keys headers ["svix-id" "svix-timestamp" "svix-signature" "content-type"]))

    (let [signature-valid? (clerk/verify-webhook-signature raw-body headers)]
      (log/info "clerk-webhook: Signature verification result:" signature-valid?)

      (if signature-valid?
        (do
          (log/info "clerk-webhook: Signature verified successfully")
          ;; Only log event details AFTER signature verification passes
          (log/info "clerk-webhook: === WEBHOOK PAYLOAD ===")
          (log/info "clerk-webhook: Event type:" (:type body))
          (log/info "clerk-webhook: Event data keys:" (when body (keys (:data body))))
          (log/info "clerk-webhook: Event timestamp:" (:timestamp body))
          (when (:data body)
            (let [data (:data body)]
              (log/info "clerk-webhook: User ID from event:" (or (:id data) (:user_id data)))))
          (log/info "clerk-webhook: Calling handle-webhook-event...")
          (let [result (clerk/handle-webhook-event body)]
            (log/info "clerk-webhook: Processing result:" result)
            (log/info "=== CLERK WEBHOOK END (SUCCESS) ===")
            (response {:received true})))
        (do
          (log/warn "clerk-webhook: Signature verification FAILED")
          (log/warn "=== CLERK WEBHOOK END (FAILED) ===")
          (-> (response {:error "Invalid signature"})
              (status 400)))))))

(defn get-content-type [file-extension]
  (case (clojure.string/lower-case file-extension)
    "csv" "text/csv"
    "tsv" "text/tab-separated-values"
    "txt" "text/plain"
    "xls" "application/vnd.ms-excel"
    "xlsx" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "xlsm" "application/vnd.ms-excel.sheet.macroEnabled.12"
    "pdf" "application/pdf"
    "json" "application/json"
    "xml" "application/xml"
    "application/octet-stream"))

(defn serve-file [request]
  (let [filename (get-in request [:path-params :file-id])]
    (if-let [rate-limit-response (when (not= filename "countries.csv")
                                   (throttle/check-rate-limit request 1000 3000 filename))]
      rate-limit-response
      (let [file-path (str "resources/files/" filename)]
        (if (.exists (jio/file file-path))
          (let [file-extension (last (clojure.string/split filename #"\."))]
            (-> (response (jio/file file-path))
                (assoc-in [:headers "Content-Type"] (get-content-type file-extension))
                (assoc-in [:headers "Content-Disposition"] (str "attachment; filename=\"" filename "\""))))
          (-> (response {:error "File not found"})
              (status 404)))))))

(def allowed-file-extensions #{"txt" "csv" "tsv" "xls" "xlsx" "xlsm"})
(def max-file-size (* 10 1024 1024)) ; 10MB in bytes

(defn- read-limited-stream
  "Reads input-stream up to max-bytes. Returns byte array on success, nil if limit exceeded."
  [input-stream max-bytes]
  (let [chunk (byte-array 8192)
        out   (java.io.ByteArrayOutputStream.)]
    (loop [total 0]
      (let [n (.read input-stream chunk)]
        (cond
          (= n -1)                    (.toByteArray out)
          (> (+ total n) max-bytes)   nil
          :else                       (do (.write out chunk 0 n)
                                          (recur (+ total n))))))))

(defn get-file-extension-from-url [url]
  (when url
    (let [path (-> url
                   (clojure.string/split #"\?")
                   first)
          filename (last (clojure.string/split path #"/"))
          extension (last (clojure.string/split filename #"\."))]
      (clojure.string/lower-case extension))))

(defn serve-url [request]
  (if-let [user (:user request)]
    (let [url-string (or (get-in request [:query-params "url"])
                         (get-in request [:body :url]))]
      (if-not url-string
        (-> (response {:error "URL parameter is required"})
            (status 400))
        ;; Rate limit: 1 second for debugging
        (if-let [rate-limit-response (throttle/check-rate-limit request 1000 3000 url-string)]
          rate-limit-response
          (try
            ;; First, check file extension from URL
            (let [file-extension (get-file-extension-from-url url-string)
                  uri (java.net.URI. url-string)
                  url (.toURL uri)]
              (if-not (allowed-file-extensions file-extension)
                (-> (response {:error (str "File type not allowed. Allowed types: " (clojure.string/join ", " allowed-file-extensions))})
                    (status 400))
                (let [connection (doto (.openConnection url)
                                   (.setConnectTimeout 5000)
                                   (.setReadTimeout 30000)
                                   (.connect))
                      input-stream (.getInputStream connection)
                      bytes        (read-limited-stream input-stream max-file-size)
                      filename     (or (last (clojure.string/split url-string #"/")) "download")]
                  (if (nil? bytes)
                    (-> (response {:error "File too large. Maximum size is 10MB"})
                        (status 400))
                    (-> (response bytes)
                        (assoc-in [:headers "Content-Type"] (get-content-type file-extension))
                        (assoc-in [:headers "Content-Disposition"] (str "attachment; filename=\"" filename "\"")))))))
            (catch java.net.URISyntaxException e
              (log/error "Invalid URL syntax:" e)
              (-> (response {:error "Invalid URL"})
                  (status 400)))
            (catch java.net.MalformedURLException e
              (log/error "Malformed URL:" e)
              (-> (response {:error "Invalid URL"})
                  (status 400)))
            (catch java.net.UnknownHostException e
              (log/error "Unknown host:" e)
              (-> (response {:error "Unable to reach the specified URL"})
                  (status 400)))
            (catch java.io.IOException e
              (log/error "IO error downloading file:" e)
              (-> (response {:error "Error downloading file"})
                  (status 500)))
            (catch Exception e
              (log/error "Error serving URL:" e)
              (-> (response {:error "An error occurred while processing the request"})
                  (status 500)))))))
    (-> (response {:error "Authentication required"})
        (status 401))))

;; Helper to wrap handlers with clerk auth
(defn with-clerk-auth [handler]
  (fn [request]
    (log/info "with-clerk-auth: Attempting to authenticate request for URI:" (:uri request))
    (let [user-info (clerk/authenticate-request request)]
      (if user-info
        (do
          (log/info "with-clerk-auth: Authentication successful for user:" (:user-id user-info))
          (sql/update-user-login (:user-id user-info))
          (handler (assoc request :user user-info)))
        (do
          (log/info "with-clerk-auth: No authentication found, continuing without user")
          (handler (assoc request :user nil)))))))

(defn tracker [_]
  (-> (response "")
      (assoc-in [:headers "Content-Type"] "text/html")))

(def routes
  [["/" {:get {:handler (fn [_] (response {:message "tabblio server API"}))}}]
   ["/api/tracker" {:get {:handler tracker}}]
   ;; Template routes (optional auth - better rate limits for authenticated users)
   ["/api/save-template" {:post {:handler (with-clerk-auth save-template)}}]
   ["/api/load-template" {:get {:handler (with-clerk-auth load-template)}}]
   ["/api/files/:file-id" {:get {:handler (with-clerk-auth serve-file)}}]
   ;; Routes that require authentication
   ["/api/link-template" {:post {:handler (with-clerk-auth link-template)}}]
   ["/api/unlink-template" {:post {:handler (with-clerk-auth unlink-template)}}]
   ["/api/user-templates" {:get {:handler (with-clerk-auth user-templates)}}]
   ["/api/serve-url" {:get {:handler (with-clerk-auth serve-url)}}]
   ;; AI text-to-SQL proxy with per-user daily quota
   ["/api/ai-query" {:post {:handler (with-clerk-auth ai/ai-query)}}]
   ;; Webhook routes (use signature verification, not session auth)
   ["/api/clerk-webhook" {:post {:handler clerk-webhook}}]])

(def app
  (ring/ring-handler
   (ring/router routes)
   (ring/routes
    (ring/create-default-handler
     {:not-found          (constantly {:status 404 :body "Not found"})
      :method-not-allowed (constantly {:status 204})}))
   {:middleware [clj-simple-stats.core/wrap-stats
                 log-requests
                 #(wrap-cors % :access-control-allow-origin [#"https://www\.tabblio\.com"
                                                             #"https://tabblio\.com"
                                                             #"http://localhost:.*"]
                             :access-control-allow-methods [:get :put :post :delete :options]
                             :access-control-allow-headers ["Content-Type" "Authorization" "Accept" "x-clerk-session-token" "x-user-ai-key" "x-ai-provider"]
                             :access-control-allow-credentials true)
                 wrap-raw-body
                 wrap-params
                 #(wrap-json-body % {:keywords? true})
                 wrap-json-response]}))
