# Backend Architecture

## Architecture Overview

The Kipotify backend is a layered Go service that exposes REST APIs and a WebSocket chat endpoint over `net/http`. It connects to PostgreSQL through `pgxpool`, runs SQL migrations on startup when enabled, loads seed data when enabled, and serves application features through a service layer backed by a repository interface.

High-level flow:

```text
Client
  |
  | HTTP / WebSocket
  v
transport/http or transport/ws
  |
  v
service.App
  |
  v
repository.Store
  |
  v
PostgreSQL
```

The backend is initialized in `server/cmd/server/main.go`:

1. Load configuration from environment variables.
2. Connect to PostgreSQL.
3. Run migrations if `RUN_MIGRATIONS=true`.
4. Run seed loading if `RUN_SEED=true`.
5. Create a PostgreSQL repository.
6. Create the application service.
7. Register HTTP and WebSocket routes.
8. Start the HTTP server and handle graceful shutdown.

## Main Backend Modules and Responsibilities

### `cmd/server`

The server entry point is responsible for process-level setup:

- Loads configuration with `config.Load()`.
- Creates a cancellable context for interrupt and `SIGTERM` handling.
- Opens the PostgreSQL pool.
- Runs migrations and seed data.
- Builds the service and HTTP router.
- Starts `http.Server` on the configured port.
- Performs graceful shutdown with a 10-second timeout.

### `internal/config`

Configuration is read from environment variables with defaults:

```text
PORT
DATABASE_URL
JWT_SECRET
JWT_ISSUER
JWT_TTL_HOURS
CORS_ORIGINS
RUN_MIGRATIONS
RUN_SEED
ALLOW_DEMO_AUTH
```

The config package also parses booleans, comma-separated CORS origins, and token TTL hours.

### `internal/database`

The database package handles:

- PostgreSQL pool creation and health checking.
- Migration discovery from `server/migrations/*.sql`.
- Migration tracking through the `schema_migrations` table.
- Seed loading from `server/seed/tracks.json`.
- Creation of demo users, artists, albums, tracks, playlists, follows, and notifications.

### `internal/domain`

The domain package contains API-facing structs:

- `User`
- `PublicUser`
- `Artist`
- `Album`
- `Track`
- `Playlist`
- `Notification`
- `Message`
- `Paged`
- filter and search result types

These structs define JSON names used by HTTP and WebSocket responses.

### `internal/repository`

The repository package defines the `Store` interface and a PostgreSQL implementation.

Responsibilities include:

- Creating and loading users.
- Updating profile settings and premium state.
- Listing and loading tracks, artists, albums, and playlists.
- Recording likes, plays, downloads, and recently played entries.
- Searching catalog, users, and playlists.
- Managing follows.
- Reading and updating notifications.
- Creating, listing, and updating chat messages.

The concrete implementation uses SQL queries against PostgreSQL and returns domain models.

### `internal/service`

The service layer contains business rules and delegates persistence to `repository.Store`.

Responsibilities include:

- Registering users.
- Logging users in.
- Hashing and checking passwords with bcrypt.
- Creating and validating JWTs.
- Validating user inputs.
- Enforcing premium-only download behavior.
- Normalizing settings defaults.
- Preventing invalid follow and message operations.

### `internal/transport/http`

The HTTP transport layer contains:

- chi router setup.
- CORS middleware.
- request ID, real IP, and recoverer middleware.
- auth middleware.
- request decoding.
- route handlers.
- JSON success and error responses.

Handlers are intentionally thin: they parse path/query/body input, call `service.App`, and write JSON responses.

### `internal/transport/ws`

The WebSocket layer manages realtime chat:

- Authenticates connections with `?token=<jwt>`.
- Upgrades HTTP requests to WebSocket connections.
- Tracks active clients by user ID.
- Sends direct events to connected users.
- Handles message sending, typing indicators, delivered receipts, and read receipts.
- Sends periodic ping frames and uses pong deadlines to detect stale connections.

## Request/Response Flow

### REST Request Flow

