package crdt

import (
	"encoding/json"
	"fieldsync/internal/protocol"
	"math/rand"
	"os"
	"testing"
)

func TestCollaborationConvergence(t *testing.T) {
	data, err := os.ReadFile("../../../testdata/collaboration.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Operations []protocol.Operation `json:"operations"`
	}
	if err = json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	for seed := int64(0); seed < 200; seed++ {
		ops := append(append([]protocol.Operation{}, fixture.Operations...), fixture.Operations...)
		rand.New(rand.NewSource(seed)).Shuffle(len(ops), func(i, j int) { ops[i], ops[j] = ops[j], ops[i] })
		tasks := map[string]Task{}
		for _, op := range ops {
			if err = op.Validate(); err != nil {
				t.Fatal(err)
			}
			Apply(tasks, op)
		}
		task := tasks["task"]
		if task.Status() != "blocked" || task.Checklist["item-1"].Result.Value != "pass" || task.Checklist["item-1"].Note.Value != "Reviewed together" || len(task.ActiveStatuses()) != 1 {
			t.Fatalf("seed %d did not converge: %+v", seed, task)
		}
	}
	tasks := map[string]Task{}
	for _, op := range fixture.Operations {
		if op.ID != "resolved" {
			Apply(tasks, op)
		}
	}
	if tasks["task"].Status() != "conflict" {
		t.Fatal("concurrent statuses were silently overwritten")
	}
}
