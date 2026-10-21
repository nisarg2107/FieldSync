package api

import (
	"bufio"
	"fieldsync/internal/protocol"
	"io"
	"net/http"
)

func mediaRoutes(mux *http.ServeMux, services Services) {
	mux.HandleFunc("PUT /v1/media/{workspace}/{hash}", func(w http.ResponseWriter, r *http.Request) {
		workspace, hash := r.PathValue("workspace"), r.PathValue("hash")
		if !protocol.ValidID(workspace) || !protocol.ValidHash(hash) {
			fail(w, 400, "invalid photo address")
			return
		}
		if teamRole(w, r, services.Accounts, workspace, true) == "" {
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, protocol.MaxPhotoBytes)
		if err := services.Media.Put(r.Context(), workspace, hash, r.Body); err != nil {
			fail(w, 400, err.Error())
			return
		}
		writeJSON(w, 200, map[string]bool{"ok": true})
	})
	mux.HandleFunc("GET /v1/media/{workspace}/{hash}", func(w http.ResponseWriter, r *http.Request) {
		workspace, hash := r.PathValue("workspace"), r.PathValue("hash")
		if !protocol.ValidID(workspace) || !protocol.ValidHash(hash) {
			fail(w, 400, "invalid photo address")
			return
		}
		if teamRole(w, r, services.Accounts, workspace, false) == "" {
			return
		}
		file, err := services.Media.Open(r.Context(), workspace, hash)
		if err != nil {
			fail(w, 404, "photo not uploaded yet")
			return
		}
		defer file.Close()
		reader := bufio.NewReader(file)
		header, _ := reader.Peek(512)
		w.Header().Set("Content-Type", http.DetectContentType(header))
		_, _ = io.Copy(w, reader)
	})
}
