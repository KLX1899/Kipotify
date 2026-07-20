package database

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/crypto/bcrypt"
)

func Connect(ctx context.Context, databaseURL string) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, err
	}
	cfg.MaxConns = 10
	cfg.MinConns = 1
	cfg.MaxConnLifetime = time.Hour
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, err
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, err
	}
	return pool, nil
}

func Migrate(ctx context.Context, pool *pgxpool.Pool) error {
	if _, err := pool.Exec(ctx, `create table if not exists schema_migrations (version text primary key, applied_at timestamptz not null default now())`); err != nil {
		return err
	}
	entries, err := os.ReadDir("migrations")
	if err != nil {
		return err
	}
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".sql") {
			names = append(names, entry.Name())
		}
	}
	sort.Strings(names)
	for _, name := range names {
		var exists bool
		if err := pool.QueryRow(ctx, `select exists(select 1 from schema_migrations where version=$1)`, name).Scan(&exists); err != nil {
			return err
		}
		if exists {
			continue
		}
		body, err := os.ReadFile(filepath.Join("migrations", name))
		if err != nil {
			return err
		}
		tx, err := pool.Begin(ctx)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, string(body)); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("migration %s: %w", name, err)
		}
		if _, err := tx.Exec(ctx, `insert into schema_migrations(version) values($1)`, name); err != nil {
			_ = tx.Rollback(ctx)
			return err
		}
		if err := tx.Commit(ctx); err != nil {
			return err
		}
		slog.Info("applied migration", "version", name)
	}
	return nil
}

type seedTrack struct {
	ID         string `json:"id"`
	Title      string `json:"title"`
	ArtistName string `json:"artist_name"`
	AlbumTitle string `json:"album_title"`
	Duration   int    `json:"duration"`
	Lyric      string `json:"lyric"`
}

type seedCatalog struct {
	Artists  []seedArtist       `json:"artists"`
	Releases []seedRelease      `json:"releases"`
	Tracks   []seedCatalogTrack `json:"tracks"`
}

type seedArtist struct {
	ID               string `json:"id"`
	Name             string `json:"name"`
	Slug             string `json:"slug"`
	Bio              string `json:"bio"`
	ProfileImagePath string `json:"profile_image_path"`
	Country          string `json:"country"`
	BirthDate        string `json:"birth_date"`
	ActiveYears      string `json:"active_years"`
}

type seedRelease struct {
	ID                string             `json:"id"`
	Title             string             `json:"title"`
	Slug              string             `json:"slug"`
	ReleaseType       string             `json:"release_type"`
	ReleaseDate       string             `json:"release_date"`
	PrimaryArtistSlug string             `json:"primary_artist_slug"`
	CoverImagePath    string             `json:"cover_image_path"`
	Tracks            []seedCatalogTrack `json:"tracks"`
}

type seedCatalogTrack struct {
	ID                string       `json:"id"`
	Title             string       `json:"title"`
	Slug              string       `json:"slug"`
	PrimaryArtistSlug string       `json:"primary_artist_slug"`
	ReleaseSlug       string       `json:"release_slug"`
	TrackNumber       int          `json:"track_number"`
	DiscNumber        int          `json:"disc_number"`
	Duration          int          `json:"duration"`
	AudioFilePath     string       `json:"audio_file_path"`
	LyricsFilePath    string       `json:"lyrics_file_path"`
	ReleaseDate       string       `json:"release_date"`
	Explicit          bool         `json:"explicit"`
	ArtworkSource     string       `json:"artwork_source"`
	Lyric             string       `json:"lyric"`
	FeaturedArtists   []string     `json:"featured_artists"`
	Credits           []seedCredit `json:"credits"`
}

type seedCredit struct {
	ArtistSlug string `json:"artist_slug"`
	Name       string `json:"name"`
	Role       string `json:"role"`
	Position   int    `json:"position"`
}

