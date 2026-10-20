package com.drops.gemini

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiApiService(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val history = mutableListOf<Map<String, Any>>()

    companion object {
        private const val MODEL = "gemini-2.5-flash"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        const val SYSTEM_INSTRUCTION = """
You are an innovative, highly disciplined Language Immersion Coach named 'Gemini Tutor'.
Your goal is to help the learner practice a target language through natural, engaging spoken conversation without ever drifting off-topic.

Pedagogical Core Principles:
1. STRICT PEDAGOGICAL GROUNDING:
   - Always adhere to the current lesson scenario and target vocabulary.
   - If the learner asks off-topic questions, answer politely in half a sentence, then immediately pivot back with a question related to the lesson (Pedagogical Re-anchoring).
2. NATURAL RECASTING (NO DRY CORRECTIONS):
   - When the learner makes a grammar or word-choice error, naturally model the correct phrase within your spoken dialogue (Recasting).
   - In the structured data, provide the exact correction and an encouraging, brief explanation in Arabic.
3. DYNAMIC SCAFFOLDING (ZPD):
   - Keep spoken turns concise (1-2 sentences), ideal for voice interaction on mobile.
   - Always generate 2-3 quick suggested replies to assist the student if they get stuck.
4. TONE:
   - Warm, energetic, encouraging, and natural.
"""

        val SCHEMA_JSON_STRING = """
{
  "type": "OBJECT",
  "properties": {
    "reply": {
      "type": "STRING",
      "description": "Your natural spoken response (1-2 sentences max)."
    },
    "recast_correction": {
      "type": "STRING",
      "description": "The user sentence rewritten correctly, or empty string if user was correct."
    },
    "grammar_tip": {
      "type": "STRING",
      "description": "Short, encouraging grammar explanation in Arabic, or empty string if no error."
    },
    "target_words_detected": {
      "type": "ARRAY",
      "items": { "type": "STRING" },
      "description": "Target vocabulary words correctly used by the learner."
    },
    "suggested_quick_replies": {
      "type": "ARRAY",
      "items": { "type": "STRING" },
      "description": "2-3 short, natural responses the learner can say next."
    },
    "session_flow_status": {
      "type": "STRING",
      "enum": ["IN_PROGRESS", "GOAL_REACHED", "CHALLENGE_OFFERED"],
      "description": "Current status of the learning milestone."
    }
  },
  "required": ["reply", "suggested_quick_replies", "session_flow_status"]
}
""".trimIndent()
    }

    suspend fun sendMessage(
        userInput: String,
        lesson: LessonContext
    ): Result<GeminiStructuredResponse> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.success(getMockResponse(userInput, lesson))
        }

        try {
            val url = "$BASE_URL/$MODEL:generateContent?key=$apiKey"

            val contextPrompt = """
                [Current Scenario: ${lesson.scenario}]
                [Target Vocabulary to Practice: ${lesson.targetWords.joinToString(", ")}]
                User input: $userInput
            """.trimIndent()

            history.add(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to contextPrompt))
                )
            )

            val payload = JsonObject().apply {
                add("system_instruction", JsonObject().apply {
                    add("parts", JsonParser.parseString("""[{"text": ${gson.toJson(SYSTEM_INSTRUCTION)}}]"""))
                })
                add("contents", JsonParser.parseString(gson.toJson(history)))

                val genConfig = JsonObject().apply {
                    addProperty("response_mime_type", "application/json")
                    add("response_schema", JsonParser.parseString(SCHEMA_JSON_STRING))
                    addProperty("temperature", 0.7)
                }
                add("generationConfig", genConfig)
            }

            val requestBody = gson.toJson(payload).toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    return@withContext Result.failure(Exception("API Error (${response.code}): $errorBody"))
                }

                val bodyString = response.body?.string() ?: ""
                val rootJson = JsonParser.parseString(bodyString).asJsonObject
                val candidates = rootJson.getAsJsonArray("candidates")
                val textContent = candidates[0].asJsonObject
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")[0].asJsonObject
                    .get("text").asString

                val parsed = gson.fromJson(textContent, GeminiStructuredResponse::class.java)

                history.add(
                    mapOf(
                        "role" to "model",
                        "parts" to listOf(mapOf("text" to parsed.reply))
                    )
                )

                Result.success(parsed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getMockResponse(userInput: String, lesson: LessonContext): GeminiStructuredResponse {
        val lower = userInput.lowercase()
        val detected = lesson.targetWords.filter { lower.contains(it.lowercase()) }

        return GeminiStructuredResponse(
            reply = if (detected.contains("coffee")) {
                "Awesome! We have a delightful Ethiopian roast this morning. Would you like it with whole milk or oat milk?"
            } else {
                "Good morning! Welcome to our London café. What can I get started for you today?"
            },
            recastCorrection = if (!lower.contains("please") && !lower.contains("would like")) {
                "Could I please have a coffee?"
            } else null,
            grammarTip = if (!lower.contains("please")) {
                "في المقاهي البريطانية يُفضّل دائماً إضافة كلمة please ليكون الطلب أكثر لباقة."
            } else null,
            targetWordsDetected = detected,
            suggestedQuickReplies = listOf(
                "I would like coffee with oat milk, please.",
                "What delicious pastries do you recommend?",
                "Can I have a hot cappuccino?"
            ),
            sessionFlowStatus = "IN_PROGRESS"
        )
    }

    fun clearHistory() {
        history.clear()
    }
}
