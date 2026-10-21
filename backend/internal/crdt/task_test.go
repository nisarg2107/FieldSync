package crdt

import (
	"encoding/json"
	"math/rand"
	"os"
	"reflect"
	"testing"

	"fieldsync/internal/protocol"
)

func TestSharedFixtureConverges(t *testing.T) {
	data, err := os.ReadFile("../../../testdata/crdt.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Operations []protocol.Operation `json:"operations"`
		Expected   Task                 `json:"expected"`
	}
	if err = json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	for seed := int64(0); seed < 200; seed++ {
		deliveries := append(append([]protocol.Operation{}, fixture.Operations...), fixture.Operations...)
		rand.New(rand.NewSource(seed)).Shuffle(len(deliveries), func(i, j int) { deliveries[i], deliveries[j] = deliveries[j], deliveries[i] })
		tasks := map[string]Task{}
		for _, op := range deliveries {
			if err = op.Validate(); err != nil {
				t.Fatal(err)
			}
			Apply(tasks, op)
		}
		if !reflect.DeepEqual(fixture.Expected, tasks[fixture.Expected.ID]) {
			t.Fatalf("did not converge at seed %d: %+v", seed, tasks)
		}
	}
}

func BenchmarkApply(b *testing.B) {
	tasks := map[string]Task{}
	op := protocol.Operation{ID: "op", TaskID: "task", ActorID: "alice", Field: "title", Value: "Inspect pump"}
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		op.Clock = int64(i + 1)
		Apply(tasks, op)
	}
}
