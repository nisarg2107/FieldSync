package dev.fieldsync.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PAGE_SIZE = 100
const val MAX_CLOCK = 9_007_199_254_740_991L
const val WORKSPACE = "demo"
val wireJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }
private val identifier = Regex("^[a-zA-Z0-9_-]{1,80}$")
val photoHash = Regex("^[a-f0-9]{64}$")
val detailFields = setOf("description", "project", "priority", "dueDate", "assignee", "notes", "gps", "createdBy")

@Serializable
data class Operation(
    val id: String,
    val taskId: String,
    val actorId: String,
    val clock: Long,
    val field: String,
    val value: String,
    val createdAt: Long = 0,
    val author: String = "",
) {
    fun validate() {
        require(listOf(id, taskId, actorId).all { identifier.matches(it) }) { "Invalid identifier" }
        require(clock in 1..MAX_CLOCK) { "Invalid logical clock" }
        require(createdAt >= 0 && author.codePointCount(0, author.length) <= 120)
        require(!value.contains('\u0000')) { "Text cannot contain a NUL character" }
        when {
            field == "title" -> require(value.isNotBlank() && value.codePointCount(0, value.length) <= 200) { "Title must contain 1–200 characters" }
            field == "completed" || field == "deleted" -> require(value == "true" || value == "false") { "Invalid boolean" }
            field in detailFields -> {
                require(value.codePointCount(0, value.length) <= 2000)
                when (field) {
                    "priority" -> require(value in listOf("low", "normal", "high"))
                    "dueDate" -> require(value.isEmpty() || validDate(value)) { "Use a valid YYYY-MM-DD date" }
                    "assignee" -> require(value.isEmpty() || identifier.matches(value))
                    "gps" -> if (value.isNotEmpty()) wireJson.decodeFromString<GeoPoint>(value).validate()
                }
            }
            field == "status" -> {
                val change = wireJson.decodeFromString<StatusChange>(value)
                require(change.value in taskStatuses && change.observed.size <= 100)
                require(change.observed.all { identifier.matches(it) && it != id })
                require(change.observed.distinct().size == change.observed.size)
            }
            field.startsWith("check:") -> {
                val parts = field.split(':')
                require(parts.size == 3 && identifier.matches(parts[1]))
                when (parts[2]) {
                    "label" -> require(value.isNotBlank() && value.codePointCount(0, value.length) <= 200)
                    "result" -> require(value in listOf("unchecked", "pass", "fail"))
                    "note" -> require(value.codePointCount(0, value.length) <= 2000)
                    "deleted" -> require(value == "true" || value == "false")
                    else -> error("Unknown checklist field")
                }
            }
            field.startsWith("photo:") -> {
                require(photoHash.matches(field.removePrefix("photo:")))
                if (value.isNotEmpty()) {
                    val photo = wireJson.decodeFromString<Photo>(value)
                    require(photo.hash == field.removePrefix("photo:"))
                    photo.validate()
                }
            }
            else -> error("Unknown task field: $field")
        }
    }
}

private fun validDate(value: String): Boolean {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) return false
    val parser = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { isLenient = false }
    return runCatching { parser.format(parser.parse(value)!!) == value }.getOrDefault(false)
}

@Serializable
data class SyncRequest(val workspace: String = WORKSPACE, val cursor: Long, val operations: List<Operation>)

@Serializable
data class SyncResponse(
    val accepted: List<String>,
    val operations: List<Operation>,
    val cursor: Long,
    val hasMore: Boolean,
)

@Serializable
data class PeerBatch(val workspace: String = WORKSPACE, val operations: List<Operation>)

@Serializable
data class PeerPage(val operations: List<Operation>, val cursor: Long, val hasMore: Boolean)

