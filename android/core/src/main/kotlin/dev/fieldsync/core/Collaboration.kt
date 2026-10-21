package dev.fieldsync.core

import kotlinx.serialization.Serializable

val taskStatuses = listOf("open", "in_progress", "blocked", "completed")

/** A new status supersedes only the versions the author has actually seen.
 * Concurrent different statuses stay visible until a user explicitly resolves them. */
@Serializable data class StatusChange(val value: String, val observed: List<String> = emptyList())
@Serializable data class StatusVersion(val value: String, val clock: Long, val actorId: String)

@Serializable
data class CheckItem(
    val id: String,
    val label: Register = Register(),
    val result: Register = Register("unchecked"),
    val note: Register = Register(),
    val deleted: Register = Register("false"),
) {
    fun apply(field: String, op: Operation): CheckItem = when (field) {
        "label" -> copy(label = label.apply(op))
        "result" -> copy(result = result.apply(op))
        "note" -> copy(note = note.apply(op))
        "deleted" -> copy(deleted = deleted.apply(op))
        else -> error("Unknown checklist field")
    }
}

@Serializable
data class Photo(val hash: String, val size: Long, val caption: String = "", val mimeType: String = "image/jpeg") {
    fun validate() {
        require(photoHash.matches(hash) && size in 1..MAX_PHOTO_BYTES && caption.codePointCount(0, caption.length) <= 200)
        require(mimeType in listOf("image/jpeg", "image/png", "image/webp"))
    }
}
const val MAX_PHOTO_BYTES = 8L * 1024 * 1024

@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double, val accuracy: Float, val recordedAt: Long) {
    fun validate() {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(accuracy.isFinite() && accuracy >= 0 && recordedAt >= 0)
    }
}

@Serializable data class User(val id: String, val name: String, val email: String)
@Serializable data class Team(val id: String, val name: String, val role: String, val inviteCode: String = "")
@Serializable data class Member(val user: User, val role: String)
@Serializable data class Credentials(val name: String = "", val email: String, val password: String)
@Serializable data class AuthResponse(val user: User, val token: String)
@Serializable data class TeamInput(val name: String = "", val inviteCode: String = "")
@Serializable data class RoleInput(val userId: String, val role: String)
