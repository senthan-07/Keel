package dev.keel.app.ui

import android.app.Activity
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.keel.app.data.BackupContents
import dev.keel.app.data.Repo
import dev.keel.domain.BackupException
import dev.keel.domain.BackupFile
import dev.keel.domain.RecoveryPhrase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Blocks screenshots and the recent-apps preview while a recovery phrase is on screen. */
@Composable
private fun SecureScreen() {
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
private fun PhraseText(phrase: String) {
    Text(phrase, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun PhraseField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private data class BackupStatus(val phrase: String?, val lastAt: Long?)

private fun lastBackupLine(lastAt: Long?): String {
    if (lastAt == null) return "No backup yet."
    val days = Duration.ofMillis(System.currentTimeMillis() - lastAt).toDays()
    return when (days) {
        0L -> "Last backup: today."
        1L -> "Last backup: yesterday."
        else -> "Last backup: $days days ago."
    }
}

@Composable
fun BackupSection(model: AppModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by loadState(model) { BackupStatus(backupPhrase(), setting(Repo.LAST_BACKUP)?.toLongOrNull()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showing by remember { mutableStateOf(false) }
    val phrase = status?.phrase

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri == null || phrase == null) return@rememberLauncherForActivityResult
        busy = true
        message = "Saving and checking your backup..."
        scope.launch {
            message = withContext(Dispatchers.IO) { writeAndCheck(context, model.repo, uri, phrase) }
            busy = false
            model.refresh()
        }
    }

    val loaded = status ?: return
    Panel {
        if (phrase == null) {
            PhraseSetup(model)
        } else {
            Text(lastBackupLine(loaded.lastAt))
            Muted("Pick somewhere that survives losing this phone, like Google Drive, or a folder you then copy off it.")
            Button(
                { create.launch("keel-backup-${LocalDate.now()}.keelbak") },
                Modifier.fillMaxWidth(),
                enabled = !busy,
            ) { Text("Back up now") }
            message?.let { Muted(it) }
            if (showing) {
                SecureScreen()
                PhraseText(phrase)
                TextButton({ showing = false }) { Text("Hide recovery phrase") }
            } else {
                TextButton({ showing = true }) { Text("Show recovery phrase") }
            }
        }
    }
}

/** Saves the backup, then reads it back and decrypts it, so "saved" means it really can be restored. */
private fun writeAndCheck(context: android.content.Context, repo: Repo, uri: Uri, phrase: String): String =
    try {
        val key = requireNotNull(RecoveryPhrase.normalize(phrase))
        val payload = repo.exportJson(pretty = false).toByteArray()
        val resolver = context.contentResolver
        requireNotNull(resolver.openOutputStream(uri, "wt")) { "Couldn't open the file." }.use {
            BackupFile.write(it, key, payload)
        }
        val back = requireNotNull(resolver.openInputStream(uri)) { "Couldn't read the file back." }.use {
            BackupFile.read(it, key)
        }
        check(back.contentEquals(payload)) { "The saved file didn't match." }
        repo.setSetting(Repo.LAST_BACKUP, System.currentTimeMillis().toString())
        "Backup saved and checked (${payload.size / 1024} KB of data)."
    } catch (error: Exception) {
        "Backup failed, so don't rely on that file: ${error.message}"
    }

/** Confirming part of the phrase means backups can't start until it has really been written down. */
@Composable
private fun PhraseSetup(model: AppModel) {
    val phrase = remember { RecoveryPhrase.generate() }
    var started by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    if (!started) {
        Text("Your data is only on this phone. If it's lost or Keel is uninstalled, it's gone without a backup.")
        Button({ started = true }, Modifier.fillMaxWidth()) { Text("Set up backup") }
        return
    }
    SecureScreen()
    Text("Write this recovery phrase down, on paper.")
    PhraseText(phrase)
    Text(
        "Backups can't be opened without it, and nobody can recover it for you. Keep it away from this phone.",
        color = MaterialTheme.colorScheme.error,
    )
    PhraseField(typed, { typed = it }, "Type its last 5 characters")
    val matches = typed.trim().uppercase() == RecoveryPhrase.lastGroup(phrase)
    Button(
        { model.act { setBackupPhrase(phrase) } },
        Modifier.fillMaxWidth(),
        enabled = matches,
    ) { Text("I've written it down") }
}

private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

private sealed interface RestoreStage {
    data object Idle : RestoreStage
    data class NeedPhrase(val uri: Uri, val error: String? = null) : RestoreStage
    data object Working : RestoreStage
    data class Confirm(val contents: BackupContents, val phrase: String) : RestoreStage
}

/** Nothing in Keel changes until the file is fully decrypted, checked, and you've confirmed. */
@Composable
fun RestoreSection(model: AppModel, replacesData: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<RestoreStage>(RestoreStage.Idle) }
    var typed by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            typed = ""
            message = null
            stage = RestoreStage.NeedPhrase(uri)
        }
    }
    when (val current = stage) {
        RestoreStage.Idle -> {
            OutlinedButton({ pick.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text("Restore from a backup") }
            message?.let { Muted(it) }
        }
        is RestoreStage.NeedPhrase -> Panel {
            Text("Enter the recovery phrase for this backup.")
            PhraseField(typed, { typed = it }, "Recovery phrase")
            current.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ stage = RestoreStage.Idle }) { Text("Cancel") }
                Button({
                    val key = RecoveryPhrase.normalize(typed)
                    if (key == null) {
                        stage = current.copy(error = "A recovery phrase has 25 letters and numbers. Check for a typo.")
                    } else {
                        stage = RestoreStage.Working
                        scope.launch {
                            stage = withContext(Dispatchers.IO) {
                                try {
                                    val bytes = requireNotNull(context.contentResolver.openInputStream(current.uri)) {
                                        "Couldn't open the file."
                                    }.use { BackupFile.read(it, key) }
                                    RestoreStage.Confirm(BackupContents.parse(bytes), RecoveryPhrase.format(key))
                                } catch (error: BackupException) {
                                    current.copy(error = error.message)
                                } catch (error: Exception) {
                                    current.copy(error = "Couldn't read the file: ${error.message}")
                                }
                            }
                        }
                    }
                }) { Text("Open backup") }
            }
        }
        RestoreStage.Working -> Panel { Text("Working...") }
        is RestoreStage.Confirm -> Panel {
            val contents = current.contents
            val date = Instant.ofEpochMilli(contents.exportedAt).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
            Text("Backup from $date", style = MaterialTheme.typography.titleMedium)
            Muted(
                listOf(
                    count(contents.goals, "goal"),
                    count(contents.tasks, "task"),
                    count(contents.rules, "rule"),
                    count(contents.pauses, "pause"),
                ).joinToString(", "),
            )
            if (replacesData) {
                Text(
                    "This replaces everything in Keel now. Anything added since this backup is lost.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ stage = RestoreStage.Idle }) { Text("Cancel") }
                Button({
                    stage = RestoreStage.Working
                    scope.launch {
                        message = withContext(Dispatchers.IO) {
                            try {
                                model.repo.restore(contents, current.phrase)
                                "Restored. Check that Keel's permissions are on under Your data."
                            } catch (error: Exception) {
                                "Restore failed, and your data wasn't changed: ${error.message}"
                            }
                        }
                        stage = RestoreStage.Idle
                        model.refresh()
                    }
                }) { Text(if (replacesData) "Replace everything" else "Restore") }
            }
        }
    }
}
