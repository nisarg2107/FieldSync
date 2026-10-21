package dev.fieldsync.core

import kotlinx.serialization.Serializable

/** Logical time wins first. Actor ID breaks concurrent ties the same way on every device. */
@Serializable
data class Register(val value: String = "", val clock: Long = 0, val actorId: String = "") {
    fun apply(op: Operation): Register =
        if (op.clock > clock || (op.clock == clock && op.actorId > actorId)) {
            Register(op.value, op.clock, op.actorId)
        } else this
}

/** Independent fields preserve a title edit made concurrently with a completion toggle. */
@Serializable
data class Task(
    val id: String,
    val title: Register = Register(),
    val completed: Register = Register("false"),
    val deleted: Register = Register("false"),
    val details: Map<String, Register> = emptyMap(),
    val checklist: Map<String, CheckItem> = emptyMap(),
    val photos: Map<String, Register> = emptyMap(),
    val statusChanges: Map<String, StatusVersion> = emptyMap(),
    val supersededStatuses: Set<String> = emptySet(),
) {
    val isVisible: Boolean get() = title.clock > 0 && deleted.value != "true"
    fun value(field: String): String = details[field]?.value.orEmpty()
    val activeStatuses: Map<String, StatusVersion> get() = statusChanges.filterKeys { it !in supersededStatuses }
    val hasStatusConflict: Boolean get() = activeStatuses.values.map { it.value }.distinct().size > 1
    val status: String get() = when {
        hasStatusConflict -> "conflict"
        activeStatuses.isNotEmpty() -> activeStatuses.values.first().value
        completed.value == "true" -> "completed" // Compatibility with original task boards.
        else -> "open"
    }
    val visibleChecks: List<CheckItem> get() = checklist.values.filter { it.label.clock > 0 && it.deleted.value != "true" }.sortedBy { it.label.value }
    val visiblePhotos: List<Photo> get() = photos.values.filter { it.value.isNotEmpty() }.map { wireJson.decodeFromString<Photo>(it.value) }

    fun apply(op: Operation): Task {
        op.validate()
        require(op.taskId == id)
        return when (op.field) {
            "title" -> copy(title = title.apply(op))
            "completed" -> copy(completed = completed.apply(op))
            "deleted" -> copy(deleted = deleted.apply(op))
            "status" -> {
                val change = wireJson.decodeFromString<StatusChange>(op.value)
                copy(
                    statusChanges = statusChanges + (op.id to StatusVersion(change.value, op.clock, op.actorId)),
                    supersededStatuses = supersededStatuses + change.observed,
                )
            }
            in detailFields -> copy(details = details + (op.field to (details[op.field] ?: Register()).apply(op)))
            else -> when {
                op.field.startsWith("check:") -> {
                    val parts = op.field.split(':')
                    val item = checklist[parts[1]] ?: CheckItem(parts[1])
                    copy(checklist = checklist + (item.id to item.apply(parts[2], op)))
                }
                op.field.startsWith("photo:") -> {
                    val hash = op.field.removePrefix("photo:")
                    copy(photos = photos + (hash to (photos[hash] ?: Register()).apply(op)))
                }
                else -> error("Unknown task field")
            }
        }
    }
}

