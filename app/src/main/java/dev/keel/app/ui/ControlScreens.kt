package dev.keel.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.keel.app.data.Repo
import dev.keel.app.data.Rule
import dev.keel.app.data.WeekUsage
import dev.keel.app.pause.Permissions
import dev.keel.app.pause.SurfaceRules
import dev.keel.domain.EMERGENCY_DELAY_SECONDS
import dev.keel.domain.Horizon
import dev.keel.domain.NEW_RULE_GRACE_MILLIS
import dev.keel.domain.RuleMode
import dev.keel.domain.RuleSetting
import dev.keel.domain.WeekStats
import dev.keel.domain.daysDoneInLast30
import dev.keel.domain.endSprintEarly
import dev.keel.domain.nextWeekStart
import dev.keel.domain.startSprint
import dev.keel.domain.reviewWeekDue
import dev.keel.domain.weekStart
import dev.keel.domain.weekStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DELAYS = listOf(5, 10, 20, 30, 60)
private val LIMITS = listOf(5, 15, 30, 60, 120)
private val SPRINT_DAYS = listOf(1, 7, 14, 21)

@Composable
fun RulesScreen(model: AppModel) {
    val context = LocalContext.current
    val rules by loadState(model) { rules() }
    var adding by remember { mutableStateOf(false) }
    val monday = dayName(nextWeekStart(System.currentTimeMillis(), ZoneId.systemDefault()))
    Page("Rules", "Choose which apps Keel pauses, and how.") {
        val loaded = rules ?: return@Page
        HowChangesWork(monday)
        SprintPanel(model)
        loaded.forEach { RuleCard(model, it) }
        KeelSecondaryButton({ adding = true }, Modifier.fillMaxWidth()) { Text("Add apps") }
    }
    if (adding) {
        AddRulesDialog(
            existing = rules.orEmpty().map { it.target }.toSet(),
            onAdd = { apps, surfaces ->
                model.act {
                    apps.forEach { addRule(it.pkg, "app", it.label, 10) }
                    surfaces.forEach { addRule(it.id, "surface", it.label, 10) }
                }
                adding = false
            },
            onCancel = { adding = false },
            context = context,
        )
    }
}

/**
 * The commitment rule in plain words, with the real date, because "next week" left people looking for a
 * week setting that doesn't exist.
 */
@Composable
private fun HowChangesWork(monday: String) {
    var open by remember { mutableStateOf(false) }
    Panel(onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("How changes work", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelMedium, color = KeelColor.TextSecondary)
        }
        StatusLine("Stricter: works straight away.")
        StatusLine("Easier: starts on $monday.", tone = Tone.Neutral)
        if (open) {
            Muted("Weeks start on Monday. Making a rule easier waits until then, so a weak moment can't undo a decision you made with a clear head. There's no setting for this, on purpose.")
            Muted("Changed your mind? Tap \"Keep current setting\" on the rule and the waiting change is cancelled.")
            Muted("A rule you've just added can be changed freely for its first 10 minutes, while you set it up.")
        }
    }
}

private fun modeHelp(mode: RuleMode): String = when (mode) {
    RuleMode.PAUSE -> "Each time you open it, you wait a few seconds and see your next step first. You can still open it after."
    RuleMode.LIMIT -> "Use it freely for a set time each day. After that, every open gets a pause."
    RuleMode.TASK_LOCK -> "Stays locked until everything on Today is ticked off. In an emergency, you can still get in after a $EMERGENCY_DELAY_SECONDS-second wait."
}

internal fun describe(setting: RuleSetting): String = when {
    !setting.enabled -> "Off"
    setting.mode == RuleMode.LIMIT -> "Free for ${setting.dailyLimitMinutes} min a day, then a pause"
    setting.mode == RuleMode.TASK_LOCK -> "Locked until today's list is done"
    else -> "Pause for ${setting.delaySeconds} seconds"
}

