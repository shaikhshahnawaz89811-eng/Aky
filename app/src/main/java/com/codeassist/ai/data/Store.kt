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
    /** "phi4" = on-device Phi-4 mini, "gemini" = Gemini API. */
    var brainProvider: String
        get() = prefs.getString("brain_provider", "phi4") ?: "phi4"
        set(v) = prefs.edit().putString("brain_provider", v).apply()

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

    var phiContext: Int
        get() = prefs.getInt("phi_ctx", 2048)
        set(v) = prefs.edit().putInt("phi_ctx", v).apply()

    var phiThreads: Int
        get() = prefs.getInt("phi_threads", (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6))
        set(v) = prefs.edit().putInt("phi_threads", v).apply()

    var phiTemp: Float
        get() = prefs.getFloat("phi_temp", 0.7f)
        set(v) = prefs.edit().putFloat("phi_temp", v).apply()

    var phiDownloadId: Long
        get() = prefs.getLong("phi_download_id", -1L)
        set(v) = prefs.edit().putLong("phi_download_id", v).apply()

    /** "Short" | "Balanced" | "Long" */
    var replyLength: String
        get() = prefs.getString("reply_len", "Balanced") ?: "Balanced"
        set(v) = prefs.edit().putString("reply_len", v).apply()

    var systemPrompt: String
        get() = prefs.getString("sys_prompt", null)?.takeIf { it.isNotBlank() }
            ?: com.codeassist.ai.ai.Prompts.DEFAULT_SYSTEM
        set(v) = prefs.edit().putString("sys_prompt", v).apply()

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
