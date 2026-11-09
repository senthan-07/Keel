package dev.keel.app.ui

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.keel.app.data.Repo
import dev.keel.domain.UsageRange
import dev.keel.domain.UsageReport
import dev.keel.domain.changeLabel
import dev.keel.domain.durationLabel
import dev.keel.domain.hourLabel
import dev.keel.domain.usageReport
import dev.keel.domain.weekStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val TOP_APPS = 5
private const val MINUTE = 60_000L

/** Neat top values for the chart, so the scale reads as a round number. */
private val SCALE_STEPS = listOf(5, 10, 15, 30, 45, 60, 90, 120, 180, 240, 300, 360, 480, 600, 720, 960, 1200, 1440)
    .map { it * MINUTE }

private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

/** App icons, loaded once off the main thread. */
private fun appIcon(context: Context, pkg: String): ImageBitmap? = iconCache[pkg] ?: runCatching {
    context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
}.getOrNull()?.also { iconCache[pkg] = it }

private data class AppRow(val pkg: String, val label: String, val icon: ImageBitmap?, val millis: Long, val previous: Long)

private data class ScreenTime(
    val report: UsageReport,
    val apps: List<AppRow>,
    val anchor: LocalDate,
    val finished: Boolean,
    val canGoBack: Boolean,
)

private fun Repo.loadScreenTime(context: Context, range: UsageRange, offset: Int, pkg: String?): ScreenTime {
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val anchor = if (range == UsageRange.DAY) today.plusDays(offset.toLong()) else today.plusWeeks(offset.toLong())
    val first = if (range == UsageRange.DAY) anchor else weekStart(anchor)
    val length = if (range == UsageRange.DAY) 1L else 7L
    val previous = first.minusDays(length)
    val sessions = sessions(previous.atStartOfDay(zone).toInstant().toEpochMilli(), first.plusDays(length).atStartOfDay(zone).toInstant().toEpochMilli())
        .filter { pkg == null || it.pkg == pkg }
    val exclude = setOfNotNull(homePackage(context), context.packageName)
    val report = usageReport(sessions, range, anchor, now, zone, exclude)
    // Nothing was recorded before Keel was set up, so there's no point going further back.
    val started = setting("first_day")?.let(LocalDate::parse) ?: today
    val apps = if (pkg != null) emptyList() else report.apps.map {
        AppRow(it.pkg, appLabel(context, it.pkg), appIcon(context, it.pkg), it.millis, it.previousMillis)
    }
    return ScreenTime(report, apps, anchor, finished = now >= report.to, canGoBack = previous >= weekStart(started).minusDays(1))
}

/** Screen time for a day or a week, with a chart and the apps behind it. [pkg] narrows it to one app. */
@Composable
fun ScreenTimeSection(model: AppModel, pkg: String? = null, onOpenApp: (String) -> Unit = {}) {
    val context = LocalContext.current
    var range by remember { mutableStateOf(UsageRange.WEEK) }
    var offset by remember { mutableIntStateOf(0) }
    val data by produceState<ScreenTime?>(null, model.tick, range, offset, pkg) {
        value = withContext(Dispatchers.IO) { model.repo.loadScreenTime(context, range, offset, pkg) }
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Segmented(
                listOf("Day" to UsageRange.DAY, "Week" to UsageRange.WEEK),
                range,
            ) {
                range = it
                offset = 0
            }
            Spacer(Modifier.weight(1f))
            KeelTextButton({ offset-- }, enabled = data?.canGoBack == true) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Earlier")
            }
            KeelTextButton({ offset++ }, enabled = offset < 0) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Later")
            }
        }
        val loaded = data ?: return@Panel
        val report = loaded.report
        Text(periodName(range, offset, loaded.anchor), style = MaterialTheme.typography.bodyMedium, color = KeelColor.TextSecondary)
        Text(durationLabel(report.total), style = MaterialTheme.typography.displaySmall.tabular, color = KeelColor.Text)
        if (range == UsageRange.WEEK && report.daysSoFar > 0 && report.total > 0) {
            Muted("${durationLabel(report.total / report.daysSoFar)} a day on average")
        }
        changeLabel(report.total, report.previousTotal, range, loaded.finished)?.let { change ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    if (report.total > report.previousTotal) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    tint = KeelColor.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(change, style = MaterialTheme.typography.bodyMedium, color = KeelColor.TextSecondary)
            }
        }
        if (report.total == 0L) {
            Muted(if (offset == 0) "Nothing recorded yet. Keel records time per app while Usage access is on." else "Nothing recorded.")
        } else {
            UsageBars(report, range)
            report.busiestHour?.let { Muted("Busiest time: ${hourLabel(it)} to ${hourLabel(it + 3)}") }
        }
    }
    val apps = data?.apps.orEmpty()
    if (pkg == null && apps.isNotEmpty()) {
        var showAll by remember { mutableStateOf(false) }
        val top = apps.first().millis
        Panel {
            Text("Apps", style = MaterialTheme.typography.titleMedium)
            (if (showAll) apps else apps.take(TOP_APPS)).forEach { app -> AppUsageRow(app, top) { onOpenApp(app.pkg) } }
            if (apps.size > TOP_APPS) {
                KeelTextButton({ showAll = !showAll }) {
                    Text(if (showAll) "Show fewer" else "Show all ${apps.size} apps")
                }
            }
        }
    }
}