```text
HTTP request
  |
  v
chi router
  |
  v
CORS / request middleware
  |
  v
auth middleware for protected routes
  |
  v
handler
  |
  v
service.App method
  |
  v
repository.Store method
  |
  v
PostgreSQL query
  |
  v
domain model or error
  |
  v
JSON response
```

Protected routes use the `Authorization` header:

```text
Authorization: Bearer <jwt>
```

If no token is supplied and `ALLOW_DEMO_AUTH=true`, the request is executed as the seeded demo user:

```text
00000000-0000-4000-8000-000000000101
```

### WebSocket Flow

```text
GET /api/ws/chat?token=<jwt>
  |
  v
JWT validation
  |
  v
WebSocket upgrade
  |
  v
client registration by user ID
  |
  v
readPump handles incoming events
  |
  v
service.App updates data
  |
  v
hub sends events to sender and receiver
```

Example client event:

```json
{
  "type": "message.send",
  "payload": {
    "toUserId": "00000000-0000-4000-8000-000000000102",
    "clientMessageId": "local-123",
    "content": "Hello",
    "sharedTrackId": null
  }
}
```

## Database/Data Layer Structure

The schema is defined in `server/migrations/001_init.sql`.

Core entities:

- `users`: account data, password hash, premium fields, language, and theme.
- `artists`: artist profiles.
- `albums`: albums linked to artists.
- `tracks`: music catalog entries linked to artists and optionally albums.
- `playlists`: global, local, and user-owned playlists.
- `playlist_tracks`: many-to-many playlist membership.
- `liked_tracks`: user-track likes.
- `recently_played`: latest play timestamp per user-track pair.
- `downloads`: user-track download records.
- `follows`: user-to-user follow relationships.
- `notifications`: user notifications and read state.
- `messages`: chat messages, shared tracks, and delivery/read state.
- `schema_migrations`: applied migration records.

Important PostgreSQL features:

- `pgcrypto` provides `gen_random_uuid()`.
- `pg_trgm` supports trigram indexes for search.
- Foreign keys maintain relationships between users, tracks, playlists, and messages.
- Unique and primary key constraints prevent duplicate likes, downloads, follows, and playlist tracks.
- Indexes support catalog filters, search, feed-like ordering, notifications, follows, and conversations.

## Authentication and Authorization Flow

Authentication is implemented with bcrypt and JWT:

1. Registration validates name, email, and password length.
2. Passwords are hashed with bcrypt.
3. Login loads the user by email and compares the bcrypt hash.
4. Successful registration or login returns a signed JWT.
5. JWT claims include:
   - `sub`: user ID
   - `iss`: configured issuer
   - `iat`: issued-at timestamp
   - `exp`: expiration timestamp
6. Protected REST routes validate the bearer token in middleware.
7. WebSocket connections validate the token from the `token` query parameter.

Authorization currently includes:

- Premium check before recording a download.
- Playlist visibility filtering for public playlists or playlists owned by the current user.
- User-specific data access for likes, recently played tracks, downloads, settings, notifications, and messages.
- Prevention of following yourself.
- Demo-user fallback when `ALLOW_DEMO_AUTH=true`.

## API Routing Structure

Public routes:

```text
GET  /healthz
POST /api/auth/register
POST /api/auth/login
GET  /api/ws/chat?token=<jwt>
```

Protected music routes:

```text
GET  /api/tracks
GET  /api/tracks/{id}
POST /api/tracks/{id}/like
POST /api/tracks/{id}/download
POST /api/tracks/{id}/play
GET  /api/v1/tracks
GET  /api/artists
GET  /api/albums
GET  /api/playlists
POST /api/playlists
GET  /api/playlists/{id}/tracks
POST /api/playlists/{id}/tracks
GET  /api/search
GET  /api/downloads/eligibility/{trackId}
```

Protected user routes:

```text
GET  /api/user/profile
POST /api/user/premium/upgrade
GET  /api/user/settings
PUT  /api/user/settings
GET  /api/user/liked-songs
GET  /api/user/recently-played
GET  /api/user/notifications
POST /api/user/notifications/{id}/read
```

Protected social and chat routes:

```text
GET  /api/social/friends
GET  /api/social/users
POST /api/social/friends/{id}/follow
GET  /api/social/followed-playlists
GET  /api/social/chat/{friendId}/messages
POST /api/social/chat/{friendId}/messages
```

