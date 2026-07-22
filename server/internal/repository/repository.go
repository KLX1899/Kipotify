package repository

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"math"
	"strings"
	"time"

	"kipotify/internal/domain"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

var ErrNotFound = errors.New("not found")

type UserWithPassword struct {
	domain.User
	PasswordHash string
}

type Store interface {
	CreateUser(ctx context.Context, name, email, passwordHash string) (domain.User, error)
	UserByEmail(ctx context.Context, email string) (UserWithPassword, error)
	UserByID(ctx context.Context, id string) (domain.User, error)
	UpdateSettings(ctx context.Context, userID, language, theme string) (domain.User, error)
	UpgradePremium(ctx context.Context, userID string, until time.Time) (domain.User, error)

	ListTracks(ctx context.Context, userID string, filters domain.TrackFilters) (domain.Paged[[]domain.Track], error)
	TrackByID(ctx context.Context, userID, trackID string) (domain.Track, error)
	ToggleLike(ctx context.Context, userID, trackID string) (bool, error)
	RecordDownload(ctx context.Context, userID, trackID string) (int, error)
	RecordPlay(ctx context.Context, userID, trackID string) error
	ListLiked(ctx context.Context, userID string, page, limit int) (domain.Paged[[]domain.Track], error)
	ListRecent(ctx context.Context, userID string, page, limit int) (domain.Paged[[]domain.Track], error)

	ListArtists(ctx context.Context, query string, page, limit int) (domain.Paged[[]domain.Artist], error)
	ListAlbums(ctx context.Context, page, limit int) (domain.Paged[[]domain.Album], error)
	ListPlaylists(ctx context.Context, userID string, filters domain.PlaylistFilters) (domain.Paged[[]domain.Playlist], error)
	PlaylistTracks(ctx context.Context, userID, playlistID string, page, limit int) (domain.Paged[[]domain.Track], error)
	CreatePlaylist(ctx context.Context, userID, name, description, category, visibility string) (domain.Playlist, error)
	AddTrackToPlaylist(ctx context.Context, userID, playlistID, trackID string) error

	Search(ctx context.Context, userID, query, resultType, genre string, page, limit int) (domain.SearchResults, error)
	ListUsers(ctx context.Context, viewerID, query string, page, limit int) (domain.Paged[[]domain.PublicUser], error)
	ToggleFollow(ctx context.Context, followerID, followedID string) (bool, error)
	PublicPlaylistsOfFollowed(ctx context.Context, userID string, page, limit int) (domain.Paged[[]domain.Playlist], error)

	Notifications(ctx context.Context, userID string, page, limit int) (domain.Paged[[]domain.Notification], error)
	MarkNotificationRead(ctx context.Context, userID, notificationID string) error

	ListMessages(ctx context.Context, userID, friendID string, before *time.Time, limit int) ([]domain.Message, error)
	CreateMessage(ctx context.Context, senderID, receiverID, content string, sharedTrackID *string) (domain.Message, error)
	MarkDelivered(ctx context.Context, messageID, receiverID string) (domain.Message, error)
	MarkRead(ctx context.Context, messageID, readerID string) (domain.Message, error)
}

type Postgres struct {
	db *pgxpool.Pool
}

func NewPostgres(db *pgxpool.Pool) *Postgres {
	return &Postgres{db: db}
}

func (p *Postgres) CreateUser(ctx context.Context, name, email, passwordHash string) (domain.User, error) {
	row := p.db.QueryRow(ctx, `insert into users(name,email,password_hash,avatar_url) values($1,$2,$3,$4) returning `+userColumns,
		name, strings.ToLower(email), passwordHash, "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?w=300")
	return scanUser(row)
}

func (p *Postgres) UserByEmail(ctx context.Context, email string) (UserWithPassword, error) {
	row := p.db.QueryRow(ctx, `select `+userColumns+`, password_hash from users where email=$1`, strings.ToLower(email))
	user, hash, err := scanUserWithHash(row)
	if err != nil {
		return UserWithPassword{}, err
	}
	return UserWithPassword{User: user, PasswordHash: hash}, nil
}

func (p *Postgres) UserByID(ctx context.Context, id string) (domain.User, error) {
	return scanUser(p.db.QueryRow(ctx, `select `+userColumns+` from users where id=$1`, id))
}

func (p *Postgres) UpdateSettings(ctx context.Context, userID, language, theme string) (domain.User, error) {
	return scanUser(p.db.QueryRow(ctx, `update users set language=$2, theme=$3, updated_at=now() where id=$1 returning `+userColumns, userID, language, theme))
}

func (p *Postgres) UpgradePremium(ctx context.Context, userID string, until time.Time) (domain.User, error) {
	return scanUser(p.db.QueryRow(ctx, `update users set is_premium=true, premium_expires_at=$2, updated_at=now() where id=$1 returning `+userColumns, userID, until))
}

