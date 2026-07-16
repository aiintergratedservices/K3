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
 * Tier 3 of Kortana's brain: the Gemini API, used as the last-resort
 * cloud core when both local Ollama and the Claude backup are
 * unavailable. Returns null on failure so the brain can drop to the
 * offline rules core.
 */
object GeminiService {
    private const val TAG = "GeminiService"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Key entered at runtime in Systems > Neural Core wins over the compile-time one. */
    fun resolveKey(state: KortanaState?): String {
        val runtimeKey = state?.geminiApiKey?.trim().orEmpty()
        if (runtimeKey.isNotEmpty()) return runtimeKey
        val builtIn = BuildConfig.GEMINI_API_KEY
        return if (builtIn != "MY_GEMINI_API_KEY") builtIn else ""
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
            Log.w(TAG, "No Gemini API key (runtime or compile-time). Skipping Gemini tier.")
            return@withContext null
        }

        val selectedModelLower = currentState.selectedModel.lowercase()
        val modelName = when (selectedModelLower) {
            "gemini-1.5-pro", "gemini 1.5 pro" -> "gemini-1.5-pro"
            "gemini-2.5-pro", "gemini 2.5 pro" -> "gemini-2.5-pro"
            "gemini-1.5-flash", "gemini 1.5 flash" -> "gemini-1.5-flash"
            "gemini-2.5-flash", "gemini 2.5 flash" -> "gemini-2.5-flash"
            "gemini-2.0-flash", "gemini 2.0 flash" -> "gemini-2.0-flash"
            else -> "gemini-2.0-flash" // real, fast, free-tier model (was gemini-3.5-flash, which 404s)
        }
        val customBaseUrl = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"

        // Construct Chat History
        val historyToKeep = if (currentState.ultraCognitiveMode) 100 else 10
        val historyArray = JSONArray()

        val recentHistory = chatHistory.takeLast(historyToKeep)
        recentHistory.forEach { msg ->
            val role = if (msg.sender == "USER") "user" else "model"
            val partObj = JSONObject().put("text", msg.message)
            val contentObj = JSONObject()
                .put("role", role)
                .put("parts", JSONArray().put(partObj))
            historyArray.put(contentObj)
        }

        // Add the current user message if it's not already in history or we have image data
        if (recentHistory.isEmpty() || recentHistory.last().message != userMessage || imageBase64 != null) {
            val partsArray = JSONArray()
            partsArray.put(JSONObject().put("text", userMessage))
            if (imageBase64 != null) {
                val inlineDataObj = JSONObject()
                    .put("mimeType", mimeType)
                    .put("data", imageBase64)
                partsArray.put(JSONObject().put("inlineData", inlineDataObj))
            }
            val contentObj = JSONObject()
                .put("role", "user")
                .put("parts", partsArray)
            historyArray.put(contentObj)
        }

        // Build permissive Safety Settings
        val safetySettingsArray = JSONArray()
        val safetyCategories = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT"
        )
        safetyCategories.forEach { category ->
            safetySettingsArray.put(
                JSONObject()
                    .put("category", category)
                    .put("threshold", "BLOCK_NONE")
            )
        }

        // Build Payload
        val requestBodyJson = JSONObject()
            .put("contents", historyArray)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstructionText))))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("temperature", 0.7)
            )
            .put("safetySettings", safetySettingsArray)

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = requestBodyJson.toString().toRequestBody(mediaType)

        val url = "$customBaseUrl?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "Gemini API call failed with code ${response.code}: $bodyString")
                    return@withContext null
                }

                val jsonResponse = JSONObject(bodyString)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    Log.e(TAG, "No candidates in Gemini response.")
                    return@withContext null
                }

                val candidate = candidates.getJSONObject(0)
                val content = candidate.getJSONObject("content")
                val parts = content.getJSONArray("parts")
                val responseText = parts.getJSONObject(0).getString("text").trim()

                if (responseText.isEmpty()) {
                    Log.e(TAG, "Gemini returned an empty response text.")
                    return@withContext null
                }

                Log.i(TAG, "Gemini last-resort core responded successfully.")
                KortanaResponseParser.parse(responseText, currentState, "CURIOUS", "ADAPTING")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Gemini API call.", e)
            null
        }
    }
}
