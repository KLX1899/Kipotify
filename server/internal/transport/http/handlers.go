package http

import (
	"encoding/json"
	"net/http"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"kipotify/internal/config"
	"kipotify/internal/domain"
	"kipotify/internal/service"
	wstransport "kipotify/internal/transport/ws"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/go-chi/cors"
)

type Handler struct {
	app           *service.App
	hub           *wstransport.Hub
	allowDemoAuth bool
}

func NewRouter(app *service.App, cfg config.Config) http.Handler {
	h := &Handler{app: app, allowDemoAuth: cfg.AllowDemoAuth}
	h.hub = wstransport.NewHub(app, cfg.CORSOrigins)
	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(middleware.RealIP)
	r.Use(middleware.Recoverer)
	r.Use(cors.Handler(cors.Options{
		AllowedOrigins:   cfg.CORSOrigins,
		AllowedMethods:   []string{"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"},
		AllowedHeaders:   []string{"Accept", "Authorization", "Content-Type", "X-CSRF-Token"},
		ExposedHeaders:   []string{"Link"},
		AllowCredentials: true,
		MaxAge:           300,
	}))

	r.Get("/healthz", h.health)
	r.Handle("/media/artwork/*", embeddedArtworkHandler("media/audio"))
	r.Handle("/media/*", http.StripPrefix("/media/", http.FileServer(http.Dir("media"))))
	r.Post("/api/auth/register", h.register)
	r.Post("/api/auth/login", h.login)
	r.Get("/api/ws/chat", h.hub.ServeHTTP)

	r.Group(func(r chi.Router) {
		r.Use(h.auth)
		r.Get("/api/tracks", h.compatTracks)
		r.Get("/api/tracks/{id}", h.track)
		r.Post("/api/tracks/{id}/like", h.toggleLike)
		r.Post("/api/tracks/{id}/download", h.download)
		r.Post("/api/tracks/{id}/play", h.recordPlay)
		r.Get("/api/v1/tracks", h.tracks)
		r.Get("/api/artists", h.artists)
		r.Get("/api/albums", h.albums)
		r.Get("/api/playlists", h.playlists)
		r.Post("/api/playlists", h.createPlaylist)
		r.Get("/api/playlists/{id}/tracks", h.playlistTracks)
		r.Post("/api/playlists/{id}/tracks", h.addPlaylistTrack)
		r.Get("/api/search", h.search)
		r.Get("/api/downloads/eligibility/{trackId}", h.downloadEligibility)

		r.Get("/api/user/profile", h.profile)
		r.Post("/api/user/premium/upgrade", h.upgradePremium)
		r.Get("/api/user/settings", h.settings)
		r.Put("/api/user/settings", h.updateSettings)
		r.Get("/api/user/liked-songs", h.liked)
		r.Get("/api/user/recently-played", h.recent)
		r.Get("/api/user/notifications", h.notifications)
		r.Post("/api/user/notifications/{id}/read", h.markNotificationRead)

		r.Get("/api/social/friends", h.friends)
		r.Get("/api/social/users", h.users)
		r.Post("/api/social/friends/{id}/follow", h.toggleFollow)
		r.Get("/api/social/followed-playlists", h.followedPlaylists)
		r.Get("/api/social/chat/{friendId}/messages", h.messages)
		r.Post("/api/social/chat/{friendId}/messages", h.sendMessage)
	})
	return r
}

func (h *Handler) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) register(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name     string `json:"name"`
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if decodeJSON(r, &req) != nil {
		writeError(w, service.ErrValidation)
		return
	}
	result, err := h.app.Register(r.Context(), req.Name, req.Email, req.Password)
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, result)
}

func (h *Handler) login(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if decodeJSON(r, &req) != nil {
		writeError(w, service.ErrValidation)
		return
	}
	result, err := h.app.Login(r.Context(), req.Email, req.Password)
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (h *Handler) compatTracks(w http.ResponseWriter, r *http.Request) {
	result, err := h.app.Tracks(r.Context(), userID(r), domain.TrackFilters{
		Query: r.URL.Query().Get("search"),
		Genre: r.URL.Query().Get("genre"),
		Page:  1,
		Limit: intParam(r, "limit", 100),
	})
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, compatTracks(result.Data))
}