Common query parameters:

- `page`
- `limit`
- `search`
- `genre`
- `locale`
- `section`
- `artist_id`
- `category`
- `mine`
- `followed`
- `q`
- `type`
- `before`

## Service, Controller, and Model Responsibilities

This project uses handlers instead of controller classes, but the responsibilities map cleanly:

- Handlers/controllers:
  - Own HTTP concerns.
  - Read URL params, query params, and JSON bodies.
  - Call service methods.
  - Convert results and errors into JSON.

- Services:
  - Own business rules.
  - Validate input.
  - Create and validate JWTs.
  - Enforce premium and settings rules.
  - Coordinate repository operations.

- Repositories:
  - Own SQL and persistence concerns.
  - Convert rows into domain models.
  - Handle transactions for operations such as toggling likes, downloads, and play recording.

- Models:
  - Live in `internal/domain`.
  - Define response shapes and shared data structures.

## Seed Data Usage

Seeding is controlled by:

```env
RUN_SEED=true
```

The seed loader reads:

```text
server/seed/tracks.json
```

Seed behavior:

- Requires at least 50 tracks.
- Creates deterministic artist and album UUIDs from seed names.
- Upserts demo users and music catalog data.
- Inserts global, local, and user playlists.
- Assigns tracks to seeded playlists.
- Inserts sample follows and notifications.

Seeded demo credentials:

```text
Email: demo@kipotify.local
Password: password123
```

Additional seeded users use the same password and local emails such as:

```text
mahan@kipotify.local
saba@kipotify.local
sara@kipotify.local
```

## Error Handling Approach

HTTP errors are centralized in `internal/transport/http/response.go`.

Known application errors map to specific HTTP statuses:

- `service.ErrValidation` -> `400 validation_error`
- `service.ErrInvalidCredentials` -> `401 invalid_credentials`
- `service.ErrPremiumRequired` -> `403 premium_required`
- `repository.ErrNotFound` -> `404 not_found`
- unrecognized errors -> `500 internal_error`

Error responses use this shape:

```json
{
  "error": {
    "code": "validation_error",
    "message": "validation failed"
  }
}
```

WebSocket errors are sent as events:

```json
{
  "type": "error",
  "payload": {
    "code": "unknown_event",
    "message": "unsupported websocket event"
  }
}
```

## Configuration and Environment Variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | HTTP server port |
| `DATABASE_URL` | `postgres://kipotify:kipotify@localhost:5432/kipotify?sslmode=disable` | PostgreSQL connection string |
| `JWT_SECRET` | `change-me-in-production` | HMAC signing secret for JWTs |
| `JWT_ISSUER` | `kipotify` | Expected token issuer |
| `JWT_TTL_HOURS` | `168` | Token lifetime in hours |
| `CORS_ORIGINS` | `*` | Comma-separated allowed origins |
| `RUN_MIGRATIONS` | `true` | Run SQL migrations on startup |
| `RUN_SEED` | `true` | Load seed data on startup |
| `ALLOW_DEMO_AUTH` | `true` | Allow missing bearer tokens to use the demo user |

Production deployments should set a strong `JWT_SECRET`, restrict `CORS_ORIGINS`, and disable `ALLOW_DEMO_AUTH`.

## Extending the Backend

Recommended extension path:

1. Add or update domain models in `internal/domain`.
2. Add persistence methods to `repository.Store`.
3. Implement SQL in `internal/repository`.
4. Add business rules to `internal/service`.
5. Add HTTP handlers and routes in `internal/transport/http`.
6. Add WebSocket events in `internal/transport/ws` if realtime behavior is needed.
7. Add a migration in `server/migrations` for schema changes.
8. Add seed updates in `server/seed` or `internal/database` when sample data is needed.
9. Add focused tests for service behavior and HTTP routes.

Good future additions include:

- Refresh tokens or token revocation.
- Role-based authorization.
- Playlist ownership checks for editing and deleting playlists.
- More complete media storage integration.
- Structured logging around requests and database errors.
- OpenAPI documentation.
- Integration tests using a temporary PostgreSQL database.
