package dev.fieldsync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import dev.fieldsync.core.Task
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun BoardScreen(model: BoardViewModel) {
    val board by model.board.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("Home") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var camera by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(board.replica?.workspace) { selected = null; editor = false; camera = null }
    val task = board.tasks.find { it.id == selected }
    BackHandler(enabled = camera != null || task != null) {
        if (camera != null) camera = null else selected = null
    }
    MaterialTheme {
        if (camera != null) {
            CameraScreen(onBack = { camera = null }, onCaptured = { uri ->
                model.attachPhoto(camera!!, uri); camera = null
            })
        } else Scaffold(
            modifier = Modifier.systemBarsPadding().imePadding(),
            bottomBar = {
                NavigationBar {
                    listOf("Home", "Tasks", "Nearby", "Team", "Sync").forEach { destination ->
                        NavigationBarItem(
                            selected = tab == destination && task == null,
                            onClick = { tab = destination; selected = null },
                            icon = { Text(destination.take(1)) }, label = { Text(destination) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("FieldSync", style = MaterialTheme.typography.headlineSmall)
                    Text("${board.workspaceName} · ${board.pending} pending uploads", style = MaterialTheme.typography.labelMedium)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (task != null) TaskDetailScreen(
                    task, board, model,
                    onBack = { selected = null },
                    onEdit = { editing = task.id; editor = true },
                    onCamera = { camera = task.id },
                ) else when (tab) {
                    "Home" -> HomeScreen(board, onCreate = { editing = null; editor = true }, onOpen = { selected = it.id })
                    "Tasks" -> TaskListScreen(board, onCreate = { editing = null; editor = true }, onOpen = { selected = it.id })
                    "Nearby" -> NearbyScreen(model)
                    "Team" -> TeamScreen(board, model)
                    "Sync" -> SyncScreen(board, model)
                }
            }
        }
        if (editor) TaskEditor(
            task = board.tasks.find { it.id == editing }, members = board.members,
            onDismiss = { editor = false },
            onSave = { fields -> model.saveTask(editing, fields); editor = false },
        )
    }
}

@Composable
private fun HomeScreen(board: BoardState, onCreate: () -> Unit, onOpen: (Task) -> Unit) {
    val tasks = board.tasks.filter { it.isVisible }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Your shared work", style = MaterialTheme.typography.headlineMedium)
            Text("Create and edit offline. Exchange nearby or sync with your team later.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onCreate, enabled = board.canEdit) { Text("Create task") }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Task overview", style = MaterialTheme.typography.titleMedium)
                    Text("${tasks.size} total · ${tasks.count { it.status == "completed" }} completed · ${tasks.count { it.status == "in_progress" }} in progress")
                    Text("${tasks.count { overdue(it) }} overdue · ${tasks.count { it.hasStatusConflict }} conflicts to resolve")
                    val completed = tasks.count { it.status == "completed" }
                    LinearProgressIndicator(progress = { if (tasks.isEmpty()) 0f else completed.toFloat() / tasks.size }, modifier = Modifier.fillMaxWidth())
                    Text("${tasks.map { it.value("project") }.filter { it.isNotBlank() }.distinct().size} projects")
                }
            }
        }
        item { Text("Needs attention", style = MaterialTheme.typography.titleMedium) }
        val attention = tasks.filter { it.hasStatusConflict || overdue(it) || it.status == "blocked" }
        if (attention.isEmpty()) item { Text("No overdue tasks or unresolved status conflicts.") }
        items(attention, key = { it.id }) { TaskSummary(it, board, onOpen) }
        item { Text(board.status, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun TaskListScreen(board: BoardState, onCreate: () -> Unit, onOpen: (Task) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("all") }
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = board.tasks.filter { task ->
        val visible = if (filter == "archived") task.deleted.value == "true" else task.isVisible
        val matches = when (filter) {
            "all", "archived" -> true
            "mine" -> task.value("assignee").isNotEmpty() && task.value("assignee") == board.replica?.userId
            "overdue" -> overdue(task)
            else -> task.status == filter
        }
        visible && matches && "${task.title.value} ${task.value("project")} ${task.value("description")}".contains(search, ignoreCase = true)
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Tasks", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onCreate, enabled = board.canEdit) { Text("New task") }
            }
        }
        item { OutlinedTextField(search, { search = it }, label = { Text("Search tasks or projects") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all", "open", "in_progress", "blocked", "completed", "conflict", "mine", "overdue", "archived").forEach { value ->
                    FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(friendly(value)) })
                }
            }
        }
        if (filtered.isEmpty()) item { Text("No tasks in this view. Offline edits appear here immediately.") }
        items(filtered.sortedBy { it.value("dueDate").ifEmpty { "9999" } }, key = { it.id }) { TaskSummary(it, board, onOpen) }
    }
}

@Composable
private fun TaskSummary(task: Task, board: BoardState, onOpen: (Task) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(task) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(task.title.value, style = MaterialTheme.typography.titleMedium)
            Text("${friendly(task.status)} · ${friendly(task.value("priority").ifEmpty { "normal" })} priority")
            if (task.value("project").isNotBlank()) Text(task.value("project"))
            if (task.value("dueDate").isNotBlank()) Text("Due ${task.value("dueDate")}${if (overdue(task)) " · Overdue" else ""}")
            val assignee = board.members.find { it.user.id == task.value("assignee") }?.user?.name
            if (assignee != null) Text("Assigned to $assignee")
            val checks = task.visibleChecks
            if (checks.isNotEmpty()) Text("Checklist: ${checks.count { it.result.value == "pass" }}/${checks.size} passed")
            if (task.visiblePhotos.isNotEmpty()) Text("${task.visiblePhotos.size} photos")
        }
    }
}

fun friendly(value: String): String = value.replace('_', ' ').replaceFirstChar { it.uppercase() }
fun overdue(task: Task): Boolean {
    val date = task.value("dueDate")
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    return date.isNotBlank() && date < today && task.status != "completed" && task.isVisible
}
fun readableBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MiB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
