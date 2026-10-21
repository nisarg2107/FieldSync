package dev.fieldsync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fieldsync.sync.NearbyManager

@Composable
fun NearbyScreen(model: BoardViewModel) {
    val nearby = model.nearby
    val devices by nearby.devices.collectAsStateWithLifecycle()
    val pairing by nearby.pairing.collectAsStateWithLifecycle()
    val connected by nearby.connected.collectAsStateWithLifecycle()
    val exchanging by nearby.exchanging.collectAsStateWithLifecycle()
    val progress by nearby.progress.collectAsStateWithLifecycle()
    val transferred by nearby.transferred.collectAsStateWithLifecycle()
    val status by nearby.status.collectAsStateWithLifecycle()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) nearby.start()
        else model.error.value = "Allow Nearby permissions on both phones to discover and connect."
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Nearby devices", style = MaterialTheme.typography.headlineSmall)
            Text("Select the same team on both phones. Turn on Wi-Fi and Bluetooth; internet is optional.")
            Text("Keep both apps visible during the exchange.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Button(onClick = { permissions.launch(NearbyManager.permissions()) }, enabled = !exchanging) { Text("Find nearby devices") }
            Text(status)
            if (exchanging) {
                if (progress > 0) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Current transfer: ${readableBytes(transferred)}", style = MaterialTheme.typography.bodySmall)
            }
            if (connected) Button(onClick = { nearby.exchange() }, enabled = !exchanging) { Text("Exchange again") }
            TextButton(onClick = { nearby.stop() }) { Text("Stop / disconnect") }
        }
        items(devices, key = { it.id }) { device ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(device.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { nearby.connect(device.id) }, enabled = !connected) { Text("Connect") }
                }
            }
        }
        item {
            Text("After connecting, changes and photos are exchanged in both directions. If interrupted, reconnect and retry; saved edits remain on each phone.")
        }
    }
    pairing?.let { request ->
        AlertDialog(
            onDismissRequest = { nearby.confirm(false) },
            title = { Text("Confirm ${request.name}") },
            text = { Column { Text("Compare this code on both phones:"); Text(request.code, style = MaterialTheme.typography.headlineLarge); Text("Accept only if both codes match.") } },
            confirmButton = { TextButton(onClick = { nearby.confirm(true) }) { Text("Codes match") } },
            dismissButton = { TextButton(onClick = { nearby.confirm(false) }) { Text("Reject") } },
        )
    }
}
