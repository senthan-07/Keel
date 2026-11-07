package dev.keel.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class SavedBackup(val uri: Uri, val name: String, val bytes: Long, val savedAt: Long)

/**
 * Backups in this phone's Download/Keel folder. This needs no storage permission, and the files
 * stay when Keel is uninstalled, but not if the phone is lost or reset.
 *
 * Android only lets an app list and delete files it created since it was installed. After a
 * reinstall, older backups can still be restored through the file picker, but not listed here.
 */
object LocalBackups {
    private const val FOLDER = "Download/Keel/"
    private val COLLECTION: Uri get() = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    /** Creates a hidden file, for [publish] to reveal once it has been written and checked. */
    fun create(context: Context, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return requireNotNull(context.contentResolver.insert(COLLECTION, values)) {
            "Couldn't create a file in Download/Keel."
        }
    }

    fun publish(context: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    fun list(context: Context): List<SavedBackup> {
        val columns = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_ADDED,
        )
        val saved = mutableListOf<SavedBackup>()
        context.contentResolver.query(
            COLLECTION,
            columns,
            "${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(FOLDER),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                saved += SavedBackup(
                    ContentUris.withAppendedId(COLLECTION, c.getLong(0)),
                    c.getString(1),
                    c.getLong(2),
                    c.getLong(3) * 1000,
                )
            }
        }
        return saved
    }

    /** Keeps only the newest automatic backups. Backups you made yourself are never touched. */
    fun pruneAutomatic(context: Context, keep: Int = AUTO_KEEP) {
        list(context).filter { it.name.startsWith(AUTO_PREFIX) }.drop(keep).forEach {
            runCatching { delete(context, it.uri) }
        }
    }

    const val AUTO_PREFIX = "keel-auto-"
    private const val AUTO_KEEP = 4

    fun delete(context: Context, uri: Uri): Boolean = context.contentResolver.delete(uri, null, null) > 0
}
