package dev.keel.spike

import android.content.Context
import android.database.sqlite.SQLiteDatabase as PlatformDb
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDb
import java.io.File
import java.util.Random

/** Compares plain SQLite with SQLCipher on the workload Keel expects: ledger rows, lookups, totals, search. */
object Bench {
    private const val ROWS = 100_000
    private const val NOTES = 20_000
    private const val LOOKUPS = 2_000
    private const val SEARCHES = 200
    private const val PASSPHRASE = "bench passphrase"
    private const val RAW_KEY = "x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'"

    private interface Db {
        fun exec(sql: String)
        fun tx(block: () -> Unit)
        fun inserter(sql: String): (Array<Any>) -> Unit
        fun long(sql: String, vararg args: String): Long
        fun close()
    }

    private class Plain(file: File) : Db {
        val db: PlatformDb = PlatformDb.openOrCreateDatabase(file, null)
        override fun exec(sql: String) = db.execSQL(sql)
        override fun tx(block: () -> Unit) {
            db.beginTransaction()
            try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
        }
        override fun inserter(sql: String): (Array<Any>) -> Unit {
            val s = db.compileStatement(sql)
            return { args ->
                args.forEachIndexed { i, a -> if (a is Long) s.bindLong(i + 1, a) else s.bindString(i + 1, a.toString()) }
                s.executeInsert()
            }
        }
        override fun long(sql: String, vararg args: String): Long =
            db.rawQuery(sql, args).use { it.moveToFirst(); it.getLong(0) }
        override fun close() = db.close()
    }

    private class Cipher(file: File, key: String) : Db {
        val db: CipherDb = CipherDb.openOrCreateDatabase(file.absolutePath, key, null, null)
        override fun exec(sql: String) = db.execSQL(sql)
        override fun tx(block: () -> Unit) {
            db.beginTransaction()
            try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
        }
        override fun inserter(sql: String): (Array<Any>) -> Unit {
            val s = db.compileStatement(sql)
            return { args ->
                args.forEachIndexed { i, a -> if (a is Long) s.bindLong(i + 1, a) else s.bindString(i + 1, a.toString()) }
                s.executeInsert()
            }
        }
        override fun long(sql: String, vararg args: String): Long =
            db.rawQuery(sql, args).use { it.moveToFirst(); it.getLong(0) }
        override fun close() = db.close()
    }

    private inline fun timed(block: () -> Unit): Long {
        val t = System.nanoTime()
        block()
        return (System.nanoTime() - t) / 1_000_000
    }

    private val WORDS = listOf(
        "rent", "groceries", "salary", "insurance", "gym", "savings", "plan", "review",
        "doctor", "budget", "travel", "school", "repair", "invoice", "family", "health",
    )

    private fun workload(context: Context, name: String, db: Db, fts: Boolean) {
        val random = Random(7)
        db.exec("CREATE TABLE tx(id INTEGER PRIMARY KEY, account INTEGER, at INTEGER, amount INTEGER, memo TEXT)")
        db.exec("CREATE INDEX tx_at ON tx(account, at)")
        val insert = timed {
            db.tx {
                val add = db.inserter("INSERT INTO tx(account, at, amount, memo) VALUES(?,?,?,?)")
                repeat(ROWS) { i ->
                    add(arrayOf(i % 5L, 1_700_000_000L + i, random.nextInt(100_000).toLong(), WORDS[i % WORDS.size]))
                }
            }
        }
        val lookup = timed {
            repeat(LOOKUPS) { db.long("SELECT amount FROM tx WHERE id=?", (random.nextInt(ROWS) + 1).toString()) }
        }
        val total = timed { repeat(20) { db.long("SELECT SUM(amount) FROM tx WHERE account=?", (it % 5).toString()) } }
        var ftsLine = "fts=skipped"
        if (fts) {
            db.exec("CREATE VIRTUAL TABLE note USING fts5(body)")
            val ftsInsert = timed {
                db.tx {
                    val add = db.inserter("INSERT INTO note(body) VALUES(?)")
                    repeat(NOTES) {
                        add(arrayOf((0 until 40).joinToString(" ") { WORDS[random.nextInt(WORDS.size)] }))
                    }
                }
            }
            val ftsSearch = timed {
                repeat(SEARCHES) {
                    db.long("SELECT COUNT(*) FROM note WHERE note MATCH ?", "${WORDS[it % WORDS.size]} ${WORDS[(it + 3) % WORDS.size]}")
                }
            }
            ftsLine = "ftsInsertMs=$ftsInsert,ftsSearchMs=$ftsSearch"
        }
        EventLog.log(context, "bench", "$name,insertMs=$insert,lookupMs=$lookup,totalMs=$total,$ftsLine")
    }

    fun run(context: Context) {
        System.loadLibrary("sqlcipher")
        val dir = File(context.cacheDir, "bench").apply { deleteRecursively(); mkdirs() }

        val plainFile = File(dir, "plain.db")
        var plain: Db? = null
        val plainOpen = timed { plain = Plain(plainFile); plain!!.long("SELECT 1") }
        val platformFts = runCatching { plain!!.exec("CREATE VIRTUAL TABLE probe USING fts5(x)"); plain!!.exec("DROP TABLE probe") }.isSuccess
        EventLog.log(context, "bench", "plain_open,ms=$plainOpen,platformFts5=$platformFts")
        workload(context, "plain", plain!!, platformFts)
        plain!!.close()

        for ((name, key) in listOf("cipher_passphrase" to PASSPHRASE, "cipher_rawkey" to RAW_KEY)) {
            val file = File(dir, "$name.db")
            var db: Db? = null
            val create = timed { db = Cipher(file, key); db!!.long("SELECT 1") }
            workload(context, name, db!!, true)
            db!!.close()
            var again: Db? = null
            val reopen = timed { again = Cipher(file, key); again!!.long("SELECT COUNT(*) FROM tx") }
            again!!.close()
            val wrongKeyRejected = runCatching {
                val wrong = Cipher(file, "wrong key")
                try { wrong.long("SELECT COUNT(*) FROM tx") } finally { wrong.close() }
            }.isFailure
            val header = file.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
            val headerHidden = !String(header, Charsets.ISO_8859_1).startsWith("SQLite format 3")
            EventLog.log(
                context,
                "bench_security",
                "$name,createMs=$create,reopenMs=$reopen,wrongKeyRejected=$wrongKeyRejected,headerHidden=$headerHidden,bytes=${file.length()}",
            )
        }
        EventLog.log(context, "bench_done", "plainBytes=${plainFile.length()}")
        dir.deleteRecursively()
    }
}
