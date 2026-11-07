package dev.keel.app.ui

import androidx.activity.compose.BackHandler
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import dev.keel.domain.findDeadline
import dev.keel.domain.orderTodayTasks
import dev.keel.domain.deadlineLabel
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.keel.app.data.PlanDoc
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

private sealed interface HorizonRoute {
    data object Home : HorizonRoute
    data object Plans : HorizonRoute
    data class Goal(val id: Long, val fromPlans: Boolean) : HorizonRoute
}

/** Going deeper slides in from the side and coming back reverses it, so you always know where you are. */
@Composable
fun HorizonsScreen(model: AppModel) {
    var route by remember { mutableStateOf<HorizonRoute>(HorizonRoute.Home) }
    val animate = motionEnabled()
    val depth = { r: HorizonRoute ->
        when (r) {
            HorizonRoute.Home -> 0
            HorizonRoute.Plans -> 1
            is HorizonRoute.Goal -> 2
        }
    }
    AnimatedContent(
        route,
        transitionSpec = {
            val direction = if (depth(targetState) >= depth(initialState)) 1 else -1
            val enter = tween<Float>(KeelMotion.PUSH_MS, easing = KeelMotion.EaseOut)
            val exit = tween<Float>(KeelMotion.STATE_MS, easing = KeelMotion.EaseOut)
            val move = tween<IntOffset>(KeelMotion.PUSH_MS, easing = KeelMotion.EaseOut)
            if (animate) {
                (fadeIn(enter) + slideInHorizontally(move) { it / 12 * direction }) togetherWith
                    (fadeOut(exit) + slideOutHorizontally(move) { -it / 24 * direction })
            } else {
                fadeIn(enter) togetherWith fadeOut(exit)
            }
        },
        label = "horizons",
    ) { current ->
        when (current) {
            HorizonRoute.Home -> HorizonsHome(
                model,
                onOpenPlans = { route = HorizonRoute.Plans },
                onOpenGoal = { route = HorizonRoute.Goal(it, fromPlans = false) },
            )
            HorizonRoute.Plans -> {
                BackHandler { route = HorizonRoute.Home }
                PlansScreen(
                    model,
                    onBack = { route = HorizonRoute.Home },
                    onOpenGoal = { route = HorizonRoute.Goal(it, fromPlans = true) },
                )
            }
            is HorizonRoute.Goal -> {
                val back = { route = if (current.fromPlans) HorizonRoute.Plans else HorizonRoute.Home }
                BackHandler(onBack = back)
                GoalDetail(model, current.id, back)
            }
        }
    }
}

private fun stepFraction(steps: List<PlanStep>): Float =
    if (steps.isEmpty()) 0f else steps.count { it.doneAt != null }.toFloat() / steps.size