@Composable
private fun RuleCard(model: AppModel, rule: Rule) {
    val current = rule.state.current
    val modes = if (rule.kind == "surface") RuleMode.entries.filter { it != RuleMode.LIMIT } else RuleMode.entries
    val later = rule.state.pending
    val animate = motionEnabled()
    // Changing mode swaps a row of choices, so the card eases to its new height.
    val resize = if (animate) Modifier.animateContentSize(tween(KeelMotion.PUSH_MS, easing = KeelMotion.EaseOut)) else Modifier
    Panel {
        Column(resize, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.label, style = MaterialTheme.typography.titleMedium)
                Muted(describe(current))
            }
            KeelSwitch(current.enabled, { on -> model.act { changeRule(rule, current.copy(enabled = on)) } })
        }
        ChipRow {
            modes.forEach { mode ->
                KeelChip(
                    selected = mode == current.mode,
                    pending = later?.mode == mode,
                    onClick = { model.act { changeRule(rule, current.copy(mode = mode)) } },
                    label = { Text(mode.label, maxLines = 1) },
                )
            }
        }
        Muted(modeHelp(current.mode))
        when (current.mode) {
            RuleMode.LIMIT -> {
                Text("Free minutes a day", style = MaterialTheme.typography.labelMedium, color = KeelColor.TextSecondary)
                ChipRow {
                    LIMITS.forEach { minutes ->
                        KeelChip(
                            selected = minutes == current.dailyLimitMinutes,
                            pending = later?.mode == RuleMode.LIMIT && later.dailyLimitMinutes == minutes,
                            onClick = { model.act { changeRule(rule, current.copy(dailyLimitMinutes = minutes)) } },
                            label = { Text("$minutes min", maxLines = 1) },
                        )
                    }
                }
            }
            RuleMode.PAUSE -> {
                Text("How long to wait", style = MaterialTheme.typography.labelMedium, color = KeelColor.TextSecondary)
                ChipRow {
                DELAYS.forEach { delay ->
                    KeelChip(
                        selected = delay == current.delaySeconds,
                        pending = later?.mode == RuleMode.PAUSE && later.delaySeconds == delay,
                        onClick = { model.act { changeRule(rule, current.copy(delaySeconds = delay)) } },
                        label = { Text("$delay sec", maxLines = 1) },
                    )
                }
                }
            }
            RuleMode.TASK_LOCK -> Unit
        }
        val age = System.currentTimeMillis() - rule.createdAt
        if (age < NEW_RULE_GRACE_MILLIS) {
            val left = ((NEW_RULE_GRACE_MILLIS - age) / 60_000 + 1).coerceAtMost(10)
            Muted("Just added, so every change works straight away for about $left more ${if (left == 1L) "minute" else "minutes"}.")
        }
        val pending = rule.state.pending
        val at = rule.state.pendingAt
        if (pending != null && at != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(KeelColor.Elevated.copy(alpha = 0.6f))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Easier setting starts ${dayName(at)}", style = MaterialTheme.typography.titleSmall)
                Muted("From then: ${describe(pending)}. Until then it stays as it is now.")
                KeelTextButton({ model.act { changeRule(rule, current) } }) { Text("Keep current setting") }
            }
        }
        }
    }
}

