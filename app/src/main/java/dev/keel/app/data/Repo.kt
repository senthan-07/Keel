package dev.keel.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import dev.keel.domain.Habit
import dev.keel.domain.HabitUnit
import dev.keel.domain.BackupException
import dev.keel.domain.ExistingItem
import dev.keel.domain.ExperimentWeek
import dev.keel.domain.TimeEnvelope
import dev.keel.domain.WeeklyCheck
import dev.keel.domain.PlanCategory
import dev.keel.domain.PlanItem
import dev.keel.domain.guessCategory
import dev.keel.domain.mergePlan
import dev.keel.domain.parsePlan
import dev.keel.domain.planWithTicks
import dev.keel.domain.Choice
import dev.keel.domain.Goal
import dev.keel.domain.GoalSort
import dev.keel.domain.GoalStatus
import dev.keel.domain.Horizon
import dev.keel.domain.InterceptEvent
import dev.keel.domain.PauseSuggestion
import dev.keel.domain.PlanStep
import dev.keel.domain.RankedGoal
import dev.keel.domain.RuleMode
import dev.keel.domain.RuleSetting
import dev.keel.domain.RuleState
import dev.keel.domain.Task
import dev.keel.domain.UsageSession
import dev.keel.domain.coolingUntil
import dev.keel.domain.effective
import dev.keel.domain.pickNext
import dev.keel.domain.rankGoals
import dev.keel.domain.requestChange
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

data class Rule(
    val target: String,
    val kind: String,
    val label: String,
    val state: RuleState,
    val createdAt: Long = 0,
)

data class WeekUsage(val pkg: String, val minutes: Long)

data class PlanDoc(val goalId: Long, val fileName: String, val category: PlanCategory, val body: String)

data class ImportResult(val title: String, val updated: Boolean, val added: Int, val removed: Int)

data class MoneyGoal(val id: Long, val title: String, val target: Long, val latestAmount: Long?, val latestAt: Long?)

/** A decrypted backup that has passed its checks but hasn't been applied. */
class BackupContents private constructor(
    val tables: JSONObject,
    val exportedAt: Long,
    val goals: Int,
    val tasks: Int,
    val rules: Int,
    val pauses: Int,
) {
    companion object {
        fun parse(bytes: ByteArray): BackupContents {
            val root = try {
                JSONObject(String(bytes))
            } catch (_: JSONException) {
                throw BackupException("This backup's contents couldn't be read.")
            }
            if (root.optString("format") != "keel-export") throw BackupException("This backup's contents couldn't be read.")
            val version = root.optInt("schemaVersion", -1)
            // Older backups restore fine: columns added since then take their defaults.
            if (version !in 1..Repo.SCHEMA_VERSION) {
                throw BackupException("This backup is from a newer version of Keel (format $version), so update Keel first.")
            }
            val tables = root.optJSONObject("tables") ?: throw BackupException("This backup is incomplete.")
            // Tables added in later versions are simply absent from older backups.
            for (table in Repo.ORIGINAL_TABLES) {
                if (tables.optJSONArray(table) == null) throw BackupException("This backup is incomplete: $table is missing.")
            }
            return BackupContents(
                tables,
                root.optLong("exportedAt"),
                tables.getJSONArray("goal").length(),
                tables.getJSONArray("task").length(),
                tables.getJSONArray("rule").length(),
                tables.getJSONArray("intercept_event").length(),
            )
        }
    }
}