@Composable
private fun HorizonsHome(model: AppModel, onOpenPlans: () -> Unit, onOpenGoal: (Long) -> Unit) {
    var sort by remember { mutableStateOf(GoalSort.MONTHS_RETURNED) }
    var adding by remember { mutableStateOf(false) }
    val data by loadState(model) {
        HorizonData(
            ranked(sort),
            steps(),
            goals(GoalStatus.COOLING),
            goals(GoalStatus.KILLED) + goals(GoalStatus.DONE),
        )
    }
    val next by loadState(model) { suggestion() }
    val now = System.currentTimeMillis()
    Page("Horizons", "Ranked by what you keep coming back to.") {
        next?.let { step ->
            Panel {
                Text("NEXT STEP", style = MaterialTheme.typography.labelSmall, color = KeelColor.TextSecondary)
                Text(step.title, style = MaterialTheme.typography.titleLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                step.serves?.let { Muted("Serves $it") }
            }
        }
        KeelButton({ adding = true }, Modifier.fillMaxWidth()) { Text("Add a goal") }
        NavRow("Plans and money", "Imported plans and savings goals", onOpenPlans)
        val loaded = data ?: return@Page
        if (loaded.ranked.isEmpty() && loaded.cooling.isEmpty()) {
            Muted("Nothing here yet. Add a goal, or switch on a plan under Plans and money.")
        } else {
            SectionTitle("Sort by")
            ChipRow {
                GoalSort.entries.forEach {
                    KeelChip(
                        selected = it == sort,
                        onClick = {
                            sort = it
                            model.refresh()
                        },
                        label = { Text(it.label, maxLines = 1) },
                    )
                }
            }
        }
        Horizon.entries.forEach { horizon ->
            val inHorizon = loaded.ranked.filter { it.goal.horizon == horizon }
            if (inHorizon.isNotEmpty()) {
                SectionTitle(horizon.label)
                inHorizon.forEach { ranked ->
                    val steps = loaded.steps.filter { it.goalId == ranked.goal.id }
                    Panel({ onOpenGoal(ranked.goal.id) }) {
                        Text(ranked.goal.title, style = MaterialTheme.typography.titleMedium)
                        if (steps.isNotEmpty()) KeelProgress(stepFraction(steps))
                        Muted(realState(steps, ranked.lastTouch, now))
                        Muted(monthsLine(ranked.monthsReturned))
                    }
                }
            }
        }
        if (loaded.cooling.isNotEmpty()) {
            SectionTitle("Cooling off")
            Muted("New long-term ambitions wait 30 days before they join your plan.")
            loaded.cooling.forEach { goal ->
                Panel({ onOpenGoal(goal.id) }) {
                    Text(goal.title, style = MaterialTheme.typography.titleMedium)
                    Muted("${goal.horizon.label}: ${coolingDaysLeft(goal.coolingUntil ?: now, now)} days left")
                }
            }
        }
        if (loaded.ended.isNotEmpty()) {
            SectionTitle("Ended")
            Panel {
                loaded.ended.forEach { goal ->
                    val why = goal.endedReason?.let { ": $it" }.orEmpty()
                    Muted("${goal.title} (${if (goal.status == GoalStatus.DONE) "done" else "dropped"}$why)")
                }
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
            KeelTextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    val cleanTitle = title.trim()
                    model.act { addGoal(cleanTitle, horizon, cleanTitle, outcome.trim(), obstacle.trim(), plan.trim()) }
                    onClose()
                },
            ) { Text("Add") }
        },
        dismissButton = { KeelTextButton(onClose) { Text("Cancel") } },
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
    KeelTextField(wish, onWish, label = { Text("Wish: what do you want?") }, modifier = Modifier.fillMaxWidth())
    KeelTextField(outcome, onOutcome, label = { Text("Outcome: best result?") }, modifier = Modifier.fillMaxWidth())
    KeelTextField(obstacle, onObstacle, label = { Text("Obstacle: what in you gets in the way?") }, modifier = Modifier.fillMaxWidth())
    KeelTextField(plan, onPlan, label = { Text("Plan: if that happens, I will...") }, modifier = Modifier.fillMaxWidth())
}

@Composable
fun HorizonChoice(selected: Horizon, onSelect: (Horizon) -> Unit) {
    ChipRow {
        Horizon.entries.forEach {
            KeelChip(selected = it == selected, onClick = { onSelect(it) }, label = { Text(it.label, maxLines = 1) })
        }
    }
}

private data class GoalData(val goal: Goal?, val steps: List<PlanStep>, val doc: PlanDoc?)

@Composable
private fun GoalDetail(model: AppModel, goalId: Long, onClose: () -> Unit) {
    val data by loadState(model) { GoalData(goal(goalId), steps(goalId), planDoc(goalId)) }
    var addingStep by remember { mutableStateOf(false) }
    var dropping by remember { mutableStateOf(false) }
    val loaded = data ?: return
    val goal = loaded.goal ?: return
    Page(goal.title, goal.horizon.label, onBack = onClose) {
        val doc = loaded.doc
        if (doc != null) {
            PlanPanel(model, doc, goal, onDeleted = onClose)
        } else if (goal.outcome.isNotEmpty() || goal.obstacle.isNotEmpty() || goal.plan.isNotEmpty()) {
            Panel {
                if (goal.outcome.isNotEmpty()) Muted("Outcome: ${goal.outcome}")
                if (goal.obstacle.isNotEmpty()) Muted("Obstacle: ${goal.obstacle}")
                if (goal.plan.isNotEmpty()) Muted("Plan: ${goal.plan}")
            }
        }
        if (loaded.steps.isEmpty()) {
            SectionTitle("Steps")
            Muted(if (doc != null) "This plan has no [ ] lines, so it's for reading." else "No steps yet. What's the smallest next step?")
        }
        // Consecutive steps from one section share a card, so long plans stay easy to scan.
        val sections = mutableListOf<MutableList<PlanStep>>()
        loaded.steps.forEach { step ->
            if (sections.isEmpty() || (doc != null && sections.last().last().source != step.source)) sections += mutableListOf<PlanStep>()
            sections.last() += step
        }
        sections.forEach { section ->
            SectionTitle(if (doc != null) section.first().source ?: "Steps" else "Steps")
            Panel {
                section.forEach { step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KeelCheck(step.doneAt != null, { model.act { setStepDone(step, it) } }, Modifier.offset { IntOffset((-12).dp.roundToPx(), 0) })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                step.title,
                                color = if (step.doneAt != null) KeelColor.TextSecondary else KeelColor.Text,
                                textDecoration = if (step.doneAt != null) TextDecoration.LineThrough else null,
                            )
                            step.estimateMinutes?.let { Text("$it min", style = MaterialTheme.typography.bodySmall, color = KeelColor.TextSecondary) }
                            step.ifThen?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = KeelColor.TextSecondary) }
                        }
                        if (step.doneAt == null) {
                            KeelTextButton({
                                model.act {
                                    addTask(step.title, LocalDate.now(), step.id, "Step of \"${goal.title}\"", step.estimateMinutes)
                                    touchGoal(goal.id)
                                }
                            }) { Text("Today") }
                        }
                    }
                }
            }
        }
        KeelSecondaryButton({ addingStep = true }, Modifier.fillMaxWidth()) { Text("Add a step") }
        if (goal.status == GoalStatus.ACTIVE || goal.status == GoalStatus.COOLING) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                KeelSecondaryButton(
                    {
                        model.act { endGoal(goal.id, GoalStatus.DONE, null) }
                        onClose()
                    },
                    Modifier.weight(1f),
                ) { Text("Mark done") }
                KeelTextButton({ dropping = true }, Modifier.weight(1f)) { Text("Drop goal", color = KeelColor.Danger) }
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
            KeelTextField(reason, { reason = it }, label = { Text("Why? (optional)") }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { KeelTextButton({ onDrop(reason.trim().ifEmpty { null }) }) { Text("Drop") } },
        dismissButton = { KeelTextButton(onCancel) { Text("Keep") } },
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
                KeelTextField(title, { title = it }, label = { Text("Step") }, modifier = Modifier.fillMaxWidth())
                KeelTextField(
                    minutes,
                    { minutes = it.filter(Char::isDigit).take(4) },
                    label = { Text("Minutes (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                KeelTextField(ifThen, { ifThen = it }, label = { Text("When X, I will Y (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            KeelTextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    model.act {
                        addStep(goal.id, title.trim(), minutes.toIntOrNull(), ifThen.trim().ifEmpty { null })
                    }
                    onClose()
                },
            ) { Text("Add") }
        },
        dismissButton = { KeelTextButton(onClose) { Text("Cancel") } },
    )
}

