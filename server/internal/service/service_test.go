package service

import (
	"context"
	"errors"
	"testing"
	"time"

	"kipotify/internal/config"
	"kipotify/internal/domain"
	"kipotify/internal/repository"
)

func TestTokenRoundTrip(t *testing.T) {
	app := New(&fakeStore{}, config.Config{JWTSecret: "test-secret", JWTIssuer: "test", TokenTTL: time.Hour})
	now := time.Now()
	app.now = func() time.Time { return now }

	token, err := app.TokenForUser("user-1")
	if err != nil {
		t.Fatalf("TokenForUser returned error: %v", err)
	}
	userID, err := app.UserIDFromToken(token)
	if err != nil {
		t.Fatalf("UserIDFromToken returned error: %v", err)
	}
	if userID != "user-1" {
		t.Fatalf("user id = %q, want user-1", userID)
	}
}

func TestDownloadRequiresPremium(t *testing.T) {
	store := &fakeStore{user: domain.User{ID: "user-1", IsPremium: false}}
	app := New(store, config.Config{JWTSecret: "test-secret", JWTIssuer: "test", TokenTTL: time.Hour})

	_, err := app.Download(context.Background(), "user-1", "track-1")
	if !errors.Is(err, ErrPremiumRequired) {
		t.Fatalf("download error = %v, want ErrPremiumRequired", err)
	}
	if store.downloadCalled {
		t.Fatal("RecordDownload should not be called for non-premium users")
	}
}

func TestPremiumDownloadRecordsDownload(t *testing.T) {
	store := &fakeStore{user: domain.User{ID: "user-1", IsPremium: true}, downloadCount: 7}
	app := New(store, config.Config{JWTSecret: "test-secret", JWTIssuer: "test", TokenTTL: time.Hour})

	result, err := app.Download(context.Background(), "user-1", "track-1")
	if err != nil {
		t.Fatalf("Download returned error: %v", err)
	}
	if !result.Success || result.DownloadCount != 7 || result.TrackID != "track-1" {
		t.Fatalf("unexpected result: %#v", result)
	}
	if !store.downloadCalled {
		t.Fatal("RecordDownload was not called for premium user")
	}
}

type fakeStore struct {
	user           domain.User
	downloadCount  int
	downloadCalled bool
}

func (f *fakeStore) CreateUser(context.Context, string, string, string) (domain.User, error) {
	return f.user, nil
}
func (f *fakeStore) UserByEmail(context.Context, string) (repository.UserWithPassword, error) {
	panic("not used")
}
func (f *fakeStore) UserByID(context.Context, string) (domain.User, error) {
	return f.user, nil
}
func (f *fakeStore) UpdateSettings(context.Context, string, string, string) (domain.User, error) {
	panic("not used")
}
func (f *fakeStore) UpgradePremium(context.Context, string, time.Time) (domain.User, error) {
	panic("not used")
}
func (f *fakeStore) ListTracks(context.Context, string, domain.TrackFilters) (domain.Paged[[]domain.Track], error) {
	panic("not used")
}
func (f *fakeStore) TrackByID(context.Context, string, string) (domain.Track, error) {
	panic("not used")
}
func (f *fakeStore) ToggleLike(context.Context, string, string) (bool, error) {
	panic("not used")
}
func (f *fakeStore) RecordDownload(context.Context, string, string) (int, error) {
	f.downloadCalled = true
	return f.downloadCount, nil
}
func (f *fakeStore) RecordPlay(context.Context, string, string) error {
	panic("not used")
}
func (f *fakeStore) ListLiked(context.Context, string, int, int) (domain.Paged[[]domain.Track], error) {
	panic("not used")
}
func (f *fakeStore) ListRecent(context.Context, string, int, int) (domain.Paged[[]domain.Track], error) {
	panic("not used")
}
func (f *fakeStore) ListArtists(context.Context, string, int, int) (domain.Paged[[]domain.Artist], error) {
	panic("not used")
}
func (f *fakeStore) ListAlbums(context.Context, int, int) (domain.Paged[[]domain.Album], error) {
	panic("not used")
}
func (f *fakeStore) ListPlaylists(context.Context, string, domain.PlaylistFilters) (domain.Paged[[]domain.Playlist], error) {
	panic("not used")
}
func (f *fakeStore) PlaylistTracks(context.Context, string, string, int, int) (domain.Paged[[]domain.Track], error) {
	panic("not used")
}
func (f *fakeStore) CreatePlaylist(context.Context, string, string, string, string, string) (domain.Playlist, error) {
	panic("not used")
}
func (f *fakeStore) AddTrackToPlaylist(context.Context, string, string, string) error {
	panic("not used")
}
func (f *fakeStore) Search(context.Context, string, string, string, string, int, int) (domain.SearchResults, error) {
	panic("not used")
}
func (f *fakeStore) ListUsers(context.Context, string, string, int, int) (domain.Paged[[]domain.PublicUser], error) {
	panic("not used")
}
func (f *fakeStore) ToggleFollow(context.Context, string, string) (bool, error) {
	panic("not used")
}
func (f *fakeStore) PublicPlaylistsOfFollowed(context.Context, string, int, int) (domain.Paged[[]domain.Playlist], error) {
	panic("not used")
}
func (f *fakeStore) Notifications(context.Context, string, int, int) (domain.Paged[[]domain.Notification], error) {
	panic("not used")
}
func (f *fakeStore) MarkNotificationRead(context.Context, string, string) error {
	panic("not used")
}
func (f *fakeStore) ListMessages(context.Context, string, string, *time.Time, int) ([]domain.Message, error) {
	panic("not used")
}
func (f *fakeStore) CreateMessage(context.Context, string, string, string, *string) (domain.Message, error) {
	panic("not used")
}
func (f *fakeStore) MarkDelivered(context.Context, string, string) (domain.Message, error) {
	panic("not used")
}
func (f *fakeStore) MarkRead(context.Context, string, string) (domain.Message, error) {
	panic("not used")
}
