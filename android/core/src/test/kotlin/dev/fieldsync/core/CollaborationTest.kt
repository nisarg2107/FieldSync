package dev.fieldsync.core

import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.test.*

class CollaborationTest {
    @Serializable private data class Fixture(val operations: List<Operation>)
    private fun operations() = wireJson.decodeFromString<Fixture>(
        checkNotNull(javaClass.getResource("/collaboration.json")).readText(),
    ).operations

    @Test fun `checklists and resolved statuses converge under replay and reordering`() {
        val ops = operations()
        repeat(200) { seed ->
            val task = (ops + ops).shuffled(Random(seed)).fold(Task("task")) { state, op -> state.apply(op) }
            assertEquals("blocked", task.status)
            assertFalse(task.hasStatusConflict)
            assertEquals("high", task.value("priority"))
            assertEquals("pass", task.visibleChecks.single().result.value)
            assertEquals("Reviewed together", task.visibleChecks.single().note.value)
            assertEquals(setOf("resolved"), task.activeStatuses.keys)
        }
    }
    @Test fun `concurrent statuses stay in conflict until explicitly resolved`() {
        val task = operations().filterNot { it.id == "resolved" }.reversed().fold(Task("task")) { state, op -> state.apply(op) }
        assertEquals("conflict", task.status)
        assertEquals(setOf("status-a", "status-b"), task.activeStatuses.keys)
    }
    @Test fun `field validation rejects malformed new values`() {
        val op = Operation("op", "task", "alice", 1, "priority", "high")
        listOf(op.copy(value = "urgent"), op.copy(field = "dueDate", value = "2026-02-30"),
            op.copy(field = "check:item:result", value = "maybe"), op.copy(field = "description", value = "a\u0000b"))
            .forEach { assertFails { it.validate() } }
    }
}
