package store

import (
	"context"
	_ "embed"
	"errors"

	"fieldsync/internal/protocol"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed schema.sql
var schema string

type Postgres struct{ pool *pgxpool.Pool }

func OpenPostgres(ctx context.Context, url string) (*Postgres, error) {
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		return nil, err
	}
	if err = pool.Ping(ctx); err == nil {
		_, err = pool.Exec(ctx, schema)
	}
	if err != nil {
		pool.Close()
		return nil, err
	}
	return &Postgres{pool: pool}, nil
}

func (p *Postgres) Close()              { p.pool.Close() }
func (p *Postgres) Pool() *pgxpool.Pool { return p.pool }

func (p *Postgres) Exchange(ctx context.Context, req protocol.SyncRequest) (protocol.SyncResponse, error) {
	if err := req.Validate(); err != nil {
		return protocol.SyncResponse{}, err
	}
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return protocol.SyncResponse{}, err
	}
	defer tx.Rollback(ctx)
	// Serialize each workspace until COMMIT. Without this lock, seq 11 could commit
	// before seq 10, and a client advancing to cursor 11 could miss operation 10.
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, req.Workspace); err != nil {
		return protocol.SyncResponse{}, err
	}
	var latest int64
	if err = tx.QueryRow(ctx, `SELECT COALESCE(MAX(seq), 0) FROM operations WHERE workspace_id=$1`, req.Workspace).Scan(&latest); err != nil {
		return protocol.SyncResponse{}, err
	}
	if req.Cursor > latest {
		return protocol.SyncResponse{}, ErrCursor
	}
	accepted := make([]string, 0, len(req.Operations))
	for _, op := range req.Operations {
		var existing protocol.Operation
		err = tx.QueryRow(ctx, `SELECT op_id,task_id,actor_id,clock,field,value,created_at,author FROM operations
			WHERE workspace_id=$1 AND (op_id=$2 OR (actor_id=$3 AND clock=$4))`,
			req.Workspace, op.ID, op.ActorID, op.Clock).Scan(
			&existing.ID, &existing.TaskID, &existing.ActorID, &existing.Clock, &existing.Field, &existing.Value, &existing.CreatedAt, &existing.Author)
		switch {
		case err == nil:
			if existing != op {
				return protocol.SyncResponse{}, ErrConflict
			}
		case errors.Is(err, pgx.ErrNoRows):
			_, err = tx.Exec(ctx, `INSERT INTO operations(workspace_id,op_id,task_id,actor_id,clock,field,value,created_at,author)
				VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`, req.Workspace, op.ID, op.TaskID, op.ActorID, op.Clock, op.Field, op.Value, op.CreatedAt, op.Author)
			if err != nil {
				return protocol.SyncResponse{}, err
			}
		default:
			return protocol.SyncResponse{}, err
		}
		accepted = append(accepted, op.ID)
	}
	rows, err := tx.Query(ctx, `SELECT seq,op_id,task_id,actor_id,clock,field,value,created_at,author FROM operations
		WHERE workspace_id=$1 AND seq>$2 ORDER BY seq LIMIT $3`, req.Workspace, req.Cursor, protocol.PageSize+1)
	if err != nil {
		return protocol.SyncResponse{}, err
	}
	response := protocol.SyncResponse{Accepted: accepted, Operations: []protocol.Operation{}, Cursor: req.Cursor}
	for rows.Next() {
		var seq int64
		var op protocol.Operation
		if err = rows.Scan(&seq, &op.ID, &op.TaskID, &op.ActorID, &op.Clock, &op.Field, &op.Value, &op.CreatedAt, &op.Author); err != nil {
			rows.Close()
			return protocol.SyncResponse{}, err
		}
		if len(response.Operations) == protocol.PageSize {
			response.HasMore = true
			break
		}
		response.Operations = append(response.Operations, op)
		response.Cursor = seq
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return protocol.SyncResponse{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return protocol.SyncResponse{}, err
	}
	return response, nil
}
