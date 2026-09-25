package com.soltini.app.memory2

import android.content.Context
import android.util.Log
import com.soltini.app.util.AppLogger
import com.soltini.app.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * RetrievedMemoryContext
 *
 * Scored, filtered, and ranked contextual memory returned to MYRA Orchestrator.
 */
data class RetrievedMemoryContext(
    val relevantItems: List<MemoryItem>,
    val workingSummary: String,
    val sessionSummary: String,
    val experienceRecommendation: Memory2Database.ExperienceRecord? = null,
    val formattedPromptBlock: String
)

/**
 * Memory2Engine
 *
 * Unified contextual, persistent, and intelligent memory system (Memory 2.0).
 * Implements 5 structured tiers:
 *   - Working Memory
 *   - Session Memory
 *   - Long-Term Memory
 *   - Knowledge Memory
 *   - Skill / Experience Memory
 */
class Memory2Engine private constructor(
    private val context: Context,
    private val appSettings: AppSettings
) {

    companion object {
        private const val TAG = "Memory2Engine"
        private const val EXTRACT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-lite:generateContent"

        @Volatile
        private var instance: Memory2Engine? = null

        fun getInstance(context: Context, appSettings: AppSettings): Memory2Engine =
            instance ?: synchronized(this) {
                instance ?: Memory2Engine(context.applicationContext, appSettings).also { instance = it }
            }
    }

    val working = WorkingMemory()
    val session = SessionMemory()
    val db = Memory2Database(context)
    val mem0 = com.soltini.app.mem0.Mem0MemoryEngine.getInstance(context, appSettings)

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ─── Selective Retrieval (Mem0 & Memory 2.0 Integration) ─────────────────

    /**
     * Selectively retrieves relevant memory for a given user request.
     *
     * Integrates Mem0 engine (https://github.com/mem0ai/mem0):
     * 1. Extracts request intent and semantic tokens.
     * 2. Queries Mem0 for active, non-duplicate, non-superseded user memories.
     * 3. Discards irrelevant memories to prevent prompt pollution.
     * 4. Queries Working Memory, Session Memory, Knowledge, and Experience patterns.
     * 5. Injects only high-relevance, fresh memories into MYRA's context.
     */
    suspend fun retrieveContext(userRequest: String, maxItems: Int = 8): RetrievedMemoryContext =
        withContext(Dispatchers.IO) {
            val queryTokens = userRequest.lowercase()
                .split(" ", "_", "-", ",", "?", "!")
                .filter { it.length > 2 }

            // 1. Mem0 Relevance-Filtered Memories
            val mem0Results = mem0.searchRelevantMemories(
                query = userRequest,
                maxItems = maxItems,
                minRelevanceThreshold = 0.22f
            )

            val mem0Items = mem0Results.map { res ->
                MemoryItem(
                    id = res.memory.id,
                    category = MemoryCategory.LONG_TERM,
                    key = res.memory.categories.firstOrNull() ?: "mem0_fact",
                    content = res.memory.memory,
                    importance = res.memory.importance,
                    relevance = res.relevanceScore,
                    createdAt = res.memory.createdAt,
                    lastAccessedAt = res.memory.lastAccessedAt,
                    accessCount = res.memory.accessCount,
                    metadata = res.memory.metadata + mapOf("source" to "mem0")
                )
            }

            // 2. Long-Term memories from DB (if not already covered by Mem0)
            val allLongTerm = db.getAllLongTermMemories()
            val scoredLongTerm = mutableListOf<Pair<MemoryItem, Float>>()

            val seenMem0Texts = mem0Results.map { it.memory.memory.lowercase().trim() }.toSet()

            for (item in allLongTerm) {
                // Deduplicate against Mem0 results
                if (seenMem0Texts.any { it.contains(item.content.lowercase().trim()) || item.content.lowercase().trim().contains(it) }) {
                    continue
                }

                var relevance = 0.1f
                val contentLower = item.content.lowercase()
                val keyLower = item.key.lowercase()
                for (token in queryTokens) {
                    if (contentLower.contains(token)) relevance += 0.35f
                    if (keyLower.contains(token)) relevance += 0.45f
                }
                relevance = relevance.coerceIn(0f, 1.0f)

                // Only include if relevant or very high importance
                if (relevance >= 0.28f || item.importance >= 0.88f) {
                    val finalScore = item.computeScore(relevance)
                    scoredLongTerm.add(Pair(item, finalScore))
                    db.recordLongTermAccess(item.id)
                }
            }

            // 3. Knowledge memories scored
            val allKnowledge = db.getAllKnowledge()
            val scoredKnowledge = mutableListOf<Pair<MemoryItem, Float>>()
            for (k in allKnowledge) {
                var relevance = 0.1f
                val textLower = "${k.title} ${k.content} ${k.tags.joinToString(" ")}".lowercase()
                for (token in queryTokens) {
                    if (textLower.contains(token)) relevance += 0.4f
                }
                relevance = relevance.coerceIn(0f, 1.0f)
                if (relevance >= 0.3f) {
                    val item = MemoryItem(
                        id = k.id,
                        category = MemoryCategory.KNOWLEDGE,
                        key = k.title,
                        content = "[Doc: ${k.title}] ${k.content}",
                        importance = 0.7f,
                        relevance = relevance
                    )
                    scoredKnowledge.add(Pair(item, item.computeScore(relevance)))
                }
            }

            // Combine and sort top-K
            val topItems = (mem0Items.map { Pair(it, it.computeScore(it.relevance)) } + scoredLongTerm + scoredKnowledge)
                .sortedByDescending { it.second }
                .map { it.first }
                .take(maxItems)

            // 4. Experience Memory pattern
            val expRec = db.getBestExperienceForTask(userRequest.take(40))

            // 5. Summaries from Working and Session
            val workingSummary = working.getActiveWorkingContext().joinToString("; ") { "${it.key}=${it.content}" }
            val sessionSummary = session.getSessionSummary()

            // 6. Build clean prompt block
            val sb = StringBuilder()
            if (mem0Results.isNotEmpty()) {
                sb.append("### RELEVANT USER MEMORIES (Mem0 Engine):\n")
                mem0Results.forEach { res ->
                    val cat = res.memory.categories.firstOrNull()?.uppercase() ?: "PREFERENCE"
                    sb.append("• [$cat] ${res.memory.memory}\n")
                }
            } else if (topItems.isNotEmpty()) {
                sb.append("### RELEVANT USER & KNOWLEDGE MEMORY:\n")
                topItems.forEach { mem ->
                    sb.append("• [${mem.category.name}] ${mem.content}\n")
                }
            }

            if (sessionSummary.isNotBlank()) {
                sb.append("\n### ACTIVE SESSION CONTEXT:\n$sessionSummary\n")
            }
            if (workingSummary.isNotBlank()) {
                sb.append("\n### WORKING VARIABLES:\n$workingSummary\n")
            }
            if (expRec != null && expRec.successRate >= 0.7f) {
                sb.append("\n### EXPERIENCE PATTERN (Learned):\nFor task \"${expRec.taskType}\", previous proven tool sequence: ${expRec.toolSequence.joinToString(" -> ")} (Success rate: ${(expRec.successRate * 100).toInt()}%)\n")
            }

            RetrievedMemoryContext(
                relevantItems = topItems,
                workingSummary = workingSummary,
                sessionSummary = sessionSummary,
                experienceRecommendation = expRec,
                formattedPromptBlock = sb.toString().trim()
            )
        }

    // ─── Post-Execution Learning (Memory Evaluation & Storage) ────────────────

    /**
     * Evaluates task outcome and conversation to decide what should be stored.
     *
     * Decides:
     * - Is this information important?
     * - Will it be useful later?
     * - Is it stable enough to remember?
     * - Did the user explicitly ask to remember?
     */
    suspend fun evaluateAndStore(
        goal: String,
        outcomeSummary: String,
        toolSequence: List<String>,
        isSuccess: Boolean,
        userExplicitStatement: String? = null
    ) = withContext(Dispatchers.IO) {
        // 1. Store in Session Memory
        session.recordCompletedTask(goal, outcomeSummary, isSuccess, toolSequence)
        session.recordTopic(goal.take(30))

        // 2. Record Skill / Experience Pattern if tools were used
        if (toolSequence.isNotEmpty()) {
            val taskType = simplifyTaskType(goal)
            db.recordExperience(taskType, toolSequence, isSuccess)
            Log.d(TAG, "Recorded experience pattern for taskType=$taskType with tools=$toolSequence (success=$isSuccess)")
        }

        // 3. Check for explicit user "Remember that..." statements
        if (!userExplicitStatement.isNullOrBlank()) {
            val lower = userExplicitStatement.lowercase()
            if (lower.contains("remember") || lower.contains("yaad rakh") || lower.contains("yaad rakhna") || lower.contains("hamesha")) {
                val cleanFact = userExplicitStatement
                    .replace(Regex("(?i)^(myra|soltini|hey myra),?\\s*"), "")
                    .replace(Regex("(?i)^(please|kripya)\\s*"), "")
                    .replace(Regex("(?i)^(remember that|yaad rakhna ki|yaad rakho ki)\\s*"), "")
                    .trim()
                if (cleanFact.isNotBlank()) {
                    // Route directly through Mem0 Engine for duplicate & conflict resolution
                    val res = mem0.addOrUpdateMemory(
                        fact = cleanFact,
                        category = "explicit_preference"
                    )
                    Log.i(TAG, "Mem0 resolved explicit memory [${res.action}]: ${res.memory} (reason: ${res.reason})")

                    saveLongTerm(
                        key = "user_explicit_preference",
                        fact = res.memory.ifBlank { cleanFact },
                        importance = 0.95f,
                        tags = listOf("explicit_preference", "user_rule", "mem0")
                    )
                    return@withContext
                }
            }
        }

        // 4. Mem0 Conversation Extraction (Evaluates stable facts & preferences)
        if (isSuccess && outcomeSummary.isNotBlank()) {
            try {
                val resolutions = mem0.processConversationTurn(goal, outcomeSummary)
                for (res in resolutions) {
                    if (res.action == com.soltini.app.mem0.Mem0Action.ADD || res.action == com.soltini.app.mem0.Mem0Action.UPDATE) {
                        saveLongTerm(
                            key = res.category,
                            fact = res.memory,
                            importance = 0.80f,
                            tags = listOf("mem0_learned", res.category)
                        )
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Mem0 conversation processing failed: ${e.message}")
                Log.w(TAG, "Mem0 conversation processing skipped: ${e.message}")
            }
        }

        // 5. Autonomous fallback extraction via Gemini Flash Lite if needed
        val apiKey = appSettings.effectiveApiKey()
        if (apiKey.isNotBlank() && isSuccess && outcomeSummary.isNotBlank()) {
            extractStablePreferences(goal, outcomeSummary, apiKey)
        }
    }

    /**
     * Saves a long-term memory item into persistent SQLite and syncs with Mem0.
     */
    fun saveLongTerm(
        key: String,
        fact: String,
        importance: Float = 0.7f,
        type: MemoryType = MemoryType.FACT,
        confidence: Float = 0.85f,
        userConfirmed: Boolean = false,
        sensitivityLevel: String = "NORMAL",
        tags: List<String> = emptyList()
    ): Boolean {
        val item = MemoryItem(
            type = type,
            category = MemoryCategory.LONG_TERM,
            key = key,
            content = fact,
            confidence = confidence,
            importance = importance,
            userConfirmed = userConfirmed,
            sensitivityLevel = sensitivityLevel,
            metadata = mapOf("tags" to tags.joinToString(","))
        )
        val dbSaved = db.saveLongTermMemory(item)

        // Ensure Mem0 knows about this fact and manages deduplication
        if (!tags.contains("mem0")) {
            engineScope.launch {
                try {
                    mem0.addOrUpdateMemory(
                        fact = fact,
                        category = key
                    )
                } catch (_: Exception) {}
            }
        }

        return dbSaved
    }

    fun deleteMemory(id: String): Boolean {
        val dbDeleted = db.deleteLongTermMemory(id)
        try {
            mem0.db.deleteMemory(id)
        } catch (_: Exception) {}
        return dbDeleted
    }

    fun updateMemory(id: String, newContent: String, newType: MemoryType? = null, newConfirmed: Boolean = true): Boolean {
        val dbUpdated = db.updateLongTermMemory(id, newContent, newType = newType)
        try {
            mem0.db.updateMemory(id, newContent, category = newType?.name?.lowercase())
        } catch (_: Exception) {}
        return dbUpdated
    }

    fun deleteMemoriesMatching(query: String): Int {
        var count = 0
        val matches = db.searchLongTermMemories(query)
        matches.forEach { item ->
            if (deleteMemory(item.id)) count++
        }
        val mem0Matches = mem0.db.getActiveMemories().filter { it.memory.contains(query, ignoreCase = true) }
        mem0Matches.forEach { m ->
            mem0.db.deleteMemory(m.id)
            count++
        }
        return count
    }

    fun getAllMemoriesUnified(): List<MemoryItem> {
        val items = mutableListOf<MemoryItem>()
        val seenIds = mutableSetOf<String>()
        val seenTexts = mutableSetOf<String>()

        // 1. Long term memories from Memory2Database
        val dbItems = db.getAllLongTermMemories()
        for (item in dbItems) {
            items.add(item)
            seenIds.add(item.id)
            seenTexts.add(item.content.trim().lowercase())
        }

        // 2. Active memories from Mem0
        val mem0Items = mem0.db.getActiveMemories()
        for (m in mem0Items) {
            val text = m.memory.trim().lowercase()
            if (!seenIds.contains(m.id) && !seenTexts.contains(text)) {
                items.add(
                    MemoryItem(
                        id = m.id,
                        type = MemoryType.fromString(m.categories.firstOrNull()),
                        category = MemoryCategory.LONG_TERM,
                        key = m.categories.firstOrNull() ?: "mem0_fact",
                        content = m.memory,
                        confidence = 0.90f,
                        importance = m.importance,
                        createdAt = m.createdAt,
                        updatedAt = m.updatedAt,
                        lastAccessedAt = m.lastAccessedAt,
                        accessCount = m.accessCount,
                        userConfirmed = true,
                        metadata = m.metadata + mapOf("source" to "mem0")
                    )
                )
                seenTexts.add(text)
            }
        }
        return items.sortedByDescending { it.updatedAt }
    }

    fun searchAllMemories(query: String): List<MemoryItem> {
        if (query.isBlank()) return getAllMemoriesUnified()
        val all = getAllMemoriesUnified()
        val q = query.lowercase().trim()
        return all.filter {
            it.content.lowercase().contains(q) ||
            it.key.lowercase().contains(q) ||
            it.type.name.lowercase().contains(q)
        }
    }

    fun searchMemoriesUnified(query: String): List<MemoryItem> = searchAllMemories(query)

    fun saveUserCorrection(correctionText: String, taskContext: String? = null): MemoryItem {
        val item = MemoryItem(
            type = MemoryType.CORRECTION,
            category = MemoryCategory.LONG_TERM,
            key = "user_correction",
            content = correctionText.trim(),
            confidence = 0.99f,
            importance = 0.95f,
            userConfirmed = true,
            metadata = if (taskContext != null) mapOf("task_context" to taskContext) else emptyMap()
        )
        db.saveLongTermMemory(item)
        engineScope.launch {
            try {
                mem0.addOrUpdateMemory(
                    fact = "User Rule / Correction: ${correctionText.trim()}",
                    category = "user_correction"
                )
            } catch (_: Exception) {}
        }
        return item
    }

    fun saveExplicitMemory(
        text: String,
        type: MemoryType = MemoryType.PREFERENCE,
        confirmed: Boolean = true
    ): MemoryItem {
        val item = MemoryItem(
            type = type,
            category = MemoryCategory.LONG_TERM,
            key = type.name.lowercase(),
            content = text.trim(),
            confidence = 0.98f,
            importance = 0.90f,
            userConfirmed = confirmed,
            metadata = mapOf("explicit" to "true")
        )
        db.saveLongTermMemory(item)
        engineScope.launch {
            try {
                mem0.addOrUpdateMemory(
                    fact = text.trim(),
                    category = type.name.lowercase()
                )
            } catch (_: Exception) {}
        }
        return item
    }

    fun getMemoriesSummaryForUser(): String {
        val memories = getAllMemoriesUnified()
        if (memories.isEmpty()) {
            return "Boss, abhi meri memory me koi saved facts ya preferences nahi hain."
        }

        val sb = StringBuilder()
        sb.append("Boss, mujhe aapke baare mein yeh sab yaad hai:\n\n")

        val grouped = memories.groupBy { it.type }
        grouped.forEach { (type, items) ->
            val header = when (type) {
                MemoryType.PREFERENCE -> "🌟 Aapki Preferences:"
                MemoryType.CORRECTION -> "📌 Aapke Rules & Corrections:"
                MemoryType.PROJECT -> "🚀 Aapke Projects:"
                MemoryType.USER_PROFILE -> "👤 User Profile:"
                MemoryType.FACT -> "💡 Facts & Info:"
                MemoryType.ROUTINE -> "⏰ Daily Routines:"
                else -> "📝 General Notes:"
            }
            sb.append(header).append("\n")
            items.take(5).forEach { item ->
                sb.append("• ").append(item.content).append("\n")
            }
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    /**
     * Saves a knowledge entry into Knowledge Memory.
     */
    fun saveKnowledge(title: String, content: String, source: String = "user_input", tags: List<String> = emptyList()) {
        db.saveKnowledge(
            Memory2Database.KnowledgeEntry(
                id = java.util.UUID.randomUUID().toString(),
                title = title,
                content = content,
                source = source,
                tags = tags,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun simplifyTaskType(goal: String): String {
        val lower = goal.lowercase()
        return when {
            lower.contains("weather") || lower.contains("mausam") -> "weather_lookup"
            lower.contains("crypto") || lower.contains("bitcoin") -> "crypto_lookup"
            lower.contains("whatsapp") -> "whatsapp_action"
            lower.contains("instagram") || lower.contains("insta") -> "instagram_action"
            lower.contains("youtube") -> "youtube_action"
            lower.contains("torch") || lower.contains("flashlight") -> "torch_action"
            lower.contains("volume") || lower.contains("sound") -> "volume_action"
            lower.contains("alarm") || lower.contains("timer") -> "clock_action"
            lower.contains("screenshot") -> "screenshot_action"
            lower.contains("screen") || lower.contains("dekho") -> "screen_vision"
            lower.contains("form") || lower.contains("fill") || lower.contains("shop") -> "screen_operator"
            else -> lower.split(" ").take(3).joinToString("_")
        }
    }

    private fun extractStablePreferences(goal: String, outcome: String, apiKey: String) {
        try {
            val prompt = """Evaluate if this task and result contains STABLE, LONG-TERM personal preferences or rules about the user.
Do NOT remember temporary information like weather forecasts, one-time flight numbers, or fleeting questions.
DO remember: dietary preferences, favorite teams, preferred contact names, recurring habit timings, communication style.

Task: $goal
Outcome: $outcome

If nothing stable to remember, return [].
If something stable should be remembered, return a JSON array of strings (max 15 words each).
Output JSON array ONLY:"""

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
            }.toString()

            val req = Request.Builder()
                .url("$EXTRACT_URL?key=$apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string() ?: ""
                AppLogger.w(TAG, "Autonomous memory extraction HTTP ${resp.code}: $errBody")
                return
            }
            val resBody = resp.body?.string() ?: return
            val candidates = JSONObject(resBody).optJSONArray("candidates")
            val text = candidates?.optJSONObject(0)?.optJSONObject("content")
                ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: return

            val clean = text.trim().removePrefix("```json").removeSuffix("```").trim()
            val arr = JSONArray(clean)
            for (i in 0 until arr.length()) {
                val fact = arr.getString(i)
                if (fact.isNotBlank()) {
                    saveLongTerm(
                        key = "learned_preference",
                        fact = fact,
                        importance = 0.75f,
                        tags = listOf("learned", "preference")
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Autonomous memory evaluation error: ${e.message}")
            Log.d(TAG, "Autonomous memory evaluation skipped: ${e.message}")
        }
    }

    fun clearAllMemories() {
        db.clearAllMemories()
        mem0.clearAllMemories()
        session.clear()
        working.clear()
    }
}

