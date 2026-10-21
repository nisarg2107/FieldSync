package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"image"
	"image/png"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"fieldsync/internal/auth"
	"fieldsync/internal/media"
	"fieldsync/internal/protocol"
	"fieldsync/internal/store"
)

func TestAccountsTeamsAndPrivateSync(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("set TEST_DATABASE_URL for account/team integration")
	}
	postgres, err := store.OpenPostgres(context.Background(), url)
	if err != nil {
		t.Fatal(err)
	}
	defer postgres.Close()
	accounts := auth.New(postgres.Pool(), "test-secret")
	handler := Handler(postgres, Services{Accounts: accounts, Media: &media.Store{Root: t.TempDir()}})
	request := func(method, path, token string, input any, expected int) *httptest.ResponseRecorder {
		t.Helper()
		data, _ := json.Marshal(input)
		req := httptest.NewRequest(method, path, bytes.NewReader(data))
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, req)
		if response.Code != expected {
			t.Fatalf("%s %s expected %d got %d: %s", method, path, expected, response.Code, response.Body.String())
		}
		return response
	}
	register := func(label string) auth.Session {
		t.Helper()
		email := fmt.Sprintf("%s-%d@example.test", label, time.Now().UnixNano())
		result := request("POST", "/v1/auth/register", "", map[string]string{"name": label, "email": email, "password": "test-password-123"}, 200)
		var session auth.Session
		if err = json.Unmarshal(result.Body.Bytes(), &session); err != nil {
			t.Fatal(err)
		}
		request("POST", "/v1/auth/login", "", map[string]string{"name": "", "email": email, "password": "wrong-password"}, 400)
		request("POST", "/v1/auth/login", "", map[string]string{"name": "", "email": email, "password": "test-password-123"}, 200)
		return session
	}
	owner, member, outsider := register("Owner"), register("Member"), register("Outsider")
	result := request("POST", "/v1/teams", owner.Token, map[string]string{"name": "Generic team", "inviteCode": ""}, 200)
	var team auth.Team
	_ = json.Unmarshal(result.Body.Bytes(), &team)
	request("POST", "/v1/teams", member.Token, map[string]string{"name": "", "inviteCode": team.InviteCode}, 200)
	path := "/v1/teams/" + team.ID + "/members"
	request("GET", path, outsider.Token, nil, 403)
	request("GET", path, owner.Token, nil, 200)
	op := protocol.Operation{ID: "shared-op", TaskID: "shared-task", ActorID: "phone-a", Clock: 1, Field: "title", Value: "Shared task", CreatedAt: 123, Author: "Owner"}
	sync := protocol.SyncRequest{Workspace: team.ID, Operations: []protocol.Operation{op}}
	request("POST", "/v1/sync", "", sync, 401)
	request("POST", "/v1/sync", outsider.Token, sync, 403)
	request("POST", "/v1/sync", member.Token, sync, 403)
	request("POST", "/v1/sync", owner.Token, sync, 200)
	request("POST", "/v1/sync", member.Token, protocol.SyncRequest{Workspace: team.ID}, 200)
	request("PUT", path, member.Token, map[string]string{"userId": member.User.ID, "role": "editor"}, 403)
	request("PUT", path, owner.Token, map[string]string{"userId": member.User.ID, "role": "editor"}, 200)
	request("POST", "/v1/sync", member.Token, sync, 200) // Identical replay by a team member.
	bad := op
	bad.ID = "assign"
	bad.Clock = 2
	bad.Field = "assignee"
	bad.Value = outsider.User.ID
	request("POST", "/v1/sync", owner.Token, protocol.SyncRequest{Workspace: team.ID, Operations: []protocol.Operation{bad}}, 400)
	bad.Value = member.User.ID
	request("POST", "/v1/sync", owner.Token, protocol.SyncRequest{Workspace: team.ID, Operations: []protocol.Operation{bad}}, 200)
	request("PUT", path, owner.Token, map[string]string{"userId": owner.User.ID, "role": "viewer"}, 400)
	request("GET", "/v1/teams", owner.Token+"tampered", nil, 401)
}

func TestPhotoIntegrityAndReplay(t *testing.T) {
	handler := Handler(store.NewMemory(), Services{Media: &media.Store{Root: t.TempDir()}})
	var data bytes.Buffer
	if err := png.Encode(&data, image.NewRGBA(image.Rect(0, 0, 4, 4))); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(data.Bytes())
	hash := hex.EncodeToString(sum[:])
	path := "/v1/media/demo/" + hash
	for i := 0; i < 2; i++ {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest("PUT", path, bytes.NewReader(data.Bytes())))
		if response.Code != 200 {
			t.Fatal(response.Body.String())
		}
	}
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest("GET", path, nil))
	if response.Code != 200 || !bytes.Equal(response.Body.Bytes(), data.Bytes()) {
		t.Fatal("photo round trip changed bytes")
	}
	bad := httptest.NewRecorder()
	handler.ServeHTTP(bad, httptest.NewRequest("PUT", path, bytes.NewBufferString("corrupt")))
	if bad.Code != 400 {
		t.Fatal("corrupt image accepted")
	}
	private := httptest.NewRecorder()
	handler.ServeHTTP(private, httptest.NewRequest("GET", "/v1/media/private/"+hash, nil))
	if private.Code == http.StatusOK {
		t.Fatal("private photo exposed without account")
	}
}
