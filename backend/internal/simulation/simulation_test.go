package simulation

import (
	"reflect"
	"testing"
)

func TestFaultRecovery(t *testing.T) {
	for seed := int64(0); seed < 20; seed++ {
		report, err := Run(Config{Seed: seed, Replicas: 3, Edits: 120, FailureRate: 0.7})
		if err != nil {
			t.Fatal(err)
		}
		if !report.Converged || report.UniqueOperations != 360 {
			t.Fatalf("seed %d: %+v", seed, report)
		}
		if report.IdleResponseBytes >= report.FullLogResponseBytes {
			t.Fatal("idle delta response should be smaller than full log")
		}
	}
}

func TestPeerOnlyThenCloudForwarding(t *testing.T) {
	a, b := NewReplica("alice"), NewReplica("bob")
	a.Edit("task", "title", "Inspect pump")
	b.Edit("task", "completed", "true")
	ExchangePeer(a, b)
	if len(a.Known) != 2 || len(b.Pending) != 2 || !reflect.DeepEqual(a.Tasks["task"], b.Tasks["task"]) {
		t.Fatal("peer exchange lost edits or upload eligibility")
	}
	b.Edit("task", "title", "Inspect north pump")
	if b.Clock != 2 {
		t.Fatal("logical clock did not advance after receiving peer edits")
	}
}
