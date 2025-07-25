# SSO Web App

A secure Single Sign-On (SSO) web application built with Clojure, supporting authentication via Microsoft 365 and GitHub OAuth2 providers.

## Kiro Review
This was generated with AWS Kiro. TODO....

This is part of an experiment with multiple version generated from the same specification:
 - [Rust version](https://github.com/mjul/kiro-hello-world-rust) 
 - [Go version](https://github.com/mjul/kiro-hello-world-go)
 - [TypeScript version](https://github.com/mjul/kiro-hello-world-typescript) 
 - [Clojure version](https://github.com/mjul/kiro-hello-world-clojure) 


## Features

- 🔐 **Secure OAuth2 Authentication** with Microsoft 365 and GitHub
- 🎨 **Modern Web Interface** with responsive design and Askama templates
- 🛡️ **Session Management** with secure HTTP-only cookies
- 🗄️ **SQLite Database** with automatic migrations
- 🔒 **CSRF Protection** for OAuth2 flows
- 📱 **Mobile-Friendly** responsive design
- ⚡ **Fast & Lightweight** built with Clojure
- 🧪 **Comprehensive Testing** with unit and integration tests

## Quick Start

### Prerequisites

- Java 11+ (for Clojure runtime)
- Leiningen or Clojure CLI tools
- Git

### 1. Clone the Repository

```bash
git clone <repository-url>
cd sso-web-app
```

### 2. Set Up Environment Variables

Copy the example environment file and configure your OAuth2 credentials:

```bash
cp .env.example .env
```

Edit `.env` with your OAuth2 application credentials:

```env
# Database
DATABASE_URL=sqlite:sso_app.db

# Microsoft OAuth2 (Azure AD)
MICROSOFT_CLIENT_ID=your_microsoft_client_id
MICROSOFT_CLIENT_SECRET=your_microsoft_client_secret

# GitHub OAuth2
GITHUB_CLIENT_ID=your_github_client_id
GITHUB_CLIENT_SECRET=your_github_client_secret

# Session Security
SESSION_SECRET=your_random_session_secret_key_here

# Application
BASE_URL=http://localhost:3000
```

### 3. Initialize Database (Optional)

Run the initialization script to set up your environment:

**On Windows (PowerShell):**
```powershell
.\scripts\init_db.ps1
```

**On Linux/macOS:**
```bash
./scripts/init_db.sh
```

### 4. Run the Application

**Development mode:**
```bash
lein run
```

**With debug logging:**
```bash
# Set logging level
export LOG_LEVEL=debug
lein run
```

**Production build:**
```bash
lein uberjar
java -jar target/uberjar/sso-web-app-*-standalone.jar
```

The application will be available at `http://localhost:3000`

## OAuth2 Setup

### Microsoft 365 / Azure AD

1. Go to [Azure Portal](https://portal.azure.com/)
2. Navigate to **Azure Active Directory** > **App registrations**
3. Click **New registration**
4. Configure:
   - **Name**: SSO Web App
   - **Redirect URI**: `http://localhost:3000/auth/callback/microsoft`
   - **Account types**: Accounts in any organizational directory and personal Microsoft accounts
5. Copy the **Application (client) ID** to `MICROSOFT_CLIENT_ID`
6. Go to **Certificates & secrets** > **New client secret**
7. Copy the secret value to `MICROSOFT_CLIENT_SECRET`

### GitHub OAuth App

1. Go to [GitHub Settings](https://github.com/settings/developers)
2. Click **OAuth Apps** > **New OAuth App**
3. Configure:
   - **Application name**: SSO Web App
   - **Homepage URL**: `http://localhost:3000`
   - **Authorization callback URL**: `http://localhost:3000/auth/callback/github`
4. Copy the **Client ID** to `GITHUB_CLIENT_ID`
5. Generate a **Client Secret** and copy to `GITHUB_CLIENT_SECRET`

## Configuration

### Environment Variables

| Variable | Description | Required | Default |
|----------|-------------|----------|---------|
| `DATABASE_URL` | SQLite database file path | No | `sqlite:sso_app.db` |
| `MICROSOFT_CLIENT_ID` | Microsoft OAuth2 client ID | Yes | - |
| `MICROSOFT_CLIENT_SECRET` | Microsoft OAuth2 client secret | Yes | - |
| `GITHUB_CLIENT_ID` | GitHub OAuth2 client ID | Yes | - |
| `GITHUB_CLIENT_SECRET` | GitHub OAuth2 client secret | Yes | - |
| `SESSION_SECRET` | Secret key for session encryption | Yes | - |
| `BASE_URL` | Application base URL for OAuth2 callbacks | No | `http://localhost:3000` |

### Session Secret Generation

Generate a secure session secret:

```bash
# Using OpenSSL
openssl rand -base64 32

# Using Python
python3 -c "import secrets; print(secrets.token_urlsafe(32))"

# Using Node.js
node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"
```

## Development

### Running Tests

```bash
# Run all tests
lein test

# Run specific test namespaces
lein test sso-web-app.core-test

# Run with detailed output
lein test :verbose
```

### Database Management

The application automatically creates and migrates the SQLite database on startup. To reset the database:

```bash
rm sso_app.db
lein run  # Will recreate and migrate
```

### Logging

Set the `LOG_LEVEL` environment variable to control logging levels:

```bash
# Debug level (default in development)
LOG_LEVEL=debug lein run

# Info level for production
LOG_LEVEL=info lein run

# Specific namespace logging
LOG_LEVEL=debug lein run
```

## Production Deployment

### Building for Production

```bash
# Build uberjar for production
lein uberjar

# The jar will be at ./target/uberjar/sso-web-app-*-standalone.jar
```

### Production Configuration

1. **Use HTTPS**: Update `BASE_URL` to use `https://`
2. **Secure Session Secret**: Use a strong, randomly generated secret
3. **Database**: Consider using a persistent volume for SQLite file
4. **Logging**: Set `LOG_LEVEL=info` or `LOG_LEVEL=warn`
5. **OAuth2 Redirects**: Update OAuth2 app configurations with production URLs

### Docker Deployment

Create a `Dockerfile`:

```dockerfile
FROM clojure:openjdk-11-lein as builder
WORKDIR /app
COPY project.clj .
COPY src ./src
COPY resources ./resources
RUN lein uberjar

FROM openjdk:11-jre-slim
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /app/target/uberjar/sso-web-app-*-standalone.jar ./app.jar
COPY --from=builder /app/resources ./resources
EXPOSE 3000
CMD ["java", "-jar", "app.jar"]
```

Build and run:

```bash
docker build -t sso-web-app .
docker run -p 3000:3000 --env-file .env sso-web-app
```

### Reverse Proxy (Nginx)

Example Nginx configuration:

```nginx
server {
    listen 80;
    server_name your-domain.com;
    
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

## API Endpoints

| Method | Path | Description | Authentication |
|--------|------|-------------|----------------|
| `GET` | `/` | Root - redirects based on auth status | Optional |
| `GET` | `/login` | Login page with OAuth2 buttons | None |
| `GET` | `/auth/microsoft` | Initiate Microsoft OAuth2 flow | None |
| `GET` | `/auth/github` | Initiate GitHub OAuth2 flow | None |
| `GET` | `/auth/callback/microsoft` | Microsoft OAuth2 callback | None |
| `GET` | `/auth/callback/github` | GitHub OAuth2 callback | None |
| `GET` | `/dashboard` | User dashboard | Required |
| `POST` | `/logout` | Logout and clear session | Required |

## Security Features

- **OAuth2 CSRF Protection**: State parameter validation
- **Secure Session Cookies**: HttpOnly, SameSite=Lax
- **SQL Injection Prevention**: Parameterized queries with SQLx
- **XSS Prevention**: Template escaping with Askama
- **Session Management**: Secure session storage and cleanup
- **Error Handling**: No sensitive information leakage

## Troubleshooting

### Common Issues

**"Configuration error" on startup**
- Check that all required environment variables are set
- Verify OAuth2 credentials are correct

**"Database error" during startup**
- Ensure the application has write permissions to the database directory
- Check disk space availability

**OAuth2 redirect errors**
- Verify redirect URIs match exactly in OAuth2 app configurations
- Check that `BASE_URL` is correctly set

**Session issues**
- Ensure `SESSION_SECRET` is set and consistent across restarts
- Check that cookies are enabled in the browser

### Debug Mode

Run with debug logging to troubleshoot issues:

```bash
LOG_LEVEL=debug lein run
```

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature-name`
3. Make your changes and add tests
4. Run tests: `lein test`
5. Commit your changes: `git commit -am 'Add feature'`
6. Push to the branch: `git push origin feature-name`
7. Submit a pull request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Architecture

### Technology Stack

- **Backend**: Clojure with Ring/Compojure web framework
- **Database**: SQLite with clojure.java.jdbc
- **Templates**: Hiccup or Selmer templating
- **Authentication**: OAuth2 with Microsoft Graph API and GitHub API
- **Session Management**: Ring session middleware
- **Testing**: Built-in Clojure testing with clojure.test

### Project Structure

```
sso-web-app/
├── src/
│   └── sso_web_app/
│       ├── core.clj         # Application entry point
│       ├── auth.clj         # OAuth2 authentication logic
│       ├── config.clj       # Configuration management
│       ├── database.clj     # Database models and repository
│       ├── handlers.clj     # HTTP route handlers
│       ├── models.clj       # Data models
│       └── session.clj      # Session management
├── resources/
│   ├── templates/           # HTML templates
│   │   ├── base.html       # Base template layout
│   │   ├── login.html      # Login page
│   │   └── dashboard.html  # User dashboard
│   └── migrations/         # Database migrations
│       └── 001_create_users_table.sql
├── test/                   # Tests
│   └── sso_web_app/
│       └── core_test.clj
├── project.clj            # Clojure dependencies
├── .env.example           # Environment variables template
└── README.md              # This file
```