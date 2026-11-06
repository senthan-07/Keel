package dev.keel.app.pause

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.provider.Settings
import dev.keel.app.KeelApp
import dev.keel.domain.HealthInput
import dev.keel.domain.healthProblems

object Permissions {
    fun overlay(context: Context) = Settings.canDrawOverlays(context)

    fun usage(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun accessibility(context: Context): Boolean {
        val component = ComponentName(context, PauseAccessibilityService::class.java).flattenToString()
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(":")?.contains(component) == true
    }

    fun problems(context: Context): List<String> {
        val repo = (context.applicationContext as KeelApp).repo
        return healthProblems(
            HealthInput(
                accessibilityOn = accessibility(context),
                overlayOn = overlay(context),
                usageOn = usage(context),
                anyRuleEnabled = repo.rules().any { it.state.current.enabled },
            ),
        )
    }
}