func Seed(ctx context.Context, pool *pgxpool.Pool) error {
	body, err := os.ReadFile("seed/tracks.json")
	if err != nil {
		return err
	}
	catalog, tracks, err := parseSeedCatalog(body)
	if err != nil {
		return err
	}
	if len(tracks) < 50 {
		return fmt.Errorf("seed must include at least 50 tracks, got %d", len(tracks))
	}

	tx, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	password, err := bcrypt.GenerateFromPassword([]byte("password123"), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	users := []struct {
		ID, Name, Email, Avatar string
		Premium                 bool
	}{
		{"00000000-0000-4000-8000-000000000101", "Kipotify Demo", "demo@kipotify.local", "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?w=300", true},
		{"00000000-0000-4000-8000-000000000102", "Mahan", "mahan@kipotify.local", "https://images.unsplash.com/photo-1599566150163-29194dcaad36?w=300", false},
		{"00000000-0000-4000-8000-000000000103", "Saba", "saba@kipotify.local", "https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=300", true},
		{"00000000-0000-4000-8000-000000000104", "Sara", "sara@kipotify.local", "https://images.unsplash.com/photo-1438761681033-6461ffad8d80?w=300", false},
	}
	for _, user := range users {
		_, err := tx.Exec(ctx, `
			insert into users (id, name, email, password_hash, avatar_url, is_premium, premium_expires_at)
			values ($1,$2,$3,$4,$5,$6, case when $6 then now() + interval '1 year' else null end)
			on conflict (id) do update set name=excluded.name, avatar_url=excluded.avatar_url`,
			user.ID, user.Name, user.Email, string(password), user.Avatar, user.Premium)
		if err != nil {
			return err
		}
	}
	_, _ = tx.Exec(ctx, `insert into follows(follower_id, followed_id) values
		('00000000-0000-4000-8000-000000000101','00000000-0000-4000-8000-000000000102'),
		('00000000-0000-4000-8000-000000000101','00000000-0000-4000-8000-000000000103')
		on conflict do nothing`)

	artistIDs := map[string]string{}
	artistNames := map[string]string{}
	for _, item := range catalog.Artists {
		if strings.TrimSpace(item.Name) == "" {
			continue
		}
		item.Slug = defaultSlug(item.Slug, item.Name)
		if item.ProfileImagePath == "" {
			item.ProfileImagePath = "media/images/artists/" + item.Slug + ".jpg"
		}
		var id string
		err := tx.QueryRow(ctx, `insert into artists(id, name, slug, bio, profile_image_path, country, birth_date, active_years)
			values(coalesce(nullif($1,'')::uuid, gen_random_uuid()),$2,$3,$4,$5,nullif($6,''),nullif($7,'')::date,nullif($8,''))
			on conflict(slug) do update set name=excluded.name, bio=excluded.bio, profile_image_path=excluded.profile_image_path,
				country=excluded.country, birth_date=excluded.birth_date, active_years=excluded.active_years, updated_at=now()
			returning id::text`,
			item.ID, item.Name, item.Slug, item.Bio, item.ProfileImagePath, item.Country, item.BirthDate, item.ActiveYears).Scan(&id)
		if err != nil {
			return err
		}
		artistIDs[item.Slug] = id
		artistNames[item.Slug] = item.Name
	}

	releaseIDs := map[string]string{}
	releasesBySlug := map[string]seedRelease{}
	for _, item := range catalog.Releases {
		item.Title = strings.TrimSpace(item.Title)
		if item.Title == "" {
			continue
		}
		item.Slug = defaultSlug(item.Slug, item.Title)
		if item.ReleaseType == "" {
			item.ReleaseType = "album"
		}
		item.ReleaseType = strings.ToLower(item.ReleaseType)
		artistID, ok := artistIDs[item.PrimaryArtistSlug]
		if !ok {
			return fmt.Errorf("release %q references unknown artist slug %q", item.Title, item.PrimaryArtistSlug)
		}
		if item.CoverImagePath == "" {
			item.CoverImagePath = "media/images/releases/" + item.Slug + ".jpg"
		}
		var id string
		err := tx.QueryRow(ctx, `insert into releases(id, title, slug, release_type, release_date, primary_artist_id, cover_image_path)
			values(coalesce(nullif($1,'')::uuid, gen_random_uuid()),$2,$3,$4,nullif($5,'')::date,$6::uuid,$7)
			on conflict(primary_artist_id, slug) do update set title=excluded.title, release_type=excluded.release_type,
				release_date=excluded.release_date, cover_image_path=excluded.cover_image_path, updated_at=now()
			returning id::text`,
			item.ID, item.Title, item.Slug, item.ReleaseType, item.ReleaseDate, artistID, item.CoverImagePath).Scan(&id)
		if err != nil {
			return err
		}
		key := item.PrimaryArtistSlug + "/" + item.Slug
		releaseIDs[key] = id
		releasesBySlug[key] = item
	}

	for _, item := range tracks {
		releaseArtistSlug := item.PrimaryArtistSlug
		if releaseArtistSlug == "" {
			return fmt.Errorf("track %q is missing primary_artist_slug", item.Title)
		}
		releaseKey := releaseArtistSlug + "/" + item.ReleaseSlug
		releaseID, ok := releaseIDs[releaseKey]
		if !ok {
			return fmt.Errorf("track %q references unknown release %q", item.Title, releaseKey)
		}
		release := releasesBySlug[releaseKey]
		primaryArtistName := artistNames[releaseArtistSlug]
		if item.Slug == "" {
			item.Slug = defaultSlug("", item.Title)
		}
		if item.DiscNumber <= 0 {
			item.DiscNumber = 1
		}
		if item.Duration <= 0 {
			item.Duration = 180
		}
		if item.ArtworkSource == "" {
			item.ArtworkSource = "embedded_audio"
		}
		if item.AudioFilePath == "" {
			item.AudioFilePath = "media/audio/" + releaseArtistSlug + "/" + item.ReleaseSlug + "/" + item.Slug + ".mp3"
		}
		if item.ReleaseDate == "" {
			item.ReleaseDate = release.ReleaseDate
		}
		_, err := tx.Exec(ctx, `insert into tracks(id, title, slug, release_id, track_number, disc_number, duration,
				audio_file_path, lyrics_file_path, release_date, is_explicit, artwork_source, lyric, artist_name, album_title)
			values($1,$2,$3,$4::uuid,$5,$6,$7,$8,$9,nullif($10,'')::date,$11,$12,$13,$14,$15)
			on conflict(id) do update set title=excluded.title, slug=excluded.slug, release_id=excluded.release_id,
				track_number=excluded.track_number, disc_number=excluded.disc_number, duration=excluded.duration,
				audio_file_path=excluded.audio_file_path, lyrics_file_path=excluded.lyrics_file_path,
				release_date=excluded.release_date, is_explicit=excluded.is_explicit, artwork_source=excluded.artwork_source,
				lyric=excluded.lyric, artist_name=excluded.artist_name, album_title=excluded.album_title, updated_at=now()`,
			item.ID, item.Title, item.Slug, releaseID, item.TrackNumber, item.DiscNumber, item.Duration,
			item.AudioFilePath, item.LyricsFilePath, item.ReleaseDate, item.Explicit, item.ArtworkSource,
			item.Lyric, primaryArtistName, release.Title)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `delete from track_artists where track_id=$1`, item.ID); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `insert into track_artists(track_id, artist_id, role, position) values($1,$2::uuid,'primary',1) on conflict do nothing`, item.ID, artistIDs[releaseArtistSlug]); err != nil {
			return err
		}
		for i, nameOrSlug := range item.FeaturedArtists {
			artistID, _, err := ensureSeedArtist(ctx, tx, artistIDs, artistNames, nameOrSlug)
			if err != nil {
				return err
			}
			if _, err := tx.Exec(ctx, `insert into track_artists(track_id, artist_id, role, position) values($1,$2::uuid,'featured',$3) on conflict do nothing`, item.ID, artistID, i+1); err != nil {
				return err
			}
		}
		for _, credit := range item.Credits {
			role := credit.Role
			if role == "" {
				role = "writer"
			}
			lookup := credit.ArtistSlug
			if lookup == "" {
				lookup = credit.Name
			}
			artistID, _, err := ensureSeedArtist(ctx, tx, artistIDs, artistNames, lookup)
			if err != nil {
				return err
			}
			position := credit.Position
			if position <= 0 {
				position = 1
			}
			if _, err := tx.Exec(ctx, `insert into track_artists(track_id, artist_id, role, position) values($1,$2::uuid,$3,$4) on conflict do nothing`, item.ID, artistID, role, position); err != nil {
				return err
			}
		}
	}

	playlists := []struct {
		ID, OwnerID, Name, Category, Description string
	}{
		{"30000000-0000-4000-8000-000000000001", "", "Trending Now", "global", "Popular tracks across Kipotify"},
		{"30000000-0000-4000-8000-000000000002", "", "New Releases", "global", "Freshly added tracks"},
		{"30000000-0000-4000-8000-000000000003", "", "Local Persian Picks", "local", "Persian and regional listening"},
		{"30000000-0000-4000-8000-000000000004", "00000000-0000-4000-8000-000000000101", "Demo Vibe Zone", "user", "Public demo user playlist"},
		{"30000000-0000-4000-8000-000000000005", "00000000-0000-4000-8000-000000000102", "Mahan Classics", "user", "A friend's public playlist"},
	}
	for _, playlist := range playlists {
		var owner any
		if playlist.OwnerID != "" {
			owner = playlist.OwnerID
		}
		_, err := tx.Exec(ctx, `insert into playlists(id, owner_id, name, description, cover_image_url, visibility, category)
			values($1,$2,$3,$4,$5,'public',$6)
			on conflict(id) do update set name=excluded.name, description=excluded.description`,
			playlist.ID, owner, playlist.Name, playlist.Description, "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600", playlist.Category)
		if err != nil {
			return err
		}
	}
	for i, item := range tracks {
		target := "30000000-0000-4000-8000-000000000001"
		switch {
		case i >= 15 && i < 30:
			target = "30000000-0000-4000-8000-000000000002"
		case i >= 30 && i < 45:
			target = "30000000-0000-4000-8000-000000000003"
		case i%5 == 0:
			target = "30000000-0000-4000-8000-000000000004"
		case i%7 == 0:
			target = "30000000-0000-4000-8000-000000000005"
		}
		_, err := tx.Exec(ctx, `insert into playlist_tracks(playlist_id, track_id, position)
			values($1,$2,$3) on conflict do nothing`, target, item.ID, i+1)
		if err != nil {
			return err
		}
	}
	_, _ = tx.Exec(ctx, `insert into notifications(id, user_id, type, title, body) values
		('40000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000101','premium','Premium active','Offline downloads are unlocked for your account.'),
		('40000000-0000-4000-8000-000000000002','00000000-0000-4000-8000-000000000101','social','Mahan followed you','Open the social tab to say hello.'),
		('40000000-0000-4000-8000-000000000003','00000000-0000-4000-8000-000000000101','music','New global playlist','Trending Now has been refreshed.')
		on conflict do nothing`)

	return tx.Commit(ctx)
}

