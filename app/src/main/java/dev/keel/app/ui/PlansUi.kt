package dev.keel.app.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.keel.app.data.MoneyGoal
import dev.keel.app.data.PlanDoc
import dev.keel.app.data.Repo
import dev.keel.domain.Goal
import dev.keel.domain.GoalStatus
import dev.keel.domain.PlanCategory
import dev.keel.domain.PlanLine
import dev.keel.domain.PlanStep
import dev.keel.domain.moneyState
import dev.keel.domain.planLines
import dev.keel.domain.realState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

private const val MAX_PLAN_BYTES = 2 * 1024 * 1024

fun money(amount: Long): String = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
    maximumFractionDigits = 0
}.format(amount)

private fun fileName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "plan.md"

/** Reads a plan file as text, refusing anything too large or that isn't text. */
private fun readPlanText(context: Context, uri: Uri): String {
    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "couldn't open it" }.use { input ->
        val buffer = ByteArray(MAX_PLAN_BYTES + 1)
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        buffer.copyOf(total)
    }
    require(bytes.size <= MAX_PLAN_BYTES) { "it's larger than 2 MB" }
    require(bytes.none { it == 0.toByte() }) { "it isn't a text file" }
    return String(bytes, Charsets.UTF_8)
}

private fun importFiles(context: Context, repo: Repo, uris: List<Uri>): String {
    val results = mutableListOf<String>()
    val failures = mutableListOf<String>()
    for (uri in uris) {
        val name = fileName(context, uri)
        try {
            val result = repo.importPlan(name, readPlanText(context, uri))
            results += if (result.updated) {
                "${result.title}: updated, ${result.added} new, ${result.removed} removed, ticks kept"
            } else {
                "${result.title}: ${result.added} steps"
            }
        } catch (error: Exception) {
            failures += "$name wasn't imported: ${error.message}"
        }
    }
    return (results + failures).joinToString("\n")
}

private data class PlansData(
    val docs: List<PlanDoc>,
    val goals: Map<Long, Goal>,
    val steps: List<PlanStep>,
    val touches: Map<Long, List<Long>>,
    val money: List<MoneyGoal>,
)

