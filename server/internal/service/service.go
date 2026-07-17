package service

import (
	"context"
	"errors"
	"strings"
	"time"

	"kipotify/internal/config"
	"kipotify/internal/domain"
	"kipotify/internal/repository"

	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"
)

const DemoUserID = "00000000-0000-4000-8000-000000000101"

var (
	ErrInvalidCredentials = errors.New("invalid email or password")
	ErrValidation         = errors.New("validation failed")
	ErrPremiumRequired    = errors.New("premium account required for downloads")
)

type App struct {
	store repository.Store
	cfg   config.Config
	now   func() time.Time
}

type AuthResult struct {
	Token string      `json:"token"`
	User  domain.User `json:"user"`
}

type DownloadResult struct {
	TrackID       string `json:"trackId"`
	DownloadCount int    `json:"downloadCount"`
	Success       bool   `json:"success"`
}

func New(store repository.Store, cfg config.Config) *App {
	return &App{store: store, cfg: cfg, now: time.Now}
}

func (a *App) Register(ctx context.Context, name, email, password string) (AuthResult, error) {
	name, email = strings.TrimSpace(name), strings.ToLower(strings.TrimSpace(email))
	if len(name) < 2 || !strings.Contains(email, "@") || len(password) < 8 {
		return AuthResult{}, ErrValidation
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return AuthResult{}, err
	}
	user, err := a.store.CreateUser(ctx, name, email, string(hash))
	if err != nil {
		return AuthResult{}, err
	}
	token, err := a.TokenForUser(user.ID)
	return AuthResult{Token: token, User: user}, err
}

func (a *App) Login(ctx context.Context, email, password string) (AuthResult, error) {
	record, err := a.store.UserByEmail(ctx, strings.ToLower(strings.TrimSpace(email)))
	if err != nil {
		return AuthResult{}, ErrInvalidCredentials
	}
	if bcrypt.CompareHashAndPassword([]byte(record.PasswordHash), []byte(password)) != nil {
		return AuthResult{}, ErrInvalidCredentials
	}
	token, err := a.TokenForUser(record.ID)
	return AuthResult{Token: token, User: record.User}, err
}

func (a *App) TokenForUser(userID string) (string, error) {
	claims := jwt.RegisteredClaims{
		Subject:   userID,
		Issuer:    a.cfg.JWTIssuer,
		IssuedAt:  jwt.NewNumericDate(a.now()),
		ExpiresAt: jwt.NewNumericDate(a.now().Add(a.cfg.TokenTTL)),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(a.cfg.JWTSecret))
}

func (a *App) UserIDFromToken(raw string) (string, error) {
	token, err := jwt.ParseWithClaims(raw, &jwt.RegisteredClaims{}, func(token *jwt.Token) (any, error) {
		return []byte(a.cfg.JWTSecret), nil
	}, jwt.WithIssuer(a.cfg.JWTIssuer))
	if err != nil || !token.Valid {
		return "", ErrInvalidCredentials
	}
	claims, ok := token.Claims.(*jwt.RegisteredClaims)
	if !ok || claims.Subject == "" {
		return "", ErrInvalidCredentials
	}
	return claims.Subject, nil
}

func (a *App) Profile(ctx context.Context, userID string) (domain.User, error) {
	return a.store.UserByID(ctx, userID)
}

func (a *App) UpdateSettings(ctx context.Context, userID, language, theme string) (domain.User, error) {
	if language == "" {
		language = "en"
	}
	if theme == "" {
		theme = "system"
	}
	if language != "en" && language != "fa" {
		return domain.User{}, ErrValidation
	}
	if theme != "system" && theme != "dark" && theme != "light" {
		return domain.User{}, ErrValidation
	}
	return a.store.UpdateSettings(ctx, userID, language, theme)
}

func (a *App) UpgradePremium(ctx context.Context, userID string) (domain.User, error) {
	return a.store.UpgradePremium(ctx, userID, a.now().AddDate(1, 0, 0))
}

func (a *App) Tracks(ctx context.Context, userID string, filters domain.TrackFilters) (domain.Paged[[]domain.Track], error) {
	return a.store.ListTracks(ctx, userID, filters)
}

func (a *App) Track(ctx context.Context, userID, trackID string) (domain.Track, error) {
	if strings.TrimSpace(trackID) == "" {
		return domain.Track{}, ErrValidation
	}
	return a.store.TrackByID(ctx, userID, trackID)
}

func (a *App) ToggleLike(ctx context.Context, userID, trackID string) (bool, error) {
	if trackID == "" {
		return false, ErrValidation
	}
	return a.store.ToggleLike(ctx, userID, trackID)
}

