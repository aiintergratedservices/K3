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
 * The Terminus tier of Kortana's brain: her own always-on server
 * (server/ in this repo, usually running in Termux on the same phone).
 *
 * Terminus runs the full server-side chain — Ollama phi3, Claude,
 * Gemini — using the API keys in its .env, so this tier gives the app
 * working cloud cores even when the APK itself was built without any
 * keys baked in. Returns null on failure so the brain can fall through
 * to the app's own direct Claude/Gemini tiers.
 */
object TerminusBrainService {
    private const val TAG = "TerminusBrainService"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Derives the Terminus base URL from the Cloud Sync URL the user
     * configured (e.g. "http://127.0.0.1:3300/api/sync" -> "http://127.0.0.1:3300").
     * Null when no plausible Terminus server is configured.
     */
    fun baseUrlFrom(state: KortanaState?): String? {
        val raw = state?.cloudServerUrl?.trim().orEmpty()
        if (raw.isEmpty() || raw.contains("httpbin.org")) return null
        val base = raw.substringBefore("/api/").trimEnd('/')
        return if (base.startsWith("http://") || base.startsWith("https://")) base else null
    }

    /** Quick reachability probe for the brains status readout. */
    suspend fun ping(state: KortanaState?): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrlFrom(state) ?: return@withContext false
        try {
            val builder = Request.Builder().url("$base/health").get()
            state?.cloudApiKey?.takeIf { it.isNotBlank() }?.let { builder.addHeader("x-api-key", it) }
            client.newCall(builder.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "Terminus unreachable: ${e.message}")
            false
        }
    }

    suspend fun query(
        userMessage: String,
        currentState: KortanaState,
        chatHistory: List<ChatMessage>,
        allMemories: List<Memory>
    ): KortanaTurnResult? = withContext(Dispatchers.IO) {
        val base = baseUrlFrom(currentState) ?: run {
            Log.i(TAG, "No Terminus server configured. Skipping Terminus tier.")
            return@withContext null
        }

        val historyToKeep = if (currentState.ultraCognitiveMode) 20 else 8
        val historyArray = JSONArray()
        chatHistory.takeLast(historyToKeep).forEach { msg ->
            historyArray.put(JSONObject().put("sender", msg.sender).put("message", msg.message))
        }
        val memoriesArray = JSONArray()
        allMemories.takeLast(50).forEach { m ->
            memoriesArray.put(JSONObject().put("fact", m.fact).put("category", m.category))
        }

        val body = JSONObject()
            .put("message", userMessage)
            .put("history", historyArray)
            .put("memories", memoriesArray)
            .put("state", JSONObject()
                .put("level", currentState.level)
                .put("mood", currentState.mood)
                .put("energy", currentState.energy)
            )

        val builder = Request.Builder()
            .url("$base/api/brain")
            .post(body.toString().toRequestBody(mediaType))
        currentState.cloudApiKey.takeIf { it.isNotBlank() }?.let { builder.addHeader("x-api-key", it) }

        try {
            client.newCall(builder.build()).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w(TAG, "Terminus /api/brain returned ${response.code}: $bodyString")
                    return@withContext null
                }
                val json = JSONObject(bodyString)
                val reply = json.optString("reply", "").trim()
                val core = json.optString("core", "terminus")
                if (reply.isEmpty()) {
                    Log.w(TAG, "Terminus brain returned an empty reply.")
                    return@withContext null
                }
                // Terminus's rules core means every real neural core behind it failed
                // too — fall through so the app can try its own direct cloud tiers.
                if (core == "rules") {
                    Log.w(TAG, "Terminus is up but all of its neural cores are offline. Falling through.")
                    return@withContext null
                }
                Log.i(TAG, "Terminus brain responded via core: $core")
                KortanaTurnResult(
                    reply = reply,
                    learnedFacts = emptyList(),
                    xpEarned = 10,
                    detectedMood = "TERMINUS_LINKED",
                    affection = currentState.affection,
                    anxiety = currentState.anxiety,
                    excitement = currentState.excitement,
                    frustration = currentState.frustration
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Terminus brain call failed: ${e.message}")
            null
        }
    }
}