private data class TodayData(val today: List<Task>, val overdue: List<Task>)

/** Your own list comes first: add, then what you've added, then what your plans suggest. */
@Composable
fun TodayScreen(model: AppModel) {
    val date = LocalDate.now()
    val data by loadState(model) { TodayData(tasks(date), overdueTasks(date)) }
    var text by remember { mutableStateOf("") }
    Page("Today", overline = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeelTextField(text, { text = it }, label = { Text("Add for today") }, modifier = Modifier.weight(1f))
            KeelSecondaryButton(
                enabled = text.isNotBlank(),
                onClick = {
                    val title = text.trim()
                    model.act { addTask(title, date, source = "Typed into Today") }
                    text = ""
                },
            ) { Text("Add") }
        }
        val loaded = data ?: return@Page
        // Overdue items count, so "done" is only ever true.
        when (val state = dayState(loaded.today + loaded.overdue)) {
            DayState.Empty -> Muted("Nothing planned yet. Add what matters, or pick from your plans below.")
            is DayState.InProgress -> Panel {
                Text("${state.done} of ${state.left + state.done} done", style = MaterialTheme.typography.titleMedium.tabular)
                KeelProgress(state.done.toFloat() / (state.left + state.done))
            }
            is DayState.Finished -> DayFinishedCard()
        }
        if (loaded.today.isNotEmpty()) {
            Panel { orderTodayTasks(loaded.today).forEach { TaskRow(model, it, date) } }
        }
        if (loaded.overdue.isNotEmpty()) {
            SectionTitle("From earlier days")
            Muted("Move these to today, or let them go.")
            Panel { orderTodayTasks(loaded.overdue).forEach { TaskRow(model, it, date) } }
        }
        DeadlinesSection(model, date)
        PlanPicksSection(model, date)
    }
}

/** A rare, earned moment, so it's allowed one quiet entrance. */
@Composable
private fun DayFinishedCard() {
    val animate = motionEnabled()
    var shown by remember { mutableStateOf(!animate) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(KeelMotion.PROGRESS_MS, easing = KeelMotion.EaseOut), label = "doneFade")
    val rise by animateDpAsState(if (shown) 0.dp else 12.dp, tween(KeelMotion.PROGRESS_MS, easing = KeelMotion.EaseOut), label = "doneRise")
    Column(Modifier.graphicsLayer { this.alpha = alpha; translationY = rise.toPx() }) {
        Panel {
            Text("You're done for today.", style = MaterialTheme.typography.titleLarge)
            Muted("That was everything, and there's nothing more to do here.")
        }
    }
}

@Composable
private fun TaskRow(model: AppModel, task: Task, today: LocalDate) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        KeelCheck(task.doneAt != null, { model.act { setTaskDone(task, it) } }, Modifier.offset { IntOffset((-12).dp.roundToPx(), 0) })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                task.title,
                color = if (task.doneAt != null) KeelColor.TextSecondary else KeelColor.Text,
                textDecoration = if (task.doneAt != null) TextDecoration.LineThrough else null,
            )
            val due = findDeadline(task.title)
            if (due != null && task.doneAt == null) {
                val days = ChronoUnit.DAYS.between(today, due)
                Text(
                    deadlineLabel(days),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (days < 0) KeelColor.Danger else KeelColor.TextSecondary,
                )
            }
            task.source?.let { Text("From: $it", style = MaterialTheme.typography.bodySmall, color = KeelColor.TextSecondary) }
        }
        if (task.day != today) {
            KeelTextButton({ model.act { moveTask(task.id, today) } }) { Text("Today") }
            KeelTextButton({ model.act { deleteTask(task.id) } }) { Text("Drop") }
        }
    }
}
