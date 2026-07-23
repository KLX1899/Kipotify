# Android architecture

The Android client follows Clean Architecture through Gradle module boundaries:

```
:app  ──────> :domain
  │              ▲
  └──> :data ────┘
```

- `:domain` is plain Kotlin. It owns business models, repository contracts, playback and
  connection abstractions, and use cases. It has no Android, Room, Retrofit, or serialization
  dependency.
- `:data` owns Room entities/DAOs, preferences, Retrofit DTOs, LAN discovery, mapping, and
  repository implementations. `DataModule` binds these implementations to domain contracts.
- `:app` owns feature-scoped Compose UI, UI state/events, the ViewModel, and Android media
  playback. The ViewModel depends only on domain use cases and interfaces.

New behavior should enter through a domain use case. Data sources should never be imported by
UI/ViewModel code, and data implementations should map transport or persistence models into
domain models before returning them.

## Dependency injection

Hilt is the application composition framework:

- `DataModule` provides the Room database and DAOs, settings, endpoint discovery, API service,
  and repository implementations.
- `ApplicationModule` provides domain use-case groups, embedded-artwork loading, and the
  process-scoped playback controller.
- `KipotifyApplication`, `MainActivity`, and `PlaybackService` are Hilt entry points.
- `KipotifyViewModel` is constructor-injected; UI code obtains it with `hiltViewModel()`.

Application and UI classes must not construct repositories, database objects, network clients,
or use cases directly.

## Presentation packages

```
ui/
├── MainActivity.kt              # Android entry point only
├── app/AppShell.kt              # top-level scaffold and feature routing
├── components/UiComponents.kt   # shared, domain-agnostic Compose components
├── feature/
│   ├── category/
│   ├── downloads/
│   ├── home/
│   ├── player/
│   ├── playlists/
│   ├── profile/
│   ├── search/
│   ├── social/
│   └── splash/
├── notification/
├── theme/
└── viewmodel/
```

Feature packages own their screens and feature-specific components. Cross-feature orchestration
belongs in `app`, while reusable presentation primitives belong in `components`.
