package dev.keel.domain

import java.time.Duration

/** One tickable line from a plan. [key] stays stable across re-imports, so ticks can carry over. */
data class PlanItem(
    val section: String?,
    val title: String,
    val detail: String?,
    val done: Boolean,
) {
    val key: String get() = "${section.orEmpty()}|$title".lowercase()
}

data class ParsedPlan(val title: String, val items: List<PlanItem>)

private val LIST_CHECKBOX = Regex("""^\s*[-*+]\s+\[( |x|X)]\s+(.+)$""")
private val TABLE_CHECKBOX = Regex("""^\s*\|\s*\[( |x|X)]\s*(.*)$""")
private val HEADING = Regex("""^(#{1,6})\s+(.+?)\s*#*$""")
private val FENCE = Regex("""^\s*(```|~~~)""")
private val IMAGE = Regex("""!\[([^]]*)]\([^)]*\)""")
private val LINK = Regex("""\[([^]]*)]\([^)]*\)""")
private val BOLD = Regex("""(\*\*|__)(.+?)\1""")
private val ITALIC = Regex("""(?<![\w*])\*([^*\s][^*]*?)\*(?![\w*])""")
private val CODE = Regex("""`([^`]*)`""")

/** Removes inline Markdown, keeping the words a person would read. */
fun plainText(markdown: String): String = markdown
    .replace(IMAGE, "$1")
    .replace(LINK, "$1")
    .replace(BOLD, "$2")
    .replace(ITALIC, "$1")
    .replace(CODE, "$1")
    .trim()

private fun tableCells(rest: String): List<String> {
    val cells = rest.split('|').map { plainText(it) }.toMutableList()
    while (cells.isNotEmpty() && cells.last().isEmpty()) cells.removeAt(cells.lastIndex)
    return cells
}

private fun itemFrom(line: String, section: String?): PlanItem? {
    LIST_CHECKBOX.find(line)?.let { match ->
        val text = plainText(match.groupValues[2])
        return if (text.isEmpty()) null else PlanItem(section, text, null, match.groupValues[1] != " ")
    }
    val row = TABLE_CHECKBOX.find(line) ?: return null
    // A step number in the checkbox's cell ("[ ] 1") says nothing useful, so it's dropped.
    val cells = tableCells(row.groupValues[2]).toMutableList()
    if (cells.firstOrNull()?.all { it.isDigit() } == true) cells.removeAt(0)
    val filled = cells.filter { it.isNotEmpty() }
    val first = filled.firstOrNull() ?: return null
    val title = if (filled.size > 1) "$first: ${filled[1]}" else first
    val detail = filled.drop(2).joinToString(" / ").ifEmpty { null }
    return PlanItem(section, title, detail, row.groupValues[1] != " ")
}

/** Reads any Markdown file: list checkboxes and table-row checkboxes become items, under their heading. */
fun parsePlan(fileName: String, markdown: String): ParsedPlan {
    var title: String? = null
    var section: String? = null
    var fenced = false
    val items = mutableListOf<PlanItem>()
    for (line in markdown.lines()) {
        if (FENCE.containsMatchIn(line)) {
            fenced = !fenced
            continue
        }
        if (fenced) continue
        val heading = HEADING.find(line)
        if (heading != null) {
            val text = plainText(heading.groupValues[2])
            if (heading.groupValues[1].length == 1 && title == null) title = text else section = text
            continue
        }
        itemFrom(line, section)?.let { items += it }
    }
    return ParsedPlan(title ?: titleFromFileName(fileName), items)
}

private fun titleFromFileName(fileName: String): String =
    fileName.substringBeforeLast('.').replace('-', ' ').replace('_', ' ').trim()
        .replaceFirstChar { it.uppercase() }
        .ifEmpty { "Untitled plan" }

data class ExistingItem(val id: Long, val key: String, val doneAt: Long?)

data class PlanMerge(
    /** Each parsed item in file order, with the existing row it continues, if any. */
    val rows: List<MergedRow>,
    val removedIds: List<Long>,
)

data class MergedRow(val existingId: Long?, val item: PlanItem, val doneAt: Long?)

/** Re-importing a changed plan never loses progress: an item stays done if either side says it's done. */
fun mergePlan(existing: List<ExistingItem>, parsed: ParsedPlan, now: Long): PlanMerge {
    val available = existing.groupBy { it.key }.mapValues { it.value.toMutableList() }
    val rows = parsed.items.map { item ->
        val match = available[item.key]?.removeFirstOrNull()
        val doneAt = match?.doneAt ?: if (item.done) now else null
        MergedRow(match?.id, item, doneAt)
    }
    return PlanMerge(rows, available.values.flatten().map { it.id })
}

