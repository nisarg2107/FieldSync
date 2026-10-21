// Package simulation drives the real HTTP handler with repeatable request/response faults.
// It models replicas in Go; Android persistence is tested separately with Room.
package simulation

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sort"
	"time"

	"fieldsync/internal/api"
	"fieldsync/internal/crdt"
	"fieldsync/internal/protocol"
	"fieldsync/internal/store"
)

type Replica struct {
	Actor   string
	Clock   int64
	Cursor  int64
	Known   map[string]protocol.Operation
	Pending map[string]protocol.Operation
	Tasks   map[string]crdt.Task
}

func NewReplica(actor string) *Replica {
	return &Replica{Actor: actor, Known: map[string]protocol.Operation{}, Pending: map[string]protocol.Operation{}, Tasks: map[string]crdt.Task{}}
}

func (r *Replica) receive(op protocol.Operation, cloud bool) {
	r.Clock = max(r.Clock, op.Clock)
	if _, exists := r.Known[op.ID]; !exists {
		r.Known[op.ID] = op
		crdt.Apply(r.Tasks, op)
		if !cloud {
			r.Pending[op.ID] = op
		}
	}
	if cloud {
		delete(r.Pending, op.ID)
	}
}

func (r *Replica) Edit(taskID, field, value string) {
	r.Clock++
	op := protocol.Operation{ID: fmt.Sprintf("%s-%d", r.Actor, r.Clock), ActorID: r.Actor, TaskID: taskID, Clock: r.Clock, Field: field, Value: value}
	r.receive(op, false)
}

// ExchangePeer needs no cloud. Received operations remain eligible for cloud upload.
func ExchangePeer(a, b *Replica) {
	for _, op := range a.Known {
		b.receive(op, false)
	}
	for _, op := range b.Known {
		a.receive(op, false)
	}
}

type Config struct {
	Seed        int64         `json:"seed"`
	Replicas    int           `json:"replicas"`
	Edits       int           `json:"edits"`
	FailureRate float64       `json:"failureRate"`
	Delay       time.Duration `json:"-"`
}

type Report struct {
	Config               Config  `json:"config"`
	Converged            bool    `json:"converged"`
	UniqueOperations     int     `json:"uniqueOperations"`
	Requests             int     `json:"requests"`
	DroppedRequests      int     `json:"droppedRequests"`
	LostResponses        int     `json:"lostResponses"`
	DuplicateDeliveries  int     `json:"duplicateDeliveries"`
	ReorderedResponses   int     `json:"reorderedResponses"`
	Restarts             int     `json:"restarts"`
	RecoveryRequests     int     `json:"recoveryRequests"`
	PayloadBytes         int     `json:"jsonPayloadBytes"`
	IdleResponseBytes    int     `json:"idleResponseBytes"`
	FullLogResponseBytes int     `json:"fullLogResponseBytes"`
	ElapsedMS            float64 `json:"elapsedMs"`
	RecoveryMS           float64 `json:"recoveryMs"`
}

type link struct {
	handler http.Handler
	rng     *rand.Rand
	failure float64
	delay   time.Duration
	report  *Report
}

func (l *link) sync(r *Replica) (bool, error) {
	l.report.Requests++
	if l.delay > 0 {
		time.Sleep(l.delay)
	}
	if l.rng.Float64() < l.failure/2 {
		l.report.DroppedRequests++
		return false, nil
	}
	pending := make([]protocol.Operation, 0, len(r.Pending))
	for _, op := range r.Pending {
		pending = append(pending, op)
	}
	sort.Slice(pending, func(i, j int) bool { return pending[i].ID < pending[j].ID })
	pending = pending[:min(len(pending), protocol.PageSize)]
	request := protocol.SyncRequest{Workspace: "simulation", Cursor: r.Cursor, Operations: pending}
	body, err := json.Marshal(request)
	if err != nil {
		return false, err
	}
	response := httptest.NewRecorder()
	l.handler.ServeHTTP(response, httptest.NewRequest(http.MethodPost, "/v1/sync", bytes.NewReader(body)))
	l.report.PayloadBytes += len(body) + response.Body.Len()
	if response.Code != http.StatusOK {
		return false, fmt.Errorf("HTTP %d: %s", response.Code, response.Body.String())
	}
	// Commit succeeded, but acknowledgment never reaches the replica. Its outbox stays intact.
	if l.rng.Float64() < l.failure/2 {
		l.report.LostResponses++
		return false, nil
	}
	var page protocol.SyncResponse
	if err = json.Unmarshal(response.Body.Bytes(), &page); err != nil {
		return false, err
	}
	if len(page.Operations) > 1 {
		l.rng.Shuffle(len(page.Operations), func(i, j int) { page.Operations[i], page.Operations[j] = page.Operations[j], page.Operations[i] })
		l.report.ReorderedResponses++
	}
	for _, op := range page.Operations {
		r.receive(op, true)
		if l.rng.Intn(3) == 0 {
			r.receive(op, true)
			l.report.DuplicateDeliveries++
		}
	}
	for _, id := range page.Accepted {
		delete(r.Pending, id)
	}
	r.Cursor = page.Cursor
	return !page.HasMore && len(r.Pending) == 0, nil
}

