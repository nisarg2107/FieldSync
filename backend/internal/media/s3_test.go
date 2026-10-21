package media

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"image"
	"image/png"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestS3AdapterWithLocalEndpoint(t *testing.T) {
	t.Setenv("AWS_ACCESS_KEY_ID", "test-key")
	t.Setenv("AWS_SECRET_ACCESS_KEY", "test-secret")
	t.Setenv("AWS_EC2_METADATA_DISABLED", "true")
	var stored []byte
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "PUT" {
			stored, _ = io.ReadAll(r.Body)
			w.WriteHeader(200)
			return
		}
		w.Header().Set("Content-Type", "image/png")
		_, _ = w.Write(stored)
	}))
	defer server.Close()
	storage, err := New(context.Background(), t.TempDir(), "photos", server.URL)
	if err != nil {
		t.Fatal(err)
	}
	var imageBytes bytes.Buffer
	_ = png.Encode(&imageBytes, image.NewRGBA(image.Rect(0, 0, 2, 2)))
	sum := sha256.Sum256(imageBytes.Bytes())
	hash := hex.EncodeToString(sum[:])
	if err = storage.Put(context.Background(), "demo", hash, bytes.NewReader(imageBytes.Bytes())); err != nil {
		t.Fatal(err)
	}
	result, err := storage.Open(context.Background(), "demo", hash)
	if err != nil {
		t.Fatal(err)
	}
	defer result.Close()
	actual, _ := io.ReadAll(result)
	if !bytes.Equal(actual, imageBytes.Bytes()) {
		t.Fatal("S3 adapter changed photo bytes")
	}
}