func (h *Handler) tracks(w http.ResponseWriter, r *http.Request) {
	result, err := h.app.Tracks(r.Context(), userID(r), domain.TrackFilters{
		Query:    r.URL.Query().Get("search"),
		Genre:    r.URL.Query().Get("genre"),
		Locale:   r.URL.Query().Get("locale"),
		Section:  r.URL.Query().Get("section"),
		ArtistID: r.URL.Query().Get("artist_id"),
		Page:     intParam(r, "page", 1),
		Limit:    intParam(r, "limit", 20),
	})
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (h *Handler) track(w http.ResponseWriter, r *http.Request) {
	track, err := h.app.Track(r.Context(), userID(r), chi.URLParam(r, "id"))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, compatTrack(track))
}

func (h *Handler) toggleLike(w http.ResponseWriter, r *http.Request) {
	liked, err := h.app.ToggleLike(r.Context(), userID(r), chi.URLParam(r, "id"))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"trackId": chi.URLParam(r, "id"), "isLiked": liked})
}

func (h *Handler) download(w http.ResponseWriter, r *http.Request) {
	result, err := h.app.Download(r.Context(), userID(r), chi.URLParam(r, "id"))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (h *Handler) recordPlay(w http.ResponseWriter, r *http.Request) {
	if err := h.app.RecordPlay(r.Context(), userID(r), chi.URLParam(r, "id")); err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"success": true})
}

func (h *Handler) artists(w http.ResponseWriter, r *http.Request) {
	result, err := h.app.Artists(r.Context(), r.URL.Query().Get("search"), intParam(r, "page", 1), intParam(r, "limit", 20))
	respond(w, result, err)
}

func (h *Handler) albums(w http.ResponseWriter, r *http.Request) {
	result, err := h.app.Albums(r.Context(), intParam(r, "page", 1), intParam(r, "limit", 20))
	respond(w, result, err)
}

func (h *Handler) playlists(w http.ResponseWriter, r *http.Request) {
	result, err := h.app.Playlists(r.Context(), userID(r), domain.PlaylistFilters{
		Category: r.URL.Query().Get("category"),
		Mine:     boolParam(r, "mine"),
		Followed: boolParam(r, "followed"),
		Page:     intParam(r, "page", 1),
		Limit:    intParam(r, "limit", 20),
	})
	respond(w, result, err)
}

func (h *Handler) createPlaylist(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name        string `json:"name"`
		Description string `json:"description"`
		Category    string `json:"category"`
		Visibility  string `json:"visibility"`
	}
	if decodeJSON(r, &req) != nil {
		writeError(w, service.ErrValidation)
		return
	}
	result, err := h.app.CreatePlaylist(r.Context(), userID(r), req.Name, req.Description, req.Category, req.Visibility)
	respond(w, result, err)
}

func (h *Handler) playlistTracks(w http.ResponseWriter, r *http.Request) {
	result, err := h.app.PlaylistTracks(r.Context(), userID(r), chi.URLParam(r, "id"), intParam(r, "page", 1), intParam(r, "limit", 20))
	respond(w, result, err)
}

func (h *Handler) addPlaylistTrack(w http.ResponseWriter, r *http.Request) {
	var req struct {
		TrackID string `json:"trackId"`
	}
	if decodeJSON(r, &req) != nil {
		writeError(w, service.ErrValidation)
		return
	}
	err := h.app.AddTrackToPlaylist(r.Context(), userID(r), chi.URLParam(r, "id"), req.TrackID)
	respond(w, map[string]bool{"success": err == nil}, err)
}

func (h *Handler) search(w http.ResponseWriter, r *http.Request) {
	result, err := h.app.Search(r.Context(), userID(r), r.URL.Query().Get("q"), r.URL.Query().Get("type"), r.URL.Query().Get("genre"), intParam(r, "page", 1), intParam(r, "limit", 20))
	respond(w, result, err)
}

func (h *Handler) downloadEligibility(w http.ResponseWriter, r *http.Request) {
	user, err := h.app.Profile(r.Context(), userID(r))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"trackId": chi.URLParam(r, "trackId"), "canDownload": user.IsPremium, "isPremium": user.IsPremium})
}

func (h *Handler) profile(w http.ResponseWriter, r *http.Request) {
	user, err := h.app.Profile(r.Context(), userID(r))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"userId": user.ID, "name": user.Name, "email": user.Email, "isPremium": user.IsPremium, "language": user.Language,
	})
}

func (h *Handler) upgradePremium(w http.ResponseWriter, r *http.Request) {
	user, err := h.app.UpgradePremium(r.Context(), userID(r))
	if err != nil {
		writeError(w, err)
		return
	}
	expires := int64(0)
	if user.PremiumExpires != nil {
		expires = user.PremiumExpires.UnixMilli()
	}
	writeJSON(w, http.StatusOK, map[string]any{"success": true, "expiresAt": expires, "message": "Premium downloads are now enabled."})
}

