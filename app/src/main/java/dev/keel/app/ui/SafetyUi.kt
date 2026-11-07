package dev.keel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.keel.app.data.LocalBackups
import dev.keel.app.data.PrivacyCheck
import dev.keel.app.data.SavedBackup
import dev.keel.app.pause.FallbackPauseService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What Keel can honestly say about privacy, with a live check where one is possible. */
@Composable
fun PrivacyPanel(model: AppModel) {
    val context = LocalContext.current
    val result by loadState(model) { PrivacyCheck.run(context) }
    Panel {
        Text("Local-first, no internet", style = MaterialTheme.typography.titleMedium)
        val loaded = result
        when {
            loaded == null -> Muted("Checking...")
            loaded.networkPermissions.isEmpty() && loaded.socketRefused -> {
                Text("Checked just now on this phone:", color = MaterialTheme.colorScheme.primary)
                Text("Keel doesn't ask for internet access.")
                Text("Android refused Keel a network connection.")
            }
            else -> Text(
                "Warning: this check failed, so don't rely on the claims below. " +
                    "Asked for: ${loaded.networkPermissions.joinToString().ifEmpty { "nothing" }}. " +
                    "Network refused: ${loaded.socketRefused}.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Muted("No account, ads, analytics or servers. Your data is encrypted and stays on this phone.")
        Muted("If you save a backup to Drive or another cloud app, that app uploads the file, not Keel. It stays encrypted.")
        Muted("To check for yourself: Settings > Apps > Keel > Permissions has no internet entry. The code is public at github.com/senthan-07/Keel.")
    }
}

/** Backups saved in Download/Keel, each of which can be deleted after a warning. */
@Composable
fun SavedBackupsSection(model: AppModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by loadState(model) { LocalBackups.list(context) }
    var confirming by remember { mutableStateOf<SavedBackup?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val list = saved.orEmpty()
    if (list.isEmpty()) {
        Muted("No backups saved by this install of Keel. Any from before a reinstall are still in Download/Keel, and Restore can open them.")
    } else {
        Text("On this phone, in Download/Keel:")
        list.forEach { backup ->
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(backup.name)
                    Muted("${savedDate(backup.savedAt)}, ${backup.bytes / 1024} KB")
                }
                TextButton({ confirming = backup }) { Text("Delete") }
            }
        }
    }
    message?.let { Muted(it) }
    confirming?.let { backup ->
        val isLast = list.size == 1
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Delete this backup?") },
            text = {
                Text(
                    if (isLast) {
                        "This is your only backup listed here. Without a backup, losing Keel means losing your data for good. This can't be undone."
                    } else {
                        "${backup.name} will be deleted for good. This can't be undone."
                    },
                )
            },
            confirmButton = {
                TextButton({
                    confirming = null
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            runCatching { LocalBackups.delete(context, backup.uri) }.getOrDefault(false)
                        }
                        message = if (deleted) "Backup deleted." else "Couldn't delete it. Try your phone's Files app."
                        model.refresh()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirming = null }) { Text("Keep") } },
        )
    }
}

private fun savedDate(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))

/** Erasing everything needs a typed confirmation, so it can't happen by accident. */
@Composable
fun DeleteEverythingSection(model: AppModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Panel {
        Text("Removes every goal, task, rule, pause record and usage record from Keel, including its copy of your recovery phrase.")
        OutlinedButton(
            { open = true; typed = ""; error = null },
            Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Delete all my Keel data") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Delete all your Keel data?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This can't be undone. Keel returns to its first screen, and stops pausing apps.")
                    Text("Backup files aren't deleted. To restore one later, you'll need your written-down recovery phrase.")
                    OutlinedTextField(
                        typed,
                        { typed = it },
                        label = { Text("Type DELETE to confirm") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = typed.trim() == "DELETE",
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        open = false
                        scope.launch {
                            val failure = withContext(Dispatchers.IO) { runCatching { model.repo.deleteEverything() }.exceptionOrNull() }
                            if (failure == null) {
                                FallbackPauseService.stop(context)
                            } else {
                                error = "Nothing was deleted: ${failure.message}"
                            }
                            model.refresh()
                        }
                    },
                ) { Text("Delete everything") }
            },
            dismissButton = { TextButton({ open = false }) { Text("Cancel") } },
        )
    }
}