func (p *Postgres) ListTracks(ctx context.Context, userID string, f domain.TrackFilters) (domain.Paged[[]domain.Track], error) {
	f.Page, f.Limit = normalizePage(f.Page, f.Limit)
	where, args := []string{"1=1"}, []any{userID}
	if f.Query != "" {
		args = append(args, "%"+f.Query+"%")
		where = append(where, fmt.Sprintf("(t.title ilike $%d or coalesce(pa.name, ra.name, t.artist_name) ilike $%d or coalesce(r.title, t.album_title) ilike $%d or t.lyric ilike $%d or t.slug ilike $%d)", len(args), len(args), len(args), len(args), len(args)))
	}
	if f.ArtistID != "" {
		args = append(args, f.ArtistID)
		where = append(where, fmt.Sprintf("exists(select 1 from track_artists ta_filter where ta_filter.track_id=t.id and ta_filter.artist_id::text=$%d)", len(args)))
	}
	order := "t.created_at desc"
	switch f.Section {
	case "trending", "popular":
		order = "t.play_count desc, t.created_at desc"
	case "new":
		order = "t.created_at desc"
	}
	return p.tracksPage(ctx, userID, strings.Join(where, " and "), order, args, f.Page, f.Limit, "")
}

func (p *Postgres) TrackByID(ctx context.Context, userID, trackID string) (domain.Track, error) {
	rows, err := p.db.Query(ctx, trackSelect()+` where t.id=$2`, userID, trackID)
	if err != nil {
		return domain.Track{}, err
	}
	defer rows.Close()
	tracks, err := scanTracks(rows)
	if err != nil {
		return domain.Track{}, err
	}
	if len(tracks) == 0 {
		return domain.Track{}, ErrNotFound
	}
	return tracks[0], nil
}

func (p *Postgres) ToggleLike(ctx context.Context, userID, trackID string) (bool, error) {
	tx, err := p.db.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)
	tag, err := tx.Exec(ctx, `delete from liked_tracks where user_id=$1 and track_id=$2`, userID, trackID)
	if err != nil {
		return false, err
	}
	if tag.RowsAffected() > 0 {
		return false, tx.Commit(ctx)
	}
	if _, err := tx.Exec(ctx, `insert into liked_tracks(user_id,track_id) values($1,$2)`, userID, trackID); err != nil {
		return false, err
	}
	return true, tx.Commit(ctx)
}

func (p *Postgres) RecordDownload(ctx context.Context, userID, trackID string) (int, error) {
	tx, err := p.db.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `insert into downloads(user_id,track_id) values($1,$2) on conflict do nothing`, userID, trackID); err != nil {
		return 0, err
	}
	var count int
	if err := tx.QueryRow(ctx, `update tracks set download_count=download_count+1 where id=$1 returning download_count`, trackID).Scan(&count); err != nil {
		return 0, err
	}
	return count, tx.Commit(ctx)
}

