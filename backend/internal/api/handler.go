package api

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"

	"fieldsync/internal/protocol"
	"fieldsync/internal/store"
)

// The server passes Services to enable account/team authorization and media.
// Tests and the fault simulator can omit it to use synthetic workspaces in isolation.
func Handler(db store.Store, options ...Services) http.Handler {
	mux := http.NewServeMux()
	var services Services
	if len(options) > 0 {
		services = options[0]
		accountRoutes(mux, services.Accounts)
		if services.Media != nil {
			mediaRoutes(mux, services)
		}
	}
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	mux.HandleFunc("POST /v1/sync", func(w http.ResponseWriter, r *http.Request) {
		r.Body = http.MaxBytesReader(w, r.Body, 2*1024*1024)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()
		var request protocol.SyncRequest
		if err := decoder.Decode(&request); err != nil {
			fail(w, http.StatusBadRequest, "invalid JSON or request too large")
			return
		}
		if err := decoder.Decode(&struct{}{}); err != io.EOF {
			fail(w, http.StatusBadRequest, "expected one JSON object")
			return
		}
		if err := request.Validate(); err != nil {
			fail(w, http.StatusBadRequest, err.Error())
			return
		}
		if len(options) > 0 {
			if teamRole(w, r, services.Accounts, request.Workspace, len(request.Operations) > 0) == "" {
				return
			}
			if request.Workspace != "demo" {
				for _, op := range request.Operations {
					if op.Field == "assignee" && op.Value != "" {
						if _, err := services.Accounts.Role(r.Context(), request.Workspace, op.Value); err != nil {
							fail(w, 400, "assignee must belong to this team")
							return
						}
					}
				}
			}
		}
		response, err := db.Exchange(r.Context(), request)
		if errors.Is(err, store.ErrConflict) || errors.Is(err, store.ErrCursor) {
			fail(w, http.StatusConflict, err.Error())
			return
		}
		if err != nil {
			slog.Error("exchange failed", "error", err)
			fail(w, http.StatusInternalServerError, "storage unavailable; retry later")
			return
		}
		writeJSON(w, http.StatusOK, response)
	})
	return mux
}

func fail(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