private fun dayName(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE d MMM"))

/** Stricter for a set time: every rule that's on pauses for the longest wait, and nothing is free. */
@Composable
private fun SprintPanel(model: AppModel) {
    val until by loadState(model) { sprintUntil() ?: 0L }
    var startDays by remember { mutableStateOf<Int?>(null) }
    var ending by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val active = (until ?: 0L) > now
    Panel {
        Text("Sprint", style = MaterialTheme.typography.titleMedium)
        if (active) {
            StatusLine("Running until ${dayName(until ?: now)}")
            Muted("Every rule that's on waits $EMERGENCY_DELAY_SECONDS seconds, including time limits and finished lists.")
            KeelSecondaryButton({ ending = true }, Modifier.fillMaxWidth()) { Text("End early") }
        } else {
            Muted("Make every rule strict for a few days, like before an exam. Each app on your list waits $EMERGENCY_DELAY_SECONDS seconds before it opens.")
        }
        ChipRow {
            SPRINT_DAYS.forEach { days ->
                KeelChip(selected = false, onClick = { startDays = days }, label = { Text(if (days == 1) "1 day" else "$days days", maxLines = 1) })
            }
        }
    }
    startDays?.let { days ->
        AlertDialog(
            onDismissRequest = { startDays = null },
            title = { Text("Start a Sprint for ${if (days == 1) "1 day" else "$days days"}?") },
            text = {
                val now2 = System.currentTimeMillis()
                val endsAt = startSprint(until, days, now2)
                val earliest = endSprintEarly(endsAt, now2, zone)
                Text(
                    if (earliest < endsAt) {
                        "It starts now and runs until ${dayName(endsAt)}. Stopping early takes effect on ${dayName(earliest)} at the soonest, so a weak moment can't end it."
                    } else {
                        "It starts now and runs until ${dayName(endsAt)}. It can't be stopped early, so a weak moment can't end it."
                    },
                )
            },
            confirmButton = {
                KeelTextButton({
                    startDays = null
                    model.act { setSprintUntil(startSprint(sprintUntil(), days, System.currentTimeMillis())) }
                }) { Text("Start") }
            },
            dismissButton = { KeelTextButton({ startDays = null }) { Text("Cancel") } },
        )
    }
    if (ending) {
        val endsAt = endSprintEarly(until ?: now, now, zone)
        AlertDialog(
            onDismissRequest = { ending = false },
            title = { Text("End this Sprint early?") },
            text = { Text("It keeps running until ${dayName(endsAt)}, then ends.") },
            confirmButton = {
                KeelTextButton({
                    ending = false
                    model.act { setSprintUntil(endsAt) }
                }) { Text("End then") }
            },
            dismissButton = { KeelTextButton({ ending = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun AddRulesDialog(
    existing: Set<String>,
    onAdd: (List<InstalledApp>, List<dev.keel.app.pause.SurfaceRule>) -> Unit,
    onCancel: () -> Unit,
    context: android.content.Context,
) {
    val apps = remember { installedApps(context).filter { it.pkg !in existing } }
    val surfaces = remember { SurfaceRules.load(context).filter { it.id !in existing } }
    var pickedApps by remember { mutableStateOf(setOf<String>()) }
    var pickedSurfaces by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Choose what to pause") },
        text = {
            AppPicker(apps, surfaces, pickedApps, { pickedApps = it }, pickedSurfaces, { pickedSurfaces = it })
        },
        confirmButton = {
            KeelTextButton({
                onAdd(apps.filter { it.pkg in pickedApps }, surfaces.filter { it.id in pickedSurfaces })
            }) { Text("Add") }
        },
        dismissButton = { KeelTextButton(onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun AppPicker(
    apps: List<InstalledApp>,
    surfaces: List<dev.keel.app.pause.SurfaceRule>,
    pickedApps: Set<String>,
    onApps: (Set<String>) -> Unit,
    pickedSurfaces: Set<String>,
    onSurfaces: (Set<String>) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
        items(surfaces.size) { i ->
            val surface = surfaces[i]
            PickRow("${surface.label} only", surface.id in pickedSurfaces) { on ->
                onSurfaces(if (on) pickedSurfaces + surface.id else pickedSurfaces - surface.id)
            }
        }
        items(apps.size) { i ->
            val app = apps[i]
            PickRow(app.label, app.pkg in pickedApps) { on ->
                onApps(if (on) pickedApps + app.pkg else pickedApps - app.pkg)
            }
        }
    }
}

@Composable
private fun PickRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row responds, not just the small checkbox.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeelCheck(checked, null)
        Text(label)
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.bodyMedium.tabular, color = KeelColor.TextSecondary)
    }
}

private data class ReviewData(
    val week: LocalDate,
    val due: Boolean,
    val stats: WeekStats,
    val usage: List<WeekUsage>,
    val stepsDone: Int,
    val tasksDone: Int,
    val daysDone: Int,
    val labels: Map<String, String>,
)

private fun Repo.loadReview(): ReviewData {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val firstWeek = setting("first_day")?.let { weekStart(LocalDate.parse(it)) }
    val due = reviewWeekDue(today, reviewedWeeks(), firstWeek)
    val week = due ?: weekStart(today)
    val from = week.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = week.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return ReviewData(
        week = week,
        due = due != null,
        stats = weekStats(intercepts(from, to)),
        usage = usage(from, to),
        stepsDone = stepsDone(from, to),
        tasksDone = tasksDone(from, to),
        daysDone = daysDoneInLast30(doneDays(), today),
        labels = rules().associate { it.target to it.label },
    )
}

@Composable
fun ReviewScreen(model: AppModel) {
    val context = LocalContext.current
    val data by loadState(model) { loadReview() }
    var ruleChange by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var openApp by remember { mutableStateOf<String?>(null) }
    openApp?.let { pkg ->
        BackHandler { openApp = null }
        AppUsagePage(model, pkg) { openApp = null }
        return
    }
    Page("Review") {
        SectionTitle("Screen time")
        ScreenTimeSection(model) { openApp = it }
        val loaded = data ?: return@Page
        val first = loaded.week.format(DateTimeFormatter.ofPattern("d MMM"))
        val last = loaded.week.plusDays(6).format(DateTimeFormatter.ofPattern("d MMM"))
        SectionTitle("Pauses, $first to $last")
        Panel {
            val percent = loaded.stats.abandonedPercent
            if (percent == null) {
                Text("No pauses yet", style = MaterialTheme.typography.headlineSmall)
                Muted("Opens you change your mind about will show here.")
            } else {
                Stat("$percent%", "of opens abandoned")
                KeelProgress(percent / 100f)
                Muted("${loaded.stats.abandoned} changed your mind, ${loaded.stats.opened} opened anyway")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KeelSpace.Item)) {
            Column(Modifier.weight(1f)) {
                Panel { Stat("${loaded.stepsDone + loaded.tasksDone}", "done this week") }
            }
            Column(Modifier.weight(1f)) {
                Panel { Stat("${loaded.daysDone}/30", "days you finished something") }
            }
        }
        if (loaded.stats.byTarget.isNotEmpty()) {
            SectionTitle("Pauses by app")
            Panel {
                loaded.stats.byTarget.entries.sortedByDescending { it.value }.forEach {
                    ValueRow(loaded.labels[it.key] ?: it.key, "${it.value}")
                }
            }
        }
        if (loaded.stats.reasons.isNotEmpty()) {
            SectionTitle("Why you opened anyway")
            Panel {
                loaded.stats.reasons.entries.sortedByDescending { it.value }.forEach { ValueRow(it.key, "${it.value}") }
            }
        }
        CheckInPanel(model, weekStart(LocalDate.now()))
        TimeBudgetsSection(model, loaded.week)
        ExperimentSection(model)
        if (loaded.due) {
            SectionTitle("One rule to change")
            KeelTextField(ruleChange, { ruleChange = it }, label = { Text("What will you change, if anything?") }, modifier = Modifier.fillMaxWidth())
            KeelTextField(note, { note = it }, label = { Text("Anything else to remember?") }, modifier = Modifier.fillMaxWidth())
            KeelButton(
                onClick = {
                    model.act { saveReview(loaded.week, ruleChange.trim(), note.trim()) }
                    ruleChange = ""
                    note = ""
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Finish review") }
        } else {
            Muted("Your weekly review opens on Sunday, to look back at the week and pick one thing to change.")
        }
    }
}

@Composable
fun DataScreen(model: AppModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val problems by loadState(model) { Permissions.problems(context) }
    val warnings by loadState(model) { SurfaceRules.versionWarnings(context) }
    var message by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                message = runCatching {
                    val json = model.repo.exportJson()
                    context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(json) }
                    "Exported ${json.length / 1024} KB."
                }.getOrElse { "Export failed: ${it.message}" }
            }
        }
    }
    Page("Your data", "Encrypted, and only on this phone.") {
        PrivacyPanel(model)
        SectionTitle("Is Keel working?")
        val found = problems.orEmpty() + warnings.orEmpty()
        if (found.isEmpty()) {
            Panel { StatusLine("Yes. Every check passed") }
        } else {
            Panel { found.forEach { problem -> StatusLine(problem, tone = Tone.Danger) } }
        }
        SectionTitle("Appearance")
        Panel {
            ChipRow {
                ThemeMode.entries.forEach { mode ->
                    KeelChip(selected = mode == model.theme, onClick = { model.theme = mode }, label = { Text(mode.label, maxLines = 1) })
                }
            }
            Muted("System follows your phone's light or dark setting.")
        }
        SectionTitle("Permissions")
        PermissionList(model)
        SectionTitle("Backup")
        BackupSection(model)
        RestoreSection(model, replacesData = true)
        SectionTitle("Export")
        Panel {
            Text("Save everything as one readable JSON file, to use elsewhere.")
            StatusLine("This file isn't encrypted", tone = Tone.Danger)
            Muted("Keep it somewhere safe. An export can't be restored, so use Backup to protect your data.")
            KeelSecondaryButton({ export.launch("keel-export-${LocalDate.now()}.json") }, Modifier.fillMaxWidth()) { Text("Export") }
            message?.let { Muted(it) }
        }
        SectionTitle("Delete")
        DeleteEverythingSection(model)
    }
}

/**
 * Every permission, why it's needed, and its state. Android doesn't let an app turn these off itself,
 * so Manage explains what will stop working and then opens the right Settings screen.
 */
@Composable
fun PermissionList(model: AppModel) {
    val context = LocalContext.current
    val tick = model.tick
    val open = { action: String, withPackage: Boolean ->
        val intent = Intent(action)
        if (withPackage) intent.data = Uri.parse("package:${context.packageName}")
        context.startActivity(intent)
    }
    // Before Android 13, notifications don't need permission.
    val notifications = remember(tick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
    PermissionRow(
        "Display over other apps",
        "Draws the pause screen.",
        remember(tick) { Permissions.overlay(context) },
        "Keel can't show the pause screen, so no app will be paused.",
    ) { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }
    PermissionRow(
        "Usage access",
        "Confirms which app is open, and records time per app.",
        remember(tick) { Permissions.usage(context) },
        "Keel stops recording time per app, and whole-app pauses become less reliable or stop.",
    ) { open(Settings.ACTION_USAGE_ACCESS_SETTINGS, false) }
    PermissionRow(
        "Accessibility",
        "Spots app launches and Shorts. Reads package names and view IDs only, never screen text.",
        remember(tick) { Permissions.accessibility(context) },
        "Shorts-only blocking stops. Whole-app pauses keep working in a slower fallback mode once you reopen Keel.",
    ) { open(Settings.ACTION_ACCESSIBILITY_SETTINGS, false) }
    PermissionRow(
        "Notifications",
        "Warns you if blocking stops working.",
        notifications,
        "Keel can't warn you when blocking stops working.",
        onManage = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        },
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    why: String,
    granted: Boolean,
    ifTurnedOff: String,
    onManage: (() -> Unit)? = null,
    onOpen: () -> Unit,
) {
    var warning by remember { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Muted(why)
                if (granted) StatusLine("On")
            }
            if (granted) {
                KeelTextButton({ warning = true }) { Text("Manage") }
            } else {
                KeelTextButton(onOpen) { Text("Turn on") }
            }
        }
    }
    if (warning) {
        AlertDialog(
            onDismissRequest = { warning = false },
            title = { Text("Turn off $title?") },
            text = { Text("$ifTurnedOff\n\nYour data stays safe. Android makes you turn this off in Settings, so Keel will take you there.") },
            confirmButton = {
                KeelTextButton({
                    warning = false
                    (onManage ?: onOpen)()
                }) { Text("Open Settings") }
            },
            dismissButton = { KeelTextButton({ warning = false }) { Text("Keep it on") } },
        )
    }
}

/** Four segments, so setup always shows how far along you are. */
@Composable
private fun StepDots(current: Int) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(4) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (index <= current) KeelColor.Accent else KeelColor.Elevated),
            )
        }
    }
}