func (a *App) Download(ctx context.Context, userID, trackID string) (DownloadResult, error) {
	user, err := a.store.UserByID(ctx, userID)
	if err != nil {
		return DownloadResult{}, err
	}
	if !user.IsPremium {
		return DownloadResult{}, ErrPremiumRequired
	}
	count, err := a.store.RecordDownload(ctx, userID, trackID)
	return DownloadResult{TrackID: trackID, DownloadCount: count, Success: err == nil}, err
}

func (a *App) RecordPlay(ctx context.Context, userID, trackID string) error {
	return a.store.RecordPlay(ctx, userID, trackID)
}

func (a *App) Liked(ctx context.Context, userID string, page, limit int) (domain.Paged[[]domain.Track], error) {
	return a.store.ListLiked(ctx, userID, page, limit)
}

func (a *App) Recent(ctx context.Context, userID string, page, limit int) (domain.Paged[[]domain.Track], error) {
	return a.store.ListRecent(ctx, userID, page, limit)
}

func (a *App) Artists(ctx context.Context, query string, page, limit int) (domain.Paged[[]domain.Artist], error) {
	return a.store.ListArtists(ctx, query, page, limit)
}

func (a *App) Albums(ctx context.Context, page, limit int) (domain.Paged[[]domain.Album], error) {
	return a.store.ListAlbums(ctx, page, limit)
}

func (a *App) Playlists(ctx context.Context, userID string, filters domain.PlaylistFilters) (domain.Paged[[]domain.Playlist], error) {
	return a.store.ListPlaylists(ctx, userID, filters)
}

func (a *App) PlaylistTracks(ctx context.Context, userID, playlistID string, page, limit int) (domain.Paged[[]domain.Track], error) {
	return a.store.PlaylistTracks(ctx, userID, playlistID, page, limit)
}

func (a *App) CreatePlaylist(ctx context.Context, userID, name, description, category, visibility string) (domain.Playlist, error) {
	if strings.TrimSpace(name) == "" {
		return domain.Playlist{}, ErrValidation
	}
	return a.store.CreatePlaylist(ctx, userID, strings.TrimSpace(name), strings.TrimSpace(description), category, visibility)
}

func (a *App) AddTrackToPlaylist(ctx context.Context, userID, playlistID, trackID string) error {
	if playlistID == "" || trackID == "" {
		return ErrValidation
	}
	return a.store.AddTrackToPlaylist(ctx, userID, playlistID, trackID)
}

func (a *App) Search(ctx context.Context, userID, query, resultType, genre string, page, limit int) (domain.SearchResults, error) {
	if strings.TrimSpace(query) == "" {
		return domain.SearchResults{}, ErrValidation
	}
	return a.store.Search(ctx, userID, strings.TrimSpace(query), resultType, genre, page, limit)
}

func (a *App) Users(ctx context.Context, userID, query string, page, limit int) (domain.Paged[[]domain.PublicUser], error) {
	return a.store.ListUsers(ctx, userID, strings.TrimSpace(query), page, limit)
}

func (a *App) ToggleFollow(ctx context.Context, followerID, followedID string) (bool, error) {
	if followedID == "" || followedID == followerID {
		return false, ErrValidation
	}
	return a.store.ToggleFollow(ctx, followerID, followedID)
}

func (a *App) FollowedPlaylists(ctx context.Context, userID string, page, limit int) (domain.Paged[[]domain.Playlist], error) {
	return a.store.PublicPlaylistsOfFollowed(ctx, userID, page, limit)
}

func (a *App) Notifications(ctx context.Context, userID string, page, limit int) (domain.Paged[[]domain.Notification], error) {
	return a.store.Notifications(ctx, userID, page, limit)
}

func (a *App) MarkNotificationRead(ctx context.Context, userID, notificationID string) error {
	if notificationID == "" {
		return ErrValidation
	}
	return a.store.MarkNotificationRead(ctx, userID, notificationID)
}

func (a *App) Messages(ctx context.Context, userID, friendID string, before *time.Time, limit int) ([]domain.Message, error) {
	if friendID == "" {
		return nil, ErrValidation
	}
	return a.store.ListMessages(ctx, userID, friendID, before, limit)
}

func (a *App) SendMessage(ctx context.Context, userID, friendID, content string, sharedTrackID *string) (domain.Message, error) {
	if friendID == "" || (strings.TrimSpace(content) == "" && sharedTrackID == nil) {
		return domain.Message{}, ErrValidation
	}
	return a.store.CreateMessage(ctx, userID, friendID, strings.TrimSpace(content), sharedTrackID)
}

func (a *App) MarkDelivered(ctx context.Context, messageID, receiverID string) (domain.Message, error) {
	return a.store.MarkDelivered(ctx, messageID, receiverID)
}

func (a *App) MarkRead(ctx context.Context, messageID, readerID string) (domain.Message, error) {
	return a.store.MarkRead(ctx, messageID, readerID)
}
