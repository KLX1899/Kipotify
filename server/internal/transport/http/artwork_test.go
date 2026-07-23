package http

import (
	"bytes"
	"encoding/binary"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"kipotify/internal/domain"

	"github.com/go-chi/chi/v5"
)

func TestEmbeddedArtworkHandlerServesID3Picture(t *testing.T) {
	audioRoot := t.TempDir()
	audioPath := filepath.Join(audioRoot, "artist", "song.mp3")
	if err := os.MkdirAll(filepath.Dir(audioPath), 0o755); err != nil {
		t.Fatal(err)
	}
	picture := []byte{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01}
	if err := os.WriteFile(audioPath, id3WithPicture(picture), 0o600); err != nil {
		t.Fatal(err)
	}

	router := chi.NewRouter()
	router.Handle("/media/artwork/*", embeddedArtworkHandler(audioRoot))
	request := httptest.NewRequest(http.MethodGet, "/media/artwork/artist/song.mp3", nil)
	response := httptest.NewRecorder()
	router.ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("got status %d, want 200", response.Code)
	}
	if got := response.Header().Get("Content-Type"); got != "image/png" {
		t.Fatalf("got content type %q, want image/png", got)
	}
	if !bytes.Equal(response.Body.Bytes(), picture) {
		t.Fatal("response did not contain the embedded picture")
	}

	headRequest := httptest.NewRequest(http.MethodHead, "/media/artwork/artist/song.mp3", nil)
	headResponse := httptest.NewRecorder()
	router.ServeHTTP(headResponse, headRequest)
	if headResponse.Code != http.StatusOK {
		t.Fatalf("HEAD got status %d, want 200", headResponse.Code)
	}
	if headResponse.Body.Len() != 0 {
		t.Fatal("HEAD response unexpectedly contained a body")
	}
}

func TestCompatTrackExposesServerEmbeddedArtwork(t *testing.T) {
	dto := compatTrack(domain.Track{
		ID:              "track-1",
		Title:           "Song",
		ArtistName:      "Artist",
		AlbumTitle:      "Album",
		AudioURL:        "/media/audio/Artist/Album/Song.mp3",
		AudioFilePath:   "media/audio/Artist/Album/Song.mp3",
		ArtworkSource:   "embedded_audio",
		FallbackArtwork: "/media/images/releases/album.jpg",
	})

	if dto.CoverImageURL != "/media/artwork/Artist/Album/Song.mp3" {
		t.Fatalf("unexpected artwork URL: %q", dto.CoverImageURL)
	}
	if dto.ArtworkSource != "embedded_audio" {
		t.Fatalf("artwork source was dropped: %q", dto.ArtworkSource)
	}
	if dto.FallbackArtworkURL != "/media/images/releases/album.jpg" {
		t.Fatalf("fallback artwork was dropped: %q", dto.FallbackArtworkURL)
	}
}

func TestResolveAudioPathRejectsTraversal(t *testing.T) {
	if _, ok := resolveAudioPath(t.TempDir(), "../../secret.mp3"); ok {
		t.Fatal("path traversal was accepted")
	}
}

func id3WithPicture(picture []byte) []byte {
	frameBody := []byte{0}
	frameBody = append(frameBody, []byte("image/png")...)
	frameBody = append(frameBody, 0, 3, 0)
	frameBody = append(frameBody, picture...)

	frame := []byte("APIC")
	size := make([]byte, 4)
	binary.BigEndian.PutUint32(size, uint32(len(frameBody)))
	frame = append(frame, size...)
	frame = append(frame, 0, 0)
	frame = append(frame, frameBody...)

	header := []byte{'I', 'D', '3', 3, 0, 0}
	header = append(header, synchsafe(len(frame))...)
	return append(header, frame...)
}

func synchsafe(value int) []byte {
	return []byte{
		byte(value >> 21 & 0x7f),
		byte(value >> 14 & 0x7f),
		byte(value >> 7 & 0x7f),
		byte(value & 0x7f),
	}
}
