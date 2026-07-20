# Kipotify Media Storage

The database stores only relative paths to media files. Do not store binary audio data in PostgreSQL.

Recommended layout:

```text
server/media/audio/{artist-slug}/{release-slug}/{track-file}.mp3
server/media/lyrics/{artist-slug}/{release-slug}/{track-file}.lrc
server/media/images/artists/{artist-slug}.jpg
server/media/images/releases/{release-slug}.jpg
```

Playback artwork convention:

- Android playback should prefer embedded artwork from the audio file metadata.
- `tracks.artwork_source = 'embedded_audio'` documents that expectation.
- If embedded artwork is missing, use `releases.cover_image_path`.
- If the release cover is missing, use `artists.profile_image_path`.

The backend serves this directory at `/media/*`, so a stored path like `media/lyrics/taylor-swift/red/all-too-well.lrc` is available as `/media/lyrics/taylor-swift/red/all-too-well.lrc`.
