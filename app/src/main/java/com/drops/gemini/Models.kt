package com.drops.gemini

import com.google.gson.annotations.SerializedName

data class GeminiStructuredResponse(
    @SerializedName("reply")
    val reply: String = "",

    @SerializedName("recast_correction")
    val recastCorrection: String? = null,

    @SerializedName("grammar_tip")
    val grammarTip: String? = null,

    @SerializedName("target_words_detected")
    val targetWordsDetected: List<String> = emptyList(),

    @SerializedName("suggested_quick_replies")
    val suggestedQuickReplies: List<String> = emptyList(),

    @SerializedName("session_flow_status")
    val sessionFlowStatus: String = "IN_PROGRESS"
)

data class ChatMessage(
    val role: MessageRole,
    val text: String,
    val recastCorrection: String? = null,
    val grammarTip: String? = null
)

enum class MessageRole {
    USER, MODEL
}

data class LessonContext(
    val scenario: String,
    val targetWords: List<String>
)
