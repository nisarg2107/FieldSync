package protocol

import "testing"

func TestStructuredValuesMatchClientModels(t *testing.T) {
	for _, value := range []string{`{}`, `null`, `{"latitude":0,"longitude":0,"accuracy":null,"recordedAt":0}`, `{"latitude":0,"longitude":0,"accuracy":0,"recordedAt":0,"extra":true}`} {
		op := Operation{ID: "op", TaskID: "task", ActorID: "actor", Clock: 1, Field: "gps", Value: value}
		if op.Validate() == nil {
			t.Fatalf("accepted unreadable location: %s", value)
		}
	}
	op := Operation{ID: "op", TaskID: "task", ActorID: "actor", Clock: 1, Field: "gps", Value: `{"latitude":0,"longitude":0,"accuracy":0,"recordedAt":0}`}
	if err := op.Validate(); err != nil {
		t.Fatal(err)
	}
	op.Field, op.Value = "status", `{"value":"open","observed":null}`
	if op.Validate() == nil {
		t.Fatal("accepted null status parents")
	}
}
