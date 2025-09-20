(ns tabblioserver.api
  (:require [reitit.ring :as ring]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.util.response :refer [response status]]
            [ring.util.io :as io]
            [tabblioserver.sql :as sql]
            [tabblioserver.clerk :as clerk]
            [tabblioserver.stripe :as stripe]
            [clojure.tools.logging :as log]
            [clojure.java.io :as jio]
            [cheshire.core]))

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
  (let [user (:user request)
        template-data (:body request)
        user-id (:user-id user)
        enhanced-data (assoc template-data :username user-id)]
    (if user-id
      (let [template-id (sql/save-template enhanced-data)]
        (response {:success true :template-id template-id}))
      (-> (response {:error "Authentication required"})
          (status 401)))))

(defn load-template [request]
  (let [template-id (get-in request [:body :template-id])
        template-data (sql/load-template template-id)]
    (if template-data
      (response template-data)
      (-> (response {:error "Template not found"})
          (status 404)))))

(defn create-payment-intent [request]
  (let [user (:user request)
        body (:body request)
        amount (:amount body)
        currency (:currency body "usd")
        user-id (:user-id user)]
    (if user-id
      (let [result (stripe/create-payment-intent amount currency nil {"user_id" user-id})]
        (if (:success result)
          (do 
            (stripe/save-payment-in-db {:user-id user-id
                                       :payment-intent-id (:payment-intent-id result)
                                       :amount amount
                                       :currency currency
                                       :status (:status result)})
            (response {:client-secret (:client-secret result)
                      :payment-intent-id (:payment-intent-id result)}))
          (-> (response {:error (:error result)})
              (status 400))))
      (-> (response {:error "Authentication required"})
          (status 401)))))

(defn create-subscription [request]
  (let [user (:user request)
        body (:body request)
        price-id (:price-id body)
        user-id (:user-id user)]
    (if user-id
      (let [customer-result (stripe/create-customer user-id (:email body) (:name body))]
        (if (:success customer-result)
          (let [subscription-result (stripe/create-subscription (:customer-id customer-result) price-id)]
            (if (:success subscription-result)
              (response {:subscription-id (:subscription-id subscription-result)
                        :status (:status subscription-result)})
              (-> (response {:error (:error subscription-result)})
                  (status 400))))
          (-> (response {:error (:error customer-result)})
              (status 400))))
      (-> (response {:error "Authentication required"})
          (status 401)))))

(defn stripe-webhook [request]
  (let [payload (slurp (:body request))
        signature (get-in request [:headers "stripe-signature"])]
    (if-let [event (stripe/verify-webhook-signature payload signature)]
      (let [result (stripe/handle-webhook-event event)]
        (log/info "Webhook processed:" result)
        (response {:received true}))
      (-> (response {:error "Invalid signature"})
          (status 400)))))

(defn clerk-webhook [request]
  (let [payload (slurp (:body request))
        headers (:headers request)]
    (if (clerk/verify-webhook-signature payload headers)
      (let [event-data (cheshire.core/parse-string payload true)
            result (clerk/handle-webhook-event event-data)]
        (log/info "Clerk webhook processed:" result)
        (response {:received true}))
      (-> (response {:error "Invalid signature"})
          (status 400)))))

(defn get-content-type [file-extension]
  (case (clojure.string/lower-case file-extension)
    "csv" "text/csv"
    "txt" "text/plain"
    "xls" "application/vnd.ms-excel"
    "xlsx" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
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

(def routes
  [["/" {:get {:handler (fn [_] (response {:message "TabblioServer API"}))}}]
   ["/save-template" {:post {:handler (require-auth save-template)}}]
   ["/load-template" {:post {:handler load-template}}]
   ["/create-payment-intent" {:post {:handler (require-auth create-payment-intent)}}]
   ["/create-subscription" {:post {:handler (require-auth create-subscription)}}]
   ["/files/:file-id" {:get {:handler (require-auth serve-file)}}]
   ["/stripe-webhook" {:post {:handler stripe-webhook}}]
   ["/clerk-webhook" {:post {:handler clerk-webhook}}]])

(def app
  (ring/ring-handler
    (ring/router routes)
    (ring/routes
      (ring/create-default-handler))
    {:middleware [log-requests
                  #(wrap-cors % :access-control-allow-origin [#"https://www\.tabblio\.com"
                                                              #"https://tabblio\.com"
                                                              #"http://localhost:.*"]
                                :access-control-allow-methods [:get :put :post :delete :options]
                                :access-control-allow-headers ["Content-Type" "Authorization" "Accept" "x-clerk-session-token"])
                  clerk/wrap-clerk-auth
                  wrap-json-body
                  wrap-json-response]}))
