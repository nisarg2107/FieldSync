// Package protocol contains the JSON types shared by cloud and Android clients.
package protocol

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

const PageSize = 100
const MaxClock int64 = 9_007_199_254_740_991 // Safe in JSON tools, well below int64 overflow.

var identifier = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,80}$`)

func ValidID(value string) bool { return identifier.MatchString(value) }

type Operation struct {
	ID        string `json:"id"`
	TaskID    string `json:"taskId"`
	ActorID   string `json:"actorId"`
	Clock     int64  `json:"clock"`
	Field     string `json:"field"`
	Value     string `json:"value"`
	CreatedAt int64  `json:"createdAt"`
	Author    string `json:"author"`
}

func (o Operation) Validate() error {
	if !ValidID(o.ID) || !ValidID(o.TaskID) || !ValidID(o.ActorID) {
		return fmt.Errorf("operation, task and actor IDs must be 1–80 ASCII letters, digits, _ or -")
	}
	if o.Clock < 1 || o.Clock > MaxClock {
		return fmt.Errorf("clock must be between 1 and %d", MaxClock)
	}
	if o.CreatedAt < 0 || utf8.RuneCountInString(o.Author) > 120 || strings.ContainsRune(o.Value, 0) || !utf8.ValidString(o.Value) {
		return fmt.Errorf("invalid operation metadata or text")
	}
	switch o.Field {
	case "title":
		if strings.TrimSpace(o.Value) == "" || !utf8.ValidString(o.Value) || utf8.RuneCountInString(o.Value) > 200 {
			return fmt.Errorf("title must contain 1–200 characters")
		}
	case "completed", "deleted":
		if o.Value != "true" && o.Value != "false" {
			return fmt.Errorf("boolean fields must be the string true or false")
		}
	case "description", "project", "priority", "dueDate", "assignee", "notes", "gps", "createdBy":
		if utf8.RuneCountInString(o.Value) > 2000 {
			return fmt.Errorf("text exceeds 2000 characters")
		}
		switch o.Field {
		case "priority":
			if o.Value != "low" && o.Value != "normal" && o.Value != "high" {
				return fmt.Errorf("invalid priority")
			}
		case "dueDate":
			if o.Value != "" {
				if _, err := time.Parse("2006-01-02", o.Value); err != nil {
					return fmt.Errorf("invalid due date")
				}
			}
		case "assignee":
			if o.Value != "" && !ValidID(o.Value) {
				return fmt.Errorf("invalid assignee")
			}
		case "gps":
			if o.Value != "" {
				var p GeoPoint
				if err := decodeValue(o.Value, &p, "latitude", "longitude", "accuracy", "recordedAt"); err != nil || math.IsNaN(p.Latitude) || math.IsNaN(p.Longitude) || p.Latitude < -90 || p.Latitude > 90 || p.Longitude < -180 || p.Longitude > 180 || p.Accuracy < 0 || p.RecordedAt < 0 {
					return fmt.Errorf("invalid location")
				}
			}
		}
	case "status":
		var change StatusChange
		if err := decodeValue(o.Value, &change, "value"); err != nil {
			return fmt.Errorf("invalid status change")
		}
		if change.Value != "open" && change.Value != "in_progress" && change.Value != "blocked" && change.Value != "completed" {
			return fmt.Errorf("invalid status")
		}
		if len(change.Observed) > 100 {
			return fmt.Errorf("too many status parents")
		}
		seen := map[string]bool{}
		for _, id := range change.Observed {
			if !ValidID(id) || id == o.ID || seen[id] {
				return fmt.Errorf("invalid status parent")
			}
			seen[id] = true
		}
	default:
		if strings.HasPrefix(o.Field, "check:") {
			parts := strings.Split(o.Field, ":")
			if len(parts) != 3 || !ValidID(parts[1]) {
				return fmt.Errorf("invalid checklist ID")
			}
			switch parts[2] {
			case "label":
				if strings.TrimSpace(o.Value) == "" || utf8.RuneCountInString(o.Value) > 200 {
					return fmt.Errorf("invalid checklist label")
				}
			case "note":
				if utf8.RuneCountInString(o.Value) > 2000 {
					return fmt.Errorf("checklist note too long")
				}
			case "result":
				if o.Value != "unchecked" && o.Value != "pass" && o.Value != "fail" {
					return fmt.Errorf("invalid checklist result")
				}
			case "deleted":
				if o.Value != "true" && o.Value != "false" {
					return fmt.Errorf("invalid checklist deletion")
				}
			default:
				return fmt.Errorf("unknown checklist field")
			}
		} else if strings.HasPrefix(o.Field, "photo:") {
			hash := strings.TrimPrefix(o.Field, "photo:")
			if !ValidHash(hash) {
				return fmt.Errorf("invalid photo hash")
			}
			if o.Value != "" {
				photo := Photo{MimeType: "image/jpeg"}
				if err := decodeValue(o.Value, &photo, "hash", "size"); err != nil || photo.Hash != hash || photo.Size < 1 || photo.Size > MaxPhotoBytes || utf8.RuneCountInString(photo.Caption) > 200 || (photo.MimeType != "image/jpeg" && photo.MimeType != "image/png" && photo.MimeType != "image/webp") {
					return fmt.Errorf("invalid photo metadata")
				}
			}
		} else {
			return fmt.Errorf("unknown field: %s", o.Field)
		}
	}
	return nil
}

const MaxPhotoBytes = 8 * 1024 * 1024

// Match Kotlin's non-null JSON models so the server cannot store an unreadable value.
func decodeValue(value string, target any, required ...string) error {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal([]byte(value), &fields); err != nil || fields == nil {
		return fmt.Errorf("expected a JSON object")
	}
	for _, key := range required {
		if _, ok := fields[key]; !ok {
			return fmt.Errorf("missing %s", key)
		}
	}
	for _, data := range fields {
		if bytes.Equal(bytes.TrimSpace(data), []byte("null")) {
			return fmt.Errorf("null field")
		}
	}
	decoder := json.NewDecoder(strings.NewReader(value))
	decoder.DisallowUnknownFields()
	return decoder.Decode(target)
}

var hashPattern = regexp.MustCompile(`^[a-f0-9]{64}$`)

func ValidHash(hash string) bool { return hashPattern.MatchString(hash) }

type StatusChange struct {
	Value    string   `json:"value"`
	Observed []string `json:"observed"`
}
type GeoPoint struct {
	Latitude   float64 `json:"latitude"`
	Longitude  float64 `json:"longitude"`
	Accuracy   float32 `json:"accuracy"`
	RecordedAt int64   `json:"recordedAt"`
}
type Photo struct {
	Hash     string `json:"hash"`
	Size     int64  `json:"size"`
	Caption  string `json:"caption"`
	MimeType string `json:"mimeType"`
}

type SyncRequest struct {
	Workspace  string      `json:"workspace"`
	Cursor     int64       `json:"cursor"`
	Operations []Operation `json:"operations"`
}

func (r SyncRequest) Validate() error {
	if !ValidID(r.Workspace) || r.Cursor < 0 || len(r.Operations) > PageSize {
		return fmt.Errorf("invalid workspace, negative cursor, or more than %d operations", PageSize)
	}
	for _, op := range r.Operations {
		if err := op.Validate(); err != nil {
			return err
		}
	}
	return nil
}

type SyncResponse struct {
	Accepted   []string    `json:"accepted"`
	Operations []Operation `json:"operations"`
	Cursor     int64       `json:"cursor"`
	HasMore    bool        `json:"hasMore"`
}
