package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"kipotify/internal/config"
	"kipotify/internal/database"
	"kipotify/internal/lan"
	"kipotify/internal/repository"
	"kipotify/internal/service"
	httptransport "kipotify/internal/transport/http"
)

func main() {
	cfg := config.Load()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	db, err := database.Connect(ctx, cfg.DatabaseURL)
	if err != nil {
		slog.Error("database connect failed", "error", err)
		os.Exit(1)
	}
	defer db.Close()

	if cfg.RunMigrations {
		if err := database.Migrate(ctx, db); err != nil {
			slog.Error("migration failed", "error", err)
			os.Exit(1)
		}
	}
	if cfg.RunSeed {
		if err := database.Seed(ctx, db); err != nil {
			slog.Error("seed failed", "error", err)
			os.Exit(1)
		}
	}
	if err := database.SyncMediaCatalog(ctx, db, "media/audio"); err != nil {
		slog.Error("media catalog sync failed", "error", err)
		os.Exit(1)
	}

	app := service.New(repository.NewPostgres(db), cfg)
	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           httptransport.NewRouter(app, cfg),
		ReadHeaderTimeout: 5 * time.Second,
	}
	advertiser, err := lan.StartAdvertisement(cfg)
	if err != nil {
		slog.Warn("mDNS advertisement disabled", "error", err)
	} else if advertiser != nil {
		defer advertiser.Shutdown()
		slog.Info("kipotify backend advertised on local network", "service", "_kipotify._tcp")
	} else {
		slog.Info("mDNS advertisement disabled; configure TLS or explicitly allow insecure development advertisement")
	}

	go func() {
		slog.Info("kipotify backend listening", "addr", server.Addr)
		serve := server.ListenAndServe
		if cfg.TLSCertFile != "" || cfg.TLSKeyFile != "" {
			if cfg.TLSCertFile == "" || cfg.TLSKeyFile == "" {
				slog.Error("both TLS_CERT_FILE and TLS_KEY_FILE must be configured together")
				stop()
				return
			}
			serve = func() error { return server.ListenAndServeTLS(cfg.TLSCertFile, cfg.TLSKeyFile) }
		}
		if err := serve(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("server failed", "error", err)
			stop()
		}
	}()

	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		slog.Error("graceful shutdown failed", "error", err)
	}
}
