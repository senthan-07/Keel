package dev.keel.app.data

import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.net.DatagramSocket

data class PrivacyResult(
    /** Network-related permissions Keel asks for. Should always be empty. */
    val networkPermissions: List<String>,
    /** Whether Android refused to give Keel a network socket. */
    val socketRefused: Boolean,
)

/** Checks the claim on this phone at the moment you look, rather than just stating it. */
object PrivacyCheck {
    private val NETWORK_PERMISSIONS = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.BLUETOOTH",
        "android.permission.BLUETOOTH_CONNECT",
    )

    fun run(context: Context): PrivacyResult {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        return PrivacyResult(requested.filter { it in NETWORK_PERMISSIONS }, socketRefused())
    }

    /** Asks for a socket without sending anything. Without the permission, Android refuses it. */
    private fun socketRefused(): Boolean = try {
        DatagramSocket().close()
        false
    } catch (_: IOException) {
        true
    } catch (_: SecurityException) {
        true
    }
}