private fun periodName(range: UsageRange, offset: Int, anchor: LocalDate): String = when (range) {
    UsageRange.DAY -> when (offset) {
        0 -> "Today"
        -1 -> "Yesterday"
        else -> anchor.format(DateTimeFormatter.ofPattern("EEEE d MMM"))
    }
    UsageRange.WEEK -> {
        val first = weekStart(anchor)
        val dates = "${first.format(DateTimeFormatter.ofPattern("d MMM"))} to ${first.plusDays(6).format(DateTimeFormatter.ofPattern("d MMM"))}"
        when (offset) {
            0 -> "This week, $dates"
            -1 -> "Last week, $dates"
            else -> dates
        }
    }
}

/** Two or three choices in one pill. The chosen one is filled, so it doesn't rely on colour alone. */
@Composable
fun <T> Segmented(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(KeelColor.Elevated.copy(alpha = 0.7f))
            .padding(3.dp)
            .selectableGroup(),
    ) {
        options.forEach { (label, value) ->
            val chosen = value == selected
            val source = remember { MutableInteractionSource() }
            val fill by animateColorAsState(
                if (chosen) KeelColor.AccentFill else KeelColor.AccentFill.copy(alpha = 0f),
                tween(KeelMotion.STATE_MS, easing = KeelMotion.EaseOut),
                label = "segment",
            )
            Box(
                Modifier
                    .pressScale(source)
                    .heightIn(min = 40.dp)
                    .clip(CircleShape)
                    .background(fill)
                    .selectable(chosen, interactionSource = source, indication = null, role = Role.Tab) { onSelect(value) }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (chosen) KeelColor.OnAccent else KeelColor.TextSecondary,
                )
            }
        }
    }
}

/**
 * One bar per hour or day. Bars are thin with rounded tops and square bases, and share one colour.
 * Tapping a bar reads out its value and lets the rest step back, and a screen reader hears every bar.
 */
