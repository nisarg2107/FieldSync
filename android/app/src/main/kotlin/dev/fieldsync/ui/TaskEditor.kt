package dev.fieldsync.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fieldsync.core.Member
import dev.fieldsync.core.Operation
import dev.fieldsync.core.Task

@Composable
fun TaskEditor(task: Task?, members: List<Member>, onDismiss: () -> Unit, onSave: (List<Pair<String, String>>) -> Unit) {
    var title by remember { mutableStateOf(task?.title?.value.orEmpty()) }
    var description by remember { mutableStateOf(task?.value("description").orEmpty()) }
    var project by remember { mutableStateOf(task?.value("project").orEmpty()) }
    var due by remember { mutableStateOf(task?.value("dueDate").orEmpty()) }
    var priority by remember { mutableStateOf(task?.value("priority")?.ifBlank { "normal" } ?: "normal") }
    var assignee by remember { mutableStateOf(task?.value("assignee").orEmpty()) }
    var chooseMember by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "Create task" else "Edit task") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, minLines = 2)
                OutlinedTextField(project, { project = it }, label = { Text("Project (optional)") })
                OutlinedTextField(due, { due = it }, label = { Text("Due date: YYYY-MM-DD") }, singleLine = true)
                Text("Priority")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("low", "normal", "high").forEach { p -> FilterChip(priority == p, { priority = p }, label = { Text(friendly(p)) }) }
                }
                Box {
                    OutlinedButton(onClick = { chooseMember = true }) {
                        Text(members.find { it.user.id == assignee }?.user?.name ?: if (assignee.isBlank()) "Unassigned" else "Current team member")
                    }
                    DropdownMenu(chooseMember, onDismissRequest = { chooseMember = false }) {
                        DropdownMenuItem(text = { Text("Unassigned") }, onClick = { assignee = ""; chooseMember = false })
                        members.forEach { member -> DropdownMenuItem(text = { Text(member.user.name) }, onClick = { assignee = member.user.id; chooseMember = false }) }
                    }
                }
                if (members.isEmpty()) Text("Refresh your team's members to assign tasks.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val fields = listOf("title" to title.trim(), "description" to description, "project" to project.trim(),
                    "dueDate" to due.trim(), "priority" to priority, "assignee" to assignee)
                try {
                    fields.forEach { (field, value) -> Operation("preview", "preview", "preview", 1, field, value).validate() }
                    onSave(fields.filter { (field, value) -> task == null || (if (field == "title") task.title.value else task.value(field)) != value })
                } catch (e: Exception) { error = e.message ?: "Check the task fields" }
            }, enabled = title.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
