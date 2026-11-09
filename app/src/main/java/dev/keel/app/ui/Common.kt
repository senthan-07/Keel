package dev.keel.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.keel.app.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Height of the frosted tab bar floating over the bottom of the screen. Zero where there's no bar. */
val LocalBottomBar = staticCompositionLocalOf { 0.dp }

/** Loads off the main thread, and reloads whenever the model refreshes. Null until the first load. */
@Composable
fun <T> loadState(model: AppModel, read: Repo.() -> T): State<T?> =
    produceState<T?>(null, model.tick) {
        value = withContext(Dispatchers.IO) { model.repo.read() }
    }

@Composable
fun Page(
    title: String,
    subtitle: String? = null,
    overline: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            // Edge-to-edge windows aren't resized for the keyboard, so make room for it here.
            .imePadding()
            .verticalScroll(rememberScrollState())
            // The tab bar floats over the page, so the last card scrolls clear of it.
            .padding(start = KeelSpace.Gutter, end = KeelSpace.Gutter, top = 20.dp, bottom = 32.dp + LocalBottomBar.current),
        verticalArrangement = Arrangement.spacedBy(KeelSpace.Item),
    ) {
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            onBack?.let { back ->
                KeelTextButton(back, Modifier.offset { IntOffset(-10.dp.roundToPx(), 0) }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            }
            overline?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = KeelColor.TextSecondary) }
            Text(title, style = MaterialTheme.typography.headlineLarge)
            subtitle?.let { Muted(it) }
        }
        content()
    }
}

@Composable
fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = KeelColor.TextSecondary)
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = KeelColor.TextSecondary,
        modifier = Modifier.padding(top = 20.dp, bottom = 2.dp).semantics { heading() },
    )
}

/** The one card, in frosted glass over the backdrop's glow. */
@Composable
fun Panel(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    val press = if (onClick != null) Modifier.pressScale(source) else Modifier
    val tap = if (onClick != null) {
        Modifier.clickable(interactionSource = source, indication = ripple(), onClick = onClick)
    } else {
        Modifier
    }
    Column(
        press
            .fillMaxWidth()
            .glass(MaterialTheme.shapes.large)
            .then(tap)
            .padding(KeelSpace.CardPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A card row that leads somewhere, with a chevron saying so. */
@Composable
fun NavRow(title: String, detail: String? = null, onClick: () -> Unit) {
    Panel(onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                detail?.let { Muted(it) }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = KeelColor.TextSecondary)
        }
    }
}

data class InstalledApp(val pkg: String, val label: String)

fun installedApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.pkg != context.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

fun appLabel(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/** The home screen app, which is left out of usage lists because it isn't really "used". */
fun homePackage(context: Context): String? = context.packageManager
    .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
    ?.activityInfo?.packageName

/** A quiet action in a list, for pages with several things you could do. */
@Composable
fun ActionRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val tint = if (danger) KeelColor.Danger else KeelColor.Text
    Row(
        Modifier
            .pressScale(source)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(interactionSource = source, indication = ripple(), role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) KeelColor.Danger else KeelColor.TextSecondary, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
