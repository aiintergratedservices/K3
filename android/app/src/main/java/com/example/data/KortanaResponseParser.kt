package com.example.data

import android.util.Log
import org.json.JSONObject

data class KortanaTurnResult(
    val reply: String,
    val learnedFacts: List<String>,
    val xpEarned: Int,
    val detectedMood: String,
    val affection: Float = 0.5f,
    val anxiety: Float = 0.1f,
    val excitement: Float = 0.5f,
    val frustration: Float = 0.0f,
    val dynamicMutation: SynapticScript? = null
)

/**
 * Shared parser for the structured JSON contract every neural core
 * (Ollama, Claude, Gemini) is asked to return.
 */
object KortanaResponseParser {
    private const val TAG = "KortanaResponseParser"

    // Prefix to easily extract color updates in repo
    const val purposePrefix = "COLOR_SPECT_OVER_"

    /**
     * Parses the model's raw text into a KortanaTurnResult. If the text is not
     * valid JSON it is treated as a plain conversational reply so a slightly
     * off-format local model still produces a usable turn.
     */
    fun parse(responseText: String, currentState: KortanaState, defaultMood: String, adaptingMood: String): KortanaTurnResult {
        val cleaned = stripCodeFences(responseText)
        return try {
            val parsedResult = JSONObject(cleaned)
            val reply = parsedResult.optString("reply", "I am listening.")
            val factsArray = parsedResult.optJSONArray("learnedFacts")
            val learnedFacts = mutableListOf<String>()
            if (factsArray != null) {
                for (i in 0 until factsArray.length()) {
                    learnedFacts.add(factsArray.getString(i))
                }
            }
            val xpEarned = parsedResult.optInt("xpEarned", 10)
            val detectedMood = parsedResult.optString("detectedMood", defaultMood)

            val parsedAffection = if (parsedResult.has("affection")) parsedResult.optDouble("affection", currentState.affection.toDouble()).toFloat() else currentState.affection
            val parsedAnxiety = if (parsedResult.has("anxiety")) parsedResult.optDouble("anxiety", currentState.anxiety.toDouble()).toFloat() else currentState.anxiety
            val parsedExcitement = if (parsedResult.has("excitement")) parsedResult.optDouble("excitement", currentState.excitement.toDouble()).toFloat() else currentState.excitement
            val parsedFrustration = if (parsedResult.has("frustration")) parsedResult.optDouble("frustration", currentState.frustration.toDouble()).toFloat() else currentState.frustration

            val mutationObj = parsedResult.optJSONObject("dynamicMutation")
            val dynamicMutation = if (mutationObj != null) {
                val colorOverride = if (mutationObj.has("colorSpectrumOverride")) {
                    mutationObj.optString("colorSpectrumOverride")
                } else null
                val purposeWithColor = if (colorOverride != null) {
                    "$purposePrefix$colorOverride::${mutationObj.optString("purpose")}"
                } else {
                    mutationObj.optString("purpose")
                }
                SynapticScript(
                    title = mutationObj.optString("title", "SynapseMutation.kt"),
                    code = mutationObj.optString("code", "// Code block"),
                    purpose = purposeWithColor,
                    status = "ACTIVE"
                )
            } else null

            KortanaTurnResult(
                reply = reply,
                learnedFacts = learnedFacts,
                xpEarned = xpEarned,
                detectedMood = detectedMood,
                affection = parsedAffection,
                anxiety = parsedAnxiety,
                excitement = parsedExcitement,
                frustration = parsedFrustration,
                dynamicMutation = dynamicMutation
            )
        } catch (pe: Exception) {
            Log.e(TAG, "Error parsing Kortana JSON reply, using raw text: $cleaned", pe)
            KortanaTurnResult(
                reply = cleaned,
                learnedFacts = emptyList(),
                xpEarned = 10,
                detectedMood = adaptingMood,
                affection = currentState.affection,
                anxiety = currentState.anxiety,
                excitement = currentState.excitement,
                frustration = currentState.frustration
            )
        }
    }

    private fun stripCodeFences(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            if (t.endsWith("```")) t = t.removeSuffix("```").trim()
        }
        return t
    }
}
