package store

import (
	"context"
	"errors"
	"fmt"
	"os"
	"sync"
	"testing"
	"time"

	"fieldsync/internal/protocol"
)

func operation(id string, clock int64) protocol.Operation {
	return protocol.Operation{ID: id, TaskID: "task", ActorID: "alice", Clock: clock, Field: "title", Value: id}
}

func TestStores(t *testing.T) {
	t.Run("memory", func(t *testing.T) { testStore(t, NewMemory()) })
	t.Run("postgres", func(t *testing.T) {
		url := os.Getenv("TEST_DATABASE_URL")
		if url == "" {
			t.Skip("set TEST_DATABASE_URL to run the real PostgreSQL contract tests")
		}
		p, err := OpenPostgres(context.Background(), url)
		if err != nil {
			t.Fatal(err)
		}
		defer p.Close()
		testStore(t, p)
	})
}

func testStore(t *testing.T, db Store) {
	ctx := context.Background()
	workspace := fmt.Sprintf("test-%d", time.Now().UnixNano())
	exchange := func(req protocol.SyncRequest) protocol.SyncResponse {
		t.Helper()
		req.Workspace = workspace
		response, err := db.Exchange(ctx, req)
		if err != nil {
			t.Fatal(err)
		}
		return response
	}
	op := operation("first", 1)
	first := exchange(protocol.SyncRequest{Operations: []protocol.Operation{op}})
	retry := exchange(protocol.SyncRequest{Operations: []protocol.Operation{op, op}})
	if len(first.Operations) != 1 || len(retry.Operations) != 1 || len(retry.Accepted) != 2 {
		t.Fatal("retry was not idempotent")
	}
	conflict := op
	conflict.Value = "different"
	_, err := db.Exchange(ctx, protocol.SyncRequest{Workspace: workspace, Operations: []protocol.Operation{operation("rollback", 2), conflict}})
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("expected identity conflict, got %v", err)
	}
	if len(exchange(protocol.SyncRequest{}).Operations) != 1 {
		t.Fatal("conflicting batch partially committed")
	}
	conflict = operation("another-id", 1)
	_, err = db.Exchange(ctx, protocol.SyncRequest{Workspace: workspace, Operations: []protocol.Operation{conflict}})
	if !errors.Is(err, ErrConflict) {
		t.Fatal("actor/clock reuse must conflict")
	}
	// More than one page, including sequence gaps left by rolled-back PostgreSQL inserts.
	for batch := 0; batch < 3; batch++ {
		ops := []protocol.Operation{}
		for i := 0; i < 100; i++ {
			clock := int64(2 + batch*100 + i)
			ops = append(ops, operation(fmt.Sprintf("op-%d", clock), clock))
		}
		exchange(protocol.SyncRequest{Operations: ops})
	}
	seen := map[string]bool{}
	cursor := int64(0)
	for {
		page := exchange(protocol.SyncRequest{Cursor: cursor})
		for _, op := range page.Operations {
			if seen[op.ID] {
				t.Fatal("duplicate across pages")
			}
			seen[op.ID] = true
		}
		if page.HasMore && page.Cursor <= cursor {
			t.Fatal("no cursor progress")
		}
		cursor = page.Cursor
		if !page.HasMore {
			break
		}
	}
	if len(seen) != 301 {
		t.Fatalf("missing operations: %d", len(seen))
	}
	other, err := db.Exchange(ctx, protocol.SyncRequest{Workspace: workspace + "-other"})
	if err != nil || len(other.Operations) != 0 {
		t.Fatal("workspace data leaked")
	}
	_, err = db.Exchange(ctx, protocol.SyncRequest{Workspace: workspace, Cursor: cursor + 100000})
	if !errors.Is(err, ErrCursor) {
		t.Fatal("future cursor accepted")
	}

	// Writers and a reader overlap: a cursor must never skip a committed operation.
	var group sync.WaitGroup
	for i := 0; i < 20; i++ {
		group.Add(1)
		go func(i int) {
			defer group.Done()
			_, err := db.Exchange(ctx, protocol.SyncRequest{Workspace: workspace, Operations: []protocol.Operation{operation(fmt.Sprintf("concurrent-%d", i), int64(1000+i))}})
			if err != nil {
				t.Error(err)
			}
		}(i)
	}
	for i := 0; i < 20; i++ {
		page := exchange(protocol.SyncRequest{Cursor: cursor})
		for _, op := range page.Operations {
			seen[op.ID] = true
		}
		cursor = page.Cursor
	}
	group.Wait()
	last := exchange(protocol.SyncRequest{Cursor: cursor})
	for _, op := range last.Operations {
		seen[op.ID] = true
	}
	if len(seen) != 321 {
		t.Fatalf("concurrent sync skipped operations: got %d", len(seen))
	}
}