func (h *Handler) settings(w http.ResponseWriter, r *http.Request) {
	user, err := h.app.Profile(r.Context(), userID(r))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"language": user.Language, "theme": user.Theme})
}

func (h *Handler) updateSettings(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Language string `json:"language"`
		Theme    string `json:"theme"`
	}
	if decodeJSON(r, &req) != nil {
		writeError(w, service.ErrValidation)
		return
	}
	user, err := h.app.UpdateSettings(r.Context(), userID(r), req.Language, req.Theme)
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"success": true, "language": user.Language, "theme": user.Theme})
}

func (h *Handler) liked(w http.ResponseWriter, r *http.Request) {
	result, err := h.app.Liked(r.Context(), userID(r), intParam(r, "page", 1), intParam(r, "limit", 20))
	respond(w, result, err)
}

func (h *Handler) recent(w http.ResponseWriter, r *http.Request) {
	result, err := h.app.Recent(r.Context(), userID(r), intParam(r, "page", 1), intParam(r, "limit", 20))
	respond(w, result, err)
}

func (h *Handler) notifications(w http.ResponseWriter, r *http.Request) {
	result, err := h.app.Notifications(r.Context(), userID(r), intParam(r, "page", 1), intParam(r, "limit", 20))
	respond(w, result, err)
}

func (h *Handler) markNotificationRead(w http.ResponseWriter, r *http.Request) {
	err := h.app.MarkNotificationRead(r.Context(), userID(r), chi.URLParam(r, "id"))
	respond(w, map[string]bool{"success": err == nil}, err)
}

func (h *Handler) friends(w http.ResponseWriter, r *http.Request) {
	result, err := h.app.Users(r.Context(), userID(r), r.URL.Query().Get("search"), 1, 100)
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, compatFriends(result.Data))
}

func (h *Handler) users(w http.ResponseWriter, r *http.Request) {
	result, err := h.app.Users(r.Context(), userID(r), r.URL.Query().Get("search"), intParam(r, "page", 1), intParam(r, "limit", 20))
	respond(w, result, err)
}

func (h *Handler) toggleFollow(w http.ResponseWriter, r *http.Request) {
	following, err := h.app.ToggleFollow(r.Context(), userID(r), chi.URLParam(r, "id"))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"friendId": chi.URLParam(r, "id"), "isFollowing": following})
}

func (h *Handler) followedPlaylists(w http.ResponseWriter, r *http.Request) {
	result, err := h.app.FollowedPlaylists(r.Context(), userID(r), intParam(r, "page", 1), intParam(r, "limit", 20))
	respond(w, result, err)
}

func (h *Handler) messages(w http.ResponseWriter, r *http.Request) {
	var before *time.Time
	if raw := r.URL.Query().Get("before"); raw != "" {
		if millis, err := strconv.ParseInt(raw, 10, 64); err == nil {
			t := time.UnixMilli(millis)
			before = &t
		}
	}
	msgs, err := h.app.Messages(r.Context(), userID(r), chi.URLParam(r, "friendId"), before, intParam(r, "limit", 50))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, compatMessages(msgs))
}

func (h *Handler) sendMessage(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Content       string  `json:"content"`
		SharedTrackID *string `json:"sharedTrackId"`
	}
	if decodeJSON(r, &req) != nil {
		writeError(w, service.ErrValidation)
		return
	}
	msg, err := h.app.SendMessage(r.Context(), userID(r), chi.URLParam(r, "friendId"), req.Content, req.SharedTrackID)
	if err != nil {
		writeError(w, err)
		return
	}
	h.hub.SendTo(chi.URLParam(r, "friendId"), "message.created", msg)
	writeJSON(w, http.StatusCreated, compatMessage(msg))
}

type compatTrackDTO struct {
	ID                 string  `json:"id"`
	Title              string  `json:"title"`
	ArtistName         string  `json:"artistName"`
	AlbumTitle         string  `json:"albumTitle"`
	CoverImageURL      string  `json:"coverImageUrl"`
	FallbackArtworkURL string  `json:"fallbackArtworkUrl"`
	AudioURL           string  `json:"audioUrl"`
	AudioFilePath      string  `json:"audioFilePath"`
	LyricsURL          string  `json:"lyricsUrl,omitempty"`
	LyricsFilePath     string  `json:"lyricsFilePath,omitempty"`
	ArtworkSource      string  `json:"artworkSource"`
	IsLiked            bool    `json:"isLiked"`
	IsDownloaded       bool    `json:"isDownloaded"`
	LocalFilePath      *string `json:"localFilePath"`
	DurationSeconds    int     `json:"durationSeconds"`
}

