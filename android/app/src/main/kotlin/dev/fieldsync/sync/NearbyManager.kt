package dev.fieldsync.sync

import android.Manifest
import android.content.Context
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import dev.fieldsync.core.wireJson
import dev.fieldsync.data.EvidenceStore
import dev.fieldsync.data.TaskRepository
import dev.fieldsync.data.readBytesBounded
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

data class NearbyDevice(val id: String, val name: String)
data class PairRequest(val id: String, val name: String, val code: String)

/** Thin Google Nearby adapter: discovery, explicit code confirmation, and stream transport. */
class NearbyManager(context: Context, private val repository: TaskRepository, evidence: EvidenceStore) {
    private val client = Nearby.getConnectionsClient(context)
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = PeerHandler(repository, evidence)
    private val session = PeerSession(repository, evidence)
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<PeerMessage>>()
    private var endpoint: String? = null
    private var initiator = false
    val devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val pairing = MutableStateFlow<PairRequest?>(null)
    val status = MutableStateFlow("Turn on Bluetooth and Wi-Fi, then find devices")
    val connected = MutableStateFlow(false)
    val exchanging = MutableStateFlow(false)
    val progress = MutableStateFlow(0)
    val transferred = MutableStateFlow(0L)

    fun start() = launch {
        require(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS) {
            "Nearby requires Google Play services on this device. The public demo also offers LAN exchange."
        }
        val state = repository.state()
        val service = "dev.fieldsync.v2.${state.workspace}"
        val name = "${state.displayName} · ${state.actorId.take(4)}"
        stop()
        client.startAdvertising(name, service, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()).await()
        client.startDiscovery(service, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()).await()
        status.value = "Looking for devices in the same workspace. Bluetooth and Wi-Fi must be on."
    }

    fun connect(id: String) = launch {
        initiator = true
        client.requestConnection(repository.state().displayName, id, lifecycle).await()
        status.value = "Waiting for code confirmation on both devices"
    }
    fun confirm(accept: Boolean) {
        val request = pairing.value ?: return
        pairing.value = null
        if (accept) client.acceptConnection(request.id, payloads).addOnFailureListener { status.value = it.message.orEmpty() }
        else client.rejectConnection(request.id)
    }

    fun exchange() = launch {
        val peer = endpoint ?: error("Connect to a device first")
        if (exchanging.value) return@launch
        exchanging.value = true
        try {
            status.value = "Exchanging changes and photos…"
            session.exchange(PeerLink { message ->
                val id = message.requestId
                val deferred = CompletableDeferred<PeerMessage>()
                waiting[id] = deferred
                try {
                    send(peer, message)
                    withTimeout(60_000) { deferred.await() }
                } finally { waiting.remove(id) }
            })
            progress.value = 100
            status.value = "Peer exchange complete"
            SyncWorker.enqueue(appContext)
        } finally { exchanging.value = false }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            devices.value = devices.value.filterNot { it.id == id } + NearbyDevice(id, info.endpointName)
        }
        override fun onEndpointLost(id: String) { devices.value = devices.value.filterNot { it.id == id } }
    }
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            pairing.value = PairRequest(id, info.endpointName, info.authenticationDigits)
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                endpoint = id; connected.value = true
                client.stopDiscovery(); client.stopAdvertising()
                status.value = "Connected. Both users confirmed the code."
                if (initiator) exchange()
            } else { status.value = "Connection was rejected or failed"; pairing.value = null }
        }
        override fun onDisconnected(id: String) {
            endpoint = null; connected.value = false; exchanging.value = false
            waiting.values.forEach { it.completeExceptionally(java.io.IOException("Peer disconnected; reconnect and retry")) }
            status.value = "Peer disconnected. Local changes are safe."
        }
    }
    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            if (payload.type != Payload.Type.STREAM) return
            launch {
                val bytes = requireNotNull(payload.asStream()).asInputStream().use { it.readBytesBounded(12L * 1024 * 1024) }
                val message = wireJson.decodeFromString<PeerMessage>(bytes.toString(Charsets.UTF_8))
                if (message.reply) waiting[message.requestId]?.complete(message)
                else {
                    val response = try { handler.handle(message) } catch (e: Exception) {
                        message.copy(reply = true, operations = emptyList(), photo = "", error = e.message ?: "Peer request failed")
                    }
                    send(id, response)
                    if (message.kind == "done" && response.error.isEmpty()) {
                        status.value = "Peer exchange complete"; progress.value = 100; SyncWorker.enqueue(appContext)
                    }
                }
            }
        }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {
            transferred.value = update.bytesTransferred
            if (update.totalBytes > 0) progress.value = ((100 * update.bytesTransferred) / update.totalBytes).toInt()
            if (update.status == PayloadTransferUpdate.Status.FAILURE || update.status == PayloadTransferUpdate.Status.CANCELED) {
                waiting.values.forEach { it.completeExceptionally(java.io.IOException("Transfer interrupted; retry the exchange")) }
                status.value = "Transfer interrupted. Reconnect and retry."
            }
        }
    }
    private suspend fun send(id: String, message: PeerMessage) {
        val bytes = wireJson.encodeToString(message).toByteArray()
        val stream = ByteArrayInputStream(bytes)
        client.sendPayload(id, Payload.fromStream(stream)).await()
        // Nearby owns the stream while transmission is in progress.
    }
    private fun launch(block: suspend () -> Unit) = scope.launch {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { status.value = e.message ?: "Nearby action failed" }
    }
    fun stop() {
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        endpoint = null; connected.value = false; pairing.value = null; devices.value = emptyList()
        initiator = false; exchanging.value = false; progress.value = 0; transferred.value = 0
        waiting.values.forEach { it.cancel() }; waiting.clear()
    }
    fun close() { stop(); scope.cancel() }

    companion object {
        fun permissions(sdk: Int = Build.VERSION.SDK_INT): Array<String> = buildList {
            // Android 12/12L still need location for Nearby's Wi-Fi discovery.
            // Request coarse and fine together on Android 12, even with Bluetooth granted.
            if (sdk <= 32) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (sdk >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (sdk >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()
    }
}
