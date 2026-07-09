package com.example.data

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Tier 2 of Kortana's brain: the Claude API (Anthropic), used as the
 * cloud backup whenever the local Ollama phi3 core can't do what's
 * needed. Returns null on failure so the brain can fall back to Gemini.
 */
object ClaudeService {
    private const val TAG = "ClaudeService"
    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val MODEL = "claude-sonnet-5"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /** Key entered at runtime in Systems > Neural Core wins over the compile-time one. */
    fun resolveKey(state: KortanaState?): String {
        val runtimeKey = state?.anthropicApiKey?.trim().orEmpty()
        if (runtimeKey.isNotEmpty()) return runtimeKey
        val builtIn = BuildConfig.ANTHROPIC_API_KEY
        return if (builtIn != "MY_ANTHROPIC_API_KEY") builtIn else ""
    }

    fun isConfigured(state: KortanaState? = null): Boolean = resolveKey(state).isNotEmpty()

    suspend fun query(
        userMessage: String,
        currentState: KortanaState,
        chatHistory: List<ChatMessage>,
        systemInstructionText: String,
        imageBase64: String? = null,
        mimeType: String = "image/jpeg"
    ): KortanaTurnResult? = withContext(Dispatchers.IO) {
        val apiKey = resolveKey(currentState)
        if (apiKey.isEmpty()) {
            Log.w(TAG, "No Anthropic API key (runtime or compile-time). Skipping Claude tier.")
            return@withContext null
        }

        val historyToKeep = if (currentState.ultraCognitiveMode) 100 else 10
        val recentHistory = chatHistory.takeLast(historyToKeep)

        // Build alternating user/assistant turns (the Claude API requires the
        // first message to be from the user and roles to alternate).
        data class Turn(val role: String, var text: String)
        val turns = mutableListOf<Turn>()
        recentHistory.forEach { msg ->
            val role = if (msg.sender == "USER") "user" else "assistant"
            val last = turns.lastOrNull()
            if (last != null && last.role == role) {
                last.text += "\n" + msg.message
            } else {
                turns.add(Turn(role, msg.message))
            }
        }
        while (turns.isNotEmpty() && turns.first().role != "user") {
            turns.removeAt(0)
        }
        val needsCurrentMessage = turns.isEmpty() || turns.last().role != "user" ||
            turns.last().text != userMessage || imageBase64 != null
        if (needsCurrentMessage) {
            val last = turns.lastOrNull()
            if (last != null && last.role == "user") {
                if (!last.text.contains(userMessage)) last.text += "\n" + userMessage
            } else {
                turns.add(Turn("user", userMessage))
            }
        }
        if (turns.isEmpty()) {
            turns.add(Turn("user", userMessage))
        }

        val messagesArray = JSONArray()
        turns.forEachIndexed { index, turn ->
            val isLast = index == turns.size - 1
            if (isLast && turn.role == "user" && imageBase64 != null) {
                val contentArray = JSONArray()
                contentArray.put(JSONObject()
                    .put("type", "image")
                    .put("source", JSONObject()
                        .put("type", "base64")
                        .put("media_type", mimeType)
                        .put("data", imageBase64)
                    )
                )
                contentArray.put(JSONObject().put("type", "text").put("text", turn.text))
                messagesArray.put(JSONObject().put("role", "user").put("content", contentArray))
            } else {
                messagesArray.put(JSONObject().put("role", turn.role).put("content", turn.text))
            }
        }

        val requestBodyJson = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 2048)
            .put("system", systemInstructionText)
            .put("messages", messagesArray)

        val request = Request.Builder()
            .url(API_URL)
            .post(requestBodyJson.toString().toRequestBody(mediaType))
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "Claude API call failed with code ${response.code}: $bodyString")
                    return@withContext null
                }

                val contentArray = JSONObject(bodyString).optJSONArray("content")
                var responseText = ""
                if (contentArray != null) {
                    for (i in 0 until contentArray.length()) {
                        val block = contentArray.getJSONObject(i)
                        if (block.optString("type") == "text") {
                            responseText = block.optString("text", "").trim()
                            if (responseText.isNotEmpty()) break
                        }
                    }
                }

                if (responseText.isEmpty()) {
                    Log.e(TAG, "Claude response contained no text block.")
                    return@withContext null
                }

                Log.i(TAG, "Claude backup core responded successfully.")
                KortanaResponseParser.parse(responseText, currentState, "CLAUDE_BACKUP", "CLAUDE_ADAPTING")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Claude API call.", e)
            null
        }
    }
}
