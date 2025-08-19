(ns tabblioserver.stripe
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as cs]
            [next.jdbc.sql :as sql]
            [tabblioserver.sql :as db])
  (:import [com.stripe Stripe]
           [com.stripe.model Customer PaymentIntent Subscription Event EventDataObjectDeserializer]
           [com.stripe.param CustomerCreateParams PaymentIntentCreateParams SubscriptionCreateParams]
           [com.stripe.exception StripeException]))

(def stripe-secret-key (System/getenv "STRIPE_SECRET_KEY"))
(def stripe-webhook-secret (System/getenv "STRIPE_WEBHOOK_SECRET"))

(defn init-stripe! []
  (when stripe-secret-key
    (set! Stripe/apiKey stripe-secret-key)
    (log/info "Stripe initialized with API key"))
  (when-not stripe-secret-key
    (log/warn "STRIPE_SECRET_KEY environment variable not set")))

(defn create-customer [user-id email name]
  (try
    (init-stripe!)
    (let [params (-> (CustomerCreateParams/builder)
                    (.setEmail email)
                    (.setName name)
                    (.putMetadata "user_id" user-id)
                    (.build))
          customer (Customer/create params)]
      (log/info "Created Stripe customer" (.getId customer) "for user" user-id)
      {:success true :customer-id (.getId customer)})
    (catch StripeException e
      (log/error e "Failed to create Stripe customer for user" user-id)
      {:success false :error (.getMessage e)})))

(defn create-payment-intent [amount currency customer-id metadata]
  (try
    (init-stripe!)
    (let [params (-> (PaymentIntentCreateParams/builder)
                    (.setAmount (long amount))
                    (.setCurrency currency)
                    (.setCustomer customer-id)
                    (.setAutomaticPaymentMethods 
                      (PaymentIntentCreateParams$AutomaticPaymentMethods/builder)
                      (.setEnabled true)
                      (.build))
                    (.setAllMetadata metadata)
                    (.build))
          intent (PaymentIntent/create params)]
      (log/info "Created payment intent" (.getId intent) "for customer" customer-id)
      {:success true 
       :payment-intent-id (.getId intent)
       :client-secret (.getClientSecret intent)
       :status (.getStatus intent)})
    (catch StripeException e
      (log/error e "Failed to create payment intent")
      {:success false :error (.getMessage e)})))

(defn create-subscription [customer-id price-id]
  (try
    (init-stripe!)
    (let [params (-> (SubscriptionCreateParams/builder)
                    (.setCustomer customer-id)
                    (.addItem (SubscriptionCreateParams$Item/builder)
                             (.setPrice price-id)
                             (.build))
                    (.build))
          subscription (Subscription/create params)]
      (log/info "Created subscription" (.getId subscription) "for customer" customer-id)
      {:success true
       :subscription-id (.getId subscription)
       :status (.getStatus subscription)
       :current-period-start (.getCurrentPeriodStart subscription)
       :current-period-end (.getCurrentPeriodEnd subscription)})
    (catch StripeException e
      (log/error e "Failed to create subscription")
      {:success false :error (.getMessage e)})))

(defn get-customer [customer-id]
  (try
    (init-stripe!)
    (let [customer (Customer/retrieve customer-id)]
      {:success true :customer customer})
    (catch StripeException e
      (log/error e "Failed to retrieve customer" customer-id)
      {:success false :error (.getMessage e)})))

(defn update-subscription-in-db [subscription-data]
  (let [ds (db/get-datasource)
        user-id (:user-id subscription-data)
        stripe-subscription-id (:stripe-subscription-id subscription-data)
        status (:status subscription-data)]
    (sql/update! ds :subscriptions
                 {:status status
                  :current_period_start (:current-period-start subscription-data)
                  :current_period_end (:current-period-end subscription-data)
                  :cancel_at_period_end (:cancel-at-period-end subscription-data false)}
                 {:user_id user-id :stripe_subscription_id stripe-subscription-id})))

(defn save-payment-in-db [payment-data]
  (let [ds (db/get-datasource)]
    (sql/insert! ds :payments
                 {:user_id (:user-id payment-data)
                  :stripe_payment_intent_id (:payment-intent-id payment-data)
                  :amount (:amount payment-data)
                  :currency (:currency payment-data "usd")
                  :status (:status payment-data)
                  :metadata (cs/generate-string (:metadata payment-data {}))})))

(defn get-user-subscription [user-id]
  (let [ds (db/get-datasource)]
    (sql/get-by-id ds :subscriptions user-id {:columns [:user_id]})))

(defn verify-webhook-signature [payload signature]
  (try
    (when stripe-webhook-secret
      (Event/constructEvent payload signature stripe-webhook-secret))
    (catch Exception e
      (log/error e "Failed to verify webhook signature")
      nil)))

(defn handle-webhook-event [event]
  (let [event-type (.getType event)]
    (log/info "Processing webhook event:" event-type)
    (case event-type
      "payment_intent.succeeded"
      (let [payment-intent (.getDataObjectDeserializer event)
            payment-intent-id (.getId payment-intent)]
        (log/info "Payment succeeded:" payment-intent-id)
        ;; Update payment status in database
        {:processed true})
      
      "invoice.payment_succeeded"
      (let [invoice (.getDataObjectDeserializer event)]
        (log/info "Invoice payment succeeded")
        ;; Handle successful subscription payment
        {:processed true})
      
      "customer.subscription.updated"
      (let [subscription (.getDataObjectDeserializer event)]
        (log/info "Subscription updated")
        ;; Update subscription status in database
        {:processed true})
      
      "customer.subscription.deleted"
      (let [subscription (.getDataObjectDeserializer event)]
        (log/info "Subscription cancelled")
        ;; Mark subscription as cancelled in database
        {:processed true})
      
      (do
        (log/info "Unhandled webhook event type:" event-type)
        {:processed false :message "Unhandled event type"}))))
