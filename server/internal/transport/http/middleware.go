package http

import (
	"context"
	stdhttp "net/http"
	"strings"

	"kipotify/internal/service"
)

type contextKey string

const userIDKey contextKey = "userID"

func (h *Handler) auth(next stdhttp.Handler) stdhttp.Handler {
	return stdhttp.HandlerFunc(func(w stdhttp.ResponseWriter, r *stdhttp.Request) {
		raw := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		userID := ""
		if raw != "" {
			var err error
			userID, err = h.app.UserIDFromToken(raw)
			if err != nil {
				writeError(w, service.ErrInvalidCredentials)
				return
			}
		} else if h.allowDemoAuth {
			userID = service.DemoUserID
		} else {
			writeError(w, service.ErrInvalidCredentials)
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), userIDKey, userID)))
	})
}

func userID(r *stdhttp.Request) string {
	id, _ := r.Context().Value(userIDKey).(string)
	return id
}
