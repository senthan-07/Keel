package dev.keel.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import dev.keel.domain.BackupException
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
            for (table in Repo.TABLES) {
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
                val rows = contents.tables.getJSONArray(table)
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
        const val SCHEMA_VERSION = 2
        private const val SPRINT_UNTIL = "sprint_until"
        const val LAST_BACKUP = "last_backup_at"
        private const val BACKUP_PHRASE = "backup_phrase"
        val TABLES = listOf(
            "goal", "goal_touch", "plan_step", "task", "rule", "commitment_change",
            "intercept_event", "usage_session", "review", "setting",
        )

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
                db.version = SCHEMA_VERSION
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

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
