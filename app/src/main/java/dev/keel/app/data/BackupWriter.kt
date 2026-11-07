package dev.keel.app.data

import android.content.Context
import android.net.Uri
import dev.keel.domain.BackupFile
import dev.keel.domain.RecoveryPhrase

/** Writes encrypted backups and proves each one can be read back before calling it saved. */
object BackupWriter {
    const val OK = "Backup saved and checked"

    /** Saves to Download/Keel. The file stays hidden until checked, and a failed one is removed. */
    fun saveToPhone(context: Context, repo: Repo, phrase: String, name: String): String {
        val uri = try {
            LocalBackups.create(context, name)
        } catch (error: Exception) {
            return "Backup failed: ${error.message}"
        }
        val result = writeAndCheck(context, repo, uri, phrase)
        if (result.startsWith(OK)) {
            LocalBackups.publish(context, uri)
        } else {
            runCatching { LocalBackups.delete(context, uri) }
        }
        return result
    }

    fun writeAndCheck(context: Context, repo: Repo, uri: Uri, phrase: String): String = try {
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
        "$OK (${payload.size / 1024} KB of data)."
    } catch (error: Exception) {
        "Backup failed, so don't rely on that file: ${error.message}"
    }
}
