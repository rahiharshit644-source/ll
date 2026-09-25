package com.soltini.app.mem0

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject

/**
 * SQLite storage engine for local Mem0 persistence.
 * Implements persistent storage for active, updated, and superseded memories.
 */
class Mem0Database(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {
    companion object {
        private const val TAG = "Mem0Database"
        private const val DB_NAME = "myra_mem0.db"
        private const val DB_VERSION = 1

        private const val TABLE_MEMORIES = "mem0_memories"
        private const val TABLE_META = "mem0_meta"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_MEMORIES (
                id TEXT PRIMARY KEY,
                memory TEXT NOT NULL,
                user_id TEXT NOT NULL,
                agent_id TEXT NOT NULL,
                run_id TEXT,
                categories TEXT,
                metadata TEXT,
                created_at INTEGER,
                updated_at INTEGER,
                last_accessed_at INTEGER,
                access_count INTEGER DEFAULT 0,
                importance REAL DEFAULT 0.7,
                state TEXT DEFAULT 'ACTIVE',
                superseded_by TEXT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_META (
                key TEXT PRIMARY KEY,
                value TEXT
            )
        """.trimIndent())

        db.execSQL("INSERT OR IGNORE INTO $TABLE_META (key, value) VALUES ('deduplication_count', '0')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MEMORIES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_META")
        onCreate(db)
    }

    fun upsertMemory(mem: Mem0Memory): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", mem.id)
            put("memory", mem.memory)
            put("user_id", mem.userId)
            put("agent_id", mem.agentId)
            put("run_id", mem.runId)
            put("categories", mem.categories.joinToString(","))
            put("metadata", JSONObject(mem.metadata).toString())
            put("created_at", mem.createdAt)
            put("updated_at", mem.updatedAt)
            put("last_accessed_at", mem.lastAccessedAt)
            put("access_count", mem.accessCount)
            put("importance", mem.importance)
            put("state", mem.state.name)
            put("superseded_by", mem.supersededBy)
        }
        val res = db.insertWithOnConflict(TABLE_MEMORIES, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        return res != -1L
    }

    fun updateMemory(id: String, newContent: String, category: String? = null, importance: Float? = null): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("memory", newContent)
            put("updated_at", System.currentTimeMillis())
            if (category != null && category.isNotBlank()) {
                put("categories", category)
            }
            if (importance != null) {
                put("importance", importance.coerceIn(0f, 1f))
            }
            put("state", Mem0MemoryState.ACTIVE.name)
        }
        val rows = db.update(TABLE_MEMORIES, cv, "id = ?", arrayOf(id))
        return rows > 0
    }

    fun markSuperseded(oldId: String, newId: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("state", Mem0MemoryState.SUPERSEDED.name)
            put("superseded_by", newId)
            put("updated_at", System.currentTimeMillis())
        }
        db.update(TABLE_MEMORIES, cv, "id = ?", arrayOf(oldId))
    }

    fun deleteMemory(id: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("state", Mem0MemoryState.DELETED.name)
            put("updated_at", System.currentTimeMillis())
        }
        db.update(TABLE_MEMORIES, cv, "id = ?", arrayOf(id))
    }

    fun getMemoryById(id: String): Mem0Memory? {
        val db = readableDatabase
        val cursor = db.query(TABLE_MEMORIES, null, "id = ?", arrayOf(id), null, null, null)
        cursor.use { c ->
            if (c.moveToFirst()) return cursorToMemory(c)
        }
        return null
    }

    fun getActiveMemories(userId: String = "boss", agentId: String = "myra"): List<Mem0Memory> {
        val list = mutableListOf<Mem0Memory>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_MEMORIES,
            null,
            "user_id = ? AND agent_id = ? AND state = 'ACTIVE'",
            arrayOf(userId, agentId),
            null,
            null,
            "importance DESC, updated_at DESC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToMemory(c))
            }
        }
        return list
    }

    fun getAllMemories(userId: String = "boss", agentId: String = "myra"): List<Mem0Memory> {
        val list = mutableListOf<Mem0Memory>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_MEMORIES,
            null,
            "user_id = ? AND agent_id = ? AND state != 'DELETED'",
            arrayOf(userId, agentId),
            null,
            null,
            "updated_at DESC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToMemory(c))
            }
        }
        return list
    }

    fun recordAccess(id: String) {
        try {
            val db = writableDatabase
            db.execSQL(
                "UPDATE $TABLE_MEMORIES SET access_count = access_count + 1, last_accessed_at = ? WHERE id = ?",
                arrayOf(System.currentTimeMillis().toString(), id)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record access: ${e.message}")
        }
    }

    fun incrementDeduplicationCount() {
        try {
            val db = writableDatabase
            db.execSQL("UPDATE $TABLE_META SET value = CAST(CAST(value AS INTEGER) + 1 AS TEXT) WHERE key = 'deduplication_count'")
        } catch (e: Exception) {
            Log.w(TAG, "Failed incrementing deduplication count: ${e.message}")
        }
    }

    fun getDeduplicationCount(): Int {
        val db = readableDatabase
        val cursor = db.query(TABLE_META, arrayOf("value"), "key = 'deduplication_count'", null, null, null, null)
        cursor.use { c ->
            if (c.moveToFirst()) {
                return c.getString(0)?.toIntOrNull() ?: 0
            }
        }
        return 0
    }

    fun getStats(userId: String = "boss", agentId: String = "myra", isCloudConnected: Boolean = false): Mem0Stats {
        val all = getAllMemories(userId, agentId)
        val active = all.filter { it.state == Mem0MemoryState.ACTIVE }
        val superseded = all.filter { it.state == Mem0MemoryState.SUPERSEDED }
        val dedupCount = getDeduplicationCount()

        val categoryCounts = mutableMapOf<String, Int>()
        active.forEach { mem ->
            mem.categories.forEach { cat ->
                val clean = cat.trim().lowercase().ifBlank { "general" }
                categoryCounts[clean] = (categoryCounts[clean] ?: 0) + 1
            }
        }

        return Mem0Stats(
            totalMemories = all.size,
            activeMemories = active.size,
            supersededMemories = superseded.size,
            deduplicationCount = dedupCount,
            categoryCounts = categoryCounts,
            isCloudConnected = isCloudConnected
        )
    }

    fun clearAllMemories() {
        val db = writableDatabase
        db.delete(TABLE_MEMORIES, null, null)
        db.execSQL("UPDATE $TABLE_META SET value = '0' WHERE key = 'deduplication_count'")
    }

    private fun cursorToMemory(c: android.database.Cursor): Mem0Memory {
        val catStr = c.getString(c.getColumnIndexOrThrow("categories")) ?: ""
        val metaStr = c.getString(c.getColumnIndexOrThrow("metadata")) ?: "{}"
        val metaMap = mutableMapOf<String, String>()
        try {
            val jsonObj = JSONObject(metaStr)
            jsonObj.keys().forEach { k -> metaMap[k] = jsonObj.optString(k, "") }
        } catch (_: Exception) {}

        val stateStr = c.getString(c.getColumnIndexOrThrow("state")) ?: "ACTIVE"
        val state = try {
            Mem0MemoryState.valueOf(stateStr)
        } catch (_: Exception) {
            Mem0MemoryState.ACTIVE
        }

        return Mem0Memory(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            memory = c.getString(c.getColumnIndexOrThrow("memory")),
            userId = c.getString(c.getColumnIndexOrThrow("user_id")),
            agentId = c.getString(c.getColumnIndexOrThrow("agent_id")),
            runId = c.getString(c.getColumnIndexOrThrow("run_id")),
            categories = catStr.split(",").map { it.trim() }.filter { it.isNotBlank() },
            metadata = metaMap,
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
            lastAccessedAt = c.getLong(c.getColumnIndexOrThrow("last_accessed_at")),
            accessCount = c.getInt(c.getColumnIndexOrThrow("access_count")),
            importance = c.getFloat(c.getColumnIndexOrThrow("importance")),
            state = state,
            supersededBy = c.getString(c.getColumnIndexOrThrow("superseded_by"))
        )
    }

    fun getAllMeta(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val db = readableDatabase
        val cursor = db.query(TABLE_META, null, null, null, null, null, null)
        cursor.use { c ->
            while (c.moveToNext()) {
                val k = c.getString(c.getColumnIndexOrThrow("key"))
                val v = c.getString(c.getColumnIndexOrThrow("value")) ?: ""
                map[k] = v
            }
        }
        return map
    }

    fun setMeta(key: String, value: String): Boolean {
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("key", key)
                put("value", value)
            }
            db.insertWithOnConflict(TABLE_META, null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving meta: ${e.message}")
            false
        }
    }
}
