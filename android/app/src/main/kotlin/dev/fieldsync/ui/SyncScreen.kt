package dev.fieldsync.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fieldsync.core.WORKSPACE
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SyncScreen(board: BoardState, model: BoardViewModel) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val hosting by model.hosting.collectAsStateWithLifecycle()
    val currentUrl = board.replica?.serverUrl ?: "http://10.0.2.2:8080"
    var server by rememberSaveable(currentUrl) { mutableStateOf(currentUrl) }
    var peer by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Synchronization", style = MaterialTheme.typography.headlineSmall)
            Text(board.status)
            Text("${board.pending} edits waiting for cloud · ${board.photos.count { !it.cloudAcknowledged }} photos waiting")
            Button(onClick = { model.sync() }, enabled = !busy) { Text(if (busy) "Working…" else "Sync now") }
            OutlinedTextField(server, { server = it }, label = { Text("Cloud server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { model.saveServer(server) }, enabled = !busy) { Text("Save server") }
            Text("Background sync retries when a connection is available. The server is optional for local editing and Nearby exchange.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            HorizontalDivider(); Text("Recent sync activity", style = MaterialTheme.typography.titleMedium)
            Text("${board.runs.count { it.success }} successful · ${board.runs.count { !it.success }} failed attempts · ${readableBytes(board.runs.sumOf { it.bytes })} payload estimate (last 30 runs)")
        }
        items(board.runs, key = { it.id }) { run ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${friendly(run.transport)} · ${if (run.success) "Complete" else "Retry needed"}")
                    Text("${run.uploaded} sent · ${run.downloaded} received · ${readableBytes(run.bytes)} · ${run.durationMs} ms")
                    Text(SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(run.startedAt)), style = MaterialTheme.typography.labelSmall)
                    if (!run.success) Text(run.message.take(180), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (board.replica?.workspace == WORKSPACE) item {
            HorizontalDivider()
            TextButton(onClick = { advanced = !advanced }) { Text("Advanced: public demo LAN fallback") }
            if (advanced) {
                Text("Operations-only fallback for emulators or devices without Nearby support. Use Nearby to transfer photos and private team data.")
                if (hosting.isEmpty()) OutlinedButton(onClick = { model.startHosting() }) { Text("Receive over LAN") }
                else {
                    hosting.forEach { Text(it) }
                    TextButton(onClick = { model.stopHosting() }) { Text("Stop receiving") }
                }
                OutlinedTextField(peer, { peer = it }, label = { Text("Peer URL: http://…:8787") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { model.peer(peer) }, enabled = !busy && peer.isNotBlank()) { Text("Exchange over LAN") }
            }
        }
    }
}
