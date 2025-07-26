# SSO Web Application

A Clojure web application that provides Single Sign-On authentication with Microsoft 365 and GitHub.

## Features

- OAuth2 authentication with Microsoft 365 and GitHub
- User session management
- SQLite database for user persistence
- Server-side rendered HTML with Hiccup templates
- Secure session handling with CSRF protection

## Development Setup

### Prerequisites

- Java 8 or higher
- Leiningen 2.0 or higher

### Configuration

1. Copy `profiles.clj` and update the OAuth client credentials:
   - `microsoft-client-id` and `microsoft-client-secret`
   - `github-client-id` and `github-client-secret`

2. Register OAuth applications:
   - **Microsoft 365**: Register at [Azure App Registrations](https://portal.azure.com/#blade/Microsoft_AAD_RegisteredApps)
     - Redirect URI: `http://localhost:3000/auth/microsoft/callback`
   - **GitHub**: Register at [GitHub Developer Settings](https://github.com/settings/developers)
     - Authorization callback URL: `http://localhost:3000/auth/callback/github`

### Running the Application

```bash
# Install dependencies
lein deps

# Start the development server
lein repl
user=> (start)

# Or run directly
lein run
```

The application will be available at `http://localhost:3000`.

### Development REPL

```clojure
;; Start the server
(start)

;; Stop the server
(stop)

;; Restart with code changes
(restart)
```

### Testing

```bash
# Run all tests
lein test

# Run tests with auto-reload
lein test-refresh
```

## Project Structure

```
src/sso_web_app/
├── core.clj        # Application entry point and server lifecycle
├── routes.clj      # HTTP route definitions
├── auth.clj        # OAuth2 authentication logic
├── db.clj          # Database operations
├── templates.clj   # HTML template generation
└── middleware.clj  # Custom middleware

dev/
└── user.clj        # Development utilities

resources/
└── logback.xml     # Logging configuration
```

## Environment Variables

- `DATABASE_URL`: SQLite database path (default: `jdbc:sqlite:dev-database.db`)
- `MICROSOFT_CLIENT_ID`: Microsoft OAuth2 client ID
- `MICROSOFT_CLIENT_SECRET`: Microsoft OAuth2 client secret
- `GITHUB_CLIENT_ID`: GitHub OAuth2 client ID
- `GITHUB_CLIENT_SECRET`: GitHub OAuth2 client secret
- `SESSION_SECRET`: Secret key for session encryption
- `PORT`: Server port (default: 3000)
- `BASE_URL`: Base URL for OAuth callbacks (default: `http://localhost:3000`)