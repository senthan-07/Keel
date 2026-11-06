package dev.keel.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A random 256-bit database key, stored wrapped by a key held in the Android Keystore.
 * If the app is uninstalled, the wrapped key and the database are lost together, so export first.
 */
object DatabaseKey {
    private const val ALIAS = "keel_db_wrap"
    private const val FILE = "db.key"

    /** Returns the key in SQLCipher's raw form, which skips the slow passphrase derivation. */
    fun load(context: Context): String {
        val file = File(context.noBackupFilesDir, FILE)
        val raw = if (file.exists()) unwrap(file.readBytes()) else create(file)
        return "x'" + raw.joinToString("") { "%02x".format(it) } + "'"
    }

    private fun create(file: File): ByteArray {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        // Written to a temporary file first, so a crash can't leave a half-written key.
        val temp = File(file.parentFile, "$FILE.tmp")
        temp.writeBytes(cipher.iv + cipher.doFinal(raw))
        check(temp.renameTo(file)) { "Could not save the database key" }
        return raw
    }

    private fun unwrap(stored: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, stored, 0, IV_SIZE))
        return cipher.doFinal(stored, IV_SIZE, stored.size - IV_SIZE)
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
}
