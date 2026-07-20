package http

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"kipotify/internal/config"
)

func TestHealthHandler(t *testing.T) {
	router := NewRouter(nil, config.Config{CORSOrigins: []string{"*"}, AllowDemoAuth: true})
	req := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	rec := httptest.NewRecorder()

	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusOK)
	}
	if !strings.Contains(rec.Body.String(), `"status":"ok"`) {
		t.Fatalf("unexpected body: %s", rec.Body.String())
	}
}
