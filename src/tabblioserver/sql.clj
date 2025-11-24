(ns tabblioserver.sql
  (:require [hikari-cp.core :as hikari]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]
            [clojure.tools.logging :as log]
            [cheshire.core :as cs]
            [tabblioserver.env :refer [env]]))

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
   :url                    (or (env :database-url) "jdbc:sqlite:tabblio.db")
   :register-mbeans        false
   ;; SQLite-specific optimizations
   :connection-init-sql    "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA cache_size=10000; PRAGMA temp_store=memory;"})

(defonce datasource (atom nil))

(defn init-db! []
  (when @datasource
    (try
      (.close @datasource)  ; Close old one first
      (catch Exception e
        (log/warn "Error closing existing datasource" e))))
  (log/info "Initializing database connection pool")
  (reset! datasource (hikari/make-datasource db-config)))

(defn close-db! []
  (when @datasource
    (.close @datasource)
    (reset! datasource nil)))

;(defn init-db! []
;  (when-not @datasource
;    (log/info "Initializing database connection pool")
;    (reset! datasource (hikari/make-datasource db-config))
;    ;(create-tables!)
;    ))

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
           content_hash INTEGER,  -- Add this
          created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
          modified_at DATETIME DEFAULT CURRENT_TIMESTAMP,
          last_opened_at DATETIME,
          opened_count INTEGER DEFAULT 0
        )"])

    (jdbc/execute! ds
                   ["CREATE TABLE IF NOT EXISTS user_templates (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          uuid TEXT NOT NULL,
          username TEXT NOT NULL,
          nickname TEXT,
          linked_at DATETIME DEFAULT CURRENT_TIMESTAMP
          )"])

    ;; Create indexes for users
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_users_clerk_id ON users(clerk_id)"])
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_users_creation_datetime ON users(creation_datetime)"])
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_users_last_login_datetime ON users(last_login_datetime)"])

    ;; Create indexes
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_templates_username ON templates(username)"])
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_templates_created_at ON templates(created_at)"])
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_templates_modified_at ON templates(modified_at)"])
    (jdbc/execute! ds ["CREATE INDEX IF NOT EXISTS idx_templates_content_hash ON templates(content_hash)"])
    ;; Create trigger for modified_at
    (jdbc/execute! ds
      ["CREATE TRIGGER IF NOT EXISTS update_templates_modified_at
          AFTER UPDATE ON templates
          FOR EACH ROW
        BEGIN
          UPDATE templates SET modified_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
        END"])))

(defn save-template [template-data]
  (with-open [conn (jdbc/get-connection (get-datasource))]
    (let [uuid (:tabblio/uuid template-data)
          username (:username template-data)
          nickname (:nickname template-data)
          template-json (pr-str template-data)
          content-hash (hash (dissoc template-data :tabblio/uuid :tabblio/last-saved-at))]
      (log/info "Saving template for user:" username)
      ;; Check if template with same hash already exists
      (if-let [existing (first (sql/find-by-keys conn :templates {:content_hash content-hash} {:builder-fn rs/as-unqualified-maps}))]
        (do
          (log/info "Duplicate template detected, skipping insert")
          {:result "Duplicate" :uuid uuid :existing-uuid (:uuid existing)})
        (try
          (sql/insert! conn :templates
                       {:uuid         uuid
                        :username     username
                        :nickname     nickname
                        :template     template-json
                        :content_hash content-hash})
          {:result "Success" :uuid uuid}
          (catch Exception e
            (log/error "Error saving template:" e)
            {:result "Failure" :uuid uuid :error (.getMessage e)}))))))

;(jdbc/execute! (get-datasource)
;               ["ALTER TABLE templates ADD COLUMN content_hash INTEGER"])
;
;;; Add the index
;(jdbc/execute! (get-datasource)
;               ["CREATE INDEX IF NOT EXISTS idx_templates_content_hash ON templates(content_hash)"])


(defn load-template
  [uuid]
  (with-open [conn (jdbc/get-connection (get-datasource))]
    (log/info "Loading template with id:" uuid)
    (when-let [template (sql/get-by-id conn :templates uuid :uuid {:builder-fn rs/as-unqualified-maps})]
      (sql/update! conn :templates {:last_opened_at "CURRENT_TIMESTAMP" :opened_count   (inc (:opened_count template))} {:uuid uuid})
      (-> (:template template)
          identity))))

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

(defn link-template [user-id uuid nickname]
  (let [ds (get-datasource)]
    (log/info "Linking template" uuid "to user:" user-id)
    (try
      (sql/insert! ds :user_templates
                   {:uuid     uuid
                    :username user-id
                    :nickname (or nickname "")})
      {:result "Success" :uuid uuid :user-id user-id}
      (catch Exception e
        (log/error "Error linking template:" e)
        {:result "Failure" :uuid uuid :error (.getMessage e)}))))

(defn unlink-template [user-id uuid]
  (let [ds (get-datasource)]
    (log/info "Unlinking template" uuid "from user:" user-id)
    (try
      (let [deleted (sql/delete! ds :user_templates {:uuid uuid :username user-id})]
        (if (pos? (first deleted))
          {:result "Success" :uuid uuid :user-id user-id}
          {:result "Not Found" :uuid uuid :user-id user-id}))
      (catch Exception e
        (log/error "Error unlinking template:" e)
        {:result "Failure" :uuid uuid :error (.getMessage e)}))))

(defn get-user-templates [user-id]
  (let [ds (get-datasource)]
    (log/info "Getting templates for user:" user-id)
    (try
      (jdbc/execute! ds
                     ["SELECT uuid, nickname, linked_at FROM user_templates WHERE username = ?" user-id]
                     {:builder-fn rs/as-unqualified-maps})
      (catch Exception e
        (log/error "Error getting user templates:" e)
        []))))


;; From Claude front end
;Your backend should handle both cases:
;
;// Make middleware optional - check auth but don't require it
;const optionalAuth = (req, res, next) => {
;                                          const token = req.headers.authorization?.replace('Bearer ', '')
;
;                                                                                            if (token) {
;                                                                                                        // Verify token and attach user info
;                                                                                                        try {
;                                                                                                             const verified = await clerkClient.verifyToken(token)
;                                                                                                             req.userId = verified.sub  // User is authenticated
;                                                                                                             } catch (err) {
;                                                                                                                            // Invalid token - treat as anonymous
;                                                                                                                            req.userId = null
;                                                                                                                            }
;                                                                                                        } else {
;                                                                                                                // No token - anonymous user
;                                                                                                                req.userId = null
;                                                                                                                }
;
;                                                                                            next()
;                                          }
;
;// Save template - works for both authenticated and anonymous
;app.post('/api/save-template', optionalAuth, async (req, res) => {
;                                                                  const template = req.body
;                                                                  const userId = req.userId  // null if anonymous, user ID if authenticated
;
;                                                                  // Save template
;                                                                  const savedTemplate = await db.templates.create({
;                                                                                                                   data: template,
;                                                                                                                   userId: userId,  // null for anonymous, user ID for authenticated
;                                                                                                                   uuid: generateUUID()
;                                                                                                                   })
;
;                                                                  res.json({ uuid: savedTemplate.uuid })
;                                                                  })
;
;Feature Differences:
;
;You can add features for authenticated users:
;
;// Load user's saved templates
;app.get('/api/my-templates', requireAuth, async (req, res) => {
;                                                               const templates = await db.templates.findMany({
;                                                                                                              where: { userId: req.userId }
;                                                                                                              })
;                                                               res.json({ templates })
;                                                               })
