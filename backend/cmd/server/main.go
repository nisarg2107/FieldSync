package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"fieldsync/internal/api"
	"fieldsync/internal/auth"
	"fieldsync/internal/media"
	"fieldsync/internal/store"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	var db store.Store
	var accounts *auth.Service
	if url := os.Getenv("DATABASE_URL"); url != "" {
		startup, cancel := context.WithTimeout(ctx, 15*time.Second)
		postgres, err := store.OpenPostgres(startup, url)
		cancel()
		if err != nil {
			slog.Error("open postgres", "error", err)
			os.Exit(1)
		}
		defer postgres.Close()
		db = postgres
		secret := os.Getenv("JWT_SECRET")
		if secret == "" {
			secret = "fieldsync-local-learning-secret-change-for-shared-hosting"
			slog.Warn("using demo JWT signing secret")
		}
		accounts = auth.New(postgres.Pool(), secret)
	} else {
		slog.Warn("using in-memory log; data is lost on restart")
		db = store.NewMemory()
	}
	address := os.Getenv("ADDR")
	if address == "" {
		address = ":8080"
	}
	mediaRoot := os.Getenv("MEDIA_DIR")
	if mediaRoot == "" {
		mediaRoot = "./data/media"
	}
	files, err := media.New(ctx, mediaRoot, os.Getenv("S3_BUCKET"), os.Getenv("S3_ENDPOINT"))
	if err != nil {
		slog.Error("configure photo storage", "error", err)
		os.Exit(1)
	}
	services := api.Services{Accounts: accounts, Media: files}
	server := &http.Server{Addr: address, Handler: api.Handler(db, services), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 60 * time.Second, WriteTimeout: 60 * time.Second, IdleTimeout: time.Minute}
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdown)
	}()
	slog.Info("FieldSync listening", "address", address)
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		slog.Error("serve", "error", err)
		os.Exit(1)
	}
}
