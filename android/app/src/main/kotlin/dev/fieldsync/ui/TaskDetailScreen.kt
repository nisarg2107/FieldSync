package dev.fieldsync.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.fieldsync.core.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TaskDetailScreen(task: Task, board: BoardState, model: BoardViewModel, onBack: () -> Unit, onEdit: () -> Unit, onCamera: () -> Unit) {
    var note by remember(task.id, task.value("notes")) { mutableStateOf(task.value("notes")) }
    var newCheck by remember { mutableStateOf("") }
    var editNote by remember { mutableStateOf<CheckItem?>(null) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var caption by remember { mutableStateOf("") }
    var fullPhoto by remember { mutableStateOf<Photo?>(null) }
    val historyFlow = remember(task.id) { model.history(task.id) }
    val history by historyFlow.collectAsStateWithLifecycle(emptyList())
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { photoUri = it }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) model.captureLocation(task)
        else model.error.value = "Location permission was declined. The task can still be used."
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack) { Text("Back") }
                TextButton(onClick = onEdit, enabled = board.canEdit) { Text("Edit task") }
                TextButton(onClick = { model.edit(task, "deleted", (task.deleted.value != "true").toString()) }, enabled = board.canEdit) { Text(if (task.deleted.value == "true") "Restore" else "Archive") }
            }
            Text(task.title.value, style = MaterialTheme.typography.headlineSmall)
            if (task.value("project").isNotEmpty()) Text("Project: ${task.value("project")}")
            if (task.value("description").isNotEmpty()) Text(task.value("description"))
            Text("${friendly(task.value("priority").ifBlank { "normal" })} priority · Due ${task.value("dueDate").ifBlank { "not set" }}")
            Text("Assigned to ${board.members.find { it.user.id == task.value("assignee") }?.user?.name ?: if (task.value("assignee").isEmpty()) "nobody" else "team member"}")
        }
        item {
            Text("Status: ${friendly(task.status)}", style = MaterialTheme.typography.titleMedium)
            if (task.hasStatusConflict) {
                Text("Different offline status changes need your decision: ${task.activeStatuses.values.map { friendly(it.value) }.distinct().joinToString()}. Choose the correct status below.", color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                taskStatuses.take(2).forEach { status -> FilterChip(task.status == status, { model.status(task, status) }, enabled = board.canEdit, label = { Text(friendly(status)) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                taskStatuses.drop(2).forEach { status -> FilterChip(task.status == status, { model.status(task, status) }, enabled = board.canEdit, label = { Text(friendly(status)) }) }
            }
        }
        item { HorizontalDivider(); Text("Checklist", style = MaterialTheme.typography.titleMedium) }
        items(task.visibleChecks, key = { it.id }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(item.label.value, style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("unchecked", "pass", "fail").forEach { result -> FilterChip(item.result.value == result,
                            onClick = { model.edit(task, "check:${item.id}:result", result) }, enabled = board.canEdit,
                            label = { Text(friendly(result)) }) }
                    }
                    if (item.note.value.isNotEmpty()) Text(item.note.value)
                    Row {
                        TextButton(onClick = { editNote = item }, enabled = board.canEdit) { Text("Note") }
                        TextButton(onClick = { model.edit(task, "check:${item.id}:deleted", "true") }, enabled = board.canEdit) { Text("Remove") }
                    }
                }
            }
        }
        item {
            OutlinedTextField(newCheck, { newCheck = it }, label = { Text("New checklist item") }, enabled = board.canEdit, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { model.addCheck(task, newCheck); newCheck = "" }, enabled = board.canEdit && newCheck.isNotBlank()) { Text("Add checklist item") }
        }
        item {
            HorizontalDivider(); Text("Notes", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(note, { note = it }, minLines = 3, modifier = Modifier.fillMaxWidth(), enabled = board.canEdit)
            TextButton(onClick = { model.edit(task, "notes", note) }, enabled = board.canEdit && note != task.value("notes")) { Text("Save notes") }
        }
        item {
            HorizontalDivider(); Text("Photos", style = MaterialTheme.typography.titleMedium)
            Row {
                TextButton(onClick = onCamera, enabled = board.canEdit) { Text("Take photo") }
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = board.canEdit) { Text("Choose photo") }
            }
            // Media rows trigger a refresh when a missing attachment finishes downloading.
            val revision = board.photos.maxOfOrNull { it.receivedAt } ?: 0L
            key(revision) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(task.visiblePhotos, key = { it.hash }) { photo ->
                        Card(onClick = { fullPhoto = photo }) {
                            Column(Modifier.width(150.dp).padding(8.dp)) {
                                if (model.photoFile(photo.hash).isFile) AsyncImage(model.photoFile(photo.hash), contentDescription = photo.caption.ifBlank { "Task photo" }, modifier = Modifier.size(134.dp), contentScale = ContentScale.Crop)
                                else Text("Photo waiting to transfer", modifier = Modifier.height(100.dp))
                                if (photo.caption.isNotBlank()) Text(photo.caption)
                                TextButton(onClick = { model.edit(task, "photo:${photo.hash}", "") }, enabled = board.canEdit) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
        item {
            HorizontalDivider(); Text("Optional location", style = MaterialTheme.typography.titleMedium)
            if (task.value("gps").isNotEmpty()) {
                val point = wireJson.decodeFromString<GeoPoint>(task.value("gps"))
                Text(String.format(Locale.US, "%.6f, %.6f · accuracy %.0f m", point.latitude, point.longitude, point.accuracy))
                TextButton(onClick = { model.edit(task, "gps", "") }, enabled = board.canEdit) { Text("Remove location") }
            }
            OutlinedButton(onClick = { location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, enabled = board.canEdit) { Text("Capture current location") }
        }
        item { HorizontalDivider(); Text("Recent activity", style = MaterialTheme.typography.titleMedium) }
        items(history.take(30), key = { it.localSeq }) { stored ->
            val op = stored.operation
            val label = when {
                op.field.startsWith("check:") -> "Checklist ${op.field.substringAfterLast(':')}"
                op.field.startsWith("photo:") -> if (op.value.isEmpty()) "Photo removed" else "Photo attached"
                else -> friendly(op.field)
            }
            val value = when {
                op.field == "status" -> friendly(wireJson.decodeFromString<StatusChange>(op.value).value)
                op.field.startsWith("photo:") || op.field == "gps" -> ""
                else -> op.value.take(150)
            }
            Text("$label${if (value.isEmpty()) "" else ": $value"}", style = MaterialTheme.typography.bodyMedium)
            val date = if (op.createdAt == 0L) "Earlier edit" else SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(op.createdAt))
            Text("${op.author.ifEmpty { "Peer" }} · $date", style = MaterialTheme.typography.labelSmall)
        }
    }
    editNote?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.note.value) }
        AlertDialog(onDismissRequest = { editNote = null }, title = { Text("Checklist note") },
            text = { OutlinedTextField(text, { text = it }, minLines = 3) },
            confirmButton = { TextButton(onClick = { model.edit(task, "check:${item.id}:note", text); editNote = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editNote = null }) { Text("Cancel") } })
    }
    photoUri?.let { uri ->
        AlertDialog(onDismissRequest = { photoUri = null }, title = { Text("Attach photo") },
            text = { OutlinedTextField(caption, { caption = it }, label = { Text("Caption (optional)") }) },
            confirmButton = { TextButton(onClick = { model.attachPhoto(task.id, uri, caption); photoUri = null; caption = "" }, enabled = caption.length <= 200) { Text("Attach") } },
            dismissButton = { TextButton(onClick = { photoUri = null }) { Text("Cancel") } })
    }
    fullPhoto?.let { photo ->
        AlertDialog(onDismissRequest = { fullPhoto = null }, title = { Text(photo.caption.ifBlank { "Task photo" }) },
            text = { AsyncImage(model.photoFile(photo.hash), contentDescription = photo.caption, modifier = Modifier.fillMaxWidth().height(300.dp)) },
            confirmButton = { TextButton(onClick = { fullPhoto = null }) { Text("Close") } })
    }
}
