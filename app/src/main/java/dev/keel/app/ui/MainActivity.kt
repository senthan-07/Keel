package dev.keel.app.ui

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
class AppModel(val repo: Repo, private val scope: CoroutineScope, initialTheme: ThemeMode) {
    var theme by mutableStateOf(initialTheme)

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

enum class Screen(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HORIZONS("Horizons", Icons.Outlined.Landscape, Icons.Filled.Landscape),
    TODAY("Today", Icons.Outlined.CheckCircle, Icons.Filled.CheckCircle),
    RULES("Rules", Icons.Outlined.Shield, Icons.Filled.Shield),
    REVIEW("Review", Icons.Outlined.BarChart, Icons.Filled.BarChart),
    DATA("Your data", Icons.Outlined.Lock, Icons.Filled.Lock),
}

class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel
    private var screen by mutableStateOf(Screen.HORIZONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        val startTheme = ThemePrefs.load(this)
        applySystemBars(ThemePrefs.palette(this, startTheme))
        super.onCreate(savedInstanceState)
        model = AppModel((application as KeelApp).repo, lifecycleScope, startTheme)
        applyIntent(intent)
        setContent {
            val systemDark = isSystemInDarkTheme()
            // Without this, the keyboard reopens over the tabs when you come back to a focused field.
            val focus = LocalFocusManager.current
            DisposableEffect(Unit) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) focus.clearFocus(force = true)
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(model.theme, systemDark) {
                ThemePrefs.save(this@MainActivity, model.theme)
                applySystemBars(ThemePrefs.palette(this@MainActivity, model.theme))
            }
            KeelTheme(model.theme) {
                // See-through, so the backdrop shows, but text still takes the theme colour.
                Surface(Modifier.fillMaxSize().keelBackdrop(KeelColor.palette), color = Color.Transparent, contentColor = KeelColor.Text) {
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

    /** Keeps status bar icons readable, and the window behind Compose the right colour, in each theme. */
    private fun applySystemBars(palette: KeelPalette) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (palette.isDark) {
            SystemBarStyle.dark(transparent)
        } else {
            SystemBarStyle.light(transparent, transparent)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        window.setBackgroundDrawable(ColorDrawable(palette.page.toArgb()))
    }

    private fun applyIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_TODAY) screen = Screen.TODAY
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_TODAY = "today"
    }
}

@Composable
private fun Root(model: AppModel, screen: Screen, onScreen: (Screen) -> Unit) {
    val onboarded by loadState(model) { setting("onboarded") ?: "false" }
    when (onboarded) {
        null -> Box(Modifier.fillMaxSize())
        "true" -> {
            val haze = rememberHazeState()
            val density = LocalDensity.current
            var barHeight by remember { mutableStateOf(0.dp) }
            Box(Modifier.fillMaxSize()) {
                // The pages and their backdrop are what the tab bar blurs as they scroll under it.
                Box(Modifier.fillMaxSize().hazeSource(haze)) {
                    Box(Modifier.fillMaxSize().keelBackdrop(KeelColor.palette))
                    CompositionLocalProvider(LocalBottomBar provides barHeight) {
                        // Tabs are switched many times a day, so they get a short fade and no movement.
                        Crossfade(
                            screen,
                            Modifier.fillMaxSize(),
                            tween(KeelMotion.STATE_MS, easing = KeelMotion.EaseOut),
                            label = "tabs",
                        ) { current ->
                            when (current) {
                                Screen.HORIZONS -> HorizonsScreen(model)
                                Screen.TODAY -> TodayScreen(model)
                                Screen.RULES -> RulesScreen(model)
                                Screen.REVIEW -> ReviewScreen(model)
                                Screen.DATA -> DataScreen(model)
                            }
                        }
                    }
                }
                KeelNavBar(
                    screen,
                    onScreen,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { barHeight = with(density) { it.height.toDp() } }
                        .hazeEffect(
                            haze,
                            HazeStyle(
                                backgroundColor = KeelColor.Page,
                                tint = HazeTint(KeelColor.Page.copy(alpha = if (KeelColor.palette.isDark) 0.7f else 0.66f)),
                                blurRadius = 24.dp,
                                noiseFactor = 0.03f,
                            ),
                        ),
                )
            }
        }
        else -> OnboardingScreen(model)
    }
}

@Composable
private fun KeelNavBar(selected: Screen, onSelect: (Screen) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        HorizontalDivider(color = KeelColor.GlassEdge.copy(alpha = KeelColor.GlassEdge.alpha * 0.6f), thickness = 1.dp)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup()) {
            Screen.entries.forEach { screen ->
                val isSelected = screen == selected
                val source = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .weight(1f)
                        .selectable(
                            selected = isSelected,
                            interactionSource = source,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(screen) },
                        )
                        .pressScale(source)
                        .padding(top = 10.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // The chosen tab sits in a soft pill, faded in on the same timing as the page.
                    val pill by animateFloatAsState(
                        if (isSelected) 1f else 0f,
                        tween(KeelMotion.STATE_MS, easing = KeelMotion.EaseOut),
                        label = "tabPill",
                    )
                    Box(
                        Modifier
                            .size(width = 56.dp, height = 30.dp)
                            .clip(CircleShape)
                            .background(KeelColor.Accent.copy(alpha = 0.16f * pill)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (isSelected) screen.selectedIcon else screen.icon,
                            contentDescription = null,
                            tint = if (isSelected) KeelColor.Accent else KeelColor.TextSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Text(
                        screen.label,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                        color = if (isSelected) KeelColor.Text else KeelColor.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
