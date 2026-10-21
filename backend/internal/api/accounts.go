package api

import (
	"encoding/json"
	"fieldsync/internal/auth"
	"fieldsync/internal/media"
	"net/http"
)

type Services struct {
	Accounts *auth.Service
	Media    *media.Store
}

func decode(w http.ResponseWriter, r *http.Request, destination any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 256*1024)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		fail(w, 400, "invalid JSON")
		return false
	}
	return true
}
func userID(w http.ResponseWriter, r *http.Request, accounts *auth.Service) string {
	if accounts == nil {
		fail(w, 503, "accounts require the PostgreSQL server; offline demo remains available")
		return ""
	}
	id, err := accounts.Authenticate(r.Header.Get("Authorization"))
	if err != nil {
		fail(w, 401, err.Error())
		return ""
	}
	return id
}
func teamRole(w http.ResponseWriter, r *http.Request, accounts *auth.Service, team string, write bool) string {
	if team == "demo" {
		return "editor"
	} // An explicitly public, isolated learning workspace.
	user := userID(w, r, accounts)
	if user == "" {
		return ""
	}
	role, err := accounts.Role(r.Context(), team, user)
	if err != nil || (write && role == "viewer") {
		fail(w, 403, "team membership or editor access required")
		return ""
	}
	return role
}
func accountRoutes(mux *http.ServeMux, accounts *auth.Service) {
	for _, path := range []string{"/v1/auth/register", "/v1/auth/login"} {
		mux.HandleFunc("POST "+path, func(w http.ResponseWriter, r *http.Request) {
			if accounts == nil {
				fail(w, 503, "start the PostgreSQL-backed server to use accounts")
				return
			}
			var input struct {
				Name     string `json:"name"`
				Email    string `json:"email"`
				Password string `json:"password"`
			}
			if !decode(w, r, &input) {
				return
			}
			var session auth.Session
			var err error
			if r.URL.Path == "/v1/auth/register" {
				session, err = accounts.Register(r.Context(), input.Name, input.Email, input.Password)
			} else {
				session, err = accounts.Login(r.Context(), input.Email, input.Password)
			}
			if err != nil {
				fail(w, 400, err.Error())
				return
			}
			writeJSON(w, 200, session)
		})
	}
	mux.HandleFunc("GET /v1/teams", func(w http.ResponseWriter, r *http.Request) {
		user := userID(w, r, accounts)
		if user == "" {
			return
		}
		teams, err := accounts.Teams(r.Context(), user)
		if err != nil {
			fail(w, 500, "cannot load teams")
			return
		}
		writeJSON(w, 200, teams)
	})
	mux.HandleFunc("POST /v1/teams", func(w http.ResponseWriter, r *http.Request) {
		user := userID(w, r, accounts)
		if user == "" {
			return
		}
		var input struct {
			Name       string `json:"name"`
			InviteCode string `json:"inviteCode"`
		}
		if !decode(w, r, &input) {
			return
		}
		var team auth.Team
		var err error
		if input.InviteCode != "" {
			team, err = accounts.JoinTeam(r.Context(), user, input.InviteCode)
		} else {
			team, err = accounts.CreateTeam(r.Context(), user, input.Name)
		}
		if err != nil {
			fail(w, 400, err.Error())
			return
		}
		writeJSON(w, 200, team)
	})
	mux.HandleFunc("GET /v1/teams/{team}/members", func(w http.ResponseWriter, r *http.Request) {
		team := r.PathValue("team")
		if team == "demo" {
			writeJSON(w, 200, []auth.Member{})
			return
		}
		if teamRole(w, r, accounts, team, false) == "" {
			return
		}
		members, err := accounts.Members(r.Context(), team)
		if err != nil {
			fail(w, 500, "cannot load members")
			return
		}
		writeJSON(w, 200, members)
	})
	mux.HandleFunc("PUT /v1/teams/{team}/members", func(w http.ResponseWriter, r *http.Request) {
		team := r.PathValue("team")
		if team == "demo" {
			fail(w, 403, "public demo has no managed membership")
			return
		}
		if role := teamRole(w, r, accounts, team, true); role != "owner" {
			if role != "" {
				fail(w, 403, "only the owner can change roles")
			}
			return
		}
		var input struct {
			UserID string `json:"userId"`
			Role   string `json:"role"`
		}
		if !decode(w, r, &input) {
			return
		}
		if err := accounts.SetRole(r.Context(), team, input.UserID, input.Role); err != nil {
			fail(w, 400, err.Error())
			return
		}
		writeJSON(w, 200, map[string]bool{"ok": true})
	})
}
