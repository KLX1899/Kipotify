package database

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

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

func Seed(ctx context.Context, pool *pgxpool.Pool) error {
	body, err := os.ReadFile("seed/tracks.json")
	if err != nil {
		return err
	}
	var tracks []seedTrack
	if err := json.Unmarshal(body, &tracks); err != nil {
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

	for _, item := range tracks {
		_, err := tx.Exec(ctx, `insert into tracks(id, title, artist_name, album_title, duration, lyric)
			values($1,$2,$3,$4,$5,$6)
			on conflict(id) do update set title=excluded.title, artist_name=excluded.artist_name,
				album_title=excluded.album_title, duration=excluded.duration, lyric=excluded.lyric`,
			item.ID, item.Title, item.ArtistName, item.AlbumTitle, item.Duration, item.Lyric)
		if err != nil {
			return err
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