/**
 * The plan's original Markdown with its ticks set, for export. [doneKeys] holds the done items, with
 * duplicates counted. Passing an empty collection gives a clean copy for sharing.
 */
fun planWithTicks(markdown: String, doneKeys: Collection<String>): String {
    val remaining = doneKeys.groupingBy { it }.eachCount().toMutableMap()
    var section: String? = null
    var fenced = false
    var seenTitle = false
    return markdown.split("\n").joinToString("\n") { line ->
        if (FENCE.containsMatchIn(line)) {
            fenced = !fenced
            return@joinToString line
        }
        if (fenced) return@joinToString line
        val heading = HEADING.find(line)
        if (heading != null) {
            if (heading.groupValues[1].length == 1 && !seenTitle) {
                seenTitle = true
            } else {
                section = plainText(heading.groupValues[2])
            }
            return@joinToString line
        }
        val item = itemFrom(line, section) ?: return@joinToString line
        val left = remaining[item.key] ?: 0
        val done = left > 0
        if (done) remaining[item.key] = left - 1
        line.replaceFirst(Regex("""\[( |x|X)]"""), if (done) "[x]" else "[ ]")
    }
}

/** Display form of one Markdown line, for reading a plan inside the app. */
sealed interface PlanLine {
    data class Heading(val level: Int, val text: String) : PlanLine
    data class Item(val text: String) : PlanLine
    data class Paragraph(val text: String) : PlanLine
}

private val TABLE_RULE = Regex("""^\s*\|?[\s:|-]+\|?\s*$""")
private val BULLET = Regex("""^\s*(?:[-*+]|\d+\.)\s+(?:\[[ xX]]\s+)?(.+)$""")

fun planLines(markdown: String): List<PlanLine> {
    var fenced = false
    return markdown.lines().mapNotNull { line ->
        if (FENCE.containsMatchIn(line)) {
            fenced = !fenced
            return@mapNotNull null
        }
        if (fenced) return@mapNotNull PlanLine.Paragraph(line)
        if (line.isBlank() || (line.contains('-') && TABLE_RULE.matches(line))) return@mapNotNull null
        val heading = HEADING.find(line)
        if (heading != null) return@mapNotNull PlanLine.Heading(heading.groupValues[1].length, plainText(heading.groupValues[2]))
        if (line.trimStart().startsWith("|")) {
            val cells = line.trim().trim('|').split('|').map { plainText(it.replace(Regex("""\[[ xX]]"""), "")) }
                .filter { it.isNotEmpty() }
            return@mapNotNull if (cells.isEmpty()) null else PlanLine.Item(cells.joinToString(" / "))
        }
        BULLET.find(line)?.let { return@mapNotNull PlanLine.Item(plainText(it.groupValues[1])) }
        PlanLine.Paragraph(plainText(line))
    }
}

private val MONEY_WORDS = Regex("finance|money|wealth|salary|budget|invest|saving|retire", RegexOption.IGNORE_CASE)
private val HEALTH_WORDS = Regex("health|habit|fitness|sleep|exercise|diet", RegexOption.IGNORE_CASE)

enum class PlanCategory(val label: String) { MONEY("Money"), HEALTH("Health"), OTHER("Other") }

fun guessCategory(fileName: String, title: String): PlanCategory {
    val text = "$fileName $title"
    return when {
        MONEY_WORDS.containsMatchIn(text) -> PlanCategory.MONEY
        HEALTH_WORDS.containsMatchIn(text) -> PlanCategory.HEALTH
        else -> PlanCategory.OTHER
    }
}

data class MoneyGoalState(val saved: Long, val percent: Int, val idleDays: Long?)

/** A money goal's real state, using its latest typed amount. Keel holds no money and links to no bank. */
fun moneyState(target: Long, latestAmount: Long?, latestAt: Long?, now: Long): MoneyGoalState {
    val saved = latestAmount ?: 0
    val percent = if (target <= 0) 0 else (saved.coerceAtLeast(0) * 100 / target).coerceAtMost(100).toInt()
    return MoneyGoalState(saved, percent, latestAt?.let { Duration.ofMillis(now - it).toDays() })
}
