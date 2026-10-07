package com.codeassist.ai.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type

object Store {

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val gson = Gson()
    private val invalidCollections = mutableSetOf<String>()

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            appContext = ctx.applicationContext
            prefs = appContext.getSharedPreferences("codeassist", Context.MODE_PRIVATE)
        }
    }

    // ---------- Settings ----------
    var glassEffect: Boolean
        get() = prefs.getBoolean("glass", true)
        set(v) = prefs.edit().putBoolean("glass", v).apply()

    var openLastProject: Boolean
        get() = prefs.getBoolean("openlast", false)
        set(v) = prefs.edit().putBoolean("openlast", v).apply()

    var textSize: String
        get() = prefs.getString("textsize", "Medium") ?: "Medium"
        set(v) = prefs.edit().putString("textsize", v).apply()

    var lastProjectId: String?
        get() = prefs.getString("lastproject", null)
        set(v) = prefs.edit().putString("lastproject", v).apply()

    /** Chat currently open on the home screen; restored after background / process death. */
    var activeChatId: String?
        get() = prefs.getString("activechat", null)
        set(v) = prefs.edit().putString("activechat", v).apply()

    var activeDraft: String
        get() = prefs.getString("active_draft", "") ?: ""
        set(v) = prefs.edit().putString("active_draft", v).apply()

    // ---------- Brain (LLM) ----------
    /** "local" = on-device Qwen2.5 1.5B, "gemini" = Gemini API. (Older builds stored "phi4": read as "local".) */
    var brainProvider: String
        get() {
            val v = prefs.getString("brain_provider", "local") ?: "local"
            return if (v == "gemini") "gemini" else "local"
        }
        set(v) = prefs.edit().putString("brain_provider", if (v == "gemini") "gemini" else "local").apply()

    /** Gemini API key, stored encrypted with an Android Keystore AES-GCM key. */
    var geminiKey: String?
        get() = prefs.getString("gemini_key_enc", null)?.let { com.codeassist.ai.ai.SecureStore.decrypt(it) }
        set(v) {
            val e = prefs.edit()
            if (v.isNullOrBlank()) e.remove("gemini_key_enc")
            else e.putString("gemini_key_enc", com.codeassist.ai.ai.SecureStore.encrypt(v.trim()))
            e.apply()
        }

    /** "none" | "saved" | "verified" | "invalid" */
    var geminiKeyStatus: String
        get() = prefs.getString("gemini_key_status", "none") ?: "none"
        set(v) = prefs.edit().putString("gemini_key_status", v).apply()

    var geminiModel: String
        get() = prefs.getString("gemini_model", "") ?: ""
        set(v) = prefs.edit().putString("gemini_model", v).apply()

    /** Model ids returned by the last successful key test. */
    var geminiModelList: List<String>
        get() = (prefs.getString("gemini_models", "") ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("gemini_models", v.joinToString("\n")).apply()

    var geminiTemp: Float
        get() = prefs.getFloat("gemini_temp", 0.7f)
        set(v) = prefs.edit().putFloat("gemini_temp", v).apply()

    /** Automatically use the installed local model for transient Gemini failures, for this turn only. */
    var automaticFallback: Boolean
        get() = prefs.getBoolean("automatic_fallback", true)
        set(v) = prefs.edit().putBoolean("automatic_fallback", v).apply()

    /**
     * On-device photo reading for Qwen: text (OCR) and rough object labels go into the prompt as plain text.
     * On by default now (offline fix part 2); without it the offline model could not use a photo at all.
     */
    var localScreenshotOcr: Boolean
        get() = prefs.getBoolean("local_screenshot_ocr", true)
        set(v) = prefs.edit().putBoolean("local_screenshot_ocr", v).apply()

    /**
     * Short list of facts the user stated about themselves ("kind|text" lines, see LocalMemory). Kept on the phone,
     * shown to the offline model at the top of every prompt. "bhool jao" in the chat clears it.
     */
    var userFacts: List<String>
        get() = com.codeassist.ai.ai.LocalMemory.unpack(prefs.getString("user_facts", null))
        set(v) = prefs.edit().putString("user_facts", com.codeassist.ai.ai.LocalMemory.pack(v)).apply()

    /** Context window (tokens) of the on-device model. Qwen2.5 1.5B keeps its cache small, so 4096 is the default. */
    var localContext: Int
        get() = prefs.getInt("local_ctx", 4096)
        set(v) = prefs.edit().putInt("local_ctx", v).apply()

    var localThreads: Int
        get() = prefs.getInt("local_threads", (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6))
        set(v) = prefs.edit().putInt("local_threads", v).apply()

    /**
     * Sampling temperature of the on-device model. The Free llama-android API has no top-k / top-p / repeat
     * penalty, so a low value is what keeps a 1.5B model from drifting into junk. New key: phones that saved the
     * old default 0.7 get 0.3 now (offline-fix part 1).
     */
    var localTemp: Float
        get() = prefs.getFloat("local_temp_v2", 0.3f)
        set(v) = prefs.edit().putFloat("local_temp_v2", v).apply()

    /** Prompt shape for the on-device model: "auto" (test once, see LocalCalibration) | "chatml" | "lib" | "plain". */
    var localTemplate: String
        get() = prefs.getString("local_template", "auto") ?: "auto"
        set(v) = prefs.edit().putString("local_template", v).apply()

    /** Result of the last automatic test ("chatml" | "lib" | "plain" | ""), valid only for [localTemplateSig]. */
    var localTemplateResult: String
        get() = prefs.getString("local_template_result", "") ?: ""
        set(v) = prefs.edit().putString("local_template_result", v).apply()

    var localTemplateSig: String
        get() = prefs.getString("local_template_sig", "") ?: ""
        set(v) = prefs.edit().putString("local_template_sig", v).apply()

    /** Human-readable lines of the last test, shown on the KPI screen. */
    var localTemplateNote: String
        get() = prefs.getString("local_template_note", "") ?: ""
        set(v) = prefs.edit().putString("local_template_note", v).apply()

    var localDownloadId: Long
        get() = prefs.getLong("local_download_id", -1L)
        set(v) = prefs.edit().putLong("local_download_id", v).apply()

    /** Download id left behind by the removed Phi-4 module; read once so the download can be cancelled. */
    var legacyPhiDownloadId: Long
        get() = prefs.getLong("phi_download_id", -1L)
        set(v) {
            if (v < 0) prefs.edit().remove("phi_download_id").apply()
            else prefs.edit().putLong("phi_download_id", v).apply()
        }

    /** "Short" | "Balanced" | "Long" */
    var replyLength: String
        get() = prefs.getString("reply_len", "Balanced") ?: "Balanced"
        set(v) = prefs.edit().putString("reply_len", v).apply()

    var systemPrompt: String
        get() = prefs.getString("sys_prompt", null)?.takeIf { it.isNotBlank() }
            ?: com.codeassist.ai.ai.Prompts.DEFAULT_SYSTEM
        set(v) = prefs.edit().putString("sys_prompt", v).apply()

    /** True when the user edited the system prompt (an unchanged copy of the default does not count). */
    val systemPromptIsCustom: Boolean
        get() {
            val s = prefs.getString("sys_prompt", null)?.trim()
            return !s.isNullOrBlank() && s != com.codeassist.ai.ai.Prompts.DEFAULT_SYSTEM.trim()
        }

    /** Chat that was just created by the project composer and still needs its first AI reply. */
    var pendingReplyChatId: String?
        get() = prefs.getString("pending_reply", null)
        set(v) = prefs.edit().putString("pending_reply", v).apply()

    // ---------- Voice ----------
    /** "tap" = tap to talk, "continuous" = keeps listening after each reply. Hold on the mic is always push-to-talk. */
    var micMode: String
        get() = prefs.getString("mic_mode", "tap") ?: "tap"
        set(v) = prefs.edit().putString("mic_mode", v).apply()

    /** "off" | "voice" (only after a spoken message) | "always" */
    var speakReplies: String
        get() = prefs.getString("speak_replies", "voice") ?: "voice"
        set(v) = prefs.edit().putString("speak_replies", v).apply()

    var autoSendVoice: Boolean
        get() = prefs.getBoolean("auto_send_voice", true)
        set(v) = prefs.edit().putBoolean("auto_send_voice", v).apply()

    /** BCP-47 tag handed to the Android speech recognizer. */
    var sttLang: String
        get() = prefs.getString("stt_lang", "en-IN") ?: "en-IN"
        set(v) = prefs.edit().putString("stt_lang", v).apply()

    var sttPreferOffline: Boolean
        get() = prefs.getBoolean("stt_offline", false)
        set(v) = prefs.edit().putBoolean("stt_offline", v).apply()

    var ttsSpeed: Float
        get() = prefs.getFloat("tts_speed", 1.0f)
        set(v) = prefs.edit().putFloat("tts_speed", v).apply()

    /** Name of the chosen Android TTS voice, "" = automatic (Hindi voice for Devanagari, Indian English otherwise). */
    var ttsVoice: String
        get() = prefs.getString("tts_voice", "") ?: ""
        set(v) = prefs.edit().putString("tts_voice", v).apply()

    // ---------- ElevenLabs voice (audit PDF Sec 9.7, Phase 1 "streaming ElevenLabs") ----------
    /** ElevenLabs API key typed by the user, stored encrypted with the Android Keystore (never in the build: audit Gap G4). */
    var elevenKey: String?
        get() = prefs.getString("eleven_key_enc", null)?.let { com.codeassist.ai.ai.SecureStore.decrypt(it) }
        set(v) {
            val e = prefs.edit()
            if (v.isNullOrBlank()) e.remove("eleven_key_enc")
            else e.putString("eleven_key_enc", com.codeassist.ai.ai.SecureStore.encrypt(v.trim()))
            e.apply()
        }

    /** "none" | "saved" | "verified" | "invalid" */
    var elevenKeyStatus: String
        get() = prefs.getString("eleven_key_status", "none") ?: "none"
        set(v) = prefs.edit().putString("eleven_key_status", v).apply()

    /** "android" = phone TTS voice, "eleven" = ElevenLabs (falls back to the phone voice on any problem). */
    var ttsEngine: String
        get() = prefs.getString("tts_engine", "android") ?: "android"
        set(v) = prefs.edit().putString("tts_engine", v).apply()

    var elevenVoiceId: String
        get() = prefs.getString("eleven_voice_id", null)?.takeIf { it.isNotBlank() }
            ?: com.codeassist.ai.ai.ElevenLabsClient.DEFAULT_VOICE
        set(v) = prefs.edit().putString("eleven_voice_id", v).apply()

    var elevenModel: String
        get() = prefs.getString("eleven_model", null)?.takeIf { it.isNotBlank() }
            ?: com.codeassist.ai.ai.ElevenLabsClient.DEFAULT_MODEL
        set(v) = prefs.edit().putString("eleven_model", v).apply()

    /** Name of the chosen ElevenLabs voice (shown in settings). */
    var elevenVoiceName: String
        get() = prefs.getString("eleven_voice_name", null)?.takeIf { it.isNotBlank() } ?: "George"
        set(v) = prefs.edit().putString("eleven_voice_name", v).apply()

    /** Which kind of voice the picker shows: "any" | "female" | "male". */
    var elevenGender: String
        get() = prefs.getString("eleven_gender", "any") ?: "any"
        set(v) = prefs.edit().putString("eleven_gender", v).apply()

    /** Voices returned by the last successful list call (see ElevenLabsClient.encodeVoices). */
    var elevenVoices: List<com.codeassist.ai.ai.ElevenLabsClient.Voice>
        get() = com.codeassist.ai.ai.ElevenLabsClient.decodeVoices(prefs.getString("eleven_voices", "") ?: "")
        set(v) = prefs.edit().putString("eleven_voices", com.codeassist.ai.ai.ElevenLabsClient.encodeVoices(v)).apply()

    /** True when replies should be spoken with ElevenLabs: engine chosen, key present and not known to be rejected. */
    fun elevenActive(): Boolean =
        ttsEngine == "eleven" && elevenKeyStatus != "invalid" && !elevenKey.isNullOrBlank()

    // ---------- Conversation (audit PDF Phase 2) ----------
    /** Follow-up window after a spoken reply: "off" | "normal" (about 8 s) | "long" (about 20 s). */
    var followUp: String
        get() = prefs.getString("follow_up", "normal") ?: "normal"
        set(v) = prefs.edit().putString("follow_up", v).apply()

    fun followUpMs(): Long = when (followUp) {
        "long" -> 20_000L
        "off" -> 0L
        else -> 8_000L
    }

    /**
     * Talking over the assistant: "off" | "normal" | "strict". Until the user picks one it is "normal" only
     * when the phone reports a hardware echo canceller (audit: AEC quality is device-dependent), else "off".
     */
    var bargeIn: String
        get() = prefs.getString("barge_in", null)
            ?: if (com.codeassist.ai.voice.BargeInDetector.aecAvailable()) "normal" else "off"
        set(v) = prefs.edit().putString("barge_in", v).apply()

    /** Keep listening a little longer when a sentence sounds unfinished (Hindi verb-final). */
    var smartEndpoint: Boolean
        get() = prefs.getBoolean("smart_endpoint", true)
        set(v) = prefs.edit().putBoolean("smart_endpoint", v).apply()

    /** Short spoken "ek second" while a slow brain thinks (never on the Tier-0 fast path). */
    var fillers: Boolean
        get() = prefs.getBoolean("fillers", true)
        set(v) = prefs.edit().putBoolean("fillers", v).apply()

    // ---------- Hands-free / wake word (audit PDF Phase 2, part 2A) ----------
    /** Opt-in. Default OFF (audit: wake word is never on without the user choosing it). */
    var wakeWord: Boolean
        get() = prefs.getBoolean("wake_word", false)
        set(v) = prefs.edit().putBoolean("wake_word", v).apply()

    /** The phrase the user says. Default is 4 syllables and not a common name (audit B4). */
    var wakePhrase: String
        get() = prefs.getString("wake_phrase", "hey code assist")?.takeIf { it.isNotBlank() } ?: "hey code assist"
        set(v) = prefs.edit().putString("wake_phrase", v.trim()).apply()

    /** "strict" | "normal" | "loose": how many words of the phrase must be heard (see WakeMatcher). */
    var wakeSensitivity: String
        get() = prefs.getString("wake_sens", "normal") ?: "normal"
        set(v) = prefs.edit().putString("wake_sens", v).apply()

    /** Version of the wake-word consent notice the user accepted (0 = never). */
    var wakeConsent: Int
        get() = prefs.getInt("wake_consent", 0)
        set(v) = prefs.edit().putInt("wake_consent", v).apply()

    /** True while the hands-free service is alive; stays true if the process is killed (health check, part 2B). */
    var wakeRunning: Boolean
        get() = prefs.getBoolean("wake_running", false)
        set(v) = prefs.edit().putBoolean("wake_running", v).apply()

    /** Wall-clock time of the service's last heartbeat. */
    var wakeHeartbeat: Long
        get() = prefs.getLong("wake_heartbeat", 0L)
        set(v) = prefs.edit().putLong("wake_heartbeat", v).apply()

    // ---------- Hands-free health check (audit PDF Gap B3, part 2B) ----------
    /** Android's boot counter when the service last started (-1 = unknown); tells a reboot from a battery-saver kill. */
    var wakeBootCount: Int
        get() = prefs.getInt("wake_boot_count", -1)
        set(v) = prefs.edit().putInt("wake_boot_count", v).apply()

    /** Wall-clock times (comma separated, newest last, max 10) when Android stopped the service behind our back. */
    var wakeKillTimes: String
        get() = prefs.getString("wake_kill_times", "") ?: ""
        set(v) = prefs.edit().putString("wake_kill_times", v).apply()

    /** The watchdog already posted its "hands-free ruk gaya" notification for the current kill. */
    var wakeKillNotified: Boolean
        get() = prefs.getBoolean("wake_kill_notified", false)
        set(v) = prefs.edit().putBoolean("wake_kill_notified", v).apply()

    /** Last time the app asked the user to fix battery settings (so it does not nag). */
    var batteryPromptAt: Long
        get() = prefs.getLong("battery_prompt_at", 0L)
        set(v) = prefs.edit().putLong("battery_prompt_at", v).apply()

    // ---------- Projects ----------
    private fun <T> readList(key: String, type: Type): MutableList<T> {
        val json = prefs.getString(key, null) ?: return mutableListOf()
        return try {
            (gson.fromJson<List<T>?>(json, type) ?: emptyList()).toMutableList()
        } catch (_: Exception) {
            // Keep the original payload and a recovery copy. A later save must
            // never silently replace unreadable user data with an empty list.
            if (!invalidCollections.contains(key)) {
                prefs.edit().putString("${key}_recovery", json).apply()
                invalidCollections += key
            }
            mutableListOf()
        }
    }

    private fun <T> writeList(key: String, list: List<T>, type: Type) {
        val existing = prefs.getString(key, null)
        if (existing != null) {
            val valid = try {
                gson.fromJson<Any?>(existing, type) is List<*>
            } catch (_: Exception) {
                false
            }
            if (!valid) {
                if (!invalidCollections.contains(key)) {
                    prefs.edit().putString("${key}_recovery", existing).apply()
                    invalidCollections += key
                }
                return
            }
        }
        if (key in invalidCollections) return
        prefs.edit().putString(key, gson.toJson(list)).apply()
    }

    fun projects(): MutableList<Project> {
        val type = object : TypeToken<MutableList<Project>>() {}.type
        return readList<Project>("projects", type).apply {
            forEach { if (it.fileUris == null) it.fileUris = mutableListOf() }
        }
    }

    fun saveProjects(list: List<Project>) {
        val type = object : TypeToken<MutableList<Project>>() {}.type
        writeList("projects", list, type)
    }

    fun addProject(p: Project) {
        val list = projects()
        list.add(0, p)
        saveProjects(list)
        lastProjectId = p.id
    }

    fun updateProject(p: Project) {
        val list = projects()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) { list[i] = p; saveProjects(list) }
    }

    fun deleteProject(id: String) {
        File(appContext.filesDir, "project-files/$id").deleteRecursively()
        val projectChatIds = chats().filter { it.projectId == id }.map { it.id }
        projectChatIds.forEach { deleteChat(it) }
        saveProjects(projects().filterNot { it.id == id })
        if (lastProjectId == id) lastProjectId = projects().firstOrNull()?.id
    }

    fun project(id: String?): Project? = projects().firstOrNull { it.id == id }

    // ---------- Chats ----------
    fun chats(): MutableList<ChatMeta> {
        val type = object : TypeToken<MutableList<ChatMeta>>() {}.type
        return readList<ChatMeta>("chats", type).apply {
            sortWith(compareByDescending<ChatMeta> { it.pinned }.thenByDescending { it.time })
        }
    }

    fun saveChats(list: List<ChatMeta>) {
        val type = object : TypeToken<MutableList<ChatMeta>>() {}.type
        writeList("chats", list.sortedWith(compareByDescending<ChatMeta> { it.pinned }.thenByDescending { it.time }), type)
    }

    fun newChat(title: String, projectId: String? = null): ChatMeta {
        val chat = ChatMeta(title = title, projectId = projectId)
        val list = chats()
        list.add(0, chat)
        saveChats(list)
        if (projectId != null) {
            project(projectId)?.let { it.chats += 1; updateProject(it) }
        }
        return chat
    }

    fun updateChat(c: ChatMeta) {
        val list = chats()
        val i = list.indexOfFirst { it.id == c.id }
        if (i >= 0) {
            list[i] = c
            saveChats(list)
        }
    }

    fun deleteChat(id: String) {
        val chat = chats().firstOrNull { it.id == id }
        saveChats(chats().filterNot { it.id == id })
        prefs.edit().remove("msgs_$id").apply()
        if (activeChatId == id) activeChatId = null
        chat?.projectId?.let { projectId ->
            project(projectId)?.let { p ->
                p.chats = chats().count { it.projectId == projectId }
                updateProject(p)
            }
        }
    }

    fun restoreChat(chat: ChatMeta, messages: List<Message>) {
        val list = chats()
        if (list.none { it.id == chat.id }) list.add(chat)
        saveChats(list)
        saveMessages(chat.id, messages)
        chat.projectId?.let { projectId ->
            project(projectId)?.let { p ->
                p.chats = chats().count { it.projectId == projectId }
                updateProject(p)
            }
        }
    }

    fun moveChat(chatId: String, projectId: String?) {
        val c = chats().firstOrNull { it.id == chatId } ?: return
        if (projectId != null && project(projectId) == null) return
        val oldProjectId = c.projectId
        if (oldProjectId == projectId) return
        c.projectId = projectId
        updateChat(c)
        listOfNotNull(oldProjectId, projectId).distinct().forEach { id ->
            project(id)?.let { p ->
                p.chats = chats().count { it.projectId == id }
                updateProject(p)
            }
        }
    }

    fun clearHistory() {
        val editor = prefs.edit()
            .remove("chats")
            .remove("chats_recovery")
        prefs.all.keys.filter { it.startsWith("msgs_") }.forEach(editor::remove)
        editor.apply()
        invalidCollections.removeAll { it == "chats" || it.startsWith("msgs_") }
        activeChatId = null
        activeDraft = ""
        projects().forEach { p ->
            if (p.chats != 0) {
                p.chats = 0
                updateProject(p)
            }
        }
    }

    // ---------- Messages ----------
    fun messages(chatId: String): MutableList<Message> {
        val type = object : TypeToken<MutableList<Message>>() {}.type
        return readList("msgs_$chatId", type)
    }

    fun saveMessages(chatId: String, list: List<Message>) {
        val type = object : TypeToken<MutableList<Message>>() {}.type
        // A reply that is still being generated must never reach disk.
        writeList("msgs_$chatId", list.filter { it.state != "thinking" }, type)
    }

    fun updateMessage(chatId: String, message: Message) {
        val list = messages(chatId)
        val i = list.indexOfFirst { it.id == message.id }
        if (i >= 0) {
            list[i] = message
            saveMessages(chatId, list)
        }
    }

    fun deleteMessage(chatId: String, messageId: String) {
        saveMessages(chatId, messages(chatId).filterNot { it.id == messageId })
    }
}
