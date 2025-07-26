# Implementation Plan

- [x] 1. Set up project structure and dependencies





  - Create project.clj with Ring, Compojure, SQLite, OAuth2, and testing dependencies
  - Set up basic directory structure for namespaces (core, routes, auth, db, templates, middleware)
  - Create development configuration files and environment setup
  - _Requirements: 4.3, 5.5_

- [x] 2. Implement database layer and user management




  - [x] 2.1 Create database initialization and schema setup


    - Write database connection utilities and SQLite schema creation
    - Implement database initialization function with users and sessions tables
    - Create database migration and setup functions
    - _Requirements: 4.1, 4.3_

  - [x] 2.2 Implement user data access layer


    - Write user CRUD operations (create, find-by-provider-id, update)
    - Implement session management functions (create, validate, cleanup)
    - Create database transaction utilities and error handling
    - Write unit tests for all database operations
    - _Requirements: 4.1, 4.2, 4.4_

- [x] 3. Create HTML templates and rendering system




  - [x] 3.1 Implement base template system with Hiccup


    - Create common layout template with HTML structure
    - Implement login page template with OAuth provider buttons
    - Create dashboard page template with user greeting and logout
    - _Requirements: 5.1, 5.2, 5.3_

  - [x] 3.2 Add template error handling and optimization


    - Implement template error handling and fallback mechanisms
    - Add template performance optimizations and caching
    - Write unit tests for template rendering with various data inputs
    - _Requirements: 5.4, 5.5_

- [x] 4. Implement OAuth2 authentication system




  - [x] 4.1 Create OAuth2 provider configurations and utilities


    - Implement Microsoft 365 OAuth2 configuration and flow initiation
    - Implement GitHub OAuth2 configuration and flow initiation
    - Create OAuth2 state generation and validation utilities
    - _Requirements: 1.2, 1.3_

  - [x] 4.2 Implement OAuth2 callback handling and user profile extraction


    - Write callback handlers for Microsoft 365 and GitHub OAuth2 responses
    - Implement access token exchange and user profile retrieval
    - Create user profile normalization from different OAuth providers
    - Write unit tests for OAuth2 flow components with mocked responses
    - _Requirements: 1.4, 1.5, 1.6_

- [x] 5. Create session management and authentication middleware





  - [x] 5.1 Implement session creation and validation


    - Write session creation logic after successful OAuth authentication
    - Implement session validation middleware for protected routes
    - Create session invalidation and cleanup functionality
    - _Requirements: 1.7, 3.1, 3.3, 4.4_

  - [x] 5.2 Create authentication middleware and route protection


    - Implement authentication checking middleware for protected routes
    - Create redirect logic for unauthenticated users to login page
    - Write middleware for automatic session cleanup and expiration
    - Write unit tests for session management and authentication middleware
    - _Requirements: 2.4, 3.4_

- [x] 6. Implement HTTP routes and request handlers





  - [x] 6.1 Create core application routes


    - Implement root route with authentication check and appropriate redirects
    - Create login page route that displays OAuth provider options
    - Implement OAuth initiation routes for Microsoft 365 and GitHub
    - _Requirements: 1.1, 1.2, 1.3_

  - [x] 6.2 Implement OAuth callback and dashboard routes


    - Create OAuth callback routes for both providers with error handling
    - Implement protected dashboard route with user greeting display
    - Create logout route with session invalidation and redirect
    - Write integration tests for all routes using ring-mock
    - _Requirements: 1.4, 1.7, 2.1, 2.2, 2.3, 3.1, 3.2_

- [x] 7. Add security middleware and CSRF protection





  - Create CSRF protection middleware for state-changing operations
  - Implement security headers middleware (HSTS, CSP, etc.)
  - Add input validation and sanitization for OAuth parameters
  - Write security tests for CSRF protection and session security
  - _Requirements: 1.4, 3.3_

- [x] 8. Create application entry point and server lifecycle





  - Implement main application entry point with server startup
  - Create application configuration loading and validation
  - Implement graceful server shutdown and resource cleanup
  - Write integration tests for complete authentication flows
  - _Requirements: 4.3, 5.5_

- [x] 9. Add comprehensive error handling and logging









  - Implement global error handling middleware for all error types
  - Create specific error handlers for OAuth and database errors
  - Add structured logging for authentication events and errors
  - Write tests for error scenarios and recovery mechanisms
  - _Requirements: 1.4, 4.3, 5.4_

- [x] 10. Create end-to-end integration tests





  - Write complete user authentication flow tests with mocked OAuth providers
  - Create tests for session persistence across multiple requests
  - Implement tests for logout functionality and session cleanup
  - Add performance tests for database operations and template rendering
  - _Requirements: 1.1-1.7, 2.1-2.4, 3.1-3.4, 4.1-4.4, 5.1-5.5_