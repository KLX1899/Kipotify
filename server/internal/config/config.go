package config

import (
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Port          string
	DatabaseURL   string
	JWTSecret     string
	JWTIssuer     string
	TokenTTL      time.Duration
	CORSOrigins   []string
	RunMigrations bool
	RunSeed       bool
	AllowDemoAuth bool
}

func Load() Config {
	return Config{
		Port:          get("PORT", "8080"),
		DatabaseURL:   get("DATABASE_URL", "postgres://kipotify:kipotify@localhost:5432/kipotify?sslmode=disable"),
		JWTSecret:     get("JWT_SECRET", "change-me-in-production"),
		JWTIssuer:     get("JWT_ISSUER", "kipotify"),
		TokenTTL:      durationHours("JWT_TTL_HOURS", 168),
		CORSOrigins:   split(get("CORS_ORIGINS", "*")),
		RunMigrations: boolEnv("RUN_MIGRATIONS", true),
		RunSeed:       boolEnv("RUN_SEED", true),
		AllowDemoAuth: boolEnv("ALLOW_DEMO_AUTH", true),
	}
}

func get(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func split(value string) []string {
	parts := strings.Split(value, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		if trimmed := strings.TrimSpace(part); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}

func boolEnv(key string, fallback bool) bool {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback
	}
	value, err := strconv.ParseBool(raw)
	if err != nil {
		return fallback
	}
	return value
}

func durationHours(key string, fallback int) time.Duration {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return time.Duration(fallback) * time.Hour
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value <= 0 {
		return time.Duration(fallback) * time.Hour
	}
	return time.Duration(value) * time.Hour
}
