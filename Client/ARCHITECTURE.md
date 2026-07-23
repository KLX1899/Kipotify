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
  repository implementations. `KipotifyDataContainer` exposes only domain contracts.
- `:app` owns Compose UI, UI state/events, the ViewModel, Android media playback, and the
  application composition root. The ViewModel depends only on domain use cases and interfaces.

New behavior should enter through a domain use case. Data sources should never be imported by
UI/ViewModel code, and data implementations should map transport or persistence models into
domain models before returning them.
