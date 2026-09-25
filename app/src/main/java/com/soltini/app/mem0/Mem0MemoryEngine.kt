package com.soltini.app.mem0

import android.content.Context
import android.util.Log
import com.soltini.app.util.AppLogger
import com.soltini.app.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mem0 Advanced Memory Engine for MYRA.
 *
 * Implements the full architecture of Mem0 (https://github.com/mem0ai/mem0):
 *   - Intelligent Conflict Resolution (ADD, UPDATE, DELETE, NOOP)
 *   - Automatic Outdated Memory Superseding
 *   - High-Precision Duplicate Prevention
 *   - Selective Relevance-Filtered Context Retrieval
 *   - Local SQLite persistence + optional Mem0 Platform Cloud Sync
 */
class Mem0MemoryEngine private constructor(
    private val context: Context,
    private val appSettings: AppSettings
) {
    companion object {
        private const val TAG = "Mem0MemoryEngine"

        @Volatile
        private var instance: Mem0MemoryEngine? = null

        fun getInstance(context: Context, appSettings: AppSettings): Mem0MemoryEngine =
            instance ?: synchronized(this) {
                instance ?: Mem0MemoryEngine(context.applicationContext, appSettings).also { instance = it }
            }
    }

    val db = Mem0Database(context)
    val apiClient = Mem0ApiClient { appSettings.mem0ApiKey }
    val deduplicator = Mem0Deduplicator { appSettings.effectiveApiKey() }

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── Core Memory Ingestion & Deduplication Loop ──────────────────────────

    /**
     * Ingests a new candidate statement or fact into Mem0.
     * Evaluates whether to ADD, UPDATE an outdated memory, DELETE a negated one, or NOOP a duplicate.
     */
    suspend fun addOrUpdateMemory(
        fact: String,
        userId: String = appSettings.mem0UserId,
        agentId: String = appSettings.mem0AgentId,
        category: String? = null,
        runId: String? = null
    ): Mem0Resolution = withContext(Dispatchers.IO) {
        val clean = fact.trim()
        if (clean.isBlank()) {
            return@withContext Mem0Resolution(Mem0Action.NOOP, "", reason = "Blank text")
        }

        val existing = db.getActiveMemories(userId, agentId)
        val resolution = deduplicator.resolveMemory(clean, existing)

        when (resolution.action) {
            Mem0Action.NOOP -> {
                db.incrementDeduplicationCount()
                if (resolution.targetId != null) {
                    db.recordAccess(resolution.targetId)
                }
                Log.i(TAG, "Mem0 [NOOP] Duplicate prevented: \"$clean\" (Matches: ${resolution.targetId})")
            }
            Mem0Action.UPDATE -> {
                val targetId = resolution.targetId
                if (targetId != null) {
                    db.updateMemory(
                        id = targetId,
                        newContent = resolution.memory,
                        category = category ?: resolution.category,
                        importance = 0.85f
                    )
                    Log.i(TAG, "Mem0 [UPDATE] Updated outdated memory #$targetId to: \"${resolution.memory}\"")
                } else {
                    // Fallback to ADD if no target ID
                    val newMem = Mem0Memory(
                        memory = resolution.memory,
                        userId = userId,
                        agentId = agentId,
                        runId = runId,
                        categories = listOf(category ?: resolution.category),
                        importance = 0.80f
                    )
                    db.upsertMemory(newMem)
                }
            }
            Mem0Action.DELETE -> {
                val targetId = resolution.targetId
                if (targetId != null) {
                    db.deleteMemory(targetId)
                    Log.i(TAG, "Mem0 [DELETE] Removed contradicted memory #$targetId")
                }
            }
            Mem0Action.ADD -> {
                val newMem = Mem0Memory(
                    memory = resolution.memory,
                    userId = userId,
                    agentId = agentId,
                    runId = runId,
                    categories = listOf(category ?: resolution.category),
                    importance = 0.75f
                )
                db.upsertMemory(newMem)
                Log.i(TAG, "Mem0 [ADD] Stored fresh memory: \"${resolution.memory}\"")
            }
        }

        // Optional asynchronous sync to Mem0 Cloud
        if (appSettings.isMem0CloudSyncEnabled && apiClient.isConfigured() && resolution.action != Mem0Action.NOOP) {
            engineScope.launch {
                try {
                    apiClient.addMemories(
                        messages = listOf("user" to clean),
                        userId = userId,
                        agentId = agentId
                    )
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Mem0 cloud sync failed: ${e.message}")
                    Log.w(TAG, "Mem0 cloud sync skipped: ${e.message}")
                }
            }
        }

        return@withContext resolution
    }

    /**
     * Analyzes conversation turn and extracts any stable user preferences or facts.
     */
    suspend fun processConversationTurn(
        userMessage: String,
        assistantResponse: String,
        userId: String = appSettings.mem0UserId,
        agentId: String = appSettings.mem0AgentId
    ): List<Mem0Resolution> = withContext(Dispatchers.IO) {
        val existing = db.getActiveMemories(userId, agentId)
        val extracted = deduplicator.extractFromConversation(userMessage, assistantResponse, existing)

        val applied = mutableListOf<Mem0Resolution>()
        for (res in extracted) {
            when (res.action) {
                Mem0Action.NOOP -> {
                    db.incrementDeduplicationCount()
                    applied.add(res)
                }
                Mem0Action.UPDATE -> {
                    if (res.targetId != null) {
                        db.updateMemory(res.targetId, res.memory, res.category, 0.85f)
                        applied.add(res)
                    }
                }
                Mem0Action.DELETE -> {
                    if (res.targetId != null) {
                        db.deleteMemory(res.targetId)
                        applied.add(res)
                    }
                }
                Mem0Action.ADD -> {
                    val newMem = Mem0Memory(
                        memory = res.memory,
                        userId = userId,
                        agentId = agentId,
                        categories = listOf(res.category),
                        importance = 0.75f
                    )
                    db.upsertMemory(newMem)
                    applied.add(res)
                }
            }
        }
        return@withContext applied
    }

    // ─── Selective Context Retrieval (Mem0 Query Ranking) ────────────────────

    /**
     * Selectively retrieves ONLY the relevant, non-duplicate, active memories for a given request.
     * Prevents context pollution by strictly discarding irrelevant memories.
     */
    suspend fun searchRelevantMemories(
        query: String,
        maxItems: Int = 6,
        minRelevanceThreshold: Float = 0.25f,
        userId: String = appSettings.mem0UserId,
        agentId: String = appSettings.mem0AgentId
    ): List<Mem0SearchResult> = withContext(Dispatchers.IO) {
        val activeMemories = db.getActiveMemories(userId, agentId)
        if (activeMemories.isEmpty()) return@withContext emptyList()

        val queryTokens = query.lowercase()
            .split(" ", "_", "-", ",", "?", "!", ".")
            .map { it.trim() }
            .filter { it.length > 2 }

        val scoredList = mutableListOf<Mem0SearchResult>()

        for (mem in activeMemories) {
            val memLower = mem.memory.lowercase()
            val catLower = mem.categories.joinToString(" ").lowercase()
            var relevance = 0.05f

            for (token in queryTokens) {
                if (memLower.contains(token)) {
                    relevance += 0.40f
                }
                if (catLower.contains(token)) {
                    relevance += 0.25f
                }
            }
            relevance = relevance.coerceIn(0f, 1.0f)

            // Include if relevance meets threshold or if marked very high importance
            if (relevance >= minRelevanceThreshold || mem.importance >= 0.90f) {
                val composite = mem.computeScore(relevance)
                scoredList.add(Mem0SearchResult(mem, relevance, composite))
                db.recordAccess(mem.id)
            }
        }

        // Deduplicate in result set (ensure no two retrieved memories have identical content)
        val deduplicated = mutableListOf<Mem0SearchResult>()
        val seenTexts = mutableSetOf<String>()

        scoredList.sortedByDescending { it.compositeScore }
            .forEach { item ->
                val normalized = item.memory.memory.lowercase().replace(Regex("[^a-z0-9]"), "")
                if (!seenTexts.contains(normalized)) {
                    seenTexts.add(normalized)
                    deduplicated.add(item)
                }
            }

        return@withContext deduplicated.take(maxItems)
    }

    /**
     * Formats retrieved Mem0 memories into a concise markdown section for system prompts.
     */
    suspend fun formatContextForPrompt(query: String, maxItems: Int = 6): String {
        val results = searchRelevantMemories(query, maxItems)
        if (results.isEmpty()) return ""

        val sb = StringBuilder("### MEM0 RELEVANT CONTEXT (Personal Facts & Preferences):\n")
        results.forEach { res ->
            val cat = res.memory.categories.firstOrNull()?.uppercase() ?: "GENERAL"
            sb.append("• [$cat] ${res.memory.memory}\n")
        }
        return sb.toString().trim()
    }

    fun getStats(): Mem0Stats {
        val isCloud = apiClient.isConfigured() && appSettings.isMem0CloudSyncEnabled
        return db.getStats(appSettings.mem0UserId, appSettings.mem0AgentId, isCloudConnected = isCloud)
    }

    fun clearAllMemories() {
        db.clearAllMemories()
    }
}
