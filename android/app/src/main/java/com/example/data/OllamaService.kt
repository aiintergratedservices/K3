package com.example.data

import android.util.Log
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
 * Tier 1 of Kortana's brain: local Ollama running on the phone
 * (e.g. inside Termux — `ollama serve` + `ollama pull phi3`).
 *
 * Returns null whenever the local core can't do what's needed so the
 * brain can escalate to Claude, then Gemini.
 */
object OllamaService {
    private const val TAG = "OllamaService"
    private const val DEFAULT_BASE_URL = "http://127.0.0.1:11434"

    /** Daemon URL is editable at runtime in Systems > Neural Core. */
    fun resolveBaseUrl(state: KortanaState?): String =
        state?.ollamaUrl?.trim()?.trimEnd('/')?.ifEmpty { null } ?: DEFAULT_BASE_URL

    // Preferred local models, in order. First one found installed wins.
    private val PREFERRED_MODELS = listOf("phi3.5", "phi3", "phi3:mini")

    // phi3-class models can't handle these; escalate straight to the cloud.
    private const val MAX_LOCAL_MESSAGE_CHARS = 2000

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Pre-flight capability check: can phi3 on-device plausibly handle this turn?
     * Images (phi3 has no vision) and very long inputs need a cloud core.
     */
    fun canHandle(userMessage: String, currentState: KortanaState, imageBase64: String?): Boolean {
        if (imageBase64 != null) {
            Log.i(TAG, "Image attached — phi3 has no vision. Escalating to cloud.")
            return false
        }
        if (userMessage.length > MAX_LOCAL_MESSAGE_CHARS) {
            Log.i(TAG, "Message too long for local core (${userMessage.length} chars). Escalating.")
            return false
        }
        return true
    }

    /** Asks the local daemon which phi3 variant is installed. Null = Ollama unreachable. */
    fun detectInstalledModel(baseUrl: String = DEFAULT_BASE_URL): String? {
        return try {
            val request = Request.Builder().url("$baseUrl/api/tags").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
                val installed = mutableListOf<String>()
                for (i in 0 until models.length()) {
                    installed.add(models.getJSONObject(i).optString("name"))
                }
                PREFERRED_MODELS.firstOrNull { pref ->
                    installed.any { it.startsWith(pref) }
                } ?: installed.firstOrNull { it.contains("phi3") }
                ?: PREFERRED_MODELS.first()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Local Ollama daemon unreachable: ${e.message}")
            null
        }
    }

    /**
     * Queries the local model. Returns null if Ollama is down, errors out,
     * or produces an empty response — the brain then falls back to Claude.
     */
    suspend fun query(
        userMessage: String,
        currentState: KortanaState,
        chatHistory: List<ChatMessage>,
        systemInstructionText: String,
        imageBase64: String? = null
    ): KortanaTurnResult? = withContext(Dispatchers.IO) {
        val baseUrl = resolveBaseUrl(currentState)
        val modelName = detectInstalledModel(baseUrl) ?: return@withContext null
        Log.i(TAG, "Local core online. Using model: $modelName")

        val messagesArray = JSONArray()
        messagesArray.put(JSONObject()
            .put("role", "system")
            .put("content", systemInstructionText)
        )

        val historyToKeep = if (currentState.ultraCognitiveMode) 20 else 6
        chatHistory.takeLast(historyToKeep).forEach { msg ->
            val role = if (msg.sender == "USER") "user" else "assistant"
            messagesArray.put(JSONObject()
                .put("role", role)
                .put("content", msg.message)
            )
        }

        if (chatHistory.isEmpty() || chatHistory.last().message != userMessage || imageBase64 != null) {
            val userMsgObj = JSONObject()
                .put("role", "user")
                .put("content", userMessage)
            if (imageBase64 != null) {
                userMsgObj.put("images", JSONArray().put(imageBase64))
            }
            messagesArray.put(userMsgObj)
        }

        var responseText = ""

        // 1. OpenAI-compatible endpoint
        try {
            val requestBodyJson = JSONObject()
                .put("model", modelName)
                .put("messages", messagesArray)
                .put("stream", false)
                .put("response_format", JSONObject().put("type", "json_object"))

            val request = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .post(requestBodyJson.toString().toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    val choices = JSONObject(bodyString).optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        responseText = choices.getJSONObject(0)
                            .optJSONObject("message")
                            ?.optString("content", "")
                            ?.trim() ?: ""
                    }
                } else {
                    Log.w(TAG, "Ollama OpenAI endpoint returned status code ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ollama OpenAI endpoint failed: ${e.message}")
        }

        // 2. Native /api/chat endpoint
        if (responseText.isEmpty()) {
            try {
                val requestBodyJson = JSONObject()
                    .put("model", modelName)
                    .put("messages", messagesArray)
                    .put("stream", false)
                    .put("format", "json")

                val request = Request.Builder()
                    .url("$baseUrl/api/chat")
                    .post(requestBodyJson.toString().toRequestBody(mediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyString = response.body?.string() ?: ""
                        responseText = JSONObject(bodyString)
                            .optJSONObject("message")
                            ?.optString("content", "")
                            ?.trim() ?: ""
                    } else {
                        Log.w(TAG, "Ollama /api/chat returned status code ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ollama /api/chat failed: ${e.message}")
            }
        }

        if (responseText.isEmpty()) {
            Log.w(TAG, "Local core produced no usable output. Escalating to cloud backup.")
            return@withContext null
        }

        KortanaResponseParser.parse(responseText, currentState, "OLLAMA_LOCAL", "OLLAMA_ADAPTING")
    }
}
