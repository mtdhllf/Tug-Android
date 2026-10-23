package com.github.mtdhllf.tug.core.store.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.github.mtdhllf.tug.core.model.NewTask
import com.github.mtdhllf.tug.core.model.TaskKind
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TaskView
import com.github.mtdhllf.tug.core.store.TaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 原生 SQLite（[SQLiteOpenHelper]）实现的 [TaskStore]。
 *
 * - 不引入 Room / KSP 等第三方依赖，减小体积；
 * - 开发期（v1.0 前）不做数据迁移：版本不一致时直接删表重建；
 * - 所有数据库操作在 IO 线程执行，写入串行化。
 */
class SqliteTaskStore(
    context: Context,
    databaseName: String = DB_NAME,
) : TaskStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val appContext = context.applicationContext
    private val helper = TaskDbHelper(appContext, databaseName)
    private val writeLock = Any()

    private val db: SQLiteDatabase
        get() = helper.writableDatabase

    override suspend fun list(): List<TaskView> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT * FROM $TABLE ORDER BY updatedAt DESC", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toView()) }
        }
    }

    override suspend fun get(id: Long): TaskView? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT * FROM $TABLE WHERE id = ?", arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursor.toView() else null
        }
    }

    override suspend fun add(input: NewTask, saveDir: String, defaultName: String): Long =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("kind", input.kind.name.lowercase())
                put("name", input.fileName?.takeIf { it.isNotBlank() } ?: defaultName)
                put("url", input.url)
                put("saveDir", saveDir)
                put("fileName", input.fileName)
                put("state", TaskState.Queued.name.lowercase())
                put("total", null as Long?)
                put("downloaded", 0L)
                put("uploaded", 0L)
                put("error", null as String?)
                put("extra", "{}")
                put("connections", input.connections ?: 8)
                put("rateLimitBps", null as Long?)
                put("createdAt", now)
                put("updatedAt", now)
                put("payload", json.encodeToString(NewTask.serializer(), input))
            }
            synchronized(writeLock) { db.insertOrThrow(TABLE, null, values) }
        }

    override suspend fun payload(id: Long): NewTask? = withContext(Dispatchers.IO) {
        val raw = db.rawQuery("SELECT payload FROM $TABLE WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.getString(0) else null }
            ?: return@withContext null
        runCatching { json.decodeFromString(NewTask.serializer(), raw) }.getOrNull()
    }

    override suspend fun updateState(
        id: Long,
        state: TaskState,
        error: String?,
        name: String?,
        fileName: String?,
    ): Unit = withContext(Dispatchers.IO) {
        val old = getRow(id) ?: return@withContext
        val safeName = name?.takeIf { it.isNotBlank() && !it.startsWith("任务") } ?: old.name
        val values = ContentValues().apply {
            put("state", state.name.lowercase())
            put("error", error)
            put("name", safeName)
            put("fileName", fileName ?: old.fileName)
            put("updatedAt", System.currentTimeMillis())
        }
        synchronized(writeLock) {
            db.update(TABLE, values, "id = ?", arrayOf(id.toString()))
        }
        Unit
    }

    override suspend fun updateProgress(id: Long, downloaded: Long, total: Long?): Unit =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put("downloaded", downloaded)
                if (total != null) put("total", total)
                put("updatedAt", System.currentTimeMillis())
            }
            synchronized(writeLock) {
                db.update(TABLE, values, "id = ?", arrayOf(id.toString()))
            }
            Unit
        }

    override suspend fun updateLocation(
        id: Long,
        saveDir: String,
        fileName: String?,
        displayName: String?,
    ): Unit = withContext(Dispatchers.IO) {
        val old = getRow(id) ?: return@withContext
        val values = ContentValues().apply {
            put("saveDir", saveDir)
            put("fileName", fileName ?: old.fileName)
            put("name", displayName?.takeIf { it.isNotBlank() } ?: old.name)
            put("updatedAt", System.currentTimeMillis())
        }
        synchronized(writeLock) {
            db.update(TABLE, values, "id = ?", arrayOf(id.toString()))
        }
        Unit
    }

    override suspend fun delete(id: Long): Unit = withContext(Dispatchers.IO) {
        synchronized(writeLock) {
            db.delete(TABLE, "id = ?", arrayOf(id.toString()))
        }
        Unit
    }

    override suspend fun markInterrupted(): Int = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("state", TaskState.Paused.name.lowercase())
            put("updatedAt", System.currentTimeMillis())
        }
        val states = listOf(
            TaskState.Queued,
            TaskState.Downloading,
            TaskState.FetchingMetadata,
            TaskState.Seeding,
        ).map { it.name.lowercase() }
        val placeholders = states.joinToString(",") { "?" }
        synchronized(writeLock) {
            db.update(TABLE, values, "state IN ($placeholders)", states.toTypedArray())
        }
    }

    /** 关闭数据库（测试/宿主退出时调用）。 */
    fun close() {
        helper.close()
    }

    private fun getRow(id: Long): Row? = db.rawQuery(
        "SELECT name, fileName FROM $TABLE WHERE id = ?",
        arrayOf(id.toString()),
    ).use { cursor ->
        if (cursor.moveToFirst()) Row(cursor.getString(0), cursor.getStringOrNull(1)) else null
    }

    private data class Row(val name: String, val fileName: String?)

    private fun Cursor.toView(): TaskView {
        val stateRaw = getString(getColumnIndexOrThrow("state"))
        return TaskView(
            id = getLong(getColumnIndexOrThrow("id")),
            kind = if (getString(getColumnIndexOrThrow("kind")).equals("torrent", true)) {
                TaskKind.Torrent
            } else {
                TaskKind.Http
            },
            name = getString(getColumnIndexOrThrow("name")),
            url = getString(getColumnIndexOrThrow("url")),
            saveDir = getString(getColumnIndexOrThrow("saveDir")),
            fileName = getStringOrNull(getColumnIndexOrThrow("fileName")),
            state = TaskState.entries.firstOrNull { it.name.lowercase() == stateRaw.lowercase() }
                ?: TaskState.Failed,
            total = getLongOrNull(getColumnIndexOrThrow("total")),
            downloaded = getLong(getColumnIndexOrThrow("downloaded")),
            uploaded = getLong(getColumnIndexOrThrow("uploaded")),
            error = getStringOrNull(getColumnIndexOrThrow("error")),
            connections = getInt(getColumnIndexOrThrow("connections")),
            rateLimitBps = getLongOrNull(getColumnIndexOrThrow("rateLimitBps")),
            createdAt = getLong(getColumnIndexOrThrow("createdAt")),
            updatedAt = getLong(getColumnIndexOrThrow("updatedAt")),
        )
    }

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.getLongOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private class TaskDbHelper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL,
                    name TEXT NOT NULL,
                    url TEXT NOT NULL,
                    saveDir TEXT NOT NULL,
                    fileName TEXT,
                    state TEXT NOT NULL,
                    total INTEGER,
                    downloaded INTEGER NOT NULL DEFAULT 0,
                    uploaded INTEGER NOT NULL DEFAULT 0,
                    error TEXT,
                    extra TEXT NOT NULL DEFAULT '{}',
                    connections INTEGER NOT NULL DEFAULT 8,
                    rateLimitBps INTEGER,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    payload TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            recreate(db)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            recreate(db)
        }

        /** 开发期约定：不迁移，直接清表重建。 */
        private fun recreate(db: SQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    private companion object {
        const val TABLE = "tasks"
        const val DB_NAME = "tug_tasks.db"
        const val DB_VERSION = 1
    }
}