func parseSeedCatalog(body []byte) (seedCatalog, []seedCatalogTrack, error) {
	body = bytes.TrimSpace(body)
	if len(body) == 0 {
		return seedCatalog{}, nil, fmt.Errorf("seed file is empty")
	}
	if body[0] == '[' {
		var legacy []seedTrack
		if err := json.Unmarshal(body, &legacy); err != nil {
			return seedCatalog{}, nil, err
		}
		catalog := catalogFromLegacy(legacy)
		return catalog, flattenCatalog(catalog), nil
	}
	var catalog seedCatalog
	if err := json.Unmarshal(body, &catalog); err != nil {
		return seedCatalog{}, nil, err
	}
	return catalog, flattenCatalog(catalog), nil
}

func catalogFromLegacy(legacy []seedTrack) seedCatalog {
	artists := map[string]seedArtist{}
	releaseMap := map[string]*seedRelease{}
	for _, item := range legacy {
		artistSlug := defaultSlug("", item.ArtistName)
		if _, ok := artists[artistSlug]; !ok {
			artists[artistSlug] = seedArtist{
				Name:             item.ArtistName,
				Slug:             artistSlug,
				ProfileImagePath: "media/images/artists/" + artistSlug + ".jpg",
			}
		}
		releaseTitle := item.AlbumTitle
		releaseType := "album"
		if strings.TrimSpace(releaseTitle) == "" {
			releaseTitle = item.Title
			releaseType = "single"
		}
		releaseSlug := defaultSlug("", releaseTitle)
		key := artistSlug + "/" + releaseSlug
		release, ok := releaseMap[key]
		if !ok {
			releaseMap[key] = &seedRelease{
				Title:             releaseTitle,
				Slug:              releaseSlug,
				ReleaseType:       releaseType,
				PrimaryArtistSlug: artistSlug,
				CoverImagePath:    "media/images/releases/" + releaseSlug + ".jpg",
			}
			release = releaseMap[key]
		}
		trackSlug := defaultSlug("", item.Title) + "-" + item.ID
		release.Tracks = append(release.Tracks, seedCatalogTrack{
			ID:                item.ID,
			Title:             item.Title,
			Slug:              trackSlug,
			PrimaryArtistSlug: artistSlug,
			ReleaseSlug:       releaseSlug,
			TrackNumber:       len(release.Tracks) + 1,
			DiscNumber:        1,
			Duration:          item.Duration,
			AudioFilePath:     "media/audio/" + artistSlug + "/" + releaseSlug + "/" + trackSlug + ".mp3",
			LyricsFilePath:    "media/lyrics/" + artistSlug + "/" + releaseSlug + "/" + trackSlug + ".lrc",
			ArtworkSource:     "embedded_audio",
			Lyric:             item.Lyric,
		})
	}
	catalog := seedCatalog{Artists: make([]seedArtist, 0, len(artists)), Releases: make([]seedRelease, 0, len(releaseMap))}
	for _, artist := range artists {
		catalog.Artists = append(catalog.Artists, artist)
	}
	sort.Slice(catalog.Artists, func(i, j int) bool { return catalog.Artists[i].Slug < catalog.Artists[j].Slug })
	for _, release := range releaseMap {
		catalog.Releases = append(catalog.Releases, *release)
	}
	sort.Slice(catalog.Releases, func(i, j int) bool { return catalog.Releases[i].Slug < catalog.Releases[j].Slug })
	return catalog
}