func (p *Postgres) RecordPlay(ctx context.Context, userID, trackID string) error {
	tx, err := p.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `insert into recently_played(user_id,track_id,played_at) values($1,$2,now())
		on conflict(user_id,track_id) do update set played_at=excluded.played_at`, userID, trackID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `update tracks set play_count=play_count+1 where id=$1`, trackID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (p *Postgres) ListLiked(ctx context.Context, userID string, page, limit int) (domain.Paged[[]domain.Track], error) {
	page, limit = normalizePage(page, limit)
	return p.tracksPage(ctx, userID, "lt.user_id is not null", "lt.created_at desc", []any{userID}, page, limit, " join liked_tracks lt on lt.track_id=t.id and lt.user_id=$1")
}

func (p *Postgres) ListRecent(ctx context.Context, userID string, page, limit int) (domain.Paged[[]domain.Track], error) {
	page, limit = normalizePage(page, limit)
	return p.tracksPage(ctx, userID, "rp.user_id is not null", "rp.played_at desc", []any{userID}, page, limit, " join recently_played rp on rp.track_id=t.id and rp.user_id=$1")
}

func (p *Postgres) ListArtists(ctx context.Context, query string, page, limit int) (domain.Paged[[]domain.Artist], error) {
	page, limit = normalizePage(page, limit)
	args, where := []any{}, "1=1"
	if query != "" {
		args = append(args, "%"+query+"%")
		where = "(name ilike $1 or slug ilike $1)"
	}
	var total int
	if err := p.db.QueryRow(ctx, `select count(*) from artists where `+where, args...).Scan(&total); err != nil {
		return domain.Paged[[]domain.Artist]{}, err
	}
	args = append(args, limit, offset(page, limit))
	rows, err := p.db.Query(ctx, `select id::text,name,slug,
		case when profile_image_path = '' or profile_image_path ~ '^https?://' or left(profile_image_path, 1) = '/' then profile_image_path else '/' || profile_image_path end avatar_url,
		profile_image_path,bio,coalesce(country,''),birth_date,coalesce(active_years,''),created_at
		from artists where `+where+` order by name limit $`+fmt.Sprint(len(args)-1)+` offset $`+fmt.Sprint(len(args)), args...)
	if err != nil {
		return domain.Paged[[]domain.Artist]{}, err
	}
	defer rows.Close()
	items := []domain.Artist{}
	for rows.Next() {
		var item domain.Artist
		var birthDate sql.NullTime
		if err := rows.Scan(&item.ID, &item.Name, &item.Slug, &item.AvatarURL, &item.ProfileImagePath, &item.Bio, &item.Country, &birthDate, &item.ActiveYears, &item.CreatedAt); err != nil {
			return domain.Paged[[]domain.Artist]{}, err
		}
		if birthDate.Valid {
			item.BirthDate = &birthDate.Time
		}
		items = append(items, item)
	}
	return paged(items, page, limit, total), rows.Err()
}

func (p *Postgres) ListAlbums(ctx context.Context, page, limit int) (domain.Paged[[]domain.Album], error) {
	page, limit = normalizePage(page, limit)
	var total int
	if err := p.db.QueryRow(ctx, `select count(*) from releases`).Scan(&total); err != nil {
		return domain.Paged[[]domain.Album]{}, err
	}
	rows, err := p.db.Query(ctx, `select r.id::text,r.title,r.slug,r.release_type,a.id::text,a.name,
		case when r.cover_image_path = '' or r.cover_image_path ~ '^https?://' or left(r.cover_image_path, 1) = '/' then r.cover_image_path else '/' || r.cover_image_path end cover_image_url,
		coalesce(r.release_date, r.created_at::date) release_date,
		(select count(*) from tracks t where t.release_id=r.id)::int track_count,
		r.created_at
		from releases r join artists a on a.id=r.primary_artist_id
		order by r.release_date desc nulls last, r.created_at desc limit $1 offset $2`, limit, offset(page, limit))
	if err != nil {
		return domain.Paged[[]domain.Album]{}, err
	}
	defer rows.Close()
	items := []domain.Album{}
	for rows.Next() {
		var item domain.Album
		if err := rows.Scan(&item.ID, &item.Title, &item.Slug, &item.ReleaseType, &item.ArtistID, &item.ArtistName, &item.CoverImageURL, &item.ReleaseDate, &item.TrackCount, &item.CreatedAt); err != nil {
			return domain.Paged[[]domain.Album]{}, err
		}
		items = append(items, item)
	}
	return paged(items, page, limit, total), rows.Err()
}

func (p *Postgres) ListPlaylists(ctx context.Context, userID string, f domain.PlaylistFilters) (domain.Paged[[]domain.Playlist], error) {
	f.Page, f.Limit = normalizePage(f.Page, f.Limit)
	where, args := []string{"(p.visibility='public' or p.owner_id=$1)"}, []any{userID}
	if f.Category != "" {
		args = append(args, f.Category)
		where = append(where, fmt.Sprintf("p.category=$%d", len(args)))
	}
	if f.Mine {
		where = append(where, "p.owner_id=$1")
	}
	if f.Followed {
		where = append(where, `exists(select 1 from follows f where f.follower_id=$1 and f.followed_id=p.owner_id)`)
	}
	return p.playlistsPage(ctx, strings.Join(where, " and "), args, f.Page, f.Limit)
}

func (p *Postgres) PublicPlaylistsOfFollowed(ctx context.Context, userID string, page, limit int) (domain.Paged[[]domain.Playlist], error) {
	page, limit = normalizePage(page, limit)
	return p.playlistsPage(ctx, `p.visibility='public' and exists(select 1 from follows f where f.follower_id=$1 and f.followed_id=p.owner_id)`, []any{userID}, page, limit)
}

func (p *Postgres) PlaylistTracks(ctx context.Context, userID, playlistID string, page, limit int) (domain.Paged[[]domain.Track], error) {
	page, limit = normalizePage(page, limit)
	return p.tracksPage(ctx, userID, "pt.playlist_id=$2", "pt.position asc, pt.added_at asc", []any{userID, playlistID}, page, limit, " join playlist_tracks pt on pt.track_id=t.id")
}

func (p *Postgres) CreatePlaylist(ctx context.Context, userID, name, description, category, visibility string) (domain.Playlist, error) {
	if category == "" {
		category = "user"
	}
	if visibility == "" {
		visibility = "public"
	}
	row := p.db.QueryRow(ctx, `with inserted as (
		insert into playlists(owner_id,name,description,category,visibility) values($1,$2,$3,$4,$5) returning id
	) `+playlistSelect()+` join inserted i on i.id=p.id`, userID, name, description, category, visibility)
	return scanPlaylist(row)
}

func (p *Postgres) AddTrackToPlaylist(ctx context.Context, userID, playlistID, trackID string) error {
	_, err := p.db.Exec(ctx, `insert into playlist_tracks(playlist_id, track_id, position)
		select $1, $2, coalesce(max(position),0)+1 from playlist_tracks where playlist_id=$1
		on conflict do nothing`, playlistID, trackID)
	return err
}

func (p *Postgres) Search(ctx context.Context, userID, query, resultType, genre string, page, limit int) (domain.SearchResults, error) {
	var result domain.SearchResults
	var err error
	if resultType == "" || resultType == "songs" || resultType == "tracks" {
		result.Songs, err = p.ListTracks(ctx, userID, domain.TrackFilters{Query: query, Genre: genre, Page: page, Limit: limit})
		if err != nil {
			return result, err
		}
	}
	if resultType == "" || resultType == "artists" {
		result.Artists, err = p.ListArtists(ctx, query, page, limit)
		if err != nil {
			return result, err
		}
	}
	if resultType == "" || resultType == "users" {
		result.Users, err = p.ListUsers(ctx, userID, query, page, limit)
		if err != nil {
			return result, err
		}
	}
	if resultType == "" || resultType == "playlists" {
		result.Playlists, err = p.playlistsPage(ctx, `(p.visibility='public' or p.owner_id=$1) and p.name ilike $2`, []any{userID, "%" + query + "%"}, page, limit)
	}
	return result, err
}

func (p *Postgres) ListUsers(ctx context.Context, viewerID, query string, page, limit int) (domain.Paged[[]domain.PublicUser], error) {
	page, limit = normalizePage(page, limit)
	where, args := "id <> $1", []any{viewerID}
	if query != "" {
		args = append(args, "%"+query+"%")
		where += fmt.Sprintf(" and name ilike $%d", len(args))
	}
	var total int
	if err := p.db.QueryRow(ctx, `select count(*) from users where `+where, args...).Scan(&total); err != nil {
		return domain.Paged[[]domain.PublicUser]{}, err
	}
	args = append(args, limit, offset(page, limit))
	rows, err := p.db.Query(ctx, `select u.id::text,u.name,u.avatar_url,u.is_premium,
		exists(select 1 from follows f where f.follower_id=$1 and f.followed_id=u.id) is_following,
		coalesce((select p.name from playlists p where p.owner_id=u.id and p.visibility='public' order by p.created_at limit 1),'') public_playlist_name,
		(select count(*) from follows f where f.followed_id=u.id)::int followers_count,
		(select count(*) from follows f where f.follower_id=u.id)::int following_count
		from users u where `+where+` order by u.name limit $`+fmt.Sprint(len(args)-1)+` offset $`+fmt.Sprint(len(args)), args...)
	if err != nil {
		return domain.Paged[[]domain.PublicUser]{}, err
	}
	defer rows.Close()
	items := []domain.PublicUser{}
	for rows.Next() {
		var item domain.PublicUser
		if err := rows.Scan(&item.ID, &item.Name, &item.AvatarURL, &item.IsPremium, &item.IsFollowing, &item.PublicPlaylistName, &item.FollowersCount, &item.FollowingCount); err != nil {
			return domain.Paged[[]domain.PublicUser]{}, err
		}
		items = append(items, item)
	}
	return paged(items, page, limit, total), rows.Err()
}

func (p *Postgres) ToggleFollow(ctx context.Context, followerID, followedID string) (bool, error) {
	tx, err := p.db.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)
	tag, err := tx.Exec(ctx, `delete from follows where follower_id=$1 and followed_id=$2`, followerID, followedID)
	if err != nil {
		return false, err
	}
	if tag.RowsAffected() > 0 {
		return false, tx.Commit(ctx)
	}
	if _, err := tx.Exec(ctx, `insert into follows(follower_id,followed_id) values($1,$2)`, followerID, followedID); err != nil {
		return false, err
	}
	return true, tx.Commit(ctx)
}

