(ns tabblioserver.sql
  (:require [hikari-cp.core :as hikari]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]
            [clojure.tools.logging :as log]
            [cheshire.core :as cs]))

(def linode-local-sqlite-path "///home/aalmosni/tabblio/tabblio.db")

(def db-config
  {:auto-commit            true
   :read-only              false
   :connection-timeout     30000
   :validation-timeout     5000
   :idle-timeout           600000
   :max-lifetime           1800000
   :minimum-idle           2        ; Lower for SQLite (single file)
   :maximum-pool-size      5        ; Lower for SQLite (single file)
   :pool-name              "clibtrader-sqlite-pool"
   :adapter                "sqlite"
   :url                    (str "jdbc:sqlite:" linode-local-sqlite-path)
   :register-mbeans        false
   ;; SQLite-specific optimizations
   :connection-init-sql    "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA cache_size=10000; PRAGMA temp_store=memory;"})

(defonce datasource (atom nil))

(defn init-db! []
  (when-not @datasource
    (log/info "Initializing database connection pool")
    (reset! datasource (hikari/make-datasource db-config))
    ;(create-tables!)
    ))

(defn get-datasource []
  (when-not @datasource (init-db!))
  @datasource)

(defn create-tables! []
  (log/info "Creating database tables")
  (let [ds (get-datasource)]
    ;; Create users table
    (jdbc/execute! ds
      ["CREATE TABLE IF NOT EXISTS users (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          clerk_id TEXT NOT NULL UNIQUE,
          creation_datetime DATETIME DEFAULT CURRENT_TIMESTAMP,
          last_login_datetime DATETIME,
          number_of_logins INTEGER DEFAULT 0
        )"])

    ;; Create templates table
    (jdbc/execute! ds
      ["CREATE TABLE IF NOT EXISTS templates (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          uuid TEXT NOT NULL,
          username TEXT,
          nickname TEXT,
          template TEXT NOT NULL,
          created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
          modified_at DATETIME DEFAULT CURRENT_TIMESTAMP,
          last_opened_at DATETIME,
          opened_count INTEGER DEFAULT 0
        )"])

    ;; Create indexes for users
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_users_clerk_id ON users(clerk_id)"])
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_users_creation_datetime ON users(creation_datetime)"])
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_users_last_login_datetime ON users(last_login_datetime)"])

    ;; Create indexes
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_templates_username ON templates(username)"])
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_templates_created_at ON templates(created_at)"])
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_templates_modified_at ON templates(modified_at)"])

    ;; Create trigger for modified_at
    (jdbc/execute! ds
      ["CREATE TRIGGER IF NOT EXISTS update_templates_modified_at
          AFTER UPDATE ON templates
          FOR EACH ROW
        BEGIN
          UPDATE templates SET modified_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
        END"])

    ;; Create subscriptions table
    (jdbc/execute! ds
      ["CREATE TABLE IF NOT EXISTS subscriptions (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          user_id TEXT NOT NULL,
          stripe_customer_id TEXT NOT NULL,
          stripe_subscription_id TEXT,
          status TEXT NOT NULL DEFAULT 'inactive',
          plan_id TEXT,
          current_period_start DATETIME,
          current_period_end DATETIME,
          cancel_at_period_end BOOLEAN DEFAULT FALSE,
          created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
          updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
        )"])

    ;; Create payments table for tracking individual payments
    (jdbc/execute! ds
      ["CREATE TABLE IF NOT EXISTS payments (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          user_id TEXT NOT NULL,
          stripe_payment_intent_id TEXT NOT NULL,
          amount INTEGER NOT NULL,
          currency TEXT NOT NULL DEFAULT 'usd',
          status TEXT NOT NULL,
          metadata TEXT,
          created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        )"])

    ;; Create indexes for subscriptions
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_subscriptions_user_id ON subscriptions(user_id)"])
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_subscriptions_stripe_customer_id ON subscriptions(stripe_customer_id)"])
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_subscriptions_status ON subscriptions(status)"])

    ;; Create indexes for payments
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_payments_user_id ON payments(user_id)"])
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_payments_status ON payments(status)"])

    ;; Create trigger for subscriptions updated_at
    (jdbc/execute! ds
      ["CREATE TRIGGER IF NOT EXISTS update_subscriptions_updated_at
          AFTER UPDATE ON subscriptions
          FOR EACH ROW
        BEGIN
          UPDATE subscriptions SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
        END"])))

(defn save-template [template-data]
  (with-open [ds (get-datasource)]
    (let [uuid (:uuid template-data)
          username (:username template-data)
          nickname (:nickname template-data)
          template-json (cs/generate-string (:template template-data))]
      (log/info "Saving template for user:" username)
      (try (sql/insert! ds :templates
                        {:uuid     uuid
                         :username username
                         :nickname nickname
                         :template template-json})
           {:result "Success" :uuid uuid}
           (catch Exception e
             {:result "Failure" :uuid uuid})))))

(defn load-template [uuid]
  (with-open [ds (get-datasource)]
    (log/info "Loading template with id:" uuid)
    (when-let [template (sql/get-by-id ds :templates uuid :uuid {:builder-fn rs/as-unqualified-maps})]
      (sql/update! ds :templates {:last_opened_at "CURRENT_TIMESTAMP"
                                  :opened_count   (inc (:templates/opened_count template))}
                   {:uuid uuid})
      (-> template
          (update :template cs/parse-string true)
          (dissoc :id)))))

(defn create-user [clerk-id]
  (let [ds (get-datasource)]
    (log/info "Creating user with clerk-id:" clerk-id)
    (try
      (let [result (sql/insert! ds :users {:clerk_id clerk-id})]
        (-> result first :users/id))
      (catch Exception e
        (if (re-find #"UNIQUE constraint failed" (.getMessage e))
          (do
            (log/info "User with clerk-id already exists:" clerk-id)
            (-> (sql/find-by-keys ds :users {:clerk_id clerk-id})
                first
                :users/id))
          (throw e))))))

(defn get-user-by-clerk-id [clerk-id]
  (let [ds (get-datasource)]
    (sql/find-by-keys ds :users {:clerk_id clerk-id})))

(defn update-user-login [clerk-id]
  (let [ds (get-datasource)]
    (log/info "Updating login for user:" clerk-id)
    (jdbc/execute! ds
      ["UPDATE users SET
          last_login_datetime = CURRENT_TIMESTAMP,
          number_of_logins = number_of_logins + 1
        WHERE clerk_id = ?" clerk-id])))

(defn delete-user [clerk-id]
  (let [ds (get-datasource)]
    (log/info "Deleting user with clerk-id:" clerk-id)
    (sql/delete! ds :users {:clerk_id clerk-id})))