@Composable
fun OnboardingScreen(model: AppModel) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    val apps = remember { installedApps(context) }
    val surfaces = remember { SurfaceRules.load(context) }
    var pickedApps by remember { mutableStateOf(setOf<String>()) }
    var pickedSurfaces by remember { mutableStateOf(setOf<String>()) }
    var title by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf("") }
    var obstacle by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf("") }
    var nextStep by remember { mutableStateOf("") }
    var horizon by remember { mutableStateOf(Horizon.YEAR) }
    when (step) {
        0 -> Page("Keel", "A pause that knows what you're trying to do.") {
            StepDots(0)
            Panel {
                Text("When you open an app you chose, Keel pauses it and shows your next step. You can always carry on.")
            }
            PrivacyPanel(model)
            KeelButton({ step = 1 }, Modifier.fillMaxWidth()) { Text("Set up") }
            RestoreSection(model, replacesData = false)
        }
        1 -> Page("Permissions", "Each one has a single job. You can turn any of them off later.") {
            StepDots(1)
            PermissionList(model)
            Muted("Return here after each one.")
            KeelButton({ step = 2 }, Modifier.fillMaxWidth()) { Text("Next") }
        }
        2 -> Page("What pulls you in?", "Pick about 3. You can change this later.") {
            StepDots(2)
            Panel {
                Column(Modifier.fillMaxWidth()) {
                    surfaces.forEach { surface ->
                        PickRow("${surface.label} only", surface.id in pickedSurfaces) { on ->
                            pickedSurfaces = if (on) pickedSurfaces + surface.id else pickedSurfaces - surface.id
                        }
                    }
                    apps.forEach { app ->
                        PickRow(app.label, app.pkg in pickedApps) { on ->
                            pickedApps = if (on) pickedApps + app.pkg else pickedApps - app.pkg
                        }
                    }
                }
            }
            KeelButton({ step = 3 }, Modifier.fillMaxWidth()) { Text("Next") }
        }
        else -> Page("One goal", "This is what the pause screen will point you towards.") {
            StepDots(3)
            WoopFields(title, { title = it }, outcome, { outcome = it }, obstacle, { obstacle = it }, plan, { plan = it })
            HorizonChoice(horizon) { horizon = it }
            KeelTextField(nextStep, { nextStep = it }, label = { Text("Smallest next step") }, modifier = Modifier.fillMaxWidth())
            KeelButton(
                enabled = title.isNotBlank() && nextStep.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val chosenApps = apps.filter { it.pkg in pickedApps }
                    val chosenSurfaces = surfaces.filter { it.id in pickedSurfaces }
                    model.act {
                        // The first goal skips cooling off, so the pause screen has a step from day one.
                        val goalId = addGoal(title.trim(), horizon, title.trim(), outcome.trim(), obstacle.trim(), plan.trim(), skipCooling = true)
                        addStep(goalId, nextStep.trim(), null, null, "Written during setup")
                        chosenApps.forEach { addRule(it.pkg, "app", it.label, 10) }
                        chosenSurfaces.forEach { addRule(it.id, "surface", it.label, 10) }
                        setSetting("first_day", LocalDate.now().toString())
                        setSetting("onboarded", "true")
                    }
                },
            ) { Text("Finish") }
        }
    }
}
