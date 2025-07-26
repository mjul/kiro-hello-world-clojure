# Requirements Document

## Introduction

The GitHub OAuth authentication flow is failing because the session cookie is not being properly recognized after successful authentication. Users can authenticate successfully with GitHub, but when they are redirected to the dashboard, they get an "Unauthorized access attempt" error. The issue is in the OAuth callback handler where the session is being cleared after setting the session cookie, preventing proper session persistence.

## Requirements

### Requirement 1

**User Story:** As a user, I want to successfully log in with GitHub OAuth and be redirected to the dashboard without getting unauthorized errors, so that I can access the protected application features.

#### Acceptance Criteria

1. WHEN a user completes GitHub OAuth authentication THEN the system SHALL create a valid session and redirect to the dashboard
2. WHEN the dashboard route is accessed after OAuth success THEN the system SHALL recognize the session cookie and allow access
3. WHEN the session cookie is set in the OAuth callback THEN the system SHALL NOT clear the session data that enables cookie persistence

### Requirement 2

**User Story:** As a developer, I want the OAuth callback handler to properly manage session state, so that session cookies work correctly with Ring middleware.

#### Acceptance Criteria

1. WHEN the OAuth callback creates a session cookie THEN the system SHALL preserve necessary session data for Ring middleware
2. WHEN clearing OAuth state from session THEN the system SHALL only remove OAuth-specific data, not all session data
3. WHEN a session cookie is set THEN the system SHALL ensure it persists across requests to protected routes