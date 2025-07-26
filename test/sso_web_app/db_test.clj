(ns sso-web-app.db-test
  (:require [clojure.test :refer :all]
            [sso-web-app.db :as db]
            [clojure.java.jdbc :as jdbc])
  (:import [java.io File]))

(def test-db-config
  {:classname "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname ":memory:"})

(defn with-test-db [test-fn]
  "Test fixture that provides an in-memory database."
  (binding [db/*db-config* test-db-config]
    (test-fn)))

(use-fixtures :each with-test-db)

(deftest test-database-initialization
  (testing "Database initialization runs without error"
    (is (true? (db/init-db!)))))

(deftest test-database-migration
  (testing "Database migration runs successfully"
    (is (true? (db/migrate-db!)))))

(deftest test-database-setup
  (testing "Complete database setup runs successfully"
    (is (true? (db/setup-db!)))))

;; User CRUD operation tests

(deftest test-user-crud-operations
  (testing "Create user"
    (db/init-db!)
    (let [user (db/create-user! "github" "123" "testuser" "test@example.com")]
      (is (not (nil? (:id user))))
      (is (= "github" (:provider user)))
      (is (= "123" (:provider_id user)))
      (is (= "testuser" (:username user)))
      (is (= "test@example.com" (:email user)))))
  
  (testing "Find user by provider ID"
    (db/init-db!)
    (let [created-user (db/create-user! "microsoft" "456" "msuser" "ms@example.com")
          found-user (db/find-user-by-provider-id "microsoft" "456")]
      (is (not (nil? found-user)))
      (is (= (:id created-user) (:id found-user)))
      (is (= "msuser" (:username found-user)))))
  
  (testing "Find user by ID"
    (db/init-db!)
    (let [created-user (db/create-user! "github" "789" "ghuser" "gh@example.com")
          found-user (db/find-user-by-id (:id created-user))]
      (is (not (nil? found-user)))
      (is (= (:id created-user) (:id found-user)))
      (is (= "ghuser" (:username found-user)))))
  
  (testing "Update user"
    (db/init-db!)
    (let [created-user (db/create-user! "github" "999" "oldname" "old@example.com")
          updated-count (db/update-user! (:id created-user) {:username "newname" :email "new@example.com"})
          updated-user (db/find-user-by-id (:id created-user))]
      (is (= 1 updated-count))
      (is (= "newname" (:username updated-user)))
      (is (= "new@example.com" (:email updated-user)))))
  
  (testing "Find non-existent user returns nil"
    (db/init-db!)
    (let [user (db/find-user-by-provider-id "nonexistent" "000")]
      (is (nil? user)))))

;; Session management tests

(deftest test-session-management
  (testing "Create and find session"
    (db/init-db!)
    (let [user (db/create-user! "github" "session-test" "sessionuser" "session@example.com")
          expires-at "2025-12-31T23:59:59Z"
          session (db/create-session! (:id user) expires-at)
          found-session (db/find-session (:session-id session))]
      (is (not (nil? (:session_id session))))
      (is (= (:id user) (:user_id session)))
      (is (= expires-at (:expires_at session)))
      (is (not (nil? found-session)))
      (is (= (:session_id session) (:session_id found-session)))))
  
  (testing "Validate valid session"
    (db/init-db!)
    (let [user (db/create-user! "github" "valid-session" "validuser" "valid@example.com")
          future-time "2025-12-31T23:59:59Z"
          session (db/create-session! (:id user) future-time)
          validated-user (db/validate-session (:session_id session))]
      (is (not (nil? validated-user)))
      (is (= (:id user) (:id validated-user)))))
  
  (testing "Validate expired session"
    (db/init-db!)
    (let [user (db/create-user! "github" "expired-session" "expireduser" "expired@example.com")
          past-time "2020-01-01T00:00:00Z"
          session (db/create-session! (:id user) past-time)
          validated-user (db/validate-session (:session_id session))]
      (is (nil? validated-user))))
  
  (testing "Delete session"
    (db/init-db!)
    (let [user (db/create-user! "github" "delete-session" "deleteuser" "delete@example.com")
          session (db/create-session! (:id user) "2025-12-31T23:59:59Z")
          delete-count (db/delete-session! (:session_id session))
          found-session (db/find-session (:session_id session))]
      (is (= 1 delete-count))
      (is (nil? found-session))))
  
  (testing "Cleanup expired sessions"
    (db/init-db!)
    (let [user (db/create-user! "github" "cleanup-test" "cleanupuser" "cleanup@example.com")]
      ;; Create one expired and one valid session
      (db/create-session! (:id user) "2020-01-01T00:00:00Z")  ; expired
      (db/create-session! (:id user) "2025-12-31T23:59:59Z")  ; valid
      (let [cleanup-count (db/cleanup-expired-sessions!)]
        (is (= 1 cleanup-count))))))

;; Transaction utility tests

(deftest test-transaction-utilities
  (testing "Successful transaction"
    (db/init-db!)
    (let [result (db/with-transaction
                   (fn [tx]
                     (jdbc/insert! tx :users {:id "tx-test-1" :provider "github" :provider_id "tx1" :username "txuser1" :email "tx1@example.com"})
                     (jdbc/insert! tx :users {:id "tx-test-2" :provider "github" :provider_id "tx2" :username "txuser2" :email "tx2@example.com"})
                     "success"))]
      (is (= "success" result))
      (is (not (nil? (db/find-user-by-id "tx-test-1"))))
      (is (not (nil? (db/find-user-by-id "tx-test-2"))))))
  
  (testing "Failed transaction rollback"
    (db/init-db!)
    (try
      (db/with-transaction
        (fn [tx]
          (jdbc/insert! tx :users {:id "rollback-test-1" :provider "github" :provider_id "rb1" :username "rbuser1" :email "rb1@example.com"})
          ;; This should cause a constraint violation (duplicate provider + provider_id)
          (jdbc/insert! tx :users {:id "rollback-test-2" :provider "github" :provider_id "rb1" :username "rbuser2" :email "rb2@example.com"})
          "should not reach here"))
      (catch Exception e
        ;; Expected to fail
        (is (instance? Exception e))))
    ;; Verify rollback - no users should exist
    (is (nil? (db/find-user-by-id "rollback-test-1")))
    (is (nil? (db/find-user-by-id "rollback-test-2")))))