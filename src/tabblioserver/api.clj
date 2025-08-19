(ns tabblioserver.api
  (:require [reitit.ring :as ring]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.util.response :refer [response status]]
            [tabblioserver.sql :as sql]
            [tabblioserver.clerk :as clerk]
            [tabblioserver.stripe :as stripe]
            [clojure.tools.logging :as log]))

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

(def routes
  [["/" {:get {:handler (fn [_] (response {:message "TabblioServer API"}))}}]
   ["/save-template" {:post {:handler (require-auth save-template)}}]
   ["/load-template" {:post {:handler load-template}}]
   ["/create-payment-intent" {:post {:handler (require-auth create-payment-intent)}}]
   ["/create-subscription" {:post {:handler (require-auth create-subscription)}}]
   ["/stripe-webhook" {:post {:handler stripe-webhook}}]])

(def app
  (ring/ring-handler
    (ring/router routes)
    (ring/routes
      (ring/create-default-handler))
    {:middleware [clerk/wrap-clerk-auth wrap-json-body wrap-json-response]}))
