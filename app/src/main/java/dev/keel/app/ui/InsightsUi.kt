package dev.keel.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.keel.domain.EXPERIMENT_MIN_WEEKS_EACH
import dev.keel.domain.EnvelopeState
import dev.keel.domain.ExperimentReport
import dev.keel.domain.GoalStatus
import dev.keel.domain.PlanPick
import dev.keel.domain.PlanStep
import dev.keel.domain.TimeEnvelope
import dev.keel.domain.WeeklyCheck
import dev.keel.domain.checkInSummary
import dev.keel.domain.deadlineLabel
import dev.keel.domain.upcomingDeadlines
import dev.keel.domain.envelopeStates
import dev.keel.domain.exchangeOwed
import dev.keel.domain.experimentReport
import dev.keel.domain.experimentWeekIsOff
import dev.keel.domain.nextPlanSteps
import dev.keel.domain.nextWeekStart
import dev.keel.domain.unassignedMinutes
import dev.keel.domain.weekStart
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DEADLINES_SHOWN = 5

/** Dated steps from every open plan, overdue or within 30 days. Rose only ever means overdue. */
@Composable
fun DeadlinesSection(model: AppModel, today: LocalDate) {
    val deadlines by loadState(model) { upcomingDeadlines(openPlanTitles(), steps(), openTaskStepIds(), today) }
    val list = deadlines.orEmpty()
    if (list.isEmpty()) return
    SectionTitle("Due soon")
    Panel {
        list.take(DEADLINES_SHOWN).forEach { deadline ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(deadline.step.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${deadlineLabel(deadline.daysLeft)}, ${deadline.due.format(DateTimeFormatter.ofPattern("d MMM"))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (deadline.daysLeft < 0) KeelColor.Danger else KeelColor.TextSecondary,
                    )
                    Text(deadline.planTitle, style = MaterialTheme.typography.bodySmall, color = KeelColor.TextSecondary)
                }
                KeelTextButton({
                    model.act {
                        addTask(deadline.step.title, today, deadline.step.id, "Step of \"${deadline.planTitle}\"", deadline.step.estimateMinutes)
                        touchGoal(deadline.step.goalId)
                    }
                }) { Text("Add") }
            }
        }
        val more = list.size - DEADLINES_SHOWN
        if (more > 0) Muted("And $more more within 30 days.")
    }
}

/** The next few steps from Active plans, one tap from today's list, and a way to browse the rest. */
@Composable
fun PlanPicksSection(model: AppModel, today: LocalDate) {
    val picks by loadState(model) {
        // Anything already under "Due soon" isn't repeated here.
        val onList = openTaskStepIds()
        val dated = upcomingDeadlines(openPlanTitles(), steps(), onList, today).map { it.step.id }
        nextPlanSteps(ranked(), steps(), onList + dated)
    }
    var browsing by remember { mutableStateOf(false) }
    val list = picks ?: return
    SectionTitle("From your plans")
    if (list.isEmpty()) Muted("Nothing waiting. Switch a plan to Active under Plans and money, or browse them all.")
    if (list.isNotEmpty()) Panel { list.forEach { pick -> PickRow(model, pick, today) } }
    KeelSecondaryButton({ browsing = true }, Modifier.fillMaxWidth()) { Text("Browse all plans") }
    if (browsing) BrowsePlansDialog(model, today) { browsing = false }
}

@Composable
private fun PickRow(model: AppModel, pick: PlanPick, today: LocalDate) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(pick.step.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(pick.planTitle, style = MaterialTheme.typography.bodySmall, color = KeelColor.TextSecondary)
        }
        KeelTextButton({
            model.act {
                addTask(pick.step.title, today, pick.step.id, "Step of \"${pick.planTitle}\"", pick.step.estimateMinutes)
                touchGoal(pick.step.goalId)
            }
        }) { Text("Add") }
    }
}

private data class BrowsablePlan(val id: Long, val title: String, val steps: List<PlanStep>)