func (p *Postgres) Notifications(ctx context.Context, userID string, page, limit int) (domain.Paged[[]domain.Notification], error) {
	page, limit = normalizePage(page, limit)
	var total int
	if err := p.db.QueryRow(ctx, `select count(*) from notifications where user_id=$1`, userID).Scan(&total); err != nil {
		return domain.Paged[[]domain.Notification]{}, err
	}
	rows, err := p.db.Query(ctx, `select id::text,type,title,body,is_read,created_at from notifications where user_id=$1 order by is_read asc, created_at desc limit $2 offset $3`, userID, limit, offset(page, limit))
	if err != nil {
		return domain.Paged[[]domain.Notification]{}, err
	}
	defer rows.Close()
	items := []domain.Notification{}
	for rows.Next() {
		var item domain.Notification
		if err := rows.Scan(&item.ID, &item.Type, &item.Title, &item.Body, &item.IsRead, &item.CreatedAt); err != nil {
			return domain.Paged[[]domain.Notification]{}, err
		}
		items = append(items, item)
	}
	return paged(items, page, limit, total), rows.Err()
}

func (p *Postgres) MarkNotificationRead(ctx context.Context, userID, notificationID string) error {
	tag, err := p.db.Exec(ctx, `update notifications set is_read=true where user_id=$1 and id=$2`, userID, notificationID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *Postgres) ListMessages(ctx context.Context, userID, friendID string, before *time.Time, limit int) ([]domain.Message, error) {
	_, limit = normalizePage(1, limit)
	args := []any{userID, friendID, limit}
	beforeClause := ""
	if before != nil {
		args = append(args, *before)
		beforeClause = " and m.created_at < $4"
	}
	rows, err := p.db.Query(ctx, messageSelect()+` where ((m.sender_id=$1 and m.receiver_id=$2) or (m.sender_id=$2 and m.receiver_id=$1))`+beforeClause+` order by m.created_at desc limit $3`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanMessages(rows)
}

func (p *Postgres) CreateMessage(ctx context.Context, senderID, receiverID, content string, sharedTrackID *string) (domain.Message, error) {
	var messageID string
	if err := p.db.QueryRow(ctx, `insert into messages(sender_id,receiver_id,content,shared_track_id,status) values($1,$2,$3,$4,'sent') returning id::text`, senderID, receiverID, content, sharedTrackID).Scan(&messageID); err != nil {
		return domain.Message{}, err
	}
	return p.messageByID(ctx, messageID)
}

func (p *Postgres) MarkDelivered(ctx context.Context, messageID, receiverID string) (domain.Message, error) {
	return scanMessage(p.db.QueryRow(ctx, `with updated as (
		update messages set status=case when status='sent' then 'delivered' else status end, delivered_at=coalesce(delivered_at,now())
		where id=$1 and receiver_id=$2 returning id
	) `+messageSelect()+` join updated u on u.id=m.id`, messageID, receiverID))
}

func (p *Postgres) MarkRead(ctx context.Context, messageID, readerID string) (domain.Message, error) {
	return scanMessage(p.db.QueryRow(ctx, `with updated as (
		update messages set status='read', delivered_at=coalesce(delivered_at,now()), read_at=coalesce(read_at,now())
		where id=$1 and receiver_id=$2 returning id
	) `+messageSelect()+` join updated u on u.id=m.id`, messageID, readerID))
}

func (p *Postgres) messageByID(ctx context.Context, messageID string) (domain.Message, error) {
	return scanMessage(p.db.QueryRow(ctx, messageSelect()+` where m.id=$1`, messageID))
}

func (p *Postgres) tracksPage(ctx context.Context, userID, where, order string, args []any, page, limit int, extraJoin string) (domain.Paged[[]domain.Track], error) {
	where = "(" + where + ") and t.audio_file_path like 'media/audio/%'"
	baseFrom := trackFrom(extraJoin)
	var total int
	if err := p.db.QueryRow(ctx, `select count(*)`+baseFrom+` where `+where, args...).Scan(&total); err != nil {
		return domain.Paged[[]domain.Track]{}, err
	}
	args = append(args, limit, offset(page, limit))
	query := trackSelectWithFrom(baseFrom) + ` where ` + where + ` order by ` + order + ` limit $` + fmt.Sprint(len(args)-1) + ` offset $` + fmt.Sprint(len(args))
	rows, err := p.db.Query(ctx, query, args...)
	if err != nil {
		return domain.Paged[[]domain.Track]{}, err
	}
	defer rows.Close()
	items, err := scanTracks(rows)
	if err != nil {
		return domain.Paged[[]domain.Track]{}, err
	}
	return paged(items, page, limit, total), nil
}

func (p *Postgres) playlistsPage(ctx context.Context, where string, args []any, page, limit int) (domain.Paged[[]domain.Playlist], error) {
	page, limit = normalizePage(page, limit)
	base := ` from playlists p left join users u on u.id=p.owner_id`
	var total int
	if err := p.db.QueryRow(ctx, `select count(*)`+base+` where `+where, args...).Scan(&total); err != nil {
		return domain.Paged[[]domain.Playlist]{}, err
	}
	args = append(args, limit, offset(page, limit))
	rows, err := p.db.Query(ctx, playlistSelect()+` where `+where+` order by p.created_at desc limit $`+fmt.Sprint(len(args)-1)+` offset $`+fmt.Sprint(len(args)), args...)
	if err != nil {
		return domain.Paged[[]domain.Playlist]{}, err
	}
	defer rows.Close()
	items := []domain.Playlist{}
	for rows.Next() {
		item, err := scanPlaylistRows(rows)
		if err != nil {
			return domain.Paged[[]domain.Playlist]{}, err
		}
		items = append(items, item)
	}
	return paged(items, page, limit, total), rows.Err()
}

const userColumns = `id::text,name,email,avatar_url,is_premium,premium_expires_at,language,theme,
	(select count(*) from follows f where f.followed_id=users.id)::int followers_count,
	(select count(*) from follows f where f.follower_id=users.id)::int following_count,
	created_at`

func scanUser(row pgx.Row) (domain.User, error) {
	var user domain.User
	err := row.Scan(&user.ID, &user.Name, &user.Email, &user.AvatarURL, &user.IsPremium, &user.PremiumExpires, &user.Language, &user.Theme, &user.FollowersCount, &user.FollowingCount, &user.CreatedAt)
	return user, mapErr(err)
}

func scanUserWithHash(row pgx.Row) (domain.User, string, error) {
	var user domain.User
	var hash string
	err := row.Scan(&user.ID, &user.Name, &user.Email, &user.AvatarURL, &user.IsPremium, &user.PremiumExpires, &user.Language, &user.Theme, &user.FollowersCount, &user.FollowingCount, &user.CreatedAt, &hash)
	return user, hash, mapErr(err)
}

func trackSelect() string {
	return trackSelectWithFrom(trackFrom(""))
}

func trackFrom(extraJoin string) string {
	return ` from tracks t
		left join releases r on r.id=t.release_id
		left join artists ra on ra.id=r.primary_artist_id
		left join lateral (
			select a.id, a.name, a.profile_image_path
			from track_artists ta
			join artists a on a.id=ta.artist_id
			where ta.track_id=t.id and ta.role='primary'
			order by ta.position, a.name
			limit 1
		) pa on true
		left join liked_tracks liked on liked.track_id=t.id and liked.user_id=$1
		left join downloads d on d.track_id=t.id and d.user_id=$1` + extraJoin
}

func trackSelectWithFrom(from string) string {
	return `select t.id::text,t.title,coalesce(t.slug,'') slug,
		coalesce(pa.id::text, ra.id::text, '') artist_id,
		coalesce(pa.name, ra.name, nullif(t.artist_name, ''), 'Unknown Artist') artist_name,
		coalesce(r.id::text, '') album_id,
		coalesce(r.title, nullif(t.album_title, ''), '') album_title,
		coalesce(r.id::text, '') release_id,
		coalesce(r.title, nullif(t.album_title, ''), '') release_title,
		coalesce(r.release_type, '') release_type,
		case
			when coalesce(nullif(r.cover_image_path, ''), nullif(pa.profile_image_path, ''), nullif(ra.profile_image_path, ''), '') = '' then ''
			when coalesce(nullif(r.cover_image_path, ''), nullif(pa.profile_image_path, ''), nullif(ra.profile_image_path, ''), '') ~ '^https?://' then coalesce(nullif(r.cover_image_path, ''), nullif(pa.profile_image_path, ''), nullif(ra.profile_image_path, ''), '')
			when left(coalesce(nullif(r.cover_image_path, ''), nullif(pa.profile_image_path, ''), nullif(ra.profile_image_path, ''), ''), 1) = '/' then coalesce(nullif(r.cover_image_path, ''), nullif(pa.profile_image_path, ''), nullif(ra.profile_image_path, ''), '')
			else '/' || coalesce(nullif(r.cover_image_path, ''), nullif(pa.profile_image_path, ''), nullif(ra.profile_image_path, ''), '')
		end cover_image_url,
		case
			when coalesce(nullif(r.cover_image_path, ''), nullif(pa.profile_image_path, ''), nullif(ra.profile_image_path, ''), '') = '' then ''
			when coalesce(nullif(r.cover_image_path, ''), nullif(pa.profile_image_path, ''), nullif(ra.profile_image_path, ''), '') ~ '^https?://' then coalesce(nullif(r.cover_image_path, ''), nullif(pa.profile_image_path, ''), nullif(ra.profile_image_path, ''), '')
			when left(coalesce(nullif(r.cover_image_path, ''), nullif(pa.profile_image_path, ''), nullif(ra.profile_image_path, ''), ''), 1) = '/' then coalesce(nullif(r.cover_image_path, ''), nullif(pa.profile_image_path, ''), nullif(ra.profile_image_path, ''), '')
			else '/' || coalesce(nullif(r.cover_image_path, ''), nullif(pa.profile_image_path, ''), nullif(ra.profile_image_path, ''), '')
		end fallback_artwork_url,
		case when t.audio_file_path = '' or t.audio_file_path ~ '^https?://' or left(t.audio_file_path, 1) = '/' then t.audio_file_path else '/' || t.audio_file_path end audio_url,
		t.audio_file_path,
		case when t.lyrics_file_path = '' or t.lyrics_file_path ~ '^https?://' or left(t.lyrics_file_path, 1) = '/' then t.lyrics_file_path else '/' || t.lyrics_file_path end lyrics_url,
		t.lyrics_file_path,
		t.artwork_source,
		'' genre,'' locale,
		coalesce(t.track_number,0),coalesce(t.disc_number,1),t.duration,t.lyric,t.release_date,t.is_explicit,
		coalesce(array(select a.name from track_artists ta join artists a on a.id=ta.artist_id where ta.track_id=t.id and ta.role='featured' order by ta.position, a.name), '{}') featured_artists,
		t.play_count,t.download_count,(liked.user_id is not null) is_liked,(d.user_id is not null) is_downloaded,t.created_at` + from
}

func scanTracks(rows pgx.Rows) ([]domain.Track, error) {
	items := []domain.Track{}
	for rows.Next() {
		var item domain.Track
		var releaseDate sql.NullTime
		if err := rows.Scan(&item.ID, &item.Title, &item.Slug, &item.ArtistID, &item.ArtistName, &item.AlbumID, &item.AlbumTitle, &item.ReleaseID, &item.ReleaseTitle, &item.ReleaseType, &item.CoverImageURL, &item.FallbackArtwork, &item.AudioURL, &item.AudioFilePath, &item.LyricsURL, &item.LyricsFilePath, &item.ArtworkSource, &item.Genre, &item.Locale, &item.TrackNumber, &item.DiscNumber, &item.DurationSeconds, &item.Lyric, &releaseDate, &item.Explicit, &item.FeaturedArtists, &item.PlayCount, &item.DownloadCount, &item.IsLiked, &item.IsDownloaded, &item.CreatedAt); err != nil {
			return nil, err
		}
		if releaseDate.Valid {
			item.ReleaseDate = &releaseDate.Time
		}
		items = append(items, item)
	}
	return items, rows.Err()
}

func playlistSelect() string {
	return `select p.id::text,coalesce(p.owner_id::text,''),coalesce(u.name,''),p.name,p.description,p.cover_image_url,p.visibility,p.category,
		(select count(*) from playlist_tracks pt where pt.playlist_id=p.id)::int track_count,p.created_at
		from playlists p left join users u on u.id=p.owner_id`
}

func scanPlaylist(row pgx.Row) (domain.Playlist, error) {
	var item domain.Playlist
	var ownerID, ownerName string
	err := row.Scan(&item.ID, &ownerID, &ownerName, &item.Name, &item.Description, &item.CoverImageURL, &item.Visibility, &item.Category, &item.TrackCount, &item.CreatedAt)
	if ownerID != "" {
		item.OwnerID = &ownerID
		item.OwnerName = &ownerName
	}
	return item, mapErr(err)
}

func scanPlaylistRows(rows pgx.Rows) (domain.Playlist, error) {
	var item domain.Playlist
	var ownerID, ownerName string
	err := rows.Scan(&item.ID, &ownerID, &ownerName, &item.Name, &item.Description, &item.CoverImageURL, &item.Visibility, &item.Category, &item.TrackCount, &item.CreatedAt)
	if ownerID != "" {
		item.OwnerID = &ownerID
		item.OwnerName = &ownerName
	}
	return item, err
}

func messageSelect() string {
	return `select m.id::text,m.sender_id::text,s.name,m.receiver_id::text,m.content,(extract(epoch from m.created_at)*1000)::bigint,m.status,
		st.id::text,coalesce(st.title,''),coalesce(st.slug,''),
		coalesce(spa.id::text, sra.id::text, '') artist_id,
		coalesce(spa.name, sra.name, nullif(st.artist_name, ''), ''),
		coalesce(sr.id::text, '') album_id,
		coalesce(sr.title, nullif(st.album_title, ''), '') album_title,
		coalesce(sr.id::text, '') release_id,
		coalesce(sr.title, nullif(st.album_title, ''), '') release_title,
		coalesce(sr.release_type, '') release_type,
		case
			when coalesce(nullif(sr.cover_image_path, ''), nullif(spa.profile_image_path, ''), nullif(sra.profile_image_path, ''), '') = '' then ''
			when coalesce(nullif(sr.cover_image_path, ''), nullif(spa.profile_image_path, ''), nullif(sra.profile_image_path, ''), '') ~ '^https?://' then coalesce(nullif(sr.cover_image_path, ''), nullif(spa.profile_image_path, ''), nullif(sra.profile_image_path, ''), '')
			when left(coalesce(nullif(sr.cover_image_path, ''), nullif(spa.profile_image_path, ''), nullif(sra.profile_image_path, ''), ''), 1) = '/' then coalesce(nullif(sr.cover_image_path, ''), nullif(spa.profile_image_path, ''), nullif(sra.profile_image_path, ''), '')
			else '/' || coalesce(nullif(sr.cover_image_path, ''), nullif(spa.profile_image_path, ''), nullif(sra.profile_image_path, ''), '')
		end cover_image_url,
		case
			when coalesce(nullif(sr.cover_image_path, ''), nullif(spa.profile_image_path, ''), nullif(sra.profile_image_path, ''), '') = '' then ''
			when coalesce(nullif(sr.cover_image_path, ''), nullif(spa.profile_image_path, ''), nullif(sra.profile_image_path, ''), '') ~ '^https?://' then coalesce(nullif(sr.cover_image_path, ''), nullif(spa.profile_image_path, ''), nullif(sra.profile_image_path, ''), '')
			when left(coalesce(nullif(sr.cover_image_path, ''), nullif(spa.profile_image_path, ''), nullif(sra.profile_image_path, ''), ''), 1) = '/' then coalesce(nullif(sr.cover_image_path, ''), nullif(spa.profile_image_path, ''), nullif(sra.profile_image_path, ''), '')
			else '/' || coalesce(nullif(sr.cover_image_path, ''), nullif(spa.profile_image_path, ''), nullif(sra.profile_image_path, ''), '')
		end fallback_artwork_url,
		case when coalesce(st.audio_file_path, '') = '' or st.audio_file_path ~ '^https?://' or left(st.audio_file_path, 1) = '/' then coalesce(st.audio_file_path, '') else '/' || st.audio_file_path end audio_url,
		coalesce(st.audio_file_path, ''),
		case when coalesce(st.lyrics_file_path, '') = '' or st.lyrics_file_path ~ '^https?://' or left(st.lyrics_file_path, 1) = '/' then coalesce(st.lyrics_file_path, '') else '/' || st.lyrics_file_path end lyrics_url,
		coalesce(st.lyrics_file_path, ''),
		coalesce(st.artwork_source, 'embedded_audio'),
		'' genre,'' locale,coalesce(st.track_number,0),coalesce(st.disc_number,1),coalesce(st.duration,0),coalesce(st.lyric,''),st.release_date,coalesce(st.is_explicit,false),
		coalesce(array(select a.name from track_artists ta join artists a on a.id=ta.artist_id where ta.track_id=st.id and ta.role='featured' order by ta.position, a.name), '{}') featured_artists,
		coalesce(st.play_count,0),coalesce(st.download_count,0),false,false,coalesce(st.created_at,now()),
		m.delivered_at,m.read_at
		from messages m join users s on s.id=m.sender_id
		left join tracks st on st.id=m.shared_track_id
		left join releases sr on sr.id=st.release_id
		left join artists sra on sra.id=sr.primary_artist_id
		left join lateral (
			select a.id, a.name, a.profile_image_path
			from track_artists ta
			join artists a on a.id=ta.artist_id
			where ta.track_id=st.id and ta.role='primary'
			order by ta.position, a.name
			limit 1
		) spa on true`
}

func scanMessages(rows pgx.Rows) ([]domain.Message, error) {
	items := []domain.Message{}
	for rows.Next() {
		item, err := scanMessageRows(rows)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, rows.Err()
}

func scanMessage(row pgx.Row) (domain.Message, error) {
	item, err := scanMessageGeneric(row)
	return item, mapErr(err)
}

func scanMessageRows(rows pgx.Rows) (domain.Message, error) {
	return scanMessageGeneric(rows)
}

type rowScanner interface {
	Scan(dest ...any) error
}

func scanMessageGeneric(row rowScanner) (domain.Message, error) {
	var msg domain.Message
	var track domain.Track
	var trackID sql.NullString
	var releaseDate sql.NullTime
	err := row.Scan(&msg.ID, &msg.SenderID, &msg.SenderName, &msg.ReceiverID, &msg.Content, &msg.Timestamp, &msg.Status,
		&trackID, &track.Title, &track.Slug, &track.ArtistID, &track.ArtistName, &track.AlbumID, &track.AlbumTitle, &track.ReleaseID, &track.ReleaseTitle, &track.ReleaseType, &track.CoverImageURL, &track.FallbackArtwork, &track.AudioURL, &track.AudioFilePath, &track.LyricsURL, &track.LyricsFilePath, &track.ArtworkSource, &track.Genre, &track.Locale, &track.TrackNumber, &track.DiscNumber, &track.DurationSeconds, &track.Lyric, &releaseDate, &track.Explicit, &track.FeaturedArtists, &track.PlayCount, &track.DownloadCount, &track.IsLiked, &track.IsDownloaded, &track.CreatedAt,
		&msg.DeliveredAt, &msg.ReadAt)
	if err != nil {
		return msg, err
	}
	if trackID.Valid && trackID.String != "" {
		track.ID = trackID.String
		if releaseDate.Valid {
			track.ReleaseDate = &releaseDate.Time
		}
		msg.SharedTrack = &track
		msg.SongCard = &domain.SharedTrackCard{ID: track.ID, Title: track.Title, ArtistName: track.ArtistName, CoverImageURL: track.CoverImageURL, AudioURL: track.AudioURL}
	}
	return msg, nil
}

func normalizePage(page, limit int) (int, int) {
	if page < 1 {
		page = 1
	}
	if limit < 1 {
		limit = 20
	}
	if limit > 100 {
		limit = 100
	}
	return page, limit
}

func offset(page, limit int) int {
	return (page - 1) * limit
}

func paged[T any](data T, page, limit, total int) domain.Paged[T] {
	totalPages := 0
	if limit > 0 {
		totalPages = int(math.Ceil(float64(total) / float64(limit)))
	}
	return domain.Paged[T]{
		Data: data,
		Page: domain.Page{Page: page, Limit: limit, Total: total, TotalPages: totalPages, HasNext: page < totalPages},
	}
}

func mapErr(err error) error {
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	return err
}
