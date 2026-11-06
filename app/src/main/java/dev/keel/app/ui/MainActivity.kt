package dev.keel.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import dev.keel.app.KeelApp
import dev.keel.app.data.Repo
import dev.keel.app.data.SyncJob
import dev.keel.app.pause.FallbackPauseService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared by all screens. Database work runs off the main thread, and each change refreshes the UI. */
class AppModel(val repo: Repo, private val scope: CoroutineScope) {
    var tick by mutableIntStateOf(0)
        private set

    fun refresh() {
        tick++
    }

    fun act(block: Repo.() -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { repo.block() }
            refresh()
        }
    }
}

enum class Screen(val label: String, val icon: ImageVector) {
    HORIZONS("Horizons", Icons.Filled.Home),
    TODAY("Today", Icons.Filled.DateRange),
    RULES("Rules", Icons.Filled.Lock),
    REVIEW("Review", Icons.Filled.Star),
    DATA("Your data", Icons.Filled.Info),
}

class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel
    private var screen by mutableStateOf(Screen.HORIZONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        model = AppModel((application as KeelApp).repo, lifecycleScope)
        applyIntent(intent)
        setContent {
            MaterialTheme(colorScheme = KeelColors) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(model, screen) { screen = it }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have changed in Settings. This is also when a force-stop gets noticed.
        model.refresh()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { SyncJob.sync(this@MainActivity) }
            if (model.repo.setting("onboarded") == "true") {
                withContext(Dispatchers.Main) { FallbackPauseService.sync(this@MainActivity) }
            }
        }
    }

    private fun applyIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_TODAY) screen = Screen.TODAY
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_TODAY = "today"
    }
}

private val KeelColors = darkColorScheme(
    primary = Color(0xFFF2C14E),
    onPrimary = Color(0xFF101820),
    secondaryContainer = Color(0xFF2B3A47),
    onSecondaryContainer = Color(0xFFF4F6F8),
    background = Color(0xFF101820),
    onBackground = Color(0xFFF4F6F8),
    surface = Color(0xFF101820),
    onSurface = Color(0xFFF4F6F8),
    surfaceVariant = Color(0xFF1C2833),
    onSurfaceVariant = Color(0xFFA9B4BE),
    surfaceContainer = Color(0xFF1C2833),
    surfaceContainerHigh = Color(0xFF243240),
    outline = Color(0xFF5A6B79),
    error = Color(0xFFFF8A80),
)

@Composable
private fun Root(model: AppModel, screen: Screen, onScreen: (Screen) -> Unit) {
    val onboarded by loadState(model) { setting("onboarded") ?: "false" }
    when (onboarded) {
        null -> Box(Modifier.fillMaxSize())
        "true" -> Scaffold(
            bottomBar = {
                NavigationBar {
                    Screen.entries.forEach {
                        NavigationBarItem(
                            selected = it == screen,
                            onClick = { onScreen(it) },
                            icon = { Icon(it.icon, contentDescription = null) },
                            label = { Text(it.label, maxLines = 1) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (screen) {
                    Screen.HORIZONS -> HorizonsScreen(model)
                    Screen.TODAY -> TodayScreen(model)
                    Screen.RULES -> RulesScreen(model)
                    Screen.REVIEW -> ReviewScreen(model)
                    Screen.DATA -> DataScreen(model)
                }
            }
        }
        else -> OnboardingScreen(model)
    }
}
