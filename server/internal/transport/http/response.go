package http

import (
	"encoding/json"
	"errors"
	stdhttp "net/http"

	"kipotify/internal/repository"
	"kipotify/internal/service"
)

type errorBody struct {
	Error responseError `json:"error"`
}

type responseError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func writeJSON(w stdhttp.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w stdhttp.ResponseWriter, err error) {
	status, code := stdhttp.StatusInternalServerError, "internal_error"
	switch {
	case errors.Is(err, service.ErrValidation):
		status, code = stdhttp.StatusBadRequest, "validation_error"
	case errors.Is(err, service.ErrInvalidCredentials):
		status, code = stdhttp.StatusUnauthorized, "invalid_credentials"
	case errors.Is(err, service.ErrPremiumRequired):
		status, code = stdhttp.StatusForbidden, "premium_required"
	case errors.Is(err, repository.ErrNotFound):
		status, code = stdhttp.StatusNotFound, "not_found"
	}
	writeJSON(w, status, errorBody{Error: responseError{Code: code, Message: err.Error()}})
}