@Composable
private fun BrowsePlansDialog(model: AppModel, today: LocalDate, onClose: () -> Unit) {
    val plans by loadState(model) {
        val all = steps().filter { it.doneAt == null }.groupBy { it.goalId }
        val open = openTaskStepIds()
        goals().filter { it.status == GoalStatus.ACTIVE || it.status == GoalStatus.LIBRARY }
            .mapNotNull { goal ->
                val steps = all[goal.id].orEmpty().filter { it.id !in open }
                if (steps.isEmpty()) null else BrowsablePlan(goal.id, goal.title, steps)
            }
    }
    var selected by remember { mutableStateOf<Long?>(null) }
    val chosen = plans.orEmpty().firstOrNull { it.id == selected }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(chosen?.title ?: "Add from a plan", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (chosen == null) {
                    if (plans.orEmpty().isEmpty()) item { Muted("No unfinished steps in any plan.") }
                    items(plans.orEmpty(), key = { it.id }) { plan ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selected = plan.id }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(plan.title)
                            Muted(if (plan.steps.size == 1) "1 step left" else "${plan.steps.size} steps left")
                        }
                    }
                } else {
                    items(chosen.steps, key = { it.id }) { step ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(step.title, Modifier.weight(1f), maxLines = 4, overflow = TextOverflow.Ellipsis)
                            KeelTextButton({
                                model.act {
                                    addTask(step.title, today, step.id, "Step of \"${chosen.title}\"", step.estimateMinutes)
                                    touchGoal(step.goalId)
                                }
                            }) { Text("Add") }
                        }
                    }
                }
            }
        },
        confirmButton = { KeelTextButton(onClose) { Text("Done") } },
        dismissButton = {
            if (chosen != null) KeelTextButton({ selected = null }) { Text("All plans") }
        },
    )
}

private fun Double.oneDecimal() = String.format(java.util.Locale.getDefault(), "%.1f", this)

/** Sleep, movement and mood, typed by hand each week. No health data is read from anywhere. */
@Composable
fun CheckInPanel(model: AppModel, week: LocalDate) {
    val data by loadState(model) { weeklyChecks() }
    val saved = data.orEmpty().firstOrNull { it.week == week }
    var sleep by remember(week, saved) { mutableStateOf(saved?.sleep ?: 0) }
    var exercise by remember(week, saved) { mutableStateOf(saved?.exercise ?: 0) }
    var mood by remember(week, saved) { mutableStateOf(saved?.mood ?: 0) }
    var note by remember(week, saved) { mutableStateOf(saved?.note.orEmpty()) }
    SectionTitle("Weekly check-in")
    Panel {
        Muted("From 1 (poor) to 5 (great). Just for you to spot patterns, and not medical advice.")
        ScoreRow("Sleep", sleep) { sleep = it }
        ScoreRow("Movement", exercise) { exercise = it }
        ScoreRow("Mood", mood) { mood = it }
        KeelTextField(note, { note = it }, label = { Text("One thing you're stuck on (optional)") }, modifier = Modifier.fillMaxWidth())
        KeelButton(
            {
                val check = WeeklyCheck(week, sleep, exercise, mood, note.trim())
                model.act { saveWeeklyCheck(check) }
            },
            Modifier.fillMaxWidth(),
            enabled = sleep > 0 && exercise > 0 && mood > 0,
        ) { Text(if (saved == null) "Save check-in" else "Update check-in") }
        checkInSummary(data.orEmpty())?.let { summary ->
            val label = if (summary.weeks == 1) "This 1 week" else "Last ${summary.weeks} weeks"
            Muted("$label: sleep ${summary.sleep.oneDecimal()}, movement ${summary.exercise.oneDecimal()}, mood ${summary.mood.oneDecimal()}")
        }
    }
}

@Composable
private fun ScoreRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Text(label)
    ChipRow {
        (1..5).forEach { score ->
            KeelChip(selected = score == value, onClick = { onChange(score) }, label = { Text("$score") })
        }
    }
}

private fun hours(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h $m min"
    }
}