type compatFriendDTO struct {
	ID                 string           `json:"id"`
	Name               string           `json:"name"`
	AvatarURL          string           `json:"avatarUrl"`
	IsFollowing        bool             `json:"isFollowing"`
	PublicPlaylistName string           `json:"publicPlaylistName"`
	PublicTracks       []compatTrackDTO `json:"publicTracks"`
}

type compatMessageDTO struct {
	ID          string          `json:"id"`
	SenderID    string          `json:"senderId"`
	SenderName  string          `json:"senderName"`
	Content     string          `json:"content"`
	Timestamp   int64           `json:"timestamp"`
	Status      string          `json:"status"`
	SharedTrack *compatTrackDTO `json:"sharedTrack"`
}

func compatTrack(track domain.Track) compatTrackDTO {
	coverImageURL := track.CoverImageURL
	if track.ArtworkSource == "embedded_audio" {
		if embeddedURL := embeddedArtworkURL(track.AudioFilePath); embeddedURL != "" {
			coverImageURL = embeddedURL
		}
	}
	return compatTrackDTO{
		ID:                 track.ID,
		Title:              track.Title,
		ArtistName:         track.ArtistName,
		AlbumTitle:         track.AlbumTitle,
		CoverImageURL:      coverImageURL,
		FallbackArtworkURL: track.FallbackArtwork,
		AudioURL:           track.AudioURL,
		AudioFilePath:      track.AudioFilePath,
		LyricsURL:          track.LyricsURL,
		LyricsFilePath:     track.LyricsFilePath,
		ArtworkSource:      track.ArtworkSource,
		IsLiked:            track.IsLiked,
		IsDownloaded:       track.IsDownloaded,
		LocalFilePath:      track.LocalFilePath,
		DurationSeconds:    track.DurationSeconds,
	}
}

func embeddedArtworkURL(audioFilePath string) string {
	normalized := strings.TrimPrefix(filepath.ToSlash(audioFilePath), "/")
	const mediaAudioPrefix = "media/audio/"
	if !strings.HasPrefix(normalized, mediaAudioPrefix) {
		return ""
	}
	relative := strings.TrimPrefix(normalized, mediaAudioPrefix)
	if relative == "" {
		return ""
	}
	return (&url.URL{Path: "/media/artwork/" + relative}).EscapedPath()
}

func compatTracks(tracks []domain.Track) []compatTrackDTO {
	out := make([]compatTrackDTO, 0, len(tracks))
	for _, track := range tracks {
		out = append(out, compatTrack(track))
	}
	return out
}

func compatFriends(users []domain.PublicUser) []compatFriendDTO {
	out := make([]compatFriendDTO, 0, len(users))
	for _, user := range users {
		playlist := user.PublicPlaylistName
		if playlist == "" {
			playlist = "Vibe Zone"
		}
		out = append(out, compatFriendDTO{
			ID:                 user.ID,
			Name:               user.Name,
			AvatarURL:          user.AvatarURL,
			IsFollowing:        user.IsFollowing,
			PublicPlaylistName: playlist,
			PublicTracks:       []compatTrackDTO{},
		})
	}
	return out
}

func compatMessage(message domain.Message) compatMessageDTO {
	var track *compatTrackDTO
	if message.SharedTrack != nil {
		dto := compatTrack(*message.SharedTrack)
		track = &dto
	}
	return compatMessageDTO{
		ID:          message.ID,
		SenderID:    message.SenderID,
		SenderName:  message.SenderName,
		Content:     message.Content,
		Timestamp:   message.Timestamp,
		Status:      message.Status,
		SharedTrack: track,
	}
}

func compatMessages(messages []domain.Message) []compatMessageDTO {
	out := make([]compatMessageDTO, 0, len(messages))
	for _, message := range messages {
		out = append(out, compatMessage(message))
	}
	return out
}

func respond(w http.ResponseWriter, value any, err error) {
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, value)
}

func decodeJSON(r *http.Request, value any) error {
	defer r.Body.Close()
	return json.NewDecoder(r.Body).Decode(value)
}

func intParam(r *http.Request, key string, fallback int) int {
	value, err := strconv.Atoi(r.URL.Query().Get(key))
	if err != nil {
		return fallback
	}
	return value
}

func boolParam(r *http.Request, key string) bool {
	value, _ := strconv.ParseBool(r.URL.Query().Get(key))
	return value
}
