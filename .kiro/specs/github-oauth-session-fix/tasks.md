# Implementation Plan

- [x] 1. Fix OAuth callback session management





  - Modify the OAuth callback handler in routes.clj to preserve session data while clearing OAuth state
  - Replace `(assoc :session {})` with `(update :session dissoc :oauth-state)` in the GitHub OAuth callback success path
  - Ensure the fix applies to both Microsoft and GitHub OAuth callback handlers for consistency
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3_

- [x] 2. Add unit tests for session state management





  - Write unit tests to verify that OAuth callback handlers preserve non-OAuth session data
  - Create tests to ensure OAuth state is properly removed from session after callback
  - Add tests to verify session cookies are correctly set and maintained across requests
  - _Requirements: 2.1, 2.2_

- [ ] 3. Create integration tests for complete OAuth flow
  - Write integration tests for the complete GitHub OAuth flow from initiation to dashboard access
  - Create tests to verify session persistence works correctly after OAuth callback
  - Add tests to ensure the fix doesn't break Microsoft OAuth flow
  - _Requirements: 1.1, 1.2, 1.3_

- [ ] 4. Manual testing and verification
  - Test the complete GitHub OAuth flow manually to verify the fix works
  - Verify that users can successfully access the dashboard after GitHub authentication
  - Test that session cookies persist correctly across multiple requests to protected routes
  - _Requirements: 1.1, 1.2, 1.3_