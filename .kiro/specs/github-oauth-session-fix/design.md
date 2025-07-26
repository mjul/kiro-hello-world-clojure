# Design Document

## Overview

The GitHub OAuth authentication flow is failing due to improper session management in the OAuth callback handler. The issue occurs when the callback handler clears the entire session data (`(assoc :session {})`) after setting the session cookie, which interferes with Ring's session middleware and prevents the session cookie from being properly persisted.

The current flow:
1. User authenticates with GitHub ✓
2. OAuth callback creates session in database ✓
3. Session cookie is added to response ✓
4. **PROBLEM**: Entire session is cleared, breaking cookie persistence ✗
5. User is redirected to dashboard but session cookie is not recognized ✗

## Architecture

The fix involves modifying the OAuth callback handler to properly manage session state without interfering with Ring's session middleware. The solution maintains the security of clearing OAuth-specific state while preserving the session data needed for cookie persistence.

### Current Architecture Issues

```clojure
;; PROBLEMATIC CODE in routes.clj
(-> (response/redirect "/dashboard")
    (middleware/add-session-cookie (:session_id session))
    ;; This clears ALL session data, breaking Ring middleware
    (assoc :session {}))
```

### Proposed Architecture

```clojure
;; FIXED CODE - only clear OAuth-specific data
(-> (response/redirect "/dashboard")
    (middleware/add-session-cookie (:session_id session))
    ;; Only remove OAuth state, preserve other session data
    (update :session dissoc :oauth-state))
```

## Components and Interfaces

### Modified Components

1. **OAuth Callback Handler** (`src/sso_web_app/routes.clj`)
   - Function: `oauth-callback-handler`
   - Change: Replace `(assoc :session {})` with `(update :session dissoc :oauth-state)`
   - Impact: Preserves session data needed for Ring middleware while clearing OAuth state

### Unchanged Components

1. **Session Management** (`src/sso_web_app/middleware.clj`)
   - All session validation and cookie handling functions remain unchanged
   - The middleware already correctly handles session cookies when session data is preserved

2. **Database Layer** (`src/sso_web_app/db.clj`)
   - Session creation and validation functions work correctly
   - No changes needed to database operations

3. **Authentication Module** (`src/sso_web_app/auth.clj`)
   - OAuth flow and token exchange work correctly
   - No changes needed to authentication logic

## Data Models

No changes to data models are required. The existing session and user models are working correctly:

```clojure
;; Session model (unchanged)
{:session_id "uuid"
 :user_id "uuid" 
 :expires_at "ISO-8601-timestamp"}

;; User model (unchanged)
{:id "uuid"
 :provider "github"
 :provider_id "github-user-id"
 :username "display-name"
 :email "user@example.com"}
```

## Error Handling

The current error handling is adequate and will continue to work with the fix:

1. **OAuth Errors**: Already properly handled in callback handler
2. **Session Errors**: Already properly handled in middleware
3. **Database Errors**: Already properly handled in database layer

The fix actually improves error handling by ensuring session cookies work correctly, reducing the likelihood of "unauthorized access" errors after successful authentication.

## Testing Strategy

### Unit Tests
- Test that OAuth callback handler preserves non-OAuth session data
- Test that OAuth state is properly removed from session
- Test that session cookies are correctly set and persist

### Integration Tests
- Test complete GitHub OAuth flow from initiation to dashboard access
- Test that session cookies work across multiple requests
- Test that other OAuth providers (Microsoft) are not affected

### Manual Testing
- Complete GitHub OAuth flow and verify dashboard access works
- Verify session persistence across browser requests
- Test logout functionality still works correctly

## Implementation Approach

The fix is minimal and surgical:

1. **Single Line Change**: Replace `(assoc :session {})` with `(update :session dissoc :oauth-state)`
2. **Low Risk**: Only affects session state management, not core OAuth logic
3. **Backward Compatible**: Does not change any APIs or data structures
4. **Immediate Effect**: Fix takes effect immediately upon deployment

## Security Considerations

The fix maintains security while improving functionality:

1. **OAuth State Cleanup**: Still removes OAuth state to prevent replay attacks
2. **Session Security**: Preserves existing session security mechanisms
3. **Cookie Security**: Maintains existing cookie security attributes
4. **No New Attack Vectors**: Does not introduce any new security risks

## Performance Impact

The change has minimal performance impact:

1. **Memory**: Slightly less memory usage (preserves existing session vs creating new empty one)
2. **CPU**: Negligible difference in processing
3. **Network**: No change in network traffic
4. **Database**: No change in database operations