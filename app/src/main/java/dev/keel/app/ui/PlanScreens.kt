package dev.keel.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.keel.domain.DayState
import dev.keel.domain.Goal
import dev.keel.domain.GoalSort
import dev.keel.domain.GoalStatus
import dev.keel.domain.Horizon
import dev.keel.domain.PlanStep
import dev.keel.domain.RankedGoal
import dev.keel.domain.Task
import dev.keel.domain.coolingDaysLeft
import dev.keel.domain.dayState
import dev.keel.domain.realState
import java.time.LocalDate

private data class HorizonData(
    val ranked: List<RankedGoal>,
    val steps: List<PlanStep>,
    val cooling: List<Goal>,
    val ended: List<Goal>,
)

@Composable
fun HorizonsScreen(model: AppModel) {
    var sort by remember { mutableStateOf(GoalSort.MONTHS_RETURNED) }
    var openGoal by remember { mutableStateOf<Long?>(null) }
    var adding by remember { mutableStateOf(false) }
    val selected = openGoal
    if (selected != null) {
        BackHandler { openGoal = null }
        GoalDetail(model, selected) { openGoal = null }
        return
    }
    val data by loadState(model) {
        HorizonData(
            ranked(sort),
            steps(),
            goals(GoalStatus.COOLING),
            goals(GoalStatus.KILLED) + goals(GoalStatus.DONE),
        )
    }
    val now = System.currentTimeMillis()
    Page("Horizons", "Ranked by what you keep coming back to.") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GoalSort.entries.forEach {
                FilterChip(
                    selected = it == sort,
                    onClick = {
                        sort = it
                        model.refresh()
                    },
                    label = { Text(it.label, maxLines = 1) },
                )
            }
        }
        Button({ adding = true }, Modifier.fillMaxWidth()) { Text("Add a goal") }
        val loaded = data ?: return@Page
        if (loaded.ranked.isEmpty() && loaded.cooling.isEmpty()) Muted("No goals yet.")
        Horizon.entries.forEach { horizon ->
            val inHorizon = loaded.ranked.filter { it.goal.horizon == horizon }
            if (inHorizon.isNotEmpty()) {
                SectionTitle(horizon.label)
                inHorizon.forEach { ranked ->
                    val steps = loaded.steps.filter { it.goalId == ranked.goal.id }
                    Panel {
                        Column(Modifier.clickable { openGoal = ranked.goal.id }) {
                            Text(ranked.goal.title, style = MaterialTheme.typography.titleMedium)
                            Muted(realState(steps, ranked.lastTouch, now))
                            Muted(monthsLine(ranked.monthsReturned))
                        }
                    }
                }
            }
        }
        if (loaded.cooling.isNotEmpty()) {
            SectionTitle("Cooling off")
            Muted("New long-term ambitions wait 30 days before they join your plan.")
            loaded.cooling.forEach { goal ->
                Panel {
                    Column(Modifier.clickable { openGoal = goal.id }) {
                        Text(goal.title, style = MaterialTheme.typography.titleMedium)
                        Muted("${goal.horizon.label}: ${coolingDaysLeft(goal.coolingUntil ?: now, now)} days left")
                    }
                }
            }
        }
        if (loaded.ended.isNotEmpty()) {
            SectionTitle("Ended")
            loaded.ended.forEach { goal ->
                val why = goal.endedReason?.let { ": $it" }.orEmpty()
                Muted("${goal.title} (${if (goal.status == GoalStatus.DONE) "done" else "dropped"}$why)")
            }
        }
    }
    if (adding) AddGoalDialog(model) { adding = false }
}

private fun monthsLine(months: Int) =
    if (months == 1) "Returned to in 1 month" else "Returned to in $months separate months"

