package dev.keel.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.keel.domain.Horizon
import dev.keel.domain.RuleSetting
import dev.keel.domain.WeekStats
import dev.keel.domain.daysDoneInLast30
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

@Composable
fun RulesScreen(model: AppModel) {
    val context = LocalContext.current
    val rules by loadState(model) { rules() }
    var adding by remember { mutableStateOf(false) }
    Page("Rules", "Tightening applies now. Loosening waits until next Monday.") {
        val loaded = rules ?: return@Page
        loaded.forEach { RuleCard(model, it) }
        OutlinedButton({ adding = true }, Modifier.fillMaxWidth()) { Text("Add apps") }
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

@Composable
private fun RuleCard(model: AppModel, rule: Rule) {
    val current = rule.state.current
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.label, style = MaterialTheme.typography.titleMedium)
                Muted(if (current.enabled) "Pause for ${current.delaySeconds} seconds" else "Off")
            }
            Switch(current.enabled, { on -> model.act { changeRule(rule, current.copy(enabled = on)) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DELAYS.forEach { delay ->
                FilterChip(
                    selected = delay == current.delaySeconds,
                    onClick = { model.act { changeRule(rule, current.copy(delaySeconds = delay)) } },
                    label = { Text("${delay}s", maxLines = 1) },
                )
            }
        }
        val pending = rule.state.pending
        val at = rule.state.pendingAt
        if (pending != null && at != null) {
            val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE d MMM"))
            val what = if (pending.enabled) "Pause drops to ${pending.delaySeconds} seconds" else "This rule turns off"
            Text("$what on $day.", color = MaterialTheme.colorScheme.primary)
        }
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
            TextButton({
                onAdd(apps.filter { it.pkg in pickedApps }, surfaces.filter { it.id in pickedSurfaces })
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
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
        Checkbox(checked, null, Modifier.padding(12.dp))
        Text(label)
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
    Page("Review") {
        val loaded = data ?: return@Page
        val title = loaded.week.format(DateTimeFormatter.ofPattern("d MMM"))
        Muted("Week starting $title")
        Panel {
            val percent = loaded.stats.abandonedPercent
            Text(
                if (percent == null) "No pauses yet" else "$percent% of opens abandoned",
                style = MaterialTheme.typography.headlineSmall,
            )
            Muted("${loaded.stats.abandoned} changed your mind, ${loaded.stats.opened} opened anyway.")
        }
        Panel {
            Text("Done: ${loaded.stepsDone} plan ${if (loaded.stepsDone == 1) "step" else "steps"}, ${loaded.tasksDone} ${if (loaded.tasksDone == 1) "task" else "tasks"}")
            Muted("You finished something on ${loaded.daysDone} of the last 30 days.")
        }
        if (loaded.stats.byTarget.isNotEmpty()) {
            SectionTitle("Pauses by app")
            loaded.stats.byTarget.entries.sortedByDescending { it.value }.forEach {
                Muted("${loaded.labels[it.key] ?: it.key}: ${it.value}")
            }
        }
        if (loaded.stats.reasons.isNotEmpty()) {
            SectionTitle("Why you opened anyway")
            loaded.stats.reasons.entries.sortedByDescending { it.value }.forEach { Muted("${it.key}: ${it.value}") }
        }
        if (loaded.usage.isNotEmpty()) {
            SectionTitle("Most-used apps")
            val home = homePackage(context)
            loaded.usage.filter { it.pkg != home && it.pkg != context.packageName }.take(5)
                .forEach { Muted("${appLabel(context, it.pkg)}: ${it.minutes} min") }
        }
        if (loaded.due) {
            SectionTitle("One rule to change")
            OutlinedTextField(ruleChange, { ruleChange = it }, label = { Text("What will you change, if anything?") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("Anything else to remember?") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    model.act { saveReview(loaded.week, ruleChange.trim(), note.trim()) }
                    ruleChange = ""
                    note = ""
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Finish review") }
        } else {
            Muted("The next review opens on Sunday.")
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
    Page("Your data") {
        PrivacyPanel(model)
        SectionTitle("Is Keel working?")
        val found = problems.orEmpty() + warnings.orEmpty()
        if (found.isEmpty()) {
            Panel { Text("Yes. Every check passed.") }
        } else {
            found.forEach { problem -> Panel { Text(problem, color = MaterialTheme.colorScheme.error) } }
        }
        SectionTitle("Permissions")
        PermissionList(model)
        SectionTitle("Backup")
        BackupSection(model)
        RestoreSection(model, replacesData = true)
        SectionTitle("Export")
        Panel {
            Text("Save everything as one readable JSON file, to use elsewhere.")
            Text(
                "This file isn't encrypted, so keep it somewhere safe. Use Backup above to protect your data, " +
                    "because an export can't be restored.",
                color = MaterialTheme.colorScheme.error,
            )
            Button({ export.launch("keel-export-${LocalDate.now()}.json") }, Modifier.fillMaxWidth()) { Text("Export") }
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
    val notifications = remember(tick) {
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
    ) { notify.launch(Manifest.permission.POST_NOTIFICATIONS) }
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
                if (granted) Text("On", color = MaterialTheme.colorScheme.primary)
            }
            if (granted) {
                TextButton({ warning = true }) { Text("Manage") }
            } else {
                TextButton(onOpen) { Text("Turn on") }
            }
        }
    }
    if (warning) {
        AlertDialog(
            onDismissRequest = { warning = false },
            title = { Text("Turn off $title?") },
            text = { Text("$ifTurnedOff\n\nYour data stays safe. Android makes you turn this off in Settings, so Keel will take you there.") },
            confirmButton = {
                TextButton({
                    warning = false
                    (onManage ?: onOpen)()
                }) { Text("Open Settings") }
            },
            dismissButton = { TextButton({ warning = false }) { Text("Keep it on") } },
        )
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
            Panel {
                Text("When you open an app you chose, Keel pauses it and shows your next step. You can always carry on.")
            }
            PrivacyPanel(model)
            Button({ step = 1 }, Modifier.fillMaxWidth()) { Text("Set up") }
            RestoreSection(model, replacesData = false)
        }
        1 -> Page("Permissions", "Each one has a single job. You can turn any of them off later.") {
            PermissionList(model)
            Muted("Return here after each one.")
            Button({ step = 2 }, Modifier.fillMaxWidth()) { Text("Next") }
        }
        2 -> Page("What pulls you in?", "Pick about 3. You can change this later.") {
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
            Button({ step = 3 }, Modifier.fillMaxWidth()) { Text("Next") }
        }
        else -> Page("One goal", "This is what the pause screen will point you towards.") {
            WoopFields(title, { title = it }, outcome, { outcome = it }, obstacle, { obstacle = it }, plan, { plan = it })
            HorizonChoice(horizon) { horizon = it }
            OutlinedTextField(nextStep, { nextStep = it }, label = { Text("Smallest next step") }, modifier = Modifier.fillMaxWidth())
            Button(
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
