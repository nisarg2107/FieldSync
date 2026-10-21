package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"fieldsync/internal/store"
)

func TestValidation(t *testing.T) {
	handler := Handler(store.NewMemory())
	for _, body := range []string{
		`{`, `{"workspace":"demo","cursor":-1,"operations":[]}`, `{"workspace":"demo","surprise":true}`,
		`{"workspace":"demo"} {}`, `{"workspace":"demo","operations":[{"id":"op","taskId":"task","actorId":"a","clock":1,"field":"completed","value":"yes"}]}`,
		strings.Repeat("x", 300000),
	} {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest(http.MethodPost, "/v1/sync", strings.NewReader(body)))
		if response.Code != http.StatusBadRequest {
			t.Fatalf("accepted malformed request: %d", response.Code)
		}
	}
}

func TestHTTPRetryAfterLostAcknowledgment(t *testing.T) {
	handler := Handler(store.NewMemory())
	body := []byte(`{"workspace":"demo","cursor":0,"operations":[{"id":"op","taskId":"task","actorId":"a","clock":1,"field":"title","value":"Offline task"}]}`)
	// First response is discarded as if Wi-Fi disappeared after the server committed.
	for attempt := 0; attempt < 2; attempt++ {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest(http.MethodPost, "/v1/sync", bytes.NewReader(body)))
		if response.Code != http.StatusOK {
			t.Fatal(response.Body.String())
		}
		var decoded map[string]any
		if err := json.Unmarshal(response.Body.Bytes(), &decoded); err != nil {
			t.Fatal(err)
		}
		if len(decoded["operations"].([]any)) != 1 {
			t.Fatal("lost response caused duplicate operation")
		}
	}
}
