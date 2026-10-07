package com.codeassist.ai.ai

/**
 * Audit Sec 9.3 / 9.6: ONE plan format for both brains (Gemini function calling and Qwen <tool_call> text).
 * Pure Kotlin, no Android types, so everything in this file family is unit-tested on the JVM.
 */
class ToolParam(val name: String, val type: String, val desc: String, val required: Boolean = true)

/**
 * One tool the brain may call. [tier] is the audit risk tier (Sec 11.1) and comes from this table, never from
 * the model: "LLM proposes, app disposes" (Sec 8.1).
 */
class ToolSpec(
    val name: String,
    val desc: String,
    val tier: String,
    val params: List<ToolParam>,
    val readOnly: Boolean = false
)

/** One step of a plan. Arguments are kept as strings and parsed (and range-checked) by [ToolSpecs.validate]. */
class PlanTask(val id: String, val tool: String, val args: Map<String, String>)

/**
 * A T2 action that waits for the user's tap (stored as JSON in Message.pending).
 * [base] is the reply text of the other tasks of the same turn, so it can be completed after the tap.
 */
class PendingAction(
    val tool: String,
    val args: Map<String, String>,
    val readback: String,
    val base: String
)