func Run(config Config) (Report, error) {
	report := Report{Config: config}
	if config.Replicas < 2 || config.Replicas > 50 || config.Edits < 1 || config.Edits > 10000 || config.FailureRate < 0 || config.FailureRate > 1 || config.Delay < 0 {
		return report, fmt.Errorf("use 2–50 replicas, 1–10000 edits per replica, failure rate 0–1, and nonnegative delay")
	}
	start := time.Now()
	replicas := make([]*Replica, config.Replicas)
	for i := range replicas {
		r := NewReplica(fmt.Sprintf("device-%02d", i))
		replicas[i] = r
		// Partition: all replicas make concurrent edits without communicating.
		for edit := 0; edit < config.Edits; edit++ {
			field, value := "title", fmt.Sprintf("Inspection %d by %s", edit, r.Actor)
			if edit%3 == 1 {
				field, value = "completed", fmt.Sprint(edit%2 == 0)
			}
			if edit%3 == 2 {
				field, value = "deleted", fmt.Sprint(edit%2 == 0)
			}
			r.Edit(fmt.Sprintf("task-%02d", edit%10), field, value)
		}
	}
	ExchangePeer(replicas[0], replicas[1])
	l := link{handler: api.Handler(store.NewMemory()), rng: rand.New(rand.NewSource(config.Seed)), failure: config.FailureRate, delay: config.Delay, report: &report}
	for round := 0; round < 8; round++ {
		for _, r := range replicas {
			if _, err := l.sync(r); err != nil {
				return report, err
			}
		}
		if round == 3 {
			for i, r := range replicas {
				// Persist/reload all client metadata as a process-restart model.
				data, err := json.Marshal(r)
				if err != nil {
					return report, err
				}
				var restarted Replica
				if err = json.Unmarshal(data, &restarted); err != nil {
					return report, err
				}
				replicas[i] = &restarted
				report.Restarts++
			}
		}
	}
	recovery := time.Now()
	beforeRecovery := report.Requests
	l.failure = 0
	// Stable connectivity: drain uploads, then make a second pass for late writers.
	for pass := 0; pass < 2; pass++ {
		for _, r := range replicas {
			for {
				done, err := l.sync(r)
				if err != nil {
					return report, err
				}
				if done {
					break
				}
			}
		}
	}
	report.RecoveryMS = float64(time.Since(recovery).Microseconds()) / 1000
	report.RecoveryRequests = report.Requests - beforeRecovery
	report.Converged = true
	expectedCount := config.Replicas * config.Edits
	for _, r := range replicas {
		if !reflect.DeepEqual(replicas[0].Tasks, r.Tasks) || len(r.Known) != expectedCount || len(r.Pending) != 0 {
			report.Converged = false
		}
	}
	// Compare an actual idle delta response to an equivalent full-log JSON response.
	full := []protocol.Operation{}
	for _, op := range replicas[0].Known {
		full = append(full, op)
	}
	sort.Slice(full, func(i, j int) bool { return full[i].ID < full[j].ID })
	fullJSON, _ := json.Marshal(protocol.SyncResponse{Accepted: []string{}, Operations: full, Cursor: replicas[0].Cursor})
	response := httptest.NewRecorder()
	idleBody, _ := json.Marshal(protocol.SyncRequest{Workspace: "simulation", Cursor: replicas[0].Cursor, Operations: []protocol.Operation{}})
	l.handler.ServeHTTP(response, httptest.NewRequest(http.MethodPost, "/v1/sync", bytes.NewReader(idleBody)))
	report.IdleResponseBytes = response.Body.Len()
	report.FullLogResponseBytes = len(fullJSON) + 1 // HTTP JSON encoder appends a newline.
	report.UniqueOperations = len(replicas[0].Known)
	report.ElapsedMS = float64(time.Since(start).Microseconds()) / 1000
	return report, nil
}