@Composable
private fun AddGoalDialog(model: AppModel, onClose: () -> Unit) {
    var horizon by remember { mutableStateOf(Horizon.QUARTER) }
    var title by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf("") }
    var obstacle by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add a goal") },
        text = {
            Column(Modifier.imePadding()) {
                WoopFields(title, { title = it }, outcome, { outcome = it }, obstacle, { obstacle = it }, plan, { plan = it })
                HorizonChoice(horizon) { horizon = it }
                if (horizon.coolsOff) Muted("${horizon.label} goals wait 30 days before joining your plan.")
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    val cleanTitle = title.trim()
                    model.act { addGoal(cleanTitle, horizon, cleanTitle, outcome.trim(), obstacle.trim(), plan.trim()) }
                    onClose()
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

@Composable
fun WoopFields(
    wish: String,
    onWish: (String) -> Unit,
    outcome: String,
    onOutcome: (String) -> Unit,
    obstacle: String,
    onObstacle: (String) -> Unit,
    plan: String,
    onPlan: (String) -> Unit,
) {
    OutlinedTextField(wish, onWish, label = { Text("Wish: what do you want?") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(outcome, onOutcome, label = { Text("Outcome: best result?") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(obstacle, onObstacle, label = { Text("Obstacle: what in you gets in the way?") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(plan, onPlan, label = { Text("Plan: if that happens, I will...") }, modifier = Modifier.fillMaxWidth())
}

@Composable
fun HorizonChoice(selected: Horizon, onSelect: (Horizon) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Horizon.entries.forEach {
            FilterChip(selected = it == selected, onClick = { onSelect(it) }, label = { Text(it.label, maxLines = 1) })
        }
    }
}

private data class GoalData(val goal: Goal?, val steps: List<PlanStep>)

@Composable
private fun GoalDetail(model: AppModel, goalId: Long, onClose: () -> Unit) {
    val data by loadState(model) { GoalData(goal(goalId), steps(goalId)) }
    var addingStep by remember { mutableStateOf(false) }
    var dropping by remember { mutableStateOf(false) }
    val loaded = data ?: return
    val goal = loaded.goal ?: return
    Page(goal.title, goal.horizon.label) {
        TextButton(onClose) { Text("Back to Horizons") }
        Panel {
            if (goal.outcome.isNotEmpty()) Muted("Outcome: ${goal.outcome}")
            if (goal.obstacle.isNotEmpty()) Muted("Obstacle: ${goal.obstacle}")
            if (goal.plan.isNotEmpty()) Muted("Plan: ${goal.plan}")
        }
        SectionTitle("Steps")
        if (loaded.steps.isEmpty()) Muted("No steps yet. What's the smallest next step?")
        loaded.steps.forEach { step ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(step.doneAt != null, { model.act { setStepDone(step, it) } })
                    Column(Modifier.weight(1f)) {
                        Text(
                            step.title,
                            textDecoration = if (step.doneAt != null) TextDecoration.LineThrough else null,
                        )
                        step.estimateMinutes?.let { Muted("$it min") }
                        step.ifThen?.let { Muted(it) }
                    }
                    if (step.doneAt == null) {
                        TextButton({
                            model.act {
                                addTask(step.title, LocalDate.now(), step.id, "Step of \"${goal.title}\"", step.estimateMinutes)
                                touchGoal(goal.id)
                            }
                        }) { Text("Today") }
                    }
                }
            }
        }
        OutlinedButton({ addingStep = true }, Modifier.fillMaxWidth()) { Text("Add a step") }
        if (goal.status == GoalStatus.ACTIVE || goal.status == GoalStatus.COOLING) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    {
                        model.act { endGoal(goal.id, GoalStatus.DONE, null) }
                        onClose()
                    },
                    Modifier.weight(1f),
                ) { Text("Mark done") }
                OutlinedButton({ dropping = true }, Modifier.weight(1f)) { Text("Drop goal") }
            }
        }
    }
    if (addingStep) AddStepDialog(model, goal) { addingStep = false }
    if (dropping) {
        DropGoalDialog(
            onDrop = { reason ->
                model.act { endGoal(goal.id, GoalStatus.KILLED, reason) }
                dropping = false
                onClose()
            },
            onCancel = { dropping = false },
        )
    }
}

/** Dropping takes one tap. A reason is welcome but never required. */
@Composable
private fun DropGoalDialog(onDrop: (String?) -> Unit, onCancel: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Drop this goal?") },
        text = {
            OutlinedTextField(reason, { reason = it }, label = { Text("Why? (optional)") }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton({ onDrop(reason.trim().ifEmpty { null }) }) { Text("Drop") } },
        dismissButton = { TextButton(onCancel) { Text("Keep") } },
    )
}

@Composable
private fun AddStepDialog(model: AppModel, goal: Goal, onClose: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var ifThen by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add a step") },
        text = {
            Column(Modifier.imePadding()) {
                OutlinedTextField(title, { title = it }, label = { Text("Step") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    minutes,
                    { minutes = it.filter(Char::isDigit).take(4) },
                    label = { Text("Minutes (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(ifThen, { ifThen = it }, label = { Text("When X, I will Y (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    model.act {
                        addStep(goal.id, title.trim(), minutes.toIntOrNull(), ifThen.trim().ifEmpty { null })
                    }
                    onClose()
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

private data class TodayData(val today: List<Task>, val overdue: List<Task>)

@Composable
fun TodayScreen(model: AppModel) {
    val date = LocalDate.now()
    val data by loadState(model) { TodayData(tasks(date), overdueTasks(date)) }
    var text by remember { mutableStateOf("") }
    Page("Today") {
        val loaded = data ?: return@Page
        // Overdue items count, so "done" is only ever true.
        when (val state = dayState(loaded.today + loaded.overdue)) {
            DayState.Empty -> Muted("Nothing planned. Add what matters, or pull a step from Horizons.")
            is DayState.InProgress -> Muted("${state.left} left, ${state.done} done.")
            is DayState.Finished -> Panel {
                Text("You're done for today.", style = MaterialTheme.typography.titleLarge)
                Muted("That was everything, and there's nothing more to do here.")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, label = { Text("Add for today") }, modifier = Modifier.weight(1f))
            Button(
                enabled = text.isNotBlank(),
                onClick = {
                    val title = text.trim()
                    model.act { addTask(title, date, source = "Typed into Today") }
                    text = ""
                },
            ) { Text("Add") }
        }
        loaded.today.forEach { TaskRow(model, it, date) }
        if (loaded.overdue.isNotEmpty()) {
            SectionTitle("From earlier days")
            Muted("Move these to today, or let them go.")
            loaded.overdue.forEach { TaskRow(model, it, date) }
        }
    }
}

@Composable
private fun TaskRow(model: AppModel, task: Task, today: LocalDate) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(task.doneAt != null, { model.act { setTaskDone(task, it) } })
            Column(Modifier.weight(1f)) {
                Text(task.title, textDecoration = if (task.doneAt != null) TextDecoration.LineThrough else null)
                task.source?.let { Muted("From: $it") }
            }
            if (task.day != today) {
                TextButton({ model.act { moveTask(task.id, today) } }) { Text("Today") }
                TextButton({ model.act { deleteTask(task.id) } }) { Text("Drop") }
            }
        }
    }
}
