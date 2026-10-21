package store

import (
	"context"
	"sync"

	"fieldsync/internal/protocol"
)

// Memory is useful for tests and a database-free demo. It is lost on restart.
type Memory struct {
	mu   sync.Mutex
	logs map[string][]protocol.Operation
}

func NewMemory() *Memory { return &Memory{logs: make(map[string][]protocol.Operation)} }

func (m *Memory) Exchange(_ context.Context, req protocol.SyncRequest) (protocol.SyncResponse, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if err := req.Validate(); err != nil {
		return protocol.SyncResponse{}, err
	}
	log := m.logs[req.Workspace]
	if req.Cursor > int64(len(log)) {
		return protocol.SyncResponse{}, ErrCursor
	}
	// Build a staged copy, so a conflict rolls back the whole request.
	staged := append([]protocol.Operation{}, log...)
	accepted := make([]string, 0, len(req.Operations))
	for _, op := range req.Operations {
		duplicate := false
		for _, existing := range staged {
			if existing.ID == op.ID || (existing.ActorID == op.ActorID && existing.Clock == op.Clock) {
				if existing != op {
					return protocol.SyncResponse{}, ErrConflict
				}
				duplicate = true
			}
		}
		if !duplicate {
			staged = append(staged, op)
		}
		accepted = append(accepted, op.ID)
	}
	m.logs[req.Workspace] = staged
	end := min(int(req.Cursor)+protocol.PageSize, len(staged))
	return protocol.SyncResponse{
		Accepted: accepted, Operations: append([]protocol.Operation{}, staged[int(req.Cursor):end]...),
		Cursor: int64(end), HasMore: end < len(staged),
	}, nil
}
