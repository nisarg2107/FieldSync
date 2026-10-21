package dev.fieldsync.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fieldsync.core.Team
import dev.fieldsync.core.WORKSPACE

@Composable
fun TeamScreen(board: BoardState, model: BoardViewModel) {
    val busy by model.busy.collectAsStateWithLifecycle()
    var register by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords are not saved in instance state or Room.
    var password by remember { mutableStateOf("") }
    var teamName by rememberSaveable { mutableStateOf("") }
    var invite by rememberSaveable { mutableStateOf("") }
    val signedIn = board.replica?.token?.isNotBlank() == true
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Team workspace", style = MaterialTheme.typography.headlineSmall)
            Text("Work offline after signing in and selecting a team. Accounts and team membership require the PostgreSQL-backed server.")
        }
        if (!signedIn) {
            item {
                Text("The public demo is available without an account.")
                if (register) OutlinedTextField(name, { name = it }, label = { Text("Your name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Password (8–72 bytes)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(onClick = { model.account(name, email, password, register); password = "" }, enabled = !busy && email.isNotBlank() && password.length >= 8 && (!register || name.isNotBlank())) { Text(if (register) "Create account" else "Sign in") }
                TextButton(onClick = { register = !register }) { Text(if (register) "Use existing account" else "Create an account") }
                Text("Set your server address in Sync before signing in.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            item {
                Text("Signed in as ${board.replica?.displayName}")
                Row {
                    OutlinedButton(onClick = { model.refreshTeams() }, enabled = !busy) { Text("Refresh teams & members") }
                    TextButton(onClick = { model.signOut() }, enabled = !busy) { Text("Sign out") }
                }
                Text("Selected: ${board.workspaceName} · ${friendly(board.replica?.role.orEmpty())}")
            }
            items(board.teams, key = { it.id }) { team ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${team.name} · ${friendly(team.role)}", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { model.selectTeam(team) }, enabled = !busy && board.replica?.workspace != team.id) { Text("Use workspace") }
                        if (team.inviteCode.isNotEmpty()) {
                            Text("Invite code (select to copy):", style = MaterialTheme.typography.labelSmall)
                            SelectionContainer { Text(team.inviteCode) }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { model.selectTeam(Team(WORKSPACE, "Public demo", "editor")) }, enabled = !busy) { Text("Use public demo") }
                HorizontalDivider()
                OutlinedTextField(teamName, { teamName = it }, label = { Text("New team name") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { model.createOrJoinTeam(teamName, ""); teamName = "" }, enabled = !busy && teamName.isNotBlank()) { Text("Create team") }
                OutlinedTextField(invite, { invite = it }, label = { Text("Invite code") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { model.createOrJoinTeam("", invite); invite = "" }, enabled = !busy && invite.isNotBlank()) { Text("Join team") }
                Text("New members join read-only. The owner can grant editor access below.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(); Text("Members", style = MaterialTheme.typography.titleMedium)
            }
            items(board.members, key = { it.user.id }) { member ->
                Column {
                    Text("${member.user.name} · ${friendly(member.role)}")
                    if (board.replica?.role == "owner" && member.role != "owner") {
                        TextButton(onClick = { model.setRole(member.user.id, if (member.role == "viewer") "editor" else "viewer") }, enabled = !busy) {
                            Text(if (member.role == "viewer") "Grant editor access" else "Make read-only")
                        }
                    }
                }
            }
        }
    }
}