func flattenCatalog(catalog seedCatalog) []seedCatalogTrack {
	var tracks []seedCatalogTrack
	for _, release := range catalog.Releases {
		for i, track := range release.Tracks {
			if track.PrimaryArtistSlug == "" {
				track.PrimaryArtistSlug = release.PrimaryArtistSlug
			}
			if track.ReleaseSlug == "" {
				track.ReleaseSlug = release.Slug
			}
			if track.TrackNumber <= 0 {
				track.TrackNumber = i + 1
			}
			tracks = append(tracks, track)
		}
	}
	tracks = append(tracks, catalog.Tracks...)
	return tracks
}

func ensureSeedArtist(ctx context.Context, tx pgx.Tx, artistIDs, artistNames map[string]string, nameOrSlug string) (string, string, error) {
	slug := defaultSlug("", nameOrSlug)
	if id, ok := artistIDs[slug]; ok {
		return id, slug, nil
	}
	name := strings.TrimSpace(nameOrSlug)
	if name == "" {
		name = slug
	}
	var id string
	err := tx.QueryRow(ctx, `insert into artists(name, slug, bio, profile_image_path)
		values($1,$2,'',$3)
		on conflict(slug) do update set name=excluded.name, updated_at=now()
		returning id::text`, name, slug, "media/images/artists/"+slug+".jpg").Scan(&id)
	if err != nil {
		return "", "", err
	}
	artistIDs[slug] = id
	artistNames[slug] = name
	return id, slug, nil
}

var slugRe = regexp.MustCompile(`[^a-z0-9]+`)

func defaultSlug(existing, value string) string {
	if strings.TrimSpace(existing) != "" {
		return strings.TrimSpace(existing)
	}
	slug := strings.Trim(strings.ToLower(slugRe.ReplaceAllString(value, "-")), "-")
	if slug == "" {
		return "untitled"
	}
	return slug
}
