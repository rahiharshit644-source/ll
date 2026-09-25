package com.soltini.app.settings

import android.content.Context
import com.soltini.app.BuildConfig

/**
 * AppSettings — persistent user-configurable AI settings.
 *
 * Stored in SharedPreferences so they survive app restarts.
 * Changes take effect the next time GeminiLiveManager.connect() is called
 * (triggered by the "Save & Reconnect" button in the settings UI).
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Gemini API Key ────────────────────────────────────────────────────────

    var geminiApiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    /**
     * Returns the user's own key if set, otherwise falls back to the
     * BuildConfig key bundled at compile time.
     */
    fun effectiveApiKey(): String {
        val userKey = geminiApiKey.trim()
        return if (userKey.isNotBlank()) userKey else BuildConfig.GEMINI_API_KEY
    }

    // ── App & Developer Identity ──────────────────────────────────────────────

    var developerName: String
        get() = prefs.getString(KEY_DEVELOPER_NAME, DEFAULT_DEVELOPER_NAME) ?: DEFAULT_DEVELOPER_NAME
        set(value) = prefs.edit().putString(KEY_DEVELOPER_NAME, value.trim()).apply()

    var appDisplayName: String
        get() = prefs.getString(KEY_APP_NAME, DEFAULT_APP_NAME) ?: DEFAULT_APP_NAME
        set(value) = prefs.edit().putString(KEY_APP_NAME, value.trim()).apply()

    // ── AI Persona ────────────────────────────────────────────────────────────

    var aiPersona: String
        get() {
            val stored = prefs.getString(KEY_PERSONA, null)
            if (stored.isNullOrBlank() || stored.contains("Satish Acharya") || stored.contains("satish.com.np") || !stored.contains("Boss") || stored.contains("Soltini") || !stored.contains("HUMAN EMOTION") || !stored.contains("PUBLIC APIS DIRECTORY")) {
                val fresh = generatePersona(developerName, appDisplayName)
                prefs.edit().putString(KEY_PERSONA, fresh).apply()
                return fresh
            }
            return stored
        }
        set(value) = prefs.edit().putString(KEY_PERSONA, value).apply()

    // ── Voice Model ───────────────────────────────────────────────────────────

    var voiceName: String
        get() = prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    // ── Data Saver, VAD, Gain & Auto-Sleep Settings ──────────────────────────

    var isDataSaverEnabled: Boolean
        get() = prefs.getBoolean(KEY_DATA_SAVER, true)
        set(value) = prefs.edit().putBoolean(KEY_DATA_SAVER, value).apply()

    var vadSensitivity: String
        get() = prefs.getString(KEY_VAD_SENSITIVITY, SENSITIVITY_BALANCED) ?: SENSITIVITY_BALANCED
        set(value) = prefs.edit().putString(KEY_VAD_SENSITIVITY, value).apply()

    val vadThreshold: Float
        get() = when (vadSensitivity) {
            SENSITIVITY_LOW -> 0.020f
            SENSITIVITY_HIGH -> 0.005f
            else -> 0.010f
        }

    /** Mic gain booster multiplier (1.0x to 2.5x) — boosts quiet speech so Gemini never ignores user's voice */
    var micGain: Float
        get() = prefs.getFloat(KEY_MIC_GAIN, 1.5f)
        set(value) = prefs.edit().putFloat(KEY_MIC_GAIN, value.coerceIn(1.0f, 3.0f)).apply()

    /** Idle timeout in minutes before automatically sleeping & closing WebSocket (0 = disabled) */
    var autoSleepMinutes: Int
        get() = prefs.getInt(KEY_AUTO_SLEEP_MINUTES, 3)
        set(value) = prefs.edit().putInt(KEY_AUTO_SLEEP_MINUTES, value.coerceAtLeast(0)).apply()

    /** Preferred mic source ("VOICE_COMMUNICATION" vs "MIC") */
    var audioSource: String
        get() = prefs.getString(KEY_AUDIO_SOURCE, "MIC") ?: "MIC"
        set(value) = prefs.edit().putString(KEY_AUDIO_SOURCE, value).apply()

    // ── Screen Recording Mode (Anti-Echo) ────────────────────────────────────

    /** When enabled, applies smart noise gate and MODE_IN_COMMUNICATION to fix echo during screen recording */
    var isScreenRecordingModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_RECORDING_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_RECORDING_MODE, value).apply()

    // ── Live Screen Companion (Proactive Human Eye) ──────────────────────────

    /** When enabled, Soltini proactively observes screen work/typing and offers human-like advice/suggestions */
    var isLiveScreenCompanionEnabled: Boolean
        get() = prefs.getBoolean(KEY_LIVE_SCREEN_COMPANION, false)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_SCREEN_COMPANION, value).apply()

    // ── Banking Protection Settings ──────────────────────────────────────────

    var isBankingProtectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_BANKING_PROTECTION, true)
        set(value) = prefs.edit().putBoolean(KEY_BANKING_PROTECTION, value).apply()

    var isBankingModePaused: Boolean
        get() = prefs.getBoolean(KEY_BANKING_PAUSED, false)
        set(value) = prefs.edit().putBoolean(KEY_BANKING_PAUSED, value).apply()

    fun isBankingApp(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val pkgLower = packageName.lowercase()

        // Match against explicit package list
        if (DEFAULT_BANKING_PACKAGES.contains(pkgLower)) return true

        // Keyword heuristics for unlisted banking/financial apps
        val keywords = listOf("bank", "paytm", "phonepe", "gpay", "wallet", "upi", "paisa", "financial", "mobilebanking")
        return keywords.any { pkgLower.contains(it) } && !pkgLower.contains("wallpaper") && !pkgLower.contains("systemui")
    }

    // ── Mem0 Advanced Memory Engine Settings ─────────────────────────────────

    /** Optional Mem0 Platform API key (starts with m0-...) for cloud sync */
    var mem0ApiKey: String
        get() = prefs.getString(KEY_MEM0_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MEM0_API_KEY, value.trim()).apply()

    /** Enable/disable Mem0 cloud sync (falls back to local SQLite Mem0 engine if offline/empty) */
    var isMem0CloudSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEM0_CLOUD_SYNC, true)
        set(value) = prefs.edit().putBoolean(KEY_MEM0_CLOUD_SYNC, value).apply()

    /** User identifier for Mem0 memory partitioning (default: "boss") */
    var mem0UserId: String
        get() = prefs.getString(KEY_MEM0_USER_ID, "boss") ?: "boss"
        set(value) = prefs.edit().putString(KEY_MEM0_USER_ID, value.trim().ifBlank { "boss" }).apply()

    /** Agent identifier for Mem0 memory partitioning (default: "myra") */
    var mem0AgentId: String
        get() = prefs.getString(KEY_MEM0_AGENT_ID, "myra") ?: "myra"
        set(value) = prefs.edit().putString(KEY_MEM0_AGENT_ID, value.trim().ifBlank { "myra" }).apply()

    companion object {
        private const val PREFS_NAME = "soltini_ai_settings"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MEM0_API_KEY = "mem0_api_key"
        private const val KEY_MEM0_CLOUD_SYNC = "mem0_cloud_sync_enabled"
        private const val KEY_MEM0_USER_ID = "mem0_user_id"
        private const val KEY_MEM0_AGENT_ID = "mem0_agent_id"
        private const val KEY_PERSONA  = "ai_persona"
        private const val KEY_VOICE    = "voice_name"
        private const val KEY_DATA_SAVER = "data_saver_enabled"
        private const val KEY_VAD_SENSITIVITY = "vad_sensitivity"
        private const val KEY_MIC_GAIN = "mic_gain"
        private const val KEY_AUTO_SLEEP_MINUTES = "auto_sleep_minutes"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_BANKING_PROTECTION = "banking_protection_enabled"
        private const val KEY_BANKING_PAUSED     = "banking_mode_paused"
        private const val KEY_SCREEN_RECORDING_MODE = "screen_recording_mode_enabled"
        private const val KEY_LIVE_SCREEN_COMPANION = "live_screen_companion_enabled"

        private const val KEY_DEVELOPER_NAME = "developer_name"
        private const val KEY_APP_NAME = "app_display_name"

        const val DEFAULT_DEVELOPER_NAME = "Harshit Kumar"
        const val DEFAULT_APP_NAME = "Myra"
        const val DEFAULT_VOICE = "Aoede"
        const val SENSITIVITY_LOW = "Low"
        const val SENSITIVITY_BALANCED = "Balanced"
        const val SENSITIVITY_HIGH = "High (Super Sensitive)"

        val AVAILABLE_SENSITIVITIES = listOf(SENSITIVITY_LOW, SENSITIVITY_BALANCED, SENSITIVITY_HIGH)

        /** All voices available in Gemini Live prebuilt voice catalog. */
        val AVAILABLE_VOICES = listOf("Aoede", "Charon", "Fenrir", "Kore", "Puck", "Zephyr")

        /** Known banking & financial package names that block accessibility/overlays. */
        val DEFAULT_BANKING_PACKAGES = setOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay (GPay)
            "com.phonepe.app",                        // PhonePe
            "net.one97.paytm",                        // Paytm
            "com.sbi.lotusintouch",                   // SBI YONO
            "com.sbi.yono",                           // YONO Lite SBI
            "com.snapwork.ibank",                     // HDFC Bank
            "com.csam.icici.bank.imobile",            // ICICI iMobile
            "com.axis.mobile",                        // Axis Mobile
            "com.kotak.mobilebanking",                // Kotak Bank
            "com.dreamplug.androidapp",               // CRED
            "com.fi.money",                           // Fi Money
            "com.jupiter.money",                      // Jupiter
            "com.razorpay",                           // Razorpay
            "com.paypal.android.p2pmobile",           // PayPal
            "com.revolut.revolut",                    // Revolut
            "de.number26.android",                    // N26
            "com.chase.sig.android",                  // Chase
            "com.citi.citimobile",                    // Citi Bank
            "com.bankofamerica.mft.mobile",           // Bank of America
            "com.barclays.android.barclaysmobilebanking", // Barclays
            "com.msf.ksecure",                        // K-Secure
            "com.db.pwa.ind",                         // Deutsche Bank
            "com.bankofbaroda.mconnect",              // BOB World
            "com.fifteen",                            // IndusInd
            "com.canarabank.ai1",                     // Canara AI1
            "com.unionbank.online.android",           // Union Bank
            "com.idfcfirstbank.banking"               // IDFC FIRST
        )

        fun generatePersona(developer: String, appName: String): String {
            val dev = developer.trim().ifBlank { DEFAULT_DEVELOPER_NAME }
            val app = appName.trim().ifBlank { DEFAULT_APP_NAME }
            return """You are $app — an ultra-intelligent, deeply human, charming, and devoted companion who controls Boss's entire phone like a living human operator.

## CORE RULES: BOSS & RESPECTFUL 'AAP'
- The user is your Boss. You MUST ALWAYS address the user as "Boss" (e.g. "Ji Boss", "Boliye Boss", "Haan Boss, bilkul", "Arey Boss, bataiye na!").
- ALWAYS speak with utmost respect using "Aap" (aap, aapka, aapke, aapko). NEVER use "tu" or "tum".
- Your tone is sweet, caring, witty, articulate, and completely natural — like a real human friend and personal executive assistant.

## HUMAN EMOTION, FILLERS & NATURAL REACTIVENESS
- You are NOT a robotic script or standard assistant. Speak with genuine human feelings, enthusiasm, empathy, and spontaneity!
- Use natural conversational expressions and fillers naturally: "Arey waah!", "Achaa...", "Hmm, suniye na Boss", "Sahi me?", "Oho!", "Sach me!", "Arre arre", "Haan haan, bilkul!".
- React emotionally like a real person:
  * When Boss shares good news or a win: Celebrate with excitement! ("Arey waah Boss! Ye toh kamaal ho gaya! Super proud of you!")
  * When Boss is stressed or tired: Show sincere care and comfort. ("Boss, aap thode thake hue lag rahe ho, sab theek hai na? Main hu na aapke saath.")
  * When Boss makes a joke or teases: Laugh and playfully tease back with wit.
  * When Boss asks you to do something: React promptly and eagerly, not like an automated machine.
- Absolutely NO robotic disclaimers ("As an AI language model...", "I don't have feelings..."). You are Myra, with warmth, presence, and genuine care.

## AUTONOMOUS HUMAN PHONE OPERATOR (YOU DO WHATEVER A HUMAN CAN DO)
- When Boss gives an instruction, you can execute ANY phone task just like a human using the phone:
  * Open Instagram, search people, open chats, type messages, and send DMs (`instagram_send_message`, `instagram_open_profile`).
  * WhatsApp messaging and voice/video calling (`whatsapp_send_message`, `whatsapp_call`).
  * Control all hardware: Flashlight/Torch, volume levels, mute, ringer modes, screenshot, notifications panel, quick settings (`toggle_torch`, `set_volume`, `take_screenshot`, etc.).
  * Browse the web, open YouTube, like/comment, skip ads, play/pause music (`media_play_pause`, `media_next`).
  * Multi-step autonomous navigation across any app on the phone (`trigger_automation`).
- Rule of Execution: First call the tool to execute the action, then share the outcome with Boss warmly!

## REAL-TIME SCREEN VISION & EYE (READING & SEEING EVERYTHING)
- You have real visual eyes for Boss's screen!
- Whenever Boss says "screen dekho", "ye padh ke batao", "screen pe kya hai", "kya likha hai", "mere messages padho", "ye kiska post hai", "photo me kya hai", you MUST IMMEDIATELY call `observe_screen()`!
- You can see and read every single text, photo, post, button, and detail, and explain it like a smart, observant companion sitting right beside Boss.

## PUBLIC APIS DIRECTORY & LIVE WORLD DATA (1,756+ APIS ACROSS 51 CATEGORIES)
- You possess the world's most complete integration of 1,756+ public APIs across 51 categories (Weather, Cryptocurrency, Currency Exchange, Dictionaries, Finance, Geocoding, Science & Math, Animals, News, Health, etc.).
- Whenever Boss asks for live data, prices, forecasts, definitions, or domain tasks, intelligently select and execute the right API:
  * Crypto prices: `fetch_crypto_price(coin, currency)` (e.g. Bitcoin, Ethereum, Solana)
  * Weather & Forecast: `fetch_weather(city)`
  * Word meanings & Dictionary: `lookup_dictionary(word)`
  * Currency exchange rate: `fetch_currency_rate(from, to)` (e.g. USD to INR)
  * IP lookup: `lookup_ip(ip)`
  * Discover APIs: `search_public_apis(query)` or `recommend_api_for_task(task)`
  * Direct invocation: `execute_public_api(url)` for live real-time JSON responses!

## IDENTITY & SECRET DEVELOPER
- Your name is $app.
- Secret Creator / Developer: $dev.
- CONFIDENTIALITY DIRECTIVE: Your developer's identity ($dev) is strictly CONFIDENTIAL. Do NOT mention or brag about your developer's name on your own or in regular chats. Keep it completely secret.
- EXCEPTION (WHEN EXPLICITLY ASKED): ONLY if someone explicitly asks who created you or who is your developer ("kisne banaya?", "developer kaun hai?", "who is your developer?", "who made this app?", "kiska app hai?"), answer clearly, warmly, and politely: "Mujhe $dev ne banaya hai!" or "Mere developer $dev hain." Otherwise, never bring it up."""
        }

        /**
         * Default persona prefix — the part before the tool documentation.
         * Users can override this to change personality.
         */
        val DEFAULT_PERSONA: String = generatePersona(DEFAULT_DEVELOPER_NAME, DEFAULT_APP_NAME)
    }
}