/** Your plans and money goals. Everything here is typed or imported by you, and is optional. */
@Composable
fun PlansScreen(model: AppModel, onBack: () -> Unit, onOpenGoal: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by loadState(model) {
        val docs = planDocs()
        val goals = goals().associateBy { it.id }
        PlansData(docs, goals, steps(), touches(), moneyGoals())
    }
    var message by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var confirmExportAll by remember { mutableStateOf(false) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                message = withContext(Dispatchers.IO) { importFiles(context, model.repo, uris) }
                model.refresh()
            }
        }
    }
    val exportAll = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            scope.launch {
                message = withContext(Dispatchers.IO) { exportEverything(context, model.repo, tree) }
            }
        }
    }
    Page("Plans and money", "Optional. Keel never asks for bank data, and all of this stays on your phone.", onBack = onBack) {
        val loaded = data ?: return@Page
        MoneySection(model, loaded.money)
        SectionTitle("Your plans")
        Muted("Import Markdown files or paste text. Every [ ] line becomes a step you can tick. Import a changed file again and your ticks carry over.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            KeelButton({ import.launch(arrayOf("text/*", "application/octet-stream")) }, Modifier.weight(1f)) { Text("Import files") }
            KeelSecondaryButton({ pasting = true }, Modifier.weight(1f)) { Text("Paste text") }
        }
        message?.let { Panel { Text(it, style = MaterialTheme.typography.bodyMedium) } }
        if (loaded.docs.isEmpty()) Muted("No plans yet.")
        PlanCategory.entries.forEach { category ->
            val inCategory = loaded.docs.filter { it.category == category }
            if (inCategory.isNotEmpty()) {
                SectionTitle(category.label)
                inCategory.forEach { doc ->
                    val goal = loaded.goals[doc.goalId] ?: return@forEach
                    val steps = loaded.steps.filter { it.goalId == doc.goalId }
                    val now = System.currentTimeMillis()
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { onOpenGoal(doc.goalId) }) {
                                Text(goal.title, style = MaterialTheme.typography.titleMedium)
                                if (steps.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    KeelProgress(steps.count { it.doneAt != null }.toFloat() / steps.size)
                                    Spacer(Modifier.height(8.dp))
                                }
                                Muted(
                                    if (steps.isEmpty()) {
                                        "For reading"
                                    } else {
                                        realState(steps, loaded.touches[doc.goalId]?.maxOrNull(), now)
                                    },
                                )
                            }
                            if (steps.isNotEmpty()) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    KeelSwitch(goal.status == GoalStatus.ACTIVE, { on -> model.act { setPlanActive(doc.goalId, on) } })
                                    Muted("Active")
                                }
                            }
                        }
                    }
                }
            }
        }
        if (loaded.docs.isNotEmpty()) {
            Muted("Active plans feed Horizons and the pause screen's next step. The rest stay here until you need them.")
            KeelSecondaryButton({ confirmExportAll = true }, Modifier.fillMaxWidth()) { Text("Export all to a folder") }
        }
    }
    if (pasting) PastePlanDialog(model, { message = it }) { pasting = false }
    if (confirmExportAll) {
        ShareWarning(
            onCancel = { confirmExportAll = false },
            onContinue = {
                confirmExportAll = false
                exportAll.launch(null)
            },
        )
    }
}

private fun exportEverything(context: Context, repo: Repo, tree: Uri): String {
    return try {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var count = 0
        repo.planDocs().forEach { doc ->
            val file = requireNotNull(
                DocumentsContract.createDocument(context.contentResolver, parent, "text/markdown", doc.fileName),
            ) { "couldn't create ${doc.fileName}" }
            requireNotNull(context.contentResolver.openOutputStream(file)) { "couldn't write ${doc.fileName}" }.use {
                it.write(repo.exportPlan(doc.goalId, clean = false).toByteArray())
            }
            count++
        }
        "Exported $count ${if (count == 1) "plan" else "plans"}, with your ticks."
    } catch (error: Exception) {
        "Export failed: ${error.message}"
    }
}

@Composable
private fun ShareWarning(onCancel: () -> Unit, onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Before you export") },
        text = {
            Text(
                "This file isn't encrypted, and anyone who gets it can read all of it. " +
                    "Check it for personal details, like names, pay and family, before sharing it.",
            )
        },
        confirmButton = { KeelTextButton(onContinue) { Text("Continue") } },
        dismissButton = { KeelTextButton(onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun PastePlanDialog(model: AppModel, onResult: (String) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Paste a plan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeelTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                KeelTextField(body, { body = it }, label = { Text("Your plan") }, minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth())
                Muted("Start a line with - [ ] to make it a step.")
            }
        },
        confirmButton = {
            KeelTextButton(
                enabled = title.isNotBlank() && body.isNotBlank(),
                onClick = {
                    val name = title.trim()
                    val text = if (body.trimStart().startsWith("# ")) body else "# $name\n\n$body"
                    onClose()
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { model.repo.importPlan("$name.md", text) }
                        }
                        onResult(
                            result.fold(
                                { "${it.title}: ${if (it.updated) "updated" else "${it.added} steps"}" },
                                { "Couldn't save it: ${it.message}" },
                            ),
                        )
                        model.refresh()
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { KeelTextButton(onClose) { Text("Cancel") } },
    )
}

/** Plan-only controls inside a goal's page: reading, category, sharing, updating and deleting. */
@Composable
fun PlanPanel(model: AppModel, doc: PlanDoc, goal: Goal, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var pendingClean by remember { mutableStateOf<Boolean?>(null) }
    var clean by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                message = withContext(Dispatchers.IO) {
                    try {
                        val text = model.repo.exportPlan(doc.goalId, clean)
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "couldn't open the file" }.use {
                            it.write(text.toByteArray())
                        }
                        if (clean) "Clean copy exported, with every tick cleared." else "Exported with your ticks."
                    } catch (error: Exception) {
                        "Export failed: ${error.message}"
                    }
                }
            }
        }
    }
    val update = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                message = withContext(Dispatchers.IO) {
                    try {
                        val result = model.repo.importPlan(doc.fileName, readPlanText(context, uri))
                        "Updated: ${result.added} new, ${result.removed} removed. Your ticks were kept."
                    } catch (error: Exception) {
                        "Couldn't update it: ${error.message}"
                    }
                }
                model.refresh()
            }
        }
    }
    Panel {
        ChipRow {
            PlanCategory.entries.forEach { category ->
                KeelChip(
                    selected = category == doc.category,
                    onClick = { model.act { setPlanCategory(doc.goalId, category) } },
                    label = { Text(category.label, maxLines = 1) },
                )
            }
        }
        ActionRow(Icons.Outlined.Description, if (reading) "Hide the full plan" else "Read the full plan") { reading = !reading }
        ActionRow(Icons.Outlined.Upload, "Update from a changed file") { update.launch(arrayOf("text/*", "application/octet-stream")) }
        ActionRow(Icons.Outlined.Download, "Export with my ticks") { pendingClean = false }
        ActionRow(Icons.Outlined.Share, "Export a clean copy to share") { pendingClean = true }
        ActionRow(Icons.Outlined.DeleteOutline, "Delete this plan", danger = true) { deleting = true }
        message?.let { Muted(it) }
    }
    if (reading) {
        Panel {
            planLines(doc.body).forEach { line ->
                when (line) {
                    is PlanLine.Heading -> Text(
                        line.text,
                        style = if (line.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    is PlanLine.Item -> Text(line.text, style = MaterialTheme.typography.bodyMedium)
                    is PlanLine.Paragraph -> Muted(line.text)
                }
            }
        }
    }
    pendingClean?.let { wantClean ->
        ShareWarning(
            onCancel = { pendingClean = null },
            onContinue = {
                pendingClean = null
                clean = wantClean
                export.launch(doc.fileName)
            },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \"${goal.title}\"?") },
            text = { Text("This removes the plan and its ticks from Keel, and can't be undone. Tasks already on Today stay. Your original file isn't touched.") },
            confirmButton = {
                KeelTextButton({
                    deleting = false
                    model.act { deletePlan(doc.goalId) }
                    onDeleted()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { KeelTextButton({ deleting = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun MoneySection(model: AppModel, goals: List<MoneyGoal>) {
    var adding by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf<MoneyGoal?>(null) }
    val now = System.currentTimeMillis()
    SectionTitle("Money goals")
    Muted("Type what you've saved whenever you like. Keel never holds or moves money.")
    goals.forEach { goal ->
        val state = moneyState(goal.target, goal.latestAmount, goal.latestAt, now)
        Panel {
            Column(Modifier.fillMaxWidth().clickable { updating = goal }) {
                Text(goal.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                KeelProgress(state.percent / 100f)
                Spacer(Modifier.height(8.dp))
                Text("${money(state.saved)} of ${money(goal.target)} (${state.percent}%)", style = MaterialTheme.typography.bodyLarge.tabular)
                Muted(
                    when (val idle = state.idleDays) {
                        null -> "Nothing recorded yet. Tap to update."
                        0L -> "Updated today. Tap to update."
                        else -> "Last updated $idle ${if (idle == 1L) "day" else "days"} ago. Tap to update."
                    },
                )
            }
        }
    }
    KeelSecondaryButton({ adding = true }, Modifier.fillMaxWidth()) { Text("Add a money goal") }
    if (adding) AddMoneyGoalDialog(model) { adding = false }
    updating?.let { goal -> UpdateMoneyDialog(model, goal) { updating = null } }
}

private fun digits(text: String) = text.filter(Char::isDigit).take(15)

@Composable
private fun AddMoneyGoalDialog(model: AppModel, onClose: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add a money goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeelTextField(title, { title = it }, label = { Text("Name, like Emergency fund") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                KeelTextField(
                    target,
                    { target = digits(it) },
                    label = { Text("Target amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            KeelTextButton(
                enabled = title.isNotBlank() && (target.toLongOrNull() ?: 0) > 0,
                onClick = {
                    val name = title.trim()
                    val amount = target.toLong()
                    model.act { addMoneyGoal(name, amount) }
                    onClose()
                },
            ) { Text("Add") }
        },
        dismissButton = { KeelTextButton(onClose) { Text("Cancel") } },
    )
}

@Composable
private fun UpdateMoneyDialog(model: AppModel, goal: MoneyGoal, onClose: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    if (deleting) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Delete \"${goal.title}\"?") },
            text = { Text("This removes the goal and its history, and can't be undone.") },
            confirmButton = {
                KeelTextButton({
                    model.act { deleteMoneyGoal(goal.id) }
                    onClose()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { KeelTextButton(onClose) { Text("Keep") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(goal.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeelTextField(
                    amount,
                    { amount = digits(it) },
                    label = { Text("Saved so far") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                KeelTextButton({ deleting = true }) { Text("Delete this goal", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            KeelTextButton(
                enabled = amount.isNotEmpty(),
                onClick = {
                    val saved = amount.toLong()
                    model.act { recordMoney(goal.id, saved) }
                    onClose()
                },
            ) { Text("Save") }
        },
        dismissButton = { KeelTextButton(onClose) { Text("Cancel") } },
    )
}
