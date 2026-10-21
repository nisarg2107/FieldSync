package store

import (
	"context"
	"errors"

	"fieldsync/internal/protocol"
)

// ErrConflict means an operation ID or actor/clock pair was reused with different data.
var ErrConflict = errors.New("operation identity reused with different data")
var ErrCursor = errors.New("cursor is ahead of this server; reconnect to a fresh workspace")

type Store interface {
	// Exchange atomically validates, appends, and reads one page of the operation log.
	Exchange(context.Context, protocol.SyncRequest) (protocol.SyncResponse, error)
}
