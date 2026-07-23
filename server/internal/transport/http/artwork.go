package http

import (
	"bytes"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/dhowden/tag"
	"github.com/go-chi/chi/v5"
)

const maxEmbeddedArtworkBytes = 20 * 1024 * 1024

func embeddedArtworkHandler(audioRoot string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		audioPath, ok := resolveAudioPath(audioRoot, chi.URLParam(r, "*"))
		if !ok {
			http.NotFound(w, r)
			return
		}

		file, err := os.Open(audioPath)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		defer file.Close()

		artwork, contentType, err := readEmbeddedArtwork(file)
		if err != nil || len(artwork) == 0 || len(artwork) > maxEmbeddedArtworkBytes {
			http.NotFound(w, r)
			return
		}

		info, err := file.Stat()
		if err != nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Cache-Control", "public, max-age=86400")
		w.Header().Set("Content-Type", contentType)
		http.ServeContent(w, r, filepath.Base(audioPath), info.ModTime(), bytes.NewReader(artwork))
	}
}

func readEmbeddedArtwork(source io.ReadSeeker) ([]byte, string, error) {
	metadata, err := tag.ReadFrom(source)
	if err != nil {
		return nil, "", err
	}
	picture := metadata.Picture()
	if picture == nil || len(picture.Data) == 0 {
		return nil, "", os.ErrNotExist
	}

	contentType := strings.ToLower(strings.TrimSpace(picture.MIMEType))
	if contentType == "image/jpg" {
		contentType = "image/jpeg"
	}
	if !strings.HasPrefix(contentType, "image/") {
		contentType = mime.TypeByExtension("." + strings.TrimPrefix(picture.Ext, "."))
	}
	if !strings.HasPrefix(contentType, "image/") {
		contentType = http.DetectContentType(picture.Data)
	}
	if !strings.HasPrefix(contentType, "image/") {
		return nil, "", os.ErrInvalid
	}
	return picture.Data, contentType, nil
}

func resolveAudioPath(audioRoot, requestedPath string) (string, bool) {
	cleaned := filepath.Clean(filepath.FromSlash(strings.TrimPrefix(requestedPath, "/")))
	if cleaned == "." || filepath.IsAbs(cleaned) ||
		cleaned == ".." || strings.HasPrefix(cleaned, ".."+string(filepath.Separator)) {
		return "", false
	}

	fullPath := filepath.Join(audioRoot, cleaned)
	relative, err := filepath.Rel(audioRoot, fullPath)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", false
	}
	return fullPath, true
}
