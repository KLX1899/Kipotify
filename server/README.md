# Kipotify

Kipotify is a music streaming project with an Android client and a Go backend. The backend provides user authentication, music catalog APIs, playlists, likes, listening history, premium-gated downloads, notifications, social following, and chat with REST and WebSocket interfaces backed by PostgreSQL.

## Table of Contents

- [Quick Start](#quick-start)
- [Backend Overview](#backend-overview)
- [Backend Functionality](#backend-functionality)
- [Backend Technologies](#backend-technologies)
- [Project Structure](#project-structure)
- [API Overview](#api-overview)
- [Seed Data and Database Notes](#seed-data-and-database-notes)
- [Development Notes](#development-notes)

## Quick Start

### Installation

Required tools:

- Go 1.26 or newer
- Docker and Docker Compose
- Android Studio or the Android Gradle toolchain for the client

Install backend dependencies:

```bash
cd server
go mod download
```

### Environment

The Docker Compose quick start works without a local `.env` file. For local overrides, copy the template:

```bash
cp .env.example .env
```

Default backend environment variables:

```env
PORT=18080
DATABASE_URL=postgres://kipotify:kipotify@localhost:15432/kipotify?sslmode=disable
JWT_SECRET=replace-with-a-long-random-secret
JWT_ISSUER=kipotify
JWT_TTL_HOURS=168
CORS_ORIGINS=*
RUN_MIGRATIONS=true
RUN_SEED=true
ALLOW_DEMO_AUTH=true
```

### Running the Backend

Run PostgreSQL and the backend together with Docker Compose:

```bash
cd server
docker compose up --build
```

Or run PostgreSQL with Docker Compose and start the Go server locally:

```bash
cd server
docker compose up -d postgres
PORT=18080 DATABASE_URL=postgres://kipotify:kipotify@localhost:15432/kipotify?sslmode=disable go run ./cmd/server
```

The Docker quick start publishes the backend at `http://localhost:18080`. Health check:

```bash
curl http://localhost:18080/healthz
```

### Wireless Android discovery

The backend advertises `_kipotify._tcp.local` with mDNS/DNS-SD. The Android app looks for this
service on launch, resolves its LAN IP address and advertised port, then activates it only after
`/healthz` succeeds. It retains the last healthy LAN endpoint and falls back to the configured
Android build URLs if discovery is unavailable.

For production, configure `TLS_CERT_FILE` and `TLS_KEY_FILE`. The advertised service will then
use `scheme=https`; the certificate must be trusted by Android and include the backend's LAN IP
as an IP subject-alternative name because clients connect to the resolved IP address. mDNS is not
an authentication mechanism, so the Android client rejects HTTP LAN advertisements by default.

For a trusted development LAN only, set both `MDNS_ADVERTISE_INSECURE=true` on the backend and
`KIPOTIFY_ALLOW_INSECURE_LAN=true` in `Client/gradle.properties`. Do not enable either setting in
a production build. Docker bridge networking generally cannot multicast onto the host LAN; run the
backend on the host or use host networking when testing mDNS.

The server watches its active LAN interfaces and recreates the advertisement when the default-route
interface or its private IPv4 addresses change. Virtual, VPN, bridge, link-local, and disconnected
interfaces are ignored. If a non-standard interface is intentionally required, set
`MDNS_INTERFACE` to its exact name; this is an explicit override, not a recommended default.

### Running the Project

Start the backend first, then run the Android client from Android Studio or with Gradle:

```bash
cd Client
./gradlew assembleDebug
```

The Android app communicates with the backend through Retrofit interfaces defined in `Client/app/src/main/java/com/example/data/remote/KipotifyApiService.kt`.

## Backend Overview

The backend is a Go HTTP service located in `server/`. It uses a layered structure:

- `cmd/server`: application entry point, configuration loading, database connection, migrations, seeding, HTTP server startup, and graceful shutdown.
- `internal/config`: environment variable loading and default values.
- `internal/database`: PostgreSQL connection pooling, SQL migration execution, and seed loading.
- `internal/domain`: API-facing domain models.
- `internal/repository`: PostgreSQL data access implementation.
- `internal/service`: application business logic.
- `internal/transport/http`: REST routing, middleware, request decoding, and response formatting.
- `internal/transport/ws`: WebSocket chat hub and realtime message events.

## Backend Functionality

The backend currently supports:

- User registration and login with bcrypt password hashing.
- JWT access tokens with configurable issuer, secret, and TTL.
- Optional demo authentication for protected REST routes when no bearer token is supplied.
- Music catalog browsing with pagination and filters for search, genre, locale, section, and artist.
- Track details, likes, play tracking, recently played tracks, and premium-only download tracking.
- Artist, album, playlist, and playlist track APIs.
- Public, local, global, and user playlist support.
- Search across tracks, artists, users, and playlists.
- User profile, premium upgrade, settings, liked songs, recently played tracks, and notifications.
- Social user discovery, following/unfollowing, followed playlists, and friend-compatible user lists.
- Chat messages over REST.
- Realtime chat, typing, delivery receipts, and read receipts over WebSocket.

## Backend Technologies

- Go 1.26
- `net/http`
- `github.com/go-chi/chi/v5` for HTTP routing
- `github.com/go-chi/cors` for CORS middleware
- `github.com/jackc/pgx/v5` and `pgxpool` for PostgreSQL access
- `github.com/golang-jwt/jwt/v5` for JWT handling
- `golang.org/x/crypto/bcrypt` for password hashing
- `github.com/gorilla/websocket` for realtime chat
- PostgreSQL 17 in Docker Compose
- SQL migrations stored in `server/migrations`
- JSON seed data stored in `server/seed/tracks.json`

## Project Structure

```text
.
├── Client/
│   └── app/src/main/java/com/example/
│       ├── data/
│       ├── playback/
│       └── ui/
├── Docs/
│   ├── Project.pdf
│   └── backend-architecture.md
├── server/
│   ├── cmd/server/main.go
│   ├── internal/
│   │   ├── config/
│   │   ├── database/
│   │   ├── domain/
│   │   ├── repository/
│   │   ├── service/
│   │   └── transport/
│   │       ├── http/
│   │       └── ws/
│   ├── migrations/
│   ├── seed/
│   ├── Dockerfile
│   ├── docker-compose.yml
│   ├── go.mod
│   └── go.sum
├── README.md
├── readme.me
└── LICENSE
```

## API Overview

Public endpoints:

- `GET /healthz`
- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/ws/chat?token=<jwt>`

Protected REST endpoints:

- `GET /api/tracks`
- `GET /api/tracks/{id}`
- `POST /api/tracks/{id}/like`
- `POST /api/tracks/{id}/download`
- `POST /api/tracks/{id}/play`
- `GET /api/v1/tracks`
- `GET /api/artists`
- `GET /api/albums`
- `GET /api/playlists`
- `POST /api/playlists`
- `GET /api/playlists/{id}/tracks`
- `POST /api/playlists/{id}/tracks`
- `GET /api/search`
- `GET /api/downloads/eligibility/{trackId}`
- `GET /api/user/profile`
- `POST /api/user/premium/upgrade`
- `GET /api/user/settings`
- `PUT /api/user/settings`
- `GET /api/user/liked-songs`
- `GET /api/user/recently-played`
- `GET /api/user/notifications`
- `POST /api/user/notifications/{id}/read`
- `GET /api/social/friends`
- `GET /api/social/users`
- `POST /api/social/friends/{id}/follow`
- `GET /api/social/followed-playlists`
- `GET /api/social/chat/{friendId}/messages`
- `POST /api/social/chat/{friendId}/messages`

Protected REST routes use an `Authorization: Bearer <token>` header unless `ALLOW_DEMO_AUTH=true`, in which case missing bearer tokens use the seeded demo user.

WebSocket chat endpoint:

```text
GET /api/ws/chat?token=<jwt>
```

Supported WebSocket event types include:

- `message.send`
- `receipt.delivered`
- `receipt.read`
- `typing`

Server event types include:

- `message.sent`
- `message.created`
- `message.delivered`
- `message.read`
- `typing`
- `error`

## Seed Data and Database Notes

The database schema is created by SQL files in `server/migrations`. It enables `pgcrypto` for UUID generation and `pg_trgm` for trigram indexes used by search.

Main tables:

- `users`
- `artists`
- `releases`
- `tracks`
- `track_artists`
- `playlists`
- `playlist_tracks`
- `liked_tracks`
- `recently_played`
- `downloads`
- `follows`
- `notifications`
- `messages`
- `schema_migrations`

Music catalog shape:

- `artists` stores artist profiles: `name`, `slug`, short `bio`, `profile_image_path`, optional `country`, optional `birth_date`, optional `active_years`, and timestamps.
- `releases` stores albums, singles, and EPs using `release_type in ('album','single','ep')`, linked to a primary artist.
- `tracks` stores songs and relative media paths: `audio_file_path`, optional `lyrics_file_path`, optional `release_id`, track/disc numbers, duration, release date, explicit flag, and `artwork_source`.
- `track_artists` stores many-to-many credits with `role in ('primary','featured','producer','writer')`.

Artwork convention:

- Playback artwork should be read from embedded audio metadata when `tracks.artwork_source = 'embedded_audio'`.
- API fallback artwork comes from `releases.cover_image_path`, then `artists.profile_image_path`.
- No binary audio is stored in the database.

Media files use relative paths under `server/media`:

```text
server/media/audio/{artist-slug}/{release-slug}/{track-file}.mp3
server/media/lyrics/{artist-slug}/{release-slug}/{track-file}.lrc
server/media/images/artists/{artist-slug}.jpg
server/media/images/releases/{release-slug}.jpg
```

The server exposes those files at `/media/*`.

When `RUN_SEED=true`, the backend reads `server/seed/tracks.json`. The seed process requires at least 50 tracks and creates:

- Demo users, including `demo@kipotify.local`.
- Seeded artists, releases, tracks, and artist credits.
- Global, local, and user playlists.
- Playlist-track relationships.
- Demo follow relationships.
- Demo notifications.

Seeded users use the password:

```text
password123
```

## Development Notes

- Run backend commands from the `server/` directory because migrations and seed files are loaded from relative paths.
- The migration runner records applied files in `schema_migrations` and skips previously applied migrations.
- `RUN_MIGRATIONS` and `RUN_SEED` default to `true`.
- `ALLOW_DEMO_AUTH` defaults to `true`, which is convenient for local development but should be disabled outside development.
- Replace `JWT_SECRET` with a strong secret before deploying.
- Tests are present for the HTTP and service layers:

```bash
cd server
go test ./...
```

- The repository layer is behind the `repository.Store` interface, which makes service tests easier to write with a fake or test implementation.
- Pagination responses use the shared `domain.Paged` shape with `data` and `page` fields.
