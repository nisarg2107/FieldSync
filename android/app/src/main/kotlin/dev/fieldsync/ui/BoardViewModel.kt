package dev.fieldsync.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.fieldsync.FieldSyncApp
import dev.fieldsync.core.*
import dev.fieldsync.data.*
import dev.fieldsync.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

data class BoardState(
    val tasks: List<Task> = emptyList(),
    val pending: Int = 0,
    val replica: ReplicaState? = null,
    val runs: List<SyncRun> = emptyList(),
    val photos: List<MediaRow> = emptyList(),
    val status: String = "Edits save on this device immediately",
) {
    val teams: List<Team> get() = replica?.let { wireJson.decodeFromString(it.teamsJson) } ?: emptyList()
    val members: List<Member> get() = replica?.let { wireJson.decodeFromString(it.membersJson) } ?: emptyList()
    val workspaceName: String get() = teams.find { it.id == replica?.workspace }?.name ?: "Public demo"
    val canEdit: Boolean get() = replica?.role != "viewer"
}

class BoardViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as FieldSyncApp).container
    private val repository = container.repository
    val nearby = NearbyManager(application, repository, container.evidence)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val notice = MutableStateFlow<String?>(null)
    val hosting = MutableStateFlow<List<String>>(emptyList())
    private var peerServer: PeerServer? = null

    private val data = combine(repository.tasks, repository.pendingCount, repository.state, repository.runs, repository.media) { tasks, pending, state, runs, photos ->
        BoardState(tasks, pending, state, runs, photos)
    }
    val board = combine(data, container.sync.status) { state, status -> state.copy(status = status) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BoardState())
    init { action { repository.state() } }

    fun saveTask(id: String?, fields: List<Pair<String, String>>) = action {
        val taskId = id ?: UUID.randomUUID().toString()
        val changes = if (id == null) fields + ("createdBy" to repository.state().displayName) else fields
        repository.editFields(taskId, changes)
        SyncWorker.enqueue(getApplication())
    }
    fun edit(task: Task, field: String, value: String) = action {
        repository.edit(task.id, field, value); SyncWorker.enqueue(getApplication())
    }
    fun status(task: Task, status: String) = action {
        repository.setStatus(task.id, status); SyncWorker.enqueue(getApplication())
    }
    fun addCheck(task: Task, label: String) = edit(task, "check:${UUID.randomUUID()}:label", label.trim())
    fun history(task: String) = repository.history(task)
    fun photoFile(hash: String) = container.evidence.file(hash)
    fun attachPhoto(task: String, uri: Uri, caption: String = "") = action {
        container.evidence.attach(task, uri, caption); SyncWorker.enqueue(getApplication())
    }
    fun captureLocation(task: Task) = action {
        val context = getApplication<Application>()
        require(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "Allow location access first" }
        val cancellation = CancellationTokenSource()
        val location = try {
            withTimeoutOrNull(25_000) { LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token).await() }
        } finally { cancellation.cancel() }
        requireNotNull(location) { "No location fix available. Turn on location and try outdoors; the task is already saved." }
        repository.edit(task.id, "gps", wireJson.encodeToString(GeoPoint(location.latitude, location.longitude, location.accuracy, location.time)))
        SyncWorker.enqueue(context)
    }
    fun sync() = action(network = true) { container.sync.cloud() }
    fun saveServer(url: String) = action(network = true) { container.sync.changeServer(url); SyncWorker.enqueue(getApplication()) }
    fun peer(url: String) = action(network = true) { container.sync.peer(url); SyncWorker.enqueue(getApplication()) }

    fun account(name: String, email: String, password: String, register: Boolean) = action(network = true) {
        val state = repository.state()
        repository.saveSession(container.transport.signIn(state.serverUrl, Credentials(name, email, password), register))
        loadTeams()
        notice.value = "Signed in. Select or create a team; the public demo stays separate."
    }
    private suspend fun loadTeams() {
        val state = repository.state()
        val teams = container.transport.teams(state.serverUrl, state.token)
        repository.saveTeamList(teams)
        val selected = teams.find { it.id == state.workspace }
        if (selected != null) container.sync.useTeam(selected, container.transport.members(state.serverUrl, state.token, selected.id))
    }
    fun refreshTeams() = action(network = true) { loadTeams() }
    fun createOrJoinTeam(name: String, code: String) = action(network = true) {
        val state = repository.state()
        val team = container.transport.createOrJoinTeam(state.serverUrl, state.token, TeamInput(name, code))
        nearby.stop(); stopHosting()
        loadTeams()
        container.sync.useTeam(team, container.transport.members(state.serverUrl, state.token, team.id))
        notice.value = if (team.role == "viewer") "Joined read-only. Ask the owner to grant editor access." else "Team created. Share its invite code."
        SyncWorker.enqueue(getApplication())
    }
    fun selectTeam(team: Team) = action {
        nearby.stop(); stopHosting()
        container.sync.useTeam(team)
        SyncWorker.enqueue(getApplication())
        // Switching among cached teams works offline. Refresh members when connectivity returns.
    }
    fun setRole(user: String, role: String) = action(network = true) {
        val state = repository.state()
        container.transport.setRole(state.serverUrl, state.token, state.workspace, RoleInput(user, role))
        loadTeams()
    }
    fun signOut() = action { nearby.stop(); stopHosting(); container.sync.signOut() }

    fun startHosting() = action {
        require(repository.state().workspace == WORKSPACE) { "Use confirmed Nearby connections for private teams" }
        if (peerServer != null) return@action
        val addresses = withContext(Dispatchers.IO) {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>().filterNot { it.isLoopbackAddress }.map { "http://${it.hostAddress}:8787" }
        }
        val server = PeerServer(repository) { SyncWorker.enqueue(getApplication()) }
        server.start(); peerServer = server
        hosting.value = addresses.ifEmpty { listOf("Connect to a Wi-Fi network first") }
    }
    fun stopHosting() { peerServer?.stop(); peerServer = null; hosting.value = emptyList() }
    fun stopConnections() { stopHosting(); nearby.stop() }
    override fun onCleared() { stopHosting(); nearby.close(); super.onCleared() }

    private fun action(network: Boolean = false, block: suspend () -> Unit) = viewModelScope.launch {
        if (network && busy.value) return@launch
        if (network) busy.value = true
        error.value = null; notice.value = null
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error.value = e.message ?: "Action failed. Your saved data is still on this device." }
        finally { if (network) busy.value = false }
    }
}
