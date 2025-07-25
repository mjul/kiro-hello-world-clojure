# Requirements Document

## Introduction

This project involves creating a small web application built with Clojure and (TODO: select web framework) that provides user authentication through Single Sign-On (SSO) with Microsoft 365 and GitHub accounts. The application features a simple login flow where users authenticate via their preferred SSO provider, then access a personalized dashboard displaying their username with a greeting message and logout functionality. The backend uses SQLite for data persistence and (TODO) templating engine for server-side rendering.

## Requirements

### Requirement 1

**User Story:** As a user, I want to log in using my Microsoft 365 or GitHub account, so that I can access the application without creating a separate account.

#### Acceptance Criteria

1. WHEN a user visits the application root URL THEN the system SHALL display a login page with Microsoft 365 and GitHub login options
2. WHEN a user clicks the Microsoft 365 login button THEN the system SHALL redirect to Microsoft's OAuth2 authorization endpoint
3. WHEN a user clicks the GitHub login button THEN the system SHALL redirect to GitHub's OAuth2 authorization endpoint
4. WHEN OAuth2 authorization is successful THEN the system SHALL receive an authorization code and exchange it for an access token
5. WHEN the access token is obtained THEN the system SHALL retrieve the user's profile information from the respective provider
6. IF the user does not exist in the database THEN the system SHALL create a new user record with the profile information
7. WHEN authentication is complete THEN the system SHALL create a session and redirect the user to their personal page

### Requirement 2

**User Story:** As an authenticated user, I want to see my personal page with a greeting, so that I know I'm successfully logged in and can see my identity.

#### Acceptance Criteria

1. WHEN an authenticated user accesses their personal page THEN the system SHALL display "hello {user_name}" where user_name is their actual username
2. WHEN the personal page loads THEN the system SHALL display the user's username prominently
3. WHEN the personal page loads THEN the system SHALL display a logout button
4. IF a user tries to access the personal page without authentication THEN the system SHALL redirect them to the login page

### Requirement 3

**User Story:** As an authenticated user, I want to log out of the application, so that I can securely end my session.

#### Acceptance Criteria

1. WHEN a user clicks the logout button THEN the system SHALL invalidate their session
2. WHEN logout is complete THEN the system SHALL redirect the user to the login page
3. WHEN a user is logged out THEN the system SHALL clear any authentication cookies or tokens
4. WHEN a logged-out user tries to access protected pages THEN the system SHALL redirect them to the login page

### Requirement 4

**User Story:** As a system administrator, I want user data to be persisted in a database, so that user sessions and profiles are maintained across application restarts.

#### Acceptance Criteria

1. WHEN a new user authenticates THEN the system SHALL store their profile information in the SQLite database
2. WHEN a user logs in THEN the system SHALL retrieve their existing profile from the database
3. WHEN the application starts THEN the system SHALL initialize the SQLite database with required tables if they don't exist
4. WHEN user sessions are created THEN the system SHALL store session data securely

### Requirement 5

**User Story:** As a developer, I want the web pages to be rendered using a templating system, so that the HTML can be generated dynamically with user data.

#### Acceptance Criteria

1. WHEN any page is requested THEN the system SHALL use (TODO) templating engine to render HTML
2. WHEN the login page is rendered THEN the system SHALL use a template to generate the HTML structure
3. WHEN the personal page is rendered THEN the system SHALL inject the user's data into the template
4. WHEN templates are processed THEN the system SHALL handle template errors gracefully
5. WHEN the application builds THEN the system SHALL compile templates at build time for performance