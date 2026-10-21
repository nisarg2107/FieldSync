package dev.fieldsync.core

import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.test.*

class ConvergenceTest {
    @Serializable
    private data class Fixture(val operations: List<Operation>, val expected: Task)

    private fun fixture(): Fixture = wireJson.decodeFromString(
        checkNotNull(javaClass.getResource("/crdt.json")).readText(),
    )

    @Test fun `Go and Kotlin share the same expected state across 200 delivery orders`() {
        val fixture = fixture()
        repeat(200) { seed ->
            val deliveries = (fixture.operations + fixture.operations).shuffled(Random(seed))
            val task = deliveries.fold(Task(fixture.expected.id)) { state, op -> state.apply(op) }
            assertEquals(fixture.expected, task, "seed=$seed")
        }
    }

    @Test fun `concurrent title and completion both survive`() {
        val rename = Operation("a1", "task", "alice", 1, "title", "New title")
        val done = Operation("b1", "task", "bob", 1, "completed", "true")
        assertEquals(Task("task").apply(rename).apply(done), Task("task").apply(done).apply(rename))
    }

    @Test fun `rename cannot accidentally resurrect archived task`() {
        val task = Task("task")
            .apply(Operation("a1", "task", "alice", 1, "deleted", "true"))
            .apply(Operation("b2", "task", "bob", 2, "title", "Still archived"))
        assertFalse(task.isVisible)
    }

    @Test fun `invalid operation never enters reducer`() {
        val valid = Operation("op", "task", "alice", 1, "title", "Valid")
        for (invalid in listOf(valid.copy(clock = 0), valid.copy(value = " "), valid.copy(field = "unknown"), valid.copy(actorId = "💥"), valid.copy(field = "completed", value = "yes"))) {
            assertFails { Task("task").apply(invalid) }
        }
        valid.copy(value = "😀".repeat(200)).validate()
        assertFails { valid.copy(value = "😀".repeat(201)).validate() }
    }
}

