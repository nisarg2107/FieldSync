// Package media stores content-addressed attachments separately from operation metadata.
package media

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fieldsync/internal/protocol"
	"io"
	"net/http"
	"os"
	"path/filepath"
)

type Store struct {
	Root   string
	remote *s3Storage
}

func (s *Store) path(workspace, hash string) (string, error) {
	if !protocol.ValidID(workspace) || !protocol.ValidHash(hash) {
		return "", errors.New("invalid workspace or photo hash")
	}
	return filepath.Join(s.Root, workspace, hash), nil
}
func (s *Store) Put(ctx context.Context, workspace, hash string, input io.Reader) error {
	path, err := s.path(workspace, hash)
	if err != nil {
		return err
	}
	data, err := io.ReadAll(io.LimitReader(input, protocol.MaxPhotoBytes+1))
	if err != nil {
		return err
	}
	if len(data) == 0 || len(data) > protocol.MaxPhotoBytes {
		return errors.New("photo must be between 1 byte and 8 MiB")
	}
	sum := sha256.Sum256(data)
	if hex.EncodeToString(sum[:]) != hash {
		return errors.New("photo checksum mismatch")
	}
	kind := http.DetectContentType(data)
	if kind != "image/jpeg" && kind != "image/png" && kind != "image/webp" {
		return errors.New("only JPEG, PNG, and WebP images are supported")
	}
	if s.remote != nil {
		return s.remote.put(ctx, workspace+"/"+hash, bytes.NewReader(data), kind)
	}
	if err = os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	if _, err = os.Stat(path); err == nil {
		return nil
	} // Same hash means the same bytes.
	temp, err := os.CreateTemp(filepath.Dir(path), ".upload-")
	if err != nil {
		return err
	}
	defer os.Remove(temp.Name())
	if _, err = temp.Write(data); err != nil {
		temp.Close()
		return err
	}
	if err = temp.Close(); err != nil {
		return err
	}
	if err = os.Rename(temp.Name(), path); err != nil {
		if _, existing := os.Stat(path); existing == nil {
			return nil
		}
		return err
	}
	return nil
}
func (s *Store) Open(ctx context.Context, workspace, hash string) (io.ReadCloser, error) {
	path, err := s.path(workspace, hash)
	if err != nil {
		return nil, err
	}
	if s.remote != nil {
		return s.remote.open(ctx, workspace+"/"+hash)
	}
	return os.Open(path)
}
