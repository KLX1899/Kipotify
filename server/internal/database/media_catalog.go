package database

import (
	"context"
	"crypto/sha256"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"unicode"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

var supportedAudioExtensions = map[string]bool{
	".aac": true, ".flac": true, ".m4a": true, ".mp3": true,
	".ogg": true, ".opus": true, ".wav": true,
}

type mediaTrack struct {
	path       string
	lyricsPath string
	artistName string
	albumTitle string
	title      string
}

// SyncMediaCatalog makes media/audio the source of truth while preserving
// metadata from seeded tracks whose artist and title match a real file.
func SyncMediaCatalog(ctx context.Context, pool *pgxpool.Pool, audioRoot string) error {
	files, err := discoverMediaTracks(audioRoot)
	if err != nil {
		return err
	}

	tx, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	existing, err := existingTrackIDs(ctx, tx)
	if err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `update tracks set audio_file_path='' where audio_file_path like 'media/audio/%'`); err != nil {
		return err
	}

	for _, file := range files {
		key := mediaMatchKey(file.artistName, file.title)
		if ids := existing[key]; len(ids) > 0 {
			id := ids[0]
			existing[key] = ids[1:]
			if _, err := tx.Exec(ctx, `update tracks set audio_file_path=$2,
				lyrics_file_path=case when $3<>'' then $3 else lyrics_file_path end,
				updated_at=now() where id=$1`, id, file.path, file.lyricsPath); err != nil {
				return err
			}
			continue
		}
		if err := insertMediaTrack(ctx, tx, file); err != nil {
			return err
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return err
	}
	slog.Info("media catalog synchronized", "tracks", len(files), "root", audioRoot)
	return nil
}

func discoverMediaTracks(audioRoot string) ([]mediaTrack, error) {
	files := make([]mediaTrack, 0)
	err := filepath.WalkDir(audioRoot, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() || !supportedAudioExtensions[strings.ToLower(filepath.Ext(entry.Name()))] {
			return nil
		}

		relative, err := filepath.Rel(audioRoot, path)
		if err != nil {
			return err
		}
		parts := strings.Split(filepath.ToSlash(relative), "/")
		artistName, albumTitle := "Unknown Artist", "Singles"
		if len(parts) >= 3 {
			artistName = humanizeMediaName(parts[0])
			albumTitle = humanizeMediaName(strings.Join(parts[1:len(parts)-1], " "))
		} else if len(parts) == 2 {
			artistName = humanizeMediaName(parts[0])
		}
		title := humanizeMediaName(strings.TrimSuffix(parts[len(parts)-1], filepath.Ext(parts[len(parts)-1])))
		files = append(files, mediaTrack{
			path:       "media/audio/" + filepath.ToSlash(relative),
			lyricsPath: discoverLyricsPath(audioRoot, relative),
			artistName: artistName,
			albumTitle: albumTitle,
			title:      title,
		})
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("scan audio directory: %w", err)
	}
	sort.Slice(files, func(i, j int) bool { return files[i].path < files[j].path })
	return files, nil
}

func discoverLyricsPath(audioRoot, relativeAudioPath string) string {
	relativeLyricsPath := strings.TrimSuffix(relativeAudioPath, filepath.Ext(relativeAudioPath)) + ".lrc"
	lyricsRoot := filepath.Join(filepath.Dir(filepath.Clean(audioRoot)), "lyrics")
	info, err := fs.Stat(os.DirFS(lyricsRoot), filepath.ToSlash(relativeLyricsPath))
	if err != nil || info.IsDir() {
		return ""
	}
	return "media/lyrics/" + filepath.ToSlash(relativeLyricsPath)
}

func existingTrackIDs(ctx context.Context, tx pgx.Tx) (map[string][]string, error) {
	rows, err := tx.Query(ctx, `select t.id, t.title,
		coalesce((select a.name from track_artists ta join artists a on a.id=ta.artist_id
			where ta.track_id=t.id and ta.role='primary' order by ta.position limit 1),
			ra.name, nullif(t.artist_name,''), 'Unknown Artist')
		from tracks t
		left join releases r on r.id=t.release_id
		left join artists ra on ra.id=r.primary_artist_id
		order by t.created_at, t.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	result := make(map[string][]string)
	for rows.Next() {
		var id, title, artistName string
		if err := rows.Scan(&id, &title, &artistName); err != nil {
			return nil, err
		}
		key := mediaMatchKey(artistName, title)
		result[key] = append(result[key], id)
	}
	return result, rows.Err()
}

func insertMediaTrack(ctx context.Context, tx pgx.Tx, file mediaTrack) error {
	artistSlug := uniqueMediaSlug(file.artistName)
	var artistID string
	if err := tx.QueryRow(ctx, `insert into artists(name,slug,bio,profile_image_path)
		values($1,$2,'','')
		on conflict(slug) do update set name=excluded.name, updated_at=now()
		returning id::text`, file.artistName, artistSlug).Scan(&artistID); err != nil {
		return err
	}

	releaseSlug := uniqueMediaSlug(file.albumTitle)
	var releaseID string
	if err := tx.QueryRow(ctx, `insert into releases(title,slug,release_type,primary_artist_id,cover_image_path)
		values($1,$2,'album',$3::uuid,'')
		on conflict(primary_artist_id,slug) do update set title=excluded.title, updated_at=now()
		returning id::text`, file.albumTitle, releaseSlug, artistID).Scan(&releaseID); err != nil {
		return err
	}

	trackSlug := uniqueMediaSlug(file.title)
	trackID := stableMediaID(file.path)
	if err := tx.QueryRow(ctx, `select coalesce((select id from tracks where release_id=$1::uuid and slug=$2 limit 1),$3)`, releaseID, trackSlug, trackID).Scan(&trackID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `insert into tracks(id,title,slug,release_id,duration,audio_file_path,lyrics_file_path,artwork_source,artist_name,album_title)
		values($1,$2,$3,$4::uuid,180,$5,$6,'embedded_audio',$7,$8)
		on conflict(id) do update set title=excluded.title, slug=excluded.slug, release_id=excluded.release_id,
			audio_file_path=excluded.audio_file_path,
			lyrics_file_path=case when excluded.lyrics_file_path<>'' then excluded.lyrics_file_path else tracks.lyrics_file_path end,
			artist_name=excluded.artist_name,
			album_title=excluded.album_title, updated_at=now()`,
		trackID, file.title, trackSlug, releaseID, file.path, file.lyricsPath, file.artistName, file.albumTitle); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `insert into track_artists(track_id,artist_id,role,position)
		values($1,$2::uuid,'primary',1) on conflict do nothing`, trackID, artistID)
	return err
}

func humanizeMediaName(value string) string {
	var result []rune
	runes := []rune(value)
	for index, current := range runes {
		if current == '-' || current == '_' || current == '.' {
			if len(result) > 0 && result[len(result)-1] != ' ' {
				result = append(result, ' ')
			}
			continue
		}
		var previous, next rune
		if index > 0 {
			previous = runes[index-1]
		}
		if index+1 < len(runes) {
			next = runes[index+1]
		}
		if unicode.IsUpper(current) && (unicode.IsLower(previous) || unicode.IsDigit(previous) ||
			(unicode.IsUpper(previous) && unicode.IsLower(next))) {
			result = append(result, ' ')
		}
		result = append(result, current)
	}
	return strings.Join(strings.Fields(string(result)), " ")
}

func mediaMatchKey(artistName, title string) string {
	return canonicalMediaName(artistName) + "/" + canonicalMediaName(title)
}

func canonicalMediaName(value string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			return unicode.ToLower(r)
		}
		return -1
	}, value)
}

func uniqueMediaSlug(value string) string {
	slug := defaultSlug("", value)
	if slug != "untitled" {
		return slug
	}
	sum := sha256.Sum256([]byte(value))
	return fmt.Sprintf("media-%x", sum[:6])
}

func stableMediaID(path string) string {
	sum := sha256.Sum256([]byte(filepath.ToSlash(path)))
	return fmt.Sprintf("media-%x", sum[:12])
}
