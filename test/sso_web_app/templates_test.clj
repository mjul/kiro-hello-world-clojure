(ns sso-web-app.templates-test
  "Tests for HTML template generation using Hiccup."
  (:require [clojure.test :refer :all]
            [sso-web-app.templates :as templates]))

(deftest test-layout-template
  (testing "Layout template generates proper HTML5 structure"
    (let [html (templates/layout "Test Page" [:h1 "Test Content"])]
      (is (string? html))
      (is (.contains html "<!DOCTYPE html>"))
      (is (.contains html "SSO Web App - Test Page"))
      (is (.contains html "Test Content")))))

(deftest test-login-page
  (testing "Login page template generates OAuth provider buttons"
    (let [html (templates/login-page)]
      (is (string? html))
      (is (.contains html "Welcome to SSO Web App"))
      (is (.contains html "/auth/microsoft"))
      (is (.contains html "/auth/github"))
      (is (.contains html "Login with Microsoft 365"))
      (is (.contains html "Login with GitHub"))))
  
  (testing "Login page template shows error messages"
    (let [html (templates/login-page :error "access_denied")]
      (is (.contains html "Access was denied")))))

(deftest test-dashboard-page
  (testing "Dashboard page template shows user greeting and logout"
    (let [user {:username "testuser" :provider "github" :email "test@example.com"}
          html (templates/dashboard-page user)]
      (is (string? html))
      (is (.contains html "Hello testuser!"))
      (is (.contains html "Github"))
      (is (.contains html "test@example.com"))
      (is (.contains html "/logout"))
      (is (.contains html "Logout")))))

(deftest test-error-page
  (testing "Error page template generates basic error content"
    (let [html (templates/error-page "Test Error" "This is a test error message")]
      (is (string? html))
      (is (.contains html "Test Error"))
      (is (.contains html "This is a test error message"))
      (is (.contains html "Return to Home")))))

(deftest test-template-caching
  (testing "Template caching works correctly"
    ;; Clear cache first
    (templates/clear-template-cache!)
    
    ;; First call should render and cache
    (let [user {:id "123" :username "testuser" :provider "github"}
          html1 (templates/dashboard-page-cached user)
          stats1 (templates/template-stats)]
      (is (string? html1))
      (is (= 1 (:cache-size stats1)))
      
      ;; Second call should use cache
      (let [html2 (templates/dashboard-page-cached user)
            stats2 (templates/template-stats)]
        (is (= html1 html2))
        (is (= 1 (:cache-size stats2)))))
    
    ;; Different user should create new cache entry
    (let [user2 {:id "456" :username "otheruser" :provider "microsoft"}
          html3 (templates/dashboard-page-cached user2)
          stats3 (templates/template-stats)]
      (is (string? html3))
      (is (= 2 (:cache-size stats3))))))

(deftest test-cached-login-page
  (testing "Cached login page works with different parameters"
    (templates/clear-template-cache!)
    
    ;; Test without error
    (let [html1 (templates/login-page-cached)
          stats1 (templates/template-stats)]
      (is (string? html1))
      (is (= 1 (:cache-size stats1))))
    
    ;; Test with error - should create separate cache entry
    (let [html2 (templates/login-page-cached :error "access_denied")
          stats2 (templates/template-stats)]
      (is (string? html2))
      (is (.contains html2 "Access was denied"))
      (is (= 2 (:cache-size stats2))))))

(deftest test-cache-management
  (testing "Cache can be cleared"
    (templates/clear-template-cache!)
    
    ;; Add some cached templates
    (templates/login-page-cached)
    (templates/dashboard-page-cached {:id "123" :username "test" :provider "github"})
    
    (let [stats-before (templates/template-stats)]
      (is (= 2 (:cache-size stats-before)))
      
      ;; Clear cache
      (templates/clear-template-cache!)
      
      (let [stats-after (templates/template-stats)]
        (is (= 0 (:cache-size stats-after)))))))

(deftest test-template-stats
  (testing "Template stats provide useful information"
    (templates/clear-template-cache!)
    
    (let [empty-stats (templates/template-stats)]
      (is (= 0 (:cache-size empty-stats)))
      (is (empty? (:cached-templates empty-stats))))
    
    ;; Add a cached template
    (templates/login-page-cached)
    
    (let [stats (templates/template-stats)]
      (is (= 1 (:cache-size stats)))
      (is (= 1 (count (:cached-templates stats)))))))