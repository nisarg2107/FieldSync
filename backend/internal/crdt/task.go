// Package crdt is an executable reference for the Android reducer.
// A task is three independent last-writer-wins registers, ordered by (clock, actor).
package crdt

import (
	"encoding/json"
	"fieldsync/internal/protocol"
	"sort"
	"strings"
)

type Register struct {
	Value   string `json:"value"`
	Clock   int64  `json:"clock"`
	ActorID string `json:"actorId"`
}

func (r Register) Apply(op protocol.Operation) Register {
	if op.Clock > r.Clock || (op.Clock == r.Clock && op.ActorID > r.ActorID) {
		return Register{Value: op.Value, Clock: op.Clock, ActorID: op.ActorID}
	}
	return r
}

type Task struct {
	ID                 string                   `json:"id"`
	Title              Register                 `json:"title"`
	Completed          Register                 `json:"completed"`
	Deleted            Register                 `json:"deleted"`
	Details            map[string]Register      `json:"details,omitempty"`
	Checklist          map[string]CheckItem     `json:"checklist,omitempty"`
	Photos             map[string]Register      `json:"photos,omitempty"`
	StatusChanges      map[string]StatusVersion `json:"statusChanges,omitempty"`
	SupersededStatuses []string                 `json:"supersededStatuses,omitempty"`
}

type CheckItem struct {
	ID      string   `json:"id"`
	Label   Register `json:"label"`
	Result  Register `json:"result"`
	Note    Register `json:"note"`
	Deleted Register `json:"deleted"`
}
type StatusVersion struct {
	Value   string `json:"value"`
	Clock   int64  `json:"clock"`
	ActorID string `json:"actorId"`
}

func (t Task) ActiveStatuses() map[string]StatusVersion {
	superseded := map[string]bool{}
	for _, id := range t.SupersededStatuses {
		superseded[id] = true
	}
	active := map[string]StatusVersion{}
	for id, version := range t.StatusChanges {
		if !superseded[id] {
			active[id] = version
		}
	}
	return active
}

func (t Task) Status() string {
	values := map[string]bool{}
	for _, version := range t.ActiveStatuses() {
		values[version.Value] = true
	}
	if len(values) > 1 {
		return "conflict"
	}
	for value := range values {
		return value
	}
	if t.Completed.Value == "true" {
		return "completed"
	}
	return "open"
}

func Apply(tasks map[string]Task, op protocol.Operation) {
	task, exists := tasks[op.TaskID]
	if !exists {
		task = Task{ID: op.TaskID, Completed: Register{Value: "false"}, Deleted: Register{Value: "false"}}
	}
	switch op.Field {
	case "title":
		task.Title = task.Title.Apply(op)
	case "completed":
		task.Completed = task.Completed.Apply(op)
	case "deleted":
		task.Deleted = task.Deleted.Apply(op)
	case "description", "project", "priority", "dueDate", "assignee", "notes", "gps", "createdBy":
		if task.Details == nil {
			task.Details = map[string]Register{}
		}
		task.Details[op.Field] = task.Details[op.Field].Apply(op)
	case "status":
		var change protocol.StatusChange
		if err := json.Unmarshal([]byte(op.Value), &change); err != nil {
			return
		}
		if task.StatusChanges == nil {
			task.StatusChanges = map[string]StatusVersion{}
		}
		task.StatusChanges[op.ID] = StatusVersion{change.Value, op.Clock, op.ActorID}
		ids := map[string]bool{}
		for _, id := range append(task.SupersededStatuses, change.Observed...) {
			ids[id] = true
		}
		task.SupersededStatuses = []string{}
		for id := range ids {
			task.SupersededStatuses = append(task.SupersededStatuses, id)
		}
		sort.Strings(task.SupersededStatuses)
	default:
		if strings.HasPrefix(op.Field, "check:") {
			parts := strings.Split(op.Field, ":")
			if len(parts) != 3 {
				return
			}
			if task.Checklist == nil {
				task.Checklist = map[string]CheckItem{}
			}
			item, exists := task.Checklist[parts[1]]
			if !exists {
				item = CheckItem{ID: parts[1], Result: Register{Value: "unchecked"}, Deleted: Register{Value: "false"}}
			}
			switch parts[2] {
			case "label":
				item.Label = item.Label.Apply(op)
			case "result":
				item.Result = item.Result.Apply(op)
			case "note":
				item.Note = item.Note.Apply(op)
			case "deleted":
				item.Deleted = item.Deleted.Apply(op)
			}
			task.Checklist[item.ID] = item
		} else if strings.HasPrefix(op.Field, "photo:") {
			if task.Photos == nil {
				task.Photos = map[string]Register{}
			}
			hash := strings.TrimPrefix(op.Field, "photo:")
			task.Photos[hash] = task.Photos[hash].Apply(op)
		}
	}
	tasks[op.TaskID] = task
}
