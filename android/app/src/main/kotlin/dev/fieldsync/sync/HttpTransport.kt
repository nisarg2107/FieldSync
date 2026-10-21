package dev.fieldsync.sync

import dev.fieldsync.core.*
import dev.fieldsync.data.readBytesBounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.io.File
import okhttp3.RequestBody.Companion.asRequestBody

/** OkHttp owns connections and timeouts; WorkManager owns cloud retry scheduling. */
class HttpTransport(private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()) {
    suspend fun cloud(url: String, request: SyncRequest, token: String = ""): SyncResponse =
        wireJson.decodeFromString(post("$url/v1/sync", wireJson.encodeToString(request), token))

    suspend fun signIn(url: String, input: Credentials, register: Boolean): AuthResponse =
        wireJson.decodeFromString(post("$url/v1/auth/${if (register) "register" else "login"}", wireJson.encodeToString(input)))

    suspend fun teams(url: String, token: String): List<Team> = wireJson.decodeFromString(get("$url/v1/teams", token))
    suspend fun members(url: String, token: String, team: String): List<Member> =
        wireJson.decodeFromString(get("$url/v1/teams/$team/members", token))
    suspend fun createOrJoinTeam(url: String, token: String, input: TeamInput): Team =
        wireJson.decodeFromString(post("$url/v1/teams", wireJson.encodeToString(input), token))
    suspend fun setRole(url: String, token: String, team: String, input: RoleInput) = withContext(Dispatchers.IO) {
        execute(request("$url/v1/teams/$team/members", token)
            .put(wireJson.encodeToString(input).toRequestBody("application/json".toMediaType())).build())
    }

    suspend fun uploadPhoto(url: String, token: String, workspace: String, hash: String, file: File) = withContext(Dispatchers.IO) {
        execute(request("$url/v1/media/$workspace/$hash", token).put(file.asRequestBody("application/octet-stream".toMediaType())).build())
    }

    suspend fun downloadPhoto(url: String, token: String, workspace: String, hash: String): ByteArray? = withContext(Dispatchers.IO) {
        client.newCall(request("$url/v1/media/$workspace/$hash", token).build()).execute().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) throw IOException("Photo download: HTTP ${response.code}")
            response.body!!.byteStream().use { it.readBytesBounded(MAX_PHOTO_BYTES) }
        }
    }

    suspend fun pushPeer(url: String, operations: List<Operation>) {
        post("$url/peer/operations", wireJson.encodeToString(PeerBatch(operations = operations)))
    }

    suspend fun pullPeer(url: String, cursor: Long): PeerPage = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$url/peer/operations?workspace=$WORKSPACE&cursor=$cursor").build()
        wireJson.decodeFromString<PeerPage>(execute(request))
    }

    private suspend fun post(url: String, body: String, token: String = ""): String = withContext(Dispatchers.IO) {
        execute(request(url, token).post(body.toRequestBody("application/json".toMediaType())).build())
    }

    private suspend fun get(url: String, token: String): String = withContext(Dispatchers.IO) { execute(request(url, token).build()) }
    private fun request(url: String, token: String) = Request.Builder().url(url).apply {
        if (token.isNotBlank()) header("Authorization", "Bearer $token")
    }

    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        val text = response.body?.string() ?: throw IOException("Empty response")
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${text.take(160)}")
        text
    }
}

fun normalizeServerUrl(input: String): String {
    val url = input.trim().toHttpUrl()
    require(url.encodedPath == "/" && url.query == null && url.fragment == null && url.username.isEmpty() && url.password.isEmpty()) {
        "Enter a base URL, for example http://192.168.1.10:8080"
    }
    return url.toString().removeSuffix("/")
}

