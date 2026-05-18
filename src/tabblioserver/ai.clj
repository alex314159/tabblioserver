(ns tabblioserver.ai
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [tabblioserver.env :refer [env]]
            [ring.util.response :refer [response status]]
            [clojure.tools.logging :as log]))

(def ^:private daily-limit 5)

;; {userId -> {:date "YYYY-MM-DD" :count N}}
(def ^:private quota-state (atom {}))

(defn- today []
  (str (java.time.LocalDate/now)))

(defn- get-quota [user-id]
  (let [entry (get @quota-state user-id)
        today-str (today)]
    (if (and entry (= (:date entry) today-str))
      {:used (:count entry) :limit daily-limit}
      {:used 0 :limit daily-limit})))

(defn- increment-quota! [user-id]
  (let [today-str (today)]
    (swap! quota-state update user-id
           (fn [entry]
             (if (and entry (= (:date entry) today-str))
               (update entry :count inc)
               {:date today-str :count 1}))))
  (get-quota user-id))

(def ^:private system-prompt
  "You are a SQL expert. The user has a table referenced as ? (AlaSQL inline table syntax).
Write a single AlaSQL SELECT statement to answer the question.
Use ? as the table name (e.g. SELECT * FROM ? WHERE ...).
Return ONLY the SQL, no markdown, no explanation.")

(defn- call-anthropic [api-key schema question]
  (let [prompt (str "Schema:\n" schema "\n\nQuestion: " question)
        body (json/generate-string
              {:model "claude-haiku-4-5-20251001"
               :max_tokens 500
               :system system-prompt
               :messages [{:role "user" :content prompt}]})
        {:keys [status body error]} @(http/request
                                      {:url "https://api.anthropic.com/v1/messages"
                                       :method :post
                                       :headers {"Content-Type" "application/json"
                                                 "x-api-key" api-key
                                                 "anthropic-version" "2023-06-01"}
                                       :body body
                                       :timeout 30000})]
    (if error
      {:error (str "Request failed: " error)}
      (let [parsed (json/parse-string body true)]
        (if (= status 200)
          {:sql (-> parsed :content first :text str/trim)}
          (do
            (log/warn "Anthropic error:" status body)
            {:error (or (-> parsed :error :message) "AI request failed")}))))))

(defn- call-openai-compat [api-key base-url model schema question]
  (let [prompt (str "Schema:\n" schema "\n\nQuestion: " question)
        body (json/generate-string
              {:model model
               :max_tokens 500
               :messages [{:role "system" :content system-prompt}
                          {:role "user" :content prompt}]})
        {:keys [status body error]} @(http/request
                                      {:url (str base-url "/chat/completions")
                                       :method :post
                                       :headers {"Content-Type" "application/json"
                                                 "Authorization" (str "Bearer " api-key)}
                                       :body body
                                       :timeout 30000})]
    (if error
      {:error (str "Request failed: " error)}
      (let [parsed (json/parse-string body true)]
        (if (= status 200)
          {:sql (-> parsed :choices first :message :content str/trim)}
          (do
            (log/warn "OpenAI-compat error:" status body)
            {:error (or (-> parsed :error :message) "AI request failed")}))))))

(defn- dispatch-provider [provider api-key schema question]
  (case provider
    "anthropic" (call-anthropic api-key schema question)
    "openai"    (call-openai-compat api-key "https://api.openai.com/v1" "gpt-4o-mini" schema question)
    "mistral"   (call-openai-compat api-key "https://api.mistral.ai/v1" "mistral-small-latest" schema question)
    {:error (str "Unknown provider: " provider)}))

(defn ai-query [request]
  (let [user-id (get-in request [:user :user-id])
        body (:body request)
        provider (or (:provider body) "anthropic")
        question (:question body)
        schema (:schema body)
        user-key (get-in request [:headers "x-user-ai-key"])
        using-user-key (and user-key (not (str/blank? user-key)))]

    (cond
      (not user-id)
      (-> (response {:error "Authentication required"})
          (status 401))

      (or (str/blank? question) (str/blank? schema))
      (-> (response {:error "question and schema are required"})
          (status 400))

      :else
      (let [quota (get-quota user-id)
            quota-exceeded? (and (not using-user-key) (>= (:used quota) daily-limit))]
        (if quota-exceeded?
          (-> (response {:error "Daily query limit reached (5/day). Provide your own API key to continue."
                         :quota quota})
              (status 429))
          (let [api-key (if using-user-key
                          user-key
                          (case provider
                            "anthropic" (env :anthropic-api-key)
                            "openai"    (env :openai-api-key)
                            "mistral"   (env :mistral-api-key)
                            nil))]
            (log/info "ai-query: user" user-id "provider" provider "own-key" (boolean using-user-key))
            (if-not api-key
              (-> (response {:error (str "No API key configured for provider: " provider)})
                  (status 500))
              (let [result (dispatch-provider provider api-key schema question)
                    new-quota (when-not using-user-key
                                (increment-quota! user-id))]
                (if (:error result)
                  (-> (response {:error (:error result)
                                 :quota (or new-quota quota)})
                      (status 500))
                  (response {:sql (:sql result)
                             :quota (or new-quota quota)}))))))))))