@Composable
private fun UsageBars(report: UsageReport, range: UsageRange) {
    val bars = report.bars
    var selected by remember(report.from, range) { mutableStateOf<Int?>(null) }
    val firstDay = java.time.Instant.ofEpochMilli(report.from).atZone(ZoneId.systemDefault()).toLocalDate()
    fun name(i: Int): String = if (range == UsageRange.DAY) {
        "${hourLabel(i)} to ${hourLabel(i + 1)}"
    } else {
        firstDay.plusDays(i.toLong()).format(DateTimeFormatter.ofPattern("EEEE d MMM"))
    }
    fun axis(i: Int): String? = if (range == UsageRange.DAY) {
        if (i % 6 == 0) hourLabel(i) else null
    } else {
        firstDay.plusDays(i.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }
    val top = SCALE_STEPS.firstOrNull { it >= (bars.maxOrNull() ?: 0L) } ?: (bars.maxOrNull() ?: MINUTE)
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = KeelColor.TextSecondary, letterSpacing = 0.sp)
    val accent = KeelColor.Accent
    val grid = KeelColor.TextSecondary.copy(alpha = 0.22f)
    val summary = bars.indices.joinToString(". ") { "${name(it)}: ${durationLabel(bars[it])}" }

    Row(Modifier.heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        val index = selected
        if (index != null) {
            Text(durationLabel(bars[index]), style = MaterialTheme.typography.labelLarge.tabular, color = KeelColor.Text)
            Text("  ${name(index)}", style = MaterialTheme.typography.bodySmall, color = KeelColor.TextSecondary)
        } else {
            Text("Tap a bar for details", style = MaterialTheme.typography.bodySmall, color = KeelColor.TextSecondary)
        }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .pointerInput(bars) {
                detectTapGestures { tap ->
                    val i = (tap.x / (size.width.toFloat() / bars.size)).toInt().coerceIn(0, bars.lastIndex)
                    selected = if (selected == i) null else i
                }
            }
            .semantics { contentDescription = "Screen time chart. $summary" },
    ) {
        val labelHeight = 18.dp.toPx()
        val topRoom = 16.dp.toPx()
        val plot = size.height - labelHeight - topRoom
        val base = topRoom + plot
        val slot = size.width / bars.size
        val barWidth = minOf(24.dp.toPx(), slot * if (bars.size <= 7) 0.5f else 0.64f)
        val radius = minOf(4.dp.toPx(), barWidth / 2)
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))

        drawLine(grid, Offset(0f, topRoom), Offset(size.width, topRoom), 1.dp.toPx(), pathEffect = dash)
        val scaleText = measurer.measure(durationLabel(top), axisStyle)
        drawText(scaleText, topLeft = Offset(size.width - scaleText.size.width, topRoom - scaleText.size.height - 2.dp.toPx()))
        drawLine(grid, Offset(0f, base), Offset(size.width, base), 1.dp.toPx())

        bars.forEachIndexed { i, value ->
            if (value > 0) {
                val height = maxOf(plot * value / top, 2.dp.toPx())
                val left = slot * i + (slot - barWidth) / 2
                // Only a tapped bar pulls focus; the rest stay full strength so the shape of the day reads.
                val emphasis = if (selected != null && selected != i) 0.35f else 1f
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left, base - height, left + barWidth, base,
                            topLeftCornerRadius = CornerRadius(radius),
                            topRightCornerRadius = CornerRadius(radius),
                        ),
                    )
                }
                drawPath(path, accent.copy(alpha = emphasis))
            }
            axis(i)?.let { label ->
                val text = measurer.measure(label, axisStyle)
                val x = (slot * i + slot / 2 - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                drawText(text, topLeft = Offset(x, base + 4.dp.toPx()))
            }
        }
    }
}

@Composable
private fun AppUsageRow(app: AppRow, top: Long, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(source)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(interactionSource = source, indication = ripple(), role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(36.dp).clip(MaterialTheme.shapes.small))
        } else {
            Box(Modifier.size(36.dp).clip(MaterialTheme.shapes.small).background(KeelColor.Elevated))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(durationLabel(app.millis), style = MaterialTheme.typography.bodyMedium.tabular, color = KeelColor.Text)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(app.millis.toFloat() / top)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(KeelColor.Accent.copy(alpha = 0.75f)),
                    )
                }
                val change = app.millis - app.previous
                if (app.previous > 0 && kotlin.math.abs(change) >= MINUTE) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${if (change > 0) "+" else "−"}${durationLabel(kotlin.math.abs(change))}",
                        style = MaterialTheme.typography.bodySmall.tabular,
                        color = KeelColor.TextSecondary,
                    )
                }
            }
        }
    }
}

/** One app's own screen time, with a quick way to add a rule for it. */
@Composable
fun AppUsagePage(model: AppModel, pkg: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val label = remember(pkg) { appLabel(context, pkg) }
    val rule by loadState(model) { rules().firstOrNull { it.target == pkg } }
    val loadedRule by loadState(model) { rules().any { it.target == pkg } }
    Page(label, onBack = onBack) {
        ScreenTimeSection(model, pkg)
        SectionTitle("Rule")
        Panel {
            val existing = rule
            if (existing != null) {
                Text(describe(existing.state.current))
                Muted("Change it on the Rules tab.")
            } else if (loadedRule == false) {
                Text("No rule yet.")
                Muted("Add one and Keel pauses $label for 10 seconds when you open it. You can change that on the Rules tab.")
                KeelSecondaryButton({ model.act { addRule(pkg, "app", label, 10) } }, Modifier.fillMaxWidth()) {
                    Text("Add a rule for $label")
                }
            }
        }
        Spacer(Modifier.padding(top = 4.dp))
    }
}