/** All storage. Each call is short, so the pause screen can read from it without noticeable delay. */
class Repo private constructor(private val db: SQLiteDatabase) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    // Goals

    fun addGoal(
        title: String,
        horizon: Horizon,
        wish: String,
        outcome: String,
        obstacle: String,
        plan: String,
        skipCooling: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val cooling = if (skipCooling) null else coolingUntil(horizon, now)
        val id = insert("goal") {
            put("title", title)
            put("horizon", horizon.name)
            put("status", (if (cooling != null) GoalStatus.COOLING else GoalStatus.ACTIVE).name)
            put("wish", wish)
            put("outcome", outcome)
            put("obstacle", obstacle)
            put("plan", plan)
            put("created_at", now)
            put("cooling_until", cooling)
        }
        touchGoal(id, now)
        return id
    }

    fun goals(status: GoalStatus? = null): List<Goal> {
        promoteCooled()
        return if (status == null) {
            list("SELECT * FROM goal") { it.toGoal() }
        } else {
            list("SELECT * FROM goal WHERE status=?", status.name) { it.toGoal() }
        }
    }

    fun goal(id: Long): Goal? = list("SELECT * FROM goal WHERE id=?", id.toString()) { it.toGoal() }.firstOrNull()

    private fun promoteCooled(now: Long = System.currentTimeMillis()) {
        db.execSQL(
            "UPDATE goal SET status=? WHERE status=? AND cooling_until<=?",
            arrayOf<Any>(GoalStatus.ACTIVE.name, GoalStatus.COOLING.name, now),
        )
    }

    fun ranked(sort: GoalSort = GoalSort.MONTHS_RETURNED): List<RankedGoal> =
        rankGoals(goals(GoalStatus.ACTIVE), touches(), zone, sort)

    fun touches(): Map<Long, List<Long>> =
        list("SELECT goal_id, at FROM goal_touch") { it.getLong(0) to it.getLong(1) }
            .groupBy({ it.first }, { it.second })

    fun touchGoal(id: Long, now: Long = System.currentTimeMillis()) {
        db.execSQL("INSERT INTO goal_touch(goal_id, at) VALUES(?,?)", arrayOf<Any>(id, now))
    }

    /** Ends a goal in one tap. The reason is optional and is kept so you can see why later. */
    fun endGoal(id: Long, status: GoalStatus, reason: String?, now: Long = System.currentTimeMillis()) {
        db.execSQL(
            "UPDATE goal SET status=?, ended_at=?, ended_reason=? WHERE id=?",
            arrayOf<Any?>(status.name, now, reason, id),
        )
    }

    // Steps

    fun addStep(goalId: Long, title: String, estimate: Int?, ifThen: String?, source: String? = null): Long {
        val position = single("SELECT COALESCE(MAX(position), -1) + 1 FROM plan_step WHERE goal_id=?", goalId.toString())
        val id = insert("plan_step") {
            put("goal_id", goalId)
            put("title", title)
            put("estimate_min", estimate)
            put("if_then", ifThen)
            put("position", position)
            put("source", source)
        }
        touchGoal(goalId)
        return id
    }

    fun steps(goalId: Long? = null): List<PlanStep> =
        if (goalId == null) {
            list("SELECT * FROM plan_step ORDER BY position") { it.toStep() }
        } else {
            list("SELECT * FROM plan_step WHERE goal_id=? ORDER BY position", goalId.toString()) { it.toStep() }
        }

    fun setStepDone(step: PlanStep, done: Boolean, now: Long = System.currentTimeMillis()) {
        db.execSQL("UPDATE plan_step SET done_at=? WHERE id=?", arrayOf<Any?>(if (done) now else null, step.id))
        if (done) {
            // A finished step also finishes its tasks, so the pause screen never suggests done work.
            db.execSQL("UPDATE task SET done_at=? WHERE step_id=? AND done_at IS NULL", arrayOf<Any>(now, step.id))
            touchGoal(step.goalId, now)
        }
    }

    // Tasks

    fun addTask(
        title: String,
        day: LocalDate,
        stepId: Long? = null,
        source: String? = null,
        estimate: Int? = null,
        now: Long = System.currentTimeMillis(),
    ): Long {
        // A plan step only needs one open task, however many times it's sent to Today.
        if (stepId != null) {
            list("SELECT id FROM task WHERE step_id=? AND done_at IS NULL", stepId.toString()) { it.getLong(0) }
                .firstOrNull()?.let { existing ->
                    moveTask(existing, day)
                    return existing
                }
        }
        return insert("task") {
            put("title", title)
            put("day", day.toString())
            put("step_id", stepId)
            put("source", source)
            put("estimate_min", estimate)
            put("created_at", now)
        }
    }

    fun tasks(day: LocalDate): List<Task> =
        list("SELECT * FROM task WHERE day=? ORDER BY created_at", day.toString()) { it.toTask() }

    /** Unfinished work from earlier days, shown separately so it never silently vanishes. */
    fun overdueTasks(today: LocalDate): List<Task> =
        list("SELECT * FROM task WHERE day<? AND done_at IS NULL ORDER BY day, created_at", today.toString()) { it.toTask() }

    fun moveTask(id: Long, day: LocalDate) {
        db.execSQL("UPDATE task SET day=? WHERE id=?", arrayOf<Any>(day.toString(), id))
    }

    fun deleteTask(id: Long) {
        db.execSQL("DELETE FROM task WHERE id=?", arrayOf<Any>(id))
    }

    /** Finishing a task linked to a plan step also finishes the step and counts as returning to the goal. */
    fun setTaskDone(task: Task, done: Boolean, now: Long = System.currentTimeMillis()) {
        db.execSQL("UPDATE task SET done_at=? WHERE id=?", arrayOf<Any?>(if (done) now else null, task.id))
        val stepId = task.stepId ?: return
        steps().firstOrNull { it.id == stepId }?.let { setStepDone(it, done, now) }
    }

    fun doneDays(): Set<LocalDate> =
        list("SELECT DISTINCT day FROM task WHERE done_at IS NOT NULL") { LocalDate.parse(it.getString(0)) }.toSet()

    fun suggestion(today: LocalDate = LocalDate.now()): PauseSuggestion? =
        pickNext(tasks(today) + overdueTasks(today), ranked(), steps())

    // Rules

    fun rules(now: Long = System.currentTimeMillis()): List<Rule> =
        list("SELECT * FROM rule ORDER BY kind, label") { it.toRule() }.map { rule ->
            val live = effective(rule.state, now)
            if (live != rule.state) saveState(rule.target, live)
            rule.copy(state = live)
        }

    /** The setting in force for a target, or null if it isn't enabled. */
    fun activeRule(target: String, now: Long = System.currentTimeMillis()): RuleSetting? {
        val rule = list("SELECT * FROM rule WHERE target=?", target) { it.toRule() }.firstOrNull() ?: return null
        val live = effective(rule.state, now)
        if (live != rule.state) saveState(target, live)
        return live.current.takeIf { it.enabled }
    }

    /** Adds a rule, which is always a tightening, so it applies at once. Existing ones are kept. */
    fun addRule(target: String, kind: String, label: String, delaySeconds: Int) {
        db.execSQL(
            "INSERT OR IGNORE INTO rule(target, kind, label, enabled, delay_seconds, created_at) VALUES(?,?,?,1,?,?)",
            arrayOf<Any>(target, kind, label, delaySeconds, System.currentTimeMillis()),
        )
    }

    /** Applies the commitment rule, and records the change so the review can show it. */
    fun changeRule(rule: Rule, requested: RuleSetting, now: Long = System.currentTimeMillis()): RuleState {
        val next = requestChange(rule.state, requested, now, zone, rule.createdAt)
        saveState(rule.target, next)
        insert("commitment_change") {
            put("at", now)
            put("target", rule.target)
            put("from_enabled", rule.state.current.enabled)
            put("from_delay", rule.state.current.delaySeconds)
            put("to_enabled", requested.enabled)
            put("to_delay", requested.delaySeconds)
            put("to_mode", requested.mode.name)
            put("to_limit", requested.dailyLimitMinutes)
            put("effective_at", next.pendingAt ?: now)
        }
        return next
    }

    private fun saveState(target: String, state: RuleState) {
        db.execSQL(
            "UPDATE rule SET enabled=?, delay_seconds=?, mode=?, daily_limit_min=?, " +
                "pending_enabled=?, pending_delay=?, pending_mode=?, pending_limit=?, pending_at=? WHERE target=?",
            arrayOf<Any?>(
                if (state.current.enabled) 1 else 0,
                state.current.delaySeconds,
                state.current.mode.name,
                state.current.dailyLimitMinutes,
                state.pending?.let { if (it.enabled) 1 else 0 },
                state.pending?.delaySeconds,
                state.pending?.mode?.name,
                state.pending?.dailyLimitMinutes,
                state.pendingAt,
                target,
            ),
        )
    }

    fun autoBackupOn(): Boolean = setting(AUTO_BACKUP) == "true"

    fun setAutoBackup(on: Boolean) = setSetting(AUTO_BACKUP, on.toString())

    // Plans

    /**
     * Imports a plan from its Markdown. A file name already in Keel updates that plan and keeps its
     * ticks. New plans go to the library, so they don't affect pauses until you switch them on.
     */
    fun importPlan(fileName: String, markdown: String, now: Long = System.currentTimeMillis()): ImportResult {
        val parsed = parsePlan(fileName, markdown)
        db.beginTransaction()
        try {
            val existingGoal = list("SELECT goal_id FROM plan_doc WHERE file_name=?", fileName) { it.getLong(0) }.firstOrNull()
            val goalId = existingGoal ?: insert("goal") {
                put("title", parsed.title)
                put("horizon", Horizon.YEAR.name)
                put("status", GoalStatus.LIBRARY.name)
                put("wish", parsed.title)
                put("outcome", "")
                put("obstacle", "")
                put("plan", "")
                put("created_at", now)
            }
            val current = steps(goalId)
            val merge = mergePlan(
                current.map { ExistingItem(it.id, PlanItem(it.source, it.title, null, false).key, it.doneAt) },
                parsed,
                now,
            )
            merge.removedIds.forEach { removeStep(it) }
            var added = 0
            merge.rows.forEachIndexed { position, row ->
                if (row.existingId == null) {
                    added++
                    insert("plan_step") {
                        put("goal_id", goalId)
                        put("title", row.item.title)
                        put("if_then", row.item.detail)
                        put("position", position)
                        put("done_at", row.doneAt)
                        put("source", row.item.section)
                    }
                } else {
                    db.execSQL(
                        "UPDATE plan_step SET position=?, if_then=?, done_at=? WHERE id=?",
                        arrayOf<Any?>(position, row.item.detail, row.doneAt, row.existingId),
                    )
                }
            }
            if (existingGoal == null) {
                insert("plan_doc") {
                    put("goal_id", goalId)
                    put("file_name", fileName)
                    put("category", guessCategory(fileName, parsed.title).name)
                    put("body", markdown)
                    put("imported_at", now)
                }
            } else {
                db.execSQL("UPDATE plan_doc SET body=?, imported_at=? WHERE goal_id=?", arrayOf<Any>(markdown, now, goalId))
                db.execSQL("UPDATE goal SET title=? WHERE id=?", arrayOf<Any>(parsed.title, goalId))
            }
            touchGoal(goalId, now)
            db.setTransactionSuccessful()
            return ImportResult(parsed.title, existingGoal != null, added, merge.removedIds.size)
        } finally {
            db.endTransaction()
        }
    }

    private fun removeStep(id: Long) {
        // Tasks already on Today stay, but no longer point at the removed step.
        db.execSQL("UPDATE task SET step_id=NULL WHERE step_id=?", arrayOf<Any>(id))
        db.execSQL("DELETE FROM plan_step WHERE id=?", arrayOf<Any>(id))
    }

    fun planDocs(): List<PlanDoc> =
        list("SELECT goal_id, file_name, category, body FROM plan_doc ORDER BY file_name") {
            PlanDoc(it.getLong(0), it.getString(1), PlanCategory.valueOf(it.getString(2)), it.getString(3))
        }

    fun planDoc(goalId: Long): PlanDoc? = list(
        "SELECT goal_id, file_name, category, body FROM plan_doc WHERE goal_id=?",
        goalId.toString(),
    ) { PlanDoc(it.getLong(0), it.getString(1), PlanCategory.valueOf(it.getString(2)), it.getString(3)) }.firstOrNull()

    fun setPlanActive(goalId: Long, active: Boolean) {
        db.execSQL(
            "UPDATE goal SET status=? WHERE id=?",
            arrayOf<Any>((if (active) GoalStatus.ACTIVE else GoalStatus.LIBRARY).name, goalId),
        )
        if (active) touchGoal(goalId)
    }

    fun setPlanCategory(goalId: Long, category: PlanCategory) {
        db.execSQL("UPDATE plan_doc SET category=? WHERE goal_id=?", arrayOf<Any>(category.name, goalId))
    }

    fun deletePlan(goalId: Long) {
        db.beginTransaction()
        try {
            steps(goalId).forEach { removeStep(it.id) }
            db.execSQL("DELETE FROM goal_touch WHERE goal_id=?", arrayOf<Any>(goalId))
            db.execSQL("DELETE FROM plan_doc WHERE goal_id=?", arrayOf<Any>(goalId))
            db.execSQL("DELETE FROM goal WHERE id=?", arrayOf<Any>(goalId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The plan's own Markdown with ticks set. A clean copy clears them all, for sharing. */
    fun exportPlan(goalId: Long, clean: Boolean): String {
        val doc = requireNotNull(planDoc(goalId)) { "That plan no longer exists." }
        val done = if (clean) {
            emptyList()
        } else {
            steps(goalId).filter { it.doneAt != null }.map { PlanItem(it.source, it.title, null, true).key }
        }
        return planWithTicks(doc.body, done)
    }

    // Money goals

    fun addMoneyGoal(title: String, target: Long, now: Long = System.currentTimeMillis()): Long =
        insert("money_goal") {
            put("title", title)
            put("target", target)
            put("created_at", now)
        }

    /** Each update is kept as its own entry, so a goal's history is never overwritten. */
    fun recordMoney(goalId: Long, amount: Long, now: Long = System.currentTimeMillis()) {
        insert("money_entry") {
            put("goal_id", goalId)
            put("at", now)
            put("amount", amount)
        }
    }

    fun moneyGoals(): List<MoneyGoal> = list(
        """SELECT g.id, g.title, g.target,
            (SELECT amount FROM money_entry e WHERE e.goal_id=g.id ORDER BY e.at DESC, e.id DESC LIMIT 1),
            (SELECT at FROM money_entry e WHERE e.goal_id=g.id ORDER BY e.at DESC, e.id DESC LIMIT 1)
           FROM money_goal g ORDER BY g.created_at""",
    ) {
        MoneyGoal(
            it.getLong(0),
            it.getString(1),
            it.getLong(2),
            if (it.isNull(3)) null else it.getLong(3),
            if (it.isNull(4)) null else it.getLong(4),
        )
    }

    fun deleteMoneyGoal(id: Long) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM money_entry WHERE goal_id=?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM money_goal WHERE id=?", arrayOf<Any>(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // Habits

    fun addHabit(title: String, amount: Int, unit: HabitUnit, now: Long = System.currentTimeMillis()): Long =
        insert("habit") {
            put("title", title)
            put("amount", amount)
            put("unit", unit.name)
            put("created_at", now)
        }

    /** Each habit with the days it was ticked, oldest habit first. */
    fun habits(): List<Pair<Habit, Set<LocalDate>>> {
        val ticks = list("SELECT habit_id, day FROM habit_day") { it.getLong(0) to LocalDate.parse(it.getString(1)) }
            .groupBy({ it.first }, { it.second })
        return list("SELECT id, title, amount, unit, created_at FROM habit ORDER BY created_at, id") {
            Habit(it.getLong(0), it.getString(1), it.getInt(2), HabitUnit.valueOf(it.getString(3)), it.getLong(4))
        }.map { habit -> habit to ticks[habit.id].orEmpty().toSet() }
    }

    fun setHabitDone(habitId: Long, day: LocalDate, done: Boolean) {
        if (done) {
            db.execSQL("INSERT OR IGNORE INTO habit_day(habit_id, day) VALUES(?, ?)", arrayOf<Any>(habitId, day.toString()))
        } else {
            db.execSQL("DELETE FROM habit_day WHERE habit_id=? AND day=?", arrayOf<Any>(habitId, day.toString()))
        }
    }

    fun deleteHabit(id: Long) {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM habit_day WHERE habit_id=?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM habit WHERE id=?", arrayOf<Any>(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // Today from plans

    /** Every plan that isn't finished or dropped, by id, for finding deadlines. */
    fun openPlanTitles(): Map<Long, String> = goals()
        .filter { it.status == GoalStatus.ACTIVE || it.status == GoalStatus.LIBRARY }
        .associate { it.id to it.title }

    /** Steps that already have an unfinished task, so they aren't suggested twice. */
    fun openTaskStepIds(): Set<Long> =
        list("SELECT step_id FROM task WHERE done_at IS NULL AND step_id IS NOT NULL") { it.getLong(0) }.toSet()

    // Weekly check-in

    fun saveWeeklyCheck(check: WeeklyCheck, now: Long = System.currentTimeMillis()) {
        db.execSQL(
            "INSERT OR REPLACE INTO weekly_check(week_start, sleep, exercise, mood, note, saved_at) VALUES(?,?,?,?,?,?)",
            arrayOf<Any>(check.week.toString(), check.sleep, check.exercise, check.mood, check.note, now),
        )
    }

    fun weeklyChecks(): List<WeeklyCheck> = list("SELECT week_start, sleep, exercise, mood, note FROM weekly_check") {
        WeeklyCheck(LocalDate.parse(it.getString(0)), it.getInt(1), it.getInt(2), it.getInt(3), it.getString(4))
    }

    // Time budgets

    fun addTimeEnvelope(name: String, minutesPerWeek: Int, apps: List<String>, perMinutes: Int?, amount: Long?, payTo: String?, now: Long = System.currentTimeMillis()) {
        insert("time_envelope") {
            put("name", name)
            put("minutes_per_week", minutesPerWeek)
            put("apps", apps.joinToString(","))
            put("per_minutes", perMinutes)
            put("amount", amount)
            put("pay_to", payTo)
            put("created_at", now)
        }
    }

    fun timeEnvelopes(): List<TimeEnvelope> = list(
        "SELECT id, name, minutes_per_week, apps, per_minutes, amount, pay_to FROM time_envelope ORDER BY created_at",
    ) {
        TimeEnvelope(
            it.getLong(0),
            it.getString(1),
            it.getInt(2),
            it.getString(3).split(",").filter(String::isNotEmpty),
            if (it.isNull(4)) null else it.getInt(4),
            if (it.isNull(5)) null else it.getLong(5),
            if (it.isNull(6)) null else it.getString(6),
        )
    }

    fun deleteTimeEnvelope(id: Long) {
        db.execSQL("DELETE FROM time_envelope WHERE id=?", arrayOf<Any>(id))
    }

    /** Envelopes whose rule was already added to Today this week, so it can't be added twice. */
    fun exchangeRecorded(week: LocalDate): Set<Long> {
        val prefix = "exchange_${week}_"
        return list("SELECT key FROM setting WHERE key LIKE ?", "$prefix%") { it.getString(0).removePrefix(prefix).toLong() }.toSet()
    }

    fun recordExchange(week: LocalDate, envelopeId: Long) = setSetting("exchange_${week}_$envelopeId", "1")

    // Personal experiment

    fun experimentStart(): LocalDate? = setting(EXPERIMENT_START)?.ifEmpty { null }?.let(LocalDate::parse)

    fun startExperiment(start: LocalDate) = setSetting(EXPERIMENT_START, start.toString())

    fun stopExperiment() = setSetting(EXPERIMENT_START, "")

    /** Time in apps with a whole-app rule, for each finished week of the experiment. */
    fun experimentWeeks(start: LocalDate, today: LocalDate): List<ExperimentWeek> {
        val zone = zone
        val targets = rules().filter { it.kind == "app" }.map { it.target }.toSet()
        val weeks = mutableListOf<ExperimentWeek>()
        var week = start
        while (week.plusWeeks(1) <= today) {
            val from = week.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = week.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
            weeks += ExperimentWeek(week, usage(from, to).filter { it.pkg in targets }.sumOf { it.minutes })
            week = week.plusWeeks(1)
        }
        return weeks
    }

    // Sprint

    fun sprintUntil(): Long? = setting(SPRINT_UNTIL)?.toLongOrNull()

    fun setSprintUntil(until: Long) = setSetting(SPRINT_UNTIL, until.toString())

    /** Today's list, as (left, done), which decides whether task-locked apps are open. */
    fun todayTaskCounts(today: LocalDate = LocalDate.now()): Pair<Int, Int> {
        val tasks = tasks(today)
        val left = tasks.count { it.doneAt == null }
        return left to tasks.size - left
    }

    // Pause events

    fun logIntercept(
        target: String,
        choice: Choice,
        reason: String?,
        suggestion: PauseSuggestion?,
        source: String,
        now: Long = System.currentTimeMillis(),
    ) {
        insert("intercept_event") {
            put("at", now)
            put("target", target)
            put("choice", choice.name)
            put("reason", reason)
            put("step_id", suggestion?.stepId)
            put("task_id", suggestion?.taskId)
            put("source", source)
        }
    }

    fun intercepts(from: Long, to: Long): List<InterceptEvent> = list(
        "SELECT at, target, choice, reason FROM intercept_event WHERE at>=? AND at<?",
        from.toString(),
        to.toString(),
    ) { InterceptEvent(it.getLong(0), it.getString(1), Choice.valueOf(it.getString(2)), it.getStringOrNull(3)) }

    fun stepsDone(from: Long, to: Long): Int =
        single("SELECT COUNT(*) FROM plan_step WHERE done_at>=? AND done_at<?", from.toString(), to.toString()).toInt()

    fun tasksDone(from: Long, to: Long): Int =
        single("SELECT COUNT(*) FROM task WHERE done_at>=? AND done_at<?", from.toString(), to.toString()).toInt()

    // Usage

    fun saveSessions(sessions: List<UsageSession>) {
        db.beginTransaction()
        try {
            sessions.forEach {
                db.execSQL(
                    "INSERT OR REPLACE INTO usage_session(pkg, start_at, end_at) VALUES(?,?,?)",
                    arrayOf<Any>(it.pkg, it.start, it.end),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Every session that overlaps the window, for splitting into hours and days. */
    fun sessions(from: Long, to: Long): List<UsageSession> = list(
        "SELECT pkg, start_at, end_at FROM usage_session WHERE end_at > ? AND start_at < ?",
        from.toString(),
        to.toString(),
    ) { UsageSession(it.getString(0), it.getLong(1), it.getLong(2)) }

    fun usage(from: Long, to: Long): List<WeekUsage> = list(
        "SELECT pkg, SUM(end_at - start_at) / 60000 AS minutes FROM usage_session " +
            "WHERE start_at>=? AND start_at<? GROUP BY pkg ORDER BY minutes DESC",
        from.toString(),
        to.toString(),
    ) { WeekUsage(it.getString(0), it.getLong(1)) }

    // Reviews

    fun reviewedWeeks(): Set<LocalDate> =
        list("SELECT week_start FROM review") { LocalDate.parse(it.getString(0)) }.toSet()

    fun saveReview(week: LocalDate, ruleChange: String, note: String, now: Long = System.currentTimeMillis()) {
        db.execSQL(
            "INSERT OR REPLACE INTO review(week_start, saved_at, rule_change, note) VALUES(?,?,?,?)",
            arrayOf<Any>(week.toString(), now, ruleChange, note),
        )
    }

    // Settings

    fun setting(key: String): String? =
        list("SELECT value FROM setting WHERE key=?", key) { it.getString(0) }.firstOrNull()

    fun setSetting(key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO setting(key, value) VALUES(?,?)", arrayOf<Any>(key, value))
    }

    // Export

    fun backupPhrase(): String? = setting(BACKUP_PHRASE)

    fun setBackupPhrase(phrase: String) = setSetting(BACKUP_PHRASE, phrase)

    /**
     * Replaces everything with a backup's contents. It's one transaction, so any failure leaves the
     * current data untouched. The phrase that opened the backup becomes the phrase for future backups.
     */
    fun restore(contents: BackupContents, phrase: String) {
        db.beginTransaction()
        try {
            for (table in TABLES) {
                val columns = list("PRAGMA table_info($table)") { it.getString(it.getColumnIndexOrThrow("name")) }.toSet()
                db.execSQL("DELETE FROM $table")
                val rows = contents.tables.optJSONArray(table) ?: continue
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val values = ContentValues()
                    for (name in row.keys()) {
                        require(name in columns) { "Unknown field $name in $table" }
                        when (val value = row.get(name)) {
                            JSONObject.NULL -> values.putNull(name)
                            is Int -> values.put(name, value.toLong())
                            is Long -> values.put(name, value)
                            is Number -> values.put(name, value.toDouble())
                            is String -> values.put(name, value)
                            else -> throw IllegalArgumentException("Unexpected value for $name in $table")
                        }
                    }
                    check(db.insert(table, 0, values) != -1L) { "Couldn't restore a row in $table" }
                }
            }
            setBackupPhrase(phrase)
            // A backup always comes from a set-up Keel, so there's no need to repeat setup.
            setSetting("onboarded", "true")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Erases all of Keel's data in one transaction, then compacts the file so it isn't left holding old pages. */
    fun deleteEverything() {
        db.beginTransaction()
        try {
            TABLES.forEach { db.execSQL("DELETE FROM $it") }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.execSQL("VACUUM")
    }

    /** Every table as JSON, so your data is never trapped in Keel. The recovery phrase is never included. */
    fun exportJson(now: Long = System.currentTimeMillis(), pretty: Boolean = true): String {
        val root = JSONObject()
        root.put("format", "keel-export")
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("exportedAt", now)
        val tables = JSONObject()
        for (table in TABLES) {
            val rows = JSONArray()
            db.rawQuery("SELECT * FROM $table", emptyArray<String>()).use { c ->
                while (c.moveToNext()) {
                    if (table == "setting" && c.getString(c.getColumnIndexOrThrow("key")) == BACKUP_PHRASE) continue
                    val row = JSONObject()
                    for (i in 0 until c.columnCount) {
                        val name = c.getColumnName(i)
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                            Cursor.FIELD_TYPE_INTEGER -> row.put(name, c.getLong(i))
                            Cursor.FIELD_TYPE_FLOAT -> row.put(name, c.getDouble(i))
                            else -> row.put(name, c.getString(i))
                        }
                    }
                    rows.put(row)
                }
            }
            tables.put(table, rows)
        }
        root.put("tables", tables)
        return if (pretty) root.toString(2) else root.toString()
    }

    // Helpers

    private fun insert(table: String, fill: ContentValues.() -> Unit): Long =
        db.insert(table, 0, ContentValues().apply(fill))

    private fun <T> list(sql: String, vararg args: String, read: (Cursor) -> T): List<T> =
        db.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    private fun single(sql: String, vararg args: String): Long =
        db.rawQuery(sql, args).use { c -> c.moveToFirst(); c.getLong(0) }

    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.stringOrNull(name: String) = getStringOrNull(getColumnIndexOrThrow(name))
    private fun Cursor.longOrNull(name: String): Long? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getLong(i)
    }
    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun Cursor.toGoal() = Goal(
        id = long("id"),
        title = string("title"),
        horizon = Horizon.valueOf(string("horizon")),
        status = GoalStatus.valueOf(string("status")),
        wish = string("wish"),
        outcome = string("outcome"),
        obstacle = string("obstacle"),
        plan = string("plan"),
        createdAt = long("created_at"),
        coolingUntil = longOrNull("cooling_until"),
        endedReason = stringOrNull("ended_reason"),
    )

    private fun Cursor.toStep() = PlanStep(
        id = long("id"),
        goalId = long("goal_id"),
        title = string("title"),
        estimateMinutes = longOrNull("estimate_min")?.toInt(),
        ifThen = stringOrNull("if_then"),
        position = long("position").toInt(),
        doneAt = longOrNull("done_at"),
        source = stringOrNull("source"),
    )

    private fun Cursor.toTask() = Task(
        id = long("id"),
        title = string("title"),
        day = LocalDate.parse(string("day")),
        stepId = longOrNull("step_id"),
        source = stringOrNull("source"),
        estimateMinutes = longOrNull("estimate_min")?.toInt(),
        doneAt = longOrNull("done_at"),
        createdAt = long("created_at"),
    )

    private fun Cursor.toRule(): Rule {
        val pendingEnabled = longOrNull("pending_enabled")
        val pendingDelay = longOrNull("pending_delay")
        val mode = RuleMode.valueOf(string("mode"))
        val limit = long("daily_limit_min").toInt()
        return Rule(
            target = string("target"),
            kind = string("kind"),
            label = string("label"),
            createdAt = long("created_at"),
            state = RuleState(
                current = RuleSetting(long("enabled") == 1L, long("delay_seconds").toInt(), mode, limit),
                pending = if (pendingEnabled != null && pendingDelay != null) {
                    RuleSetting(
                        pendingEnabled == 1L,
                        pendingDelay.toInt(),
                        stringOrNull("pending_mode")?.let(RuleMode::valueOf) ?: mode,
                        longOrNull("pending_limit")?.toInt() ?: limit,
                    )
                } else {
                    null
                },
                pendingAt = longOrNull("pending_at"),
            ),
        )
    }

    companion object {
        const val SCHEMA_VERSION = 5
        private const val EXPERIMENT_START = "experiment_start"
        private const val AUTO_BACKUP = "auto_backup"
        private const val SPRINT_UNTIL = "sprint_until"
        const val LAST_BACKUP = "last_backup_at"
        private const val BACKUP_PHRASE = "backup_phrase"
        val ORIGINAL_TABLES = listOf(
            "goal", "goal_touch", "plan_step", "task", "rule", "commitment_change",
            "intercept_event", "usage_session", "review", "setting",
        )
        val TABLES = ORIGINAL_TABLES + listOf("plan_doc", "money_goal", "money_entry", "weekly_check", "time_envelope", "habit", "habit_day")

        fun open(context: Context, key: String): Repo {
            System.loadLibrary("sqlcipher")
            val file = context.getDatabasePath("keel.db").apply { parentFile?.mkdirs() }
            val db = SQLiteDatabase.openOrCreateDatabase(file.absolutePath, key, null, null)
            migrate(db)
            return Repo(db)
        }

        private fun migrate(db: SQLiteDatabase) {
            if (db.version >= SCHEMA_VERSION) return
            db.beginTransaction()
            try {
                if (db.version < 1) SCHEMA.forEach { db.execSQL(it) }
                if (db.version < 2) MODES_MIGRATION.forEach { db.execSQL(it) }
                if (db.version < 3) PLANS_MIGRATION.forEach { db.execSQL(it) }
                if (db.version < 4) INSIGHTS_MIGRATION.forEach { db.execSQL(it) }
                if (db.version < 5) HABITS_MIGRATION.forEach { db.execSQL(it) }
                db.version = SCHEMA_VERSION
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        private val HABITS_MIGRATION = listOf(
            """CREATE TABLE habit(
                id INTEGER PRIMARY KEY, title TEXT NOT NULL, amount INTEGER NOT NULL, unit TEXT NOT NULL,
                created_at INTEGER NOT NULL)""",
            """CREATE TABLE habit_day(
                habit_id INTEGER NOT NULL REFERENCES habit(id), day TEXT NOT NULL, PRIMARY KEY(habit_id, day))""",
        )

        private val INSIGHTS_MIGRATION = listOf(
            """CREATE TABLE weekly_check(
                week_start TEXT PRIMARY KEY, sleep INTEGER NOT NULL, exercise INTEGER NOT NULL,
                mood INTEGER NOT NULL, note TEXT NOT NULL, saved_at INTEGER NOT NULL)""",
            """CREATE TABLE time_envelope(
                id INTEGER PRIMARY KEY, name TEXT NOT NULL, minutes_per_week INTEGER NOT NULL, apps TEXT NOT NULL,
                per_minutes INTEGER, amount INTEGER, pay_to TEXT, created_at INTEGER NOT NULL)""",
        )

        private val PLANS_MIGRATION = listOf(
            """CREATE TABLE plan_doc(
                goal_id INTEGER PRIMARY KEY REFERENCES goal(id), file_name TEXT NOT NULL UNIQUE,
                category TEXT NOT NULL, body TEXT NOT NULL, imported_at INTEGER NOT NULL)""",
            """CREATE TABLE money_goal(
                id INTEGER PRIMARY KEY, title TEXT NOT NULL, target INTEGER NOT NULL, created_at INTEGER NOT NULL)""",
            """CREATE TABLE money_entry(
                id INTEGER PRIMARY KEY, goal_id INTEGER NOT NULL REFERENCES money_goal(id),
                at INTEGER NOT NULL, amount INTEGER NOT NULL)""",
            "CREATE INDEX money_entry_goal ON money_entry(goal_id, at)",
        )

        /** Existing rules become plain pauses, so upgrading changes nothing until you choose a mode. */
        private val MODES_MIGRATION = listOf(
            "ALTER TABLE rule ADD COLUMN mode TEXT NOT NULL DEFAULT 'PAUSE'",
            "ALTER TABLE rule ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE rule ADD COLUMN daily_limit_min INTEGER NOT NULL DEFAULT 30",
            "ALTER TABLE rule ADD COLUMN pending_mode TEXT",
            "ALTER TABLE rule ADD COLUMN pending_limit INTEGER",
            "ALTER TABLE commitment_change ADD COLUMN to_mode TEXT",
            "ALTER TABLE commitment_change ADD COLUMN to_limit INTEGER",
        )

        private val SCHEMA = listOf(
            """CREATE TABLE goal(
                id INTEGER PRIMARY KEY, title TEXT NOT NULL, horizon TEXT NOT NULL, status TEXT NOT NULL,
                wish TEXT NOT NULL, outcome TEXT NOT NULL, obstacle TEXT NOT NULL, plan TEXT NOT NULL,
                created_at INTEGER NOT NULL, cooling_until INTEGER, ended_at INTEGER, ended_reason TEXT)""",
            "CREATE TABLE goal_touch(goal_id INTEGER NOT NULL REFERENCES goal(id), at INTEGER NOT NULL)",
            "CREATE INDEX goal_touch_goal ON goal_touch(goal_id)",
            """CREATE TABLE plan_step(
                id INTEGER PRIMARY KEY, goal_id INTEGER NOT NULL REFERENCES goal(id), title TEXT NOT NULL,
                estimate_min INTEGER, if_then TEXT, position INTEGER NOT NULL, done_at INTEGER, source TEXT)""",
            "CREATE INDEX plan_step_goal ON plan_step(goal_id, position)",
            """CREATE TABLE task(
                id INTEGER PRIMARY KEY, title TEXT NOT NULL, day TEXT NOT NULL,
                step_id INTEGER REFERENCES plan_step(id), source TEXT, estimate_min INTEGER,
                done_at INTEGER, created_at INTEGER NOT NULL)""",
            "CREATE INDEX task_day ON task(day)",
            """CREATE TABLE rule(
                target TEXT PRIMARY KEY, kind TEXT NOT NULL, label TEXT NOT NULL, enabled INTEGER NOT NULL,
                delay_seconds INTEGER NOT NULL, pending_enabled INTEGER, pending_delay INTEGER, pending_at INTEGER)""",
            """CREATE TABLE commitment_change(
                id INTEGER PRIMARY KEY, at INTEGER NOT NULL, target TEXT NOT NULL, from_enabled INTEGER NOT NULL,
                from_delay INTEGER NOT NULL, to_enabled INTEGER NOT NULL, to_delay INTEGER NOT NULL,
                effective_at INTEGER NOT NULL)""",
            """CREATE TABLE intercept_event(
                id INTEGER PRIMARY KEY, at INTEGER NOT NULL, target TEXT NOT NULL, choice TEXT NOT NULL,
                reason TEXT, step_id INTEGER, task_id INTEGER, source TEXT NOT NULL)""",
            "CREATE INDEX intercept_at ON intercept_event(at)",
            """CREATE TABLE usage_session(
                pkg TEXT NOT NULL, start_at INTEGER NOT NULL, end_at INTEGER NOT NULL, PRIMARY KEY(pkg, start_at))""",
            """CREATE TABLE review(
                week_start TEXT PRIMARY KEY, saved_at INTEGER NOT NULL, rule_change TEXT NOT NULL, note TEXT NOT NULL)""",
            "CREATE TABLE setting(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        )
    }
}
