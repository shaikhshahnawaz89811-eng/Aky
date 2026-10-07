package com.codeassist.ai.data

import java.util.UUID

enum class AttachKind { IMAGE, ZIP, FILE }

data class Attachment(
    val uri: String,
    val name: String,
    val size: Long,
    val mime: String,
    val kind: AttachKind
)

enum class Role { USER, AI }

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val fileName: String? = null,
    val fileType: String? = null,
    val time: Long = System.currentTimeMillis(),
    /** null = normal, "thinking" = reply in progress (never persisted), "error" = failed reply. */
    val state: String? = null,
    /** Small caption under AI replies ("Qwen2.5 1.5B · 8.1 tok/s") or the live phase while thinking. */
    val note: String? = null,
    /** "local" (on-device Qwen), "gemini" or "tool" for AI replies; older chats may hold "phi4". */
    val engine: String? = null,
    /** True when the user message was spoken (shows the voice caption). */
    val viaVoice: Boolean = false,
    val voiceMs: Long = 0L,
    /** Id of the ActivityLog entry when this reply is a phone action that can be undone. */
    val undoId: String? = null,
    /** Short chip text when the brain ran phone tools in this reply (for example "Alarm · Torch"). */
    val actions: String? = null,
    /** JSON of a PendingAction: a T2 tool waiting for the user's "Haan" tap. Null once answered. */
    val pending: String? = null
)

data class ChatMeta(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var snippet: String = "",
    var projectId: String? = null,
    var draft: String = "",
    var pinned: Boolean = false,
    var time: Long = System.currentTimeMillis()
)

data class Project(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var desc: String = "",
    var iconIdx: Int = 0,
    var colorIdx: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    var chats: Int = 0,
    var files: MutableList<String> = mutableListOf(),
    var fileUris: MutableList<String>? = null,
    var instructions: MutableList<String> = mutableListOf()
)
