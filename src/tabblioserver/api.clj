(ns tabblioserver.api
  (:require [reitit.ring :as ring]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.util.response :refer [response status]]
            [ring.util.io :as io]
            [tabblioserver.sql :as sql]
            [tabblioserver.clerk :as clerk]
    ;[tabblioserver.stripe :as stripe]
            [clojure.tools.logging :as log]
            [clojure.java.io :as jio]
            [cheshire.core]))

(defn wrap-raw-body
  "Middleware to capture raw body as string before JSON parsing.
   Needed for webhook signature verification."
  [handler]
  (fn [request]
    (if-let [body (:body request)]
      (if (instance? java.io.InputStream body)
        (let [raw-body (slurp body)
              ;; Create new InputStream for next middleware
              new-body (java.io.ByteArrayInputStream. (.getBytes raw-body "UTF-8"))]
          (handler (assoc request
                         :raw-body raw-body
                         :body new-body)))
        ;; Body already processed, pass through
        (handler request))
      ;; No body, pass through
      (handler request))))

(defn log-requests [handler]
  (fn [request]
    (let [method (name (:request-method request))
          uri (:uri request)
          start-time (System/currentTimeMillis)]
      (log/info (str "[" method "] " uri " - Request started"))
      (log/info request)
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

(def last-request (atom nil))

(defn save-template [request]
  (reset! last-request request)
  (let [                                                    ;user (:user request)
        template-data (clojure.edn/read-string (:body request))
        ;user-id (:user-id user)
        user-id nil
        enhanced-data (assoc template-data :username (or user-id "") :nickname "")]
    (response (sql/save-template enhanced-data))))

(defn load-template [request]
  (reset! last-request request)
  (let [template-id (get-in request [:query-params "uuid"])
        template-data (sql/load-template template-id)]
    (if template-data
      (response template-data)
      (-> (response {:error "Template not found"})
          (status 404)))))

(defn link-template [request]
  (if-let [user (:user request)]
    (let [user-id (:user-id user)
          body (:body request)
          uuid (:uuid body)
          nickname (:nickname body)]
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

;(defn create-payment-intent [request]
;  (let [user (:user request)
;        body (:body request)
;        amount (:amount body)
;        currency (:currency body "usd")
;        user-id (:user-id user)]
;    (if user-id
;      (let [result (stripe/create-payment-intent amount currency nil {"user_id" user-id})]
;        (if (:success result)
;          (do
;            (stripe/save-payment-in-db {:user-id user-id
;                                       :payment-intent-id (:payment-intent-id result)
;                                       :amount amount
;                                       :currency currency
;                                       :status (:status result)})
;            (response {:client-secret (:client-secret result)
;                      :payment-intent-id (:payment-intent-id result)}))
;          (-> (response {:error (:error result)})
;              (status 400))))
;      (-> (response {:error "Authentication required"})
;          (status 401)))))

;(defn create-subscription [request]
;  (let [user (:user request)
;        body (:body request)
;        price-id (:price-id body)
;        user-id (:user-id user)]
;    (if user-id
;      (let [customer-result (stripe/create-customer user-id (:email body) (:name body))]
;        (if (:success customer-result)
;          (let [subscription-result (stripe/create-subscription (:customer-id customer-result) price-id)]
;            (if (:success subscription-result)
;              (response {:subscription-id (:subscription-id subscription-result)
;                        :status (:status subscription-result)})
;              (-> (response {:error (:error subscription-result)})
;                  (status 400))))
;          (-> (response {:error (:error customer-result)})
;              (status 400))))
;      (-> (response {:error "Authentication required"})
;          (status 401)))))

;(defn stripe-webhook [request]
;  (let [payload (slurp (:body request))
;        signature (get-in request [:headers "stripe-signature"])]
;    (if-let [event (stripe/verify-webhook-signature payload signature)]
;      (let [result (stripe/handle-webhook-event event)]
;        (log/info "Webhook processed:" result)
;        (response {:received true}))
;      (-> (response {:error "Invalid signature"})
;          (status 400)))))

(defn clerk-webhook [request]
  (let [body (:body request)
        headers (:headers request)]
    (log/info "Clerk webhook received")
    (log/info "Event type:" (:type body))
    (log/info "Event data:" (:data body))
    (log/info "Event timestamp:" (:timestamp body))
    ;; For now, just log and accept all webhooks
    ;; TODO: Add signature verification back when we have the raw payload
    (let [result (clerk/handle-webhook-event body)]
      (log/info "Clerk webhook processed:" result)
      (response {:received true}))))

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
  (let [user (:user request)
        user-id (:user-id user)
        filename (get-in request [:path-params :file-id])]
    (if user-id
      (let [file-path (str "resources/files/" filename)]
        (if (.exists (jio/file file-path))
          (let [file-extension (last (clojure.string/split filename #"\."))]
            (-> (response (jio/file file-path))
                (assoc-in [:headers "Content-Type"] (get-content-type file-extension))
                (assoc-in [:headers "Content-Disposition"] (str "attachment; filename=\"" filename "\""))))
          (-> (response {:error "File not found"})
              (status 404))))
      (-> (response {:error "Authentication required"})
          (status 401)))))

(def allowed-file-extensions #{"txt" "csv" "tsv" "xls" "xlsx" "xlsm"})
(def max-file-size (* 10 1024 1024)) ; 10MB in bytes

(defn get-file-extension-from-url [url]
  (when url
    (let [path (-> url
                   (clojure.string/split #"\?")
                   first)
          filename (last (clojure.string/split path #"/"))
          extension (last (clojure.string/split filename #"\."))]
      (clojure.string/lower-case extension))))

(defn serve-url [request]
  (let [url-string (or (get-in request [:query-params "url"])
                       (get-in request [:body :url]))]
    (if-not url-string
      (-> (response {:error "URL parameter is required"})
          (status 400))
      (try
        ;; First, check file extension from URL
        (let [file-extension (get-file-extension-from-url url-string)
              uri (java.net.URI. url-string)
              url (.toURL uri)]
          (if-not (allowed-file-extensions file-extension)
            (-> (response {:error (str "File type not allowed. Allowed types: " (clojure.string/join ", " allowed-file-extensions))})
                (status 400))
            ;; Make HEAD request to check Content-Length
            (let [connection (doto (.openConnection url)
                              (.setRequestMethod "HEAD")
                              (.setConnectTimeout 5000)
                              (.setReadTimeout 5000)
                              (.connect))
                  content-length (.getContentLengthLong connection)]

              (if (and (pos? content-length) (> content-length max-file-size))
                (-> (response {:error (str "File too large. Maximum size is 10MB, file is " (/ content-length 1024 1024) "MB")})
                    (status 400))
                ;; Download and stream the file
                (let [input-stream (.getInputStream (.openConnection url))
                      filename (or (last (clojure.string/split url-string #"/")) "download")]
                  (-> (response input-stream)
                      (assoc-in [:headers "Content-Type"] (get-content-type file-extension))
                      (assoc-in [:headers "Content-Disposition"] (str "attachment; filename=\"" filename "\""))))))))
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

(def routes
  [["/" {:get {:handler (fn [_] (response {:message "TabblioServer API"}))}}]
   ["/api/save-template" {:post {:handler save-template }}] ;(require-auth save-template) THAT IS IF I WANT CLERK
   ["/api/load-template" {:get {:handler load-template}}]
   ["/api/link-template" {:post {:handler link-template}}]
   ["/api/unlink-template" {:post {:handler unlink-template}}]
   ["/api/user-templates" {:get {:handler user-templates}}]
   ;["/create-payment-intent" {:post {:handler (require-auth create-payment-intent)}}]
   ;["/create-subscription" {:post {:handler (require-auth create-subscription)}}]
   ["/api/files/:file-id" {:get {:handler serve-file}}]
   ["/api/serve-url" {:get {:handler serve-url}}]
   ;["/stripe-webhook" {:post {:handler stripe-webhook}}]
   ["/api/clerk-webhook" {:post {:handler clerk-webhook}}]])

(def app
  (ring/ring-handler
    (ring/router routes)
    (ring/routes
      (ring/create-default-handler
        {:not-found          (constantly {:status 404 :body "Not found"})
         :method-not-allowed (constantly {:status 204})}))
    {:middleware [log-requests
                  #(wrap-cors % :access-control-allow-origin [#"https://www\.tabblio\.com"
                                                              #"https://tabblio\.com"
                                                              #"http://localhost:.*"]
                              :access-control-allow-methods [:get :put :post :delete :options]
                              :access-control-allow-headers ["Content-Type" "Authorization" "Accept" "x-clerk-session-token"]
                              :access-control-allow-credentials true
                              )
                  clerk/wrap-clerk-auth
                  wrap-params
                  wrap-json-body
                  wrap-json-response]}))
