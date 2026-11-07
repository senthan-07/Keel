package dev.keel.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.keel.domain.Habit
import dev.keel.domain.HabitState
import dev.keel.domain.HabitUnit
import dev.keel.domain.habitState
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val HABIT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy")
private const val MAX_HABIT_AMOUNT = 99

private data class HabitRow(val habit: Habit, val state: HabitState, val doneToday: Boolean)

private fun days(count: Long) = if (count == 1L) "1 day" else "$count days"

/** Habits you promised to keep every day. One missed day sends the streak back to 0. */
@Composable
fun HabitsSection(model: AppModel, today: LocalDate) {
    val rows by loadState(model) {
        habits().map { (habit, ticks) -> HabitRow(habit, habitState(habit, ticks, today), today in ticks) }
            .sortedBy { row ->
                when {
                    row.state is HabitState.Finished -> 2
                    row.doneToday -> 1
                    else -> 0
                }
            }
    }
    var adding by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<HabitRow?>(null) }
    SectionTitle("Habits")
    rows?.takeIf { it.isNotEmpty() }?.let { list ->
        Panel { list.forEach { row -> HabitItem(model, row, today) { opened = row } } }
    }
    KeelSecondaryButton({ adding = true }, Modifier.fillMaxWidth()) { Text("Add a habit") }
    if (adding) AddHabitDialog(model) { adding = false }
    opened?.let { row -> HabitDialog(model, row) { opened = null } }
}

@Composable
private fun HabitItem(model: AppModel, row: HabitRow, today: LocalDate, onOpen: () -> Unit) {
    val state = row.state
    val finishedEarlier = state is HabitState.Finished && state.on != today
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Only today can be ticked, so a missed day can't be filled in later.
        if (!finishedEarlier) {
            KeelCheck(
                row.doneToday,
                { model.act { setHabitDone(row.habit.id, today, it) } },
                Modifier.offset { IntOffset((-12).dp.roundToPx(), 0) },
            )
        }
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clickable(onClick = onOpen),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(row.habit.title, color = KeelColor.Text)
            when (state) {
                is HabitState.NotStarted -> Note("Tick today to start. 0 of ${state.needed} days.")
                is HabitState.Going -> {
                    Note("${days(state.streak)} in a row, ${state.streak} of ${state.needed}.")
                    KeelProgress(state.streak.toFloat() / state.needed)
                }
                is HabitState.Broken -> Text(
                    "You missed a day, so the streak is back to 0. Tick today to start again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KeelColor.Danger,
                )
                is HabitState.Finished -> Note("Done: ${row.habit.unit.label(row.habit.amount)} in a row, on ${state.on.format(HABIT_DATE)}.")
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = KeelColor.TextSecondary)
}

@Composable
private fun AddHabitDialog(model: AppModel, onClose: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(HabitUnit.MONTHS) }
    val amount = amountText.toIntOrNull()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add a habit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeelTextField(title, { title = it }, label = { Text("Habit, like Read 10 pages") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                KeelTextField(
                    amountText,
                    { text -> amountText = text.filter(Char::isDigit).take(2) },
                    label = { Text("Keep it up for") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HabitUnit.entries.forEach { choice ->
                        KeelChip(choice == unit, { unit = choice }) {
                            Text(choice.plural.replaceFirstChar(Char::uppercase), maxLines = 1)
                        }
                    }
                }
                Muted("Tick it every day. Miss one day and the streak goes back to 0, and the full time starts again.")
            }
        },
        confirmButton = {
            KeelTextButton(
                enabled = title.isNotBlank() && amount != null && amount in 1..MAX_HABIT_AMOUNT,
                onClick = {
                    val name = title.trim()
                    val length = requireNotNull(amount)
                    val chosen = unit
                    model.act { addHabit(name, length, chosen) }
                    onClose()
                },
            ) { Text("Add") }
        },
        dismissButton = { KeelTextButton(onClose) { Text("Cancel") } },
    )
}

@Composable
private fun HabitDialog(model: AppModel, row: HabitRow, onClose: () -> Unit) {
    var deleting by remember { mutableStateOf(false) }
    if (deleting) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Delete \"${row.habit.title}\"?") },
            text = { Text("This removes the habit and its whole streak history, and can't be undone.") },
            confirmButton = {
                KeelTextButton({
                    model.act { deleteHabit(row.habit.id) }
                    onClose()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { KeelTextButton(onClose) { Text("Keep") } },
        )
        return
    }
    val state = row.state
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(row.habit.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Goal: ${row.habit.unit.label(row.habit.amount)} without missing a day.")
                when (state) {
                    is HabitState.NotStarted -> Unit
                    is HabitState.Going -> {
                        Text("Current streak: ${days(state.streak)}")
                        Text("Days left: ${state.needed - state.streak}")
                        Text("Finishes ${state.finishesOn.format(HABIT_DATE)} if you don't miss a day.")
                    }
                    is HabitState.Broken -> Text("Current streak: 0 days")
                    is HabitState.Finished -> Text("Finished ${state.on.format(HABIT_DATE)}.")
                }
                if (state.best > 0) Muted("Longest streak: ${days(state.best)}")
            }
        },
        confirmButton = { KeelTextButton(onClose) { Text("Close") } },
        dismissButton = {
            KeelTextButton({ deleting = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
    )
}