private data class EnvelopeData(val states: List<EnvelopeState>, val unassigned: Int, val paid: Set<Long>)

/** Waking hours split into budgets. Apps you attach are measured from your own usage records. */
@Composable
fun TimeBudgetsSection(model: AppModel, week: LocalDate) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val from = week.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = week.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val data by loadState(model) {
        val envelopes = timeEnvelopes()
        val used = usage(from, to).associate { it.pkg to it.minutes }
        EnvelopeData(envelopeStates(envelopes, used), unassignedMinutes(envelopes), exchangeRecorded(week))
    }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TimeEnvelope?>(null) }
    SectionTitle("Time budgets")
    Muted("Give your waking hours a job. Attach apps and Keel measures them. Going over means taking time from something else.")
    val loaded = data ?: return
    loaded.states.forEach { state ->
        val envelope = state.envelope
        Panel {
            Column(Modifier.fillMaxWidth().clickable { deleting = envelope }) {
                Text(envelope.name, style = MaterialTheme.typography.titleMedium)
                state.usedMinutes?.let { used ->
                    KeelProgress(used.toFloat() / envelope.minutesPerWeek, Modifier.padding(vertical = 8.dp), danger = state.overMinutes > 0)
                }
                val budget = hours(envelope.minutesPerWeek.toLong())
                Text(
                    when (val used = state.usedMinutes) {
                        null -> "$budget a week, planned"
                        else -> "${hours(used)} of $budget this week"
                    },
                )
                if (state.overMinutes > 0) {
                    Text("Over by ${hours(state.overMinutes)}", color = MaterialTheme.colorScheme.error)
                }
            }
            val owed = exchangeOwed(state)
            if (owed > 0) {
                val destination = envelope.payTo?.ifBlank { null } ?: "savings"
                val already = envelope.id in loaded.paid
                Muted("Your rule says move ${money(owed)} to $destination. You chose this rule, and Keel doesn't move money.")
                if (already) {
                    Muted("Added to Today.")
                } else {
                    KeelSecondaryButton(
                        {
                            model.act {
                                addTask("Move ${money(owed)} to $destination", LocalDate.now(), source = "Your ${envelope.name} time rule")
                                recordExchange(week, envelope.id)
                            }
                        },
                        Modifier.fillMaxWidth(),
                    ) { Text("Add to Today") }
                }
            }
        }
    }
    val free = loaded.unassigned
    if (loaded.states.isNotEmpty()) {
        Muted(if (free >= 0) "${hours(free.toLong())} of a 112-hour waking week have no job yet." else "That's ${hours((-free).toLong())} more than a 112-hour waking week holds.")
    }
    KeelSecondaryButton({ adding = true }, Modifier.fillMaxWidth()) { Text("Add a time budget") }
    if (adding) AddEnvelopeDialog(model, context) { adding = false }
    deleting?.let { envelope ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${envelope.name}\"?") },
            text = { Text("This removes the budget and its rule. Your usage records stay.") },
            confirmButton = {
                KeelTextButton({
                    model.act { deleteTimeEnvelope(envelope.id) }
                    deleting = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { KeelTextButton({ deleting = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun AddEnvelopeDialog(model: AppModel, context: android.content.Context, onClose: () -> Unit) {
    val apps = remember { installedApps(context) }
    var name by remember { mutableStateOf("") }
    var hoursText by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var perMinutes by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var payTo by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add a time budget") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    KeelTextField(name, { name = it }, label = { Text("Name, like Social or Study") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    KeelTextField(
                        hoursText,
                        { hoursText = it.filter(Char::isDigit).take(3) },
                        label = { Text("Hours a week") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item { Muted("Optional: if you go over, a rule you set for yourself.") }
                item {
                    KeelTextField(
                        perMinutes,
                        { perMinutes = it.filter(Char::isDigit).take(4) },
                        label = { Text("For every this many minutes over") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    KeelTextField(
                        amount,
                        { amount = it.filter(Char::isDigit).take(9) },
                        label = { Text("I'll move this amount") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    KeelTextField(payTo, { payTo = it }, label = { Text("To, like savings") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item { Muted("Optional: apps to measure against this budget.") }
                items(apps, key = { it.pkg }) { app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { picked = if (app.pkg in picked) picked - app.pkg else picked + app.pkg },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KeelCheck(app.pkg in picked, null)
                        Text(app.label)
                    }
                }
            }
        },
        confirmButton = {
            KeelTextButton(
                enabled = name.isNotBlank() && (hoursText.toIntOrNull() ?: 0) > 0,
                onClick = {
                    val envelopeName = name.trim()
                    val minutes = hoursText.toInt() * 60
                    val apps2 = picked.toList()
                    val per = perMinutes.toIntOrNull()?.takeIf { it > 0 }
                    val value = amount.toLongOrNull()?.takeIf { it > 0 }
                    val to = payTo.trim().ifEmpty { null }
                    model.act { addTimeEnvelope(envelopeName, minutes, apps2, per, value, to) }
                    onClose()
                },
            ) { Text("Add") }
        },
        dismissButton = { KeelTextButton(onClose) { Text("Cancel") } },
    )
}

private data class ExperimentData(val start: LocalDate?, val report: ExperimentReport?)

/** Tests whether the pause actually helps you, and is allowed to say that it doesn't. */
@Composable
fun ExperimentSection(model: AppModel) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val data by loadState(model) {
        val start = experimentStart()
        ExperimentData(start, start?.let { experimentReport(it, experimentWeeks(it, today)) })
    }
    var confirming by remember { mutableStateOf(false) }
    SectionTitle("Does the pause help you?")
    val loaded = data ?: return
    Panel {
        val start = loaded.start
        if (start == null) {
            Muted("Run a personal test: pause rules go on one week and off the next, starting next Monday, then Keel compares your time in those apps. Task-locked, time limits and Sprints keep working.")
            KeelSecondaryButton({ confirming = true }, Modifier.fillMaxWidth()) { Text("Start the experiment") }
        } else {
            when {
                today.isBefore(start) -> StatusLine("Starts ${start.format(DateTimeFormatter.ofPattern("EEEE d MMM"))}", tone = Tone.Neutral)
                experimentWeekIsOff(start, today) -> StatusLine("Friction-off week: pause rules aren't pausing", tone = Tone.Neutral)
                else -> StatusLine("Friction-on week: pause rules are working as usual")
            }
            when (val report = loaded.report) {
                is ExperimentReport.NotEnough -> Muted(
                    "Needs at least $EXPERIMENT_MIN_WEEKS_EACH finished weeks of each. So far: ${report.onWeeks} on, ${report.offWeeks} off.",
                )
                is ExperimentReport.Result -> {
                    Text("With pauses: ${hours(report.onAverage)} a week. Without: ${hours(report.offAverage)} a week.")
                    Text(
                        if (report.clear) {
                            "Every week with pauses beat every week without, so it looks like pauses help you. It's still only ${report.onWeeks + report.offWeeks} weeks."
                        } else {
                            "No clear effect: the two sets of weeks overlap. Pauses may not be doing much for you on these apps, and that's useful to know."
                        },
                    )
                }
                null -> Unit
            }
            KeelTextButton({ model.act { stopExperiment() } }) { Text("Stop the experiment") }
        }
    }
    if (confirming) {
        val startDate = Instant.ofEpochMilli(nextWeekStart(System.currentTimeMillis(), zone)).atZone(zone).toLocalDate()
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Start the experiment?") },
            text = { Text("It starts ${startDate.format(DateTimeFormatter.ofPattern("EEEE d MMM"))} with a friction-on week. Every second week after that, pause rules won't pause. You can stop at any time, and pauses return at once.") },
            confirmButton = {
                KeelTextButton({
                    confirming = false
                    model.act { startExperiment(weekStart(startDate)) }
                }) { Text("Start") }
            },
            dismissButton = { KeelTextButton({ confirming = false }) { Text("Cancel") } },
        )
    }
}
