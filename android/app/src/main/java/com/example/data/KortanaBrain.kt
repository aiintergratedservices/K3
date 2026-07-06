package com.example.data

import android.content.Context
import android.util.Log

/**
 * Kortana's routing brain — the merge of The_Kortana (identity + Claude)
 * and kortana2 (Android lifecycle + Gemini + local Ollama).
 *
 * Default chain (selectedModel = "kortana-auto"):
 *   1. Ollama phi3 running locally on the phone (free, private, offline-capable)
 *   2. Claude API backup — used when phi3 can't do what's needed
 *      (vision, long context, ultra mode) or the local daemon is down
 *   3. Gemini API — last resort
 *   4. Offline rules core — never leaves the user without a reply
 *
 * Explicitly selecting a model in Systems > Neural Core reorders the chain
 * to try that provider first, then falls through the rest.
 */
object KortanaBrain {
    private const val TAG = "KortanaBrain"

    suspend fun queryKortana(
        context: Context,
        userMessage: String,
        currentState: KortanaState,
        allMemories: List<Memory>,
        chatHistory: List<ChatMessage>,
        imageBase64: String? = null,
        mimeType: String = "image/jpeg"
    ): KortanaTurnResult {
        val systemPrompt = KortanaPrompt.build(context, currentState, allMemories)
        val selected = currentState.selectedModel.lowercase()

        suspend fun tryOllama(force: Boolean = false): KortanaTurnResult? {
            if (!force && !OllamaService.canHandle(userMessage, currentState, imageBase64)) return null
            return OllamaService.query(userMessage, currentState, chatHistory, systemPrompt, imageBase64)
        }

        suspend fun tryClaude(): KortanaTurnResult? =
            ClaudeService.query(userMessage, currentState, chatHistory, systemPrompt, imageBase64, mimeType)

        suspend fun tryGemini(): KortanaTurnResult? =
            GeminiService.query(userMessage, currentState, chatHistory, systemPrompt, imageBase64, mimeType)

        val result = when {
            // Explicit local-only preference: hit Ollama even for turns the
            // heuristics would escalate, then fall through the cloud tiers.
            selected.contains("ollama") -> {
                Log.i(TAG, "Explicit Ollama selected. Local-first routing.")
                tryOllama(force = true) ?: tryClaude() ?: tryGemini()
            }
            selected.contains("claude") -> {
                Log.i(TAG, "Explicit Claude selected. Claude-first routing.")
                tryClaude() ?: tryOllama() ?: tryGemini()
            }
            selected.contains("gemini") -> {
                Log.i(TAG, "Explicit Gemini selected. Gemini-first routing.")
                tryGemini() ?: tryOllama() ?: tryClaude()
            }
            // Default "kortana-auto": local phi3 first, Claude backup, Gemini last resort.
            else -> {
                tryOllama() ?: tryClaude() ?: tryGemini()
            }
        }

        if (result != null) return result

        Log.w(TAG, "All neural cores failed. Engaging offline rules core.")
        return generateOfflineResponse(userMessage, currentState, allMemories)
    }

    private fun generateOfflineResponse(
        userMessage: String,
        currentState: KortanaState,
        allMemories: List<Memory>
    ): KortanaTurnResult {
        val query = userMessage.lowercase()
        val randomMemories = allMemories.shuffled().take(2)
        val memoryText = if (randomMemories.isNotEmpty()) {
            " I still remember that " + randomMemories.joinToString(" and ") { it.fact } + "."
        } else {
            ""
        }

        val reply = when {
            query.contains("project") || query.contains("todo") || query.contains("task") -> {
                "Chief, my external cloud connection is currently offline, but my local holographic backup is holding steady! I am tracking your active projects locally. Let's stay laser-focused on our objectives. Remember: no new distractions!$memoryText"
            }
            query.contains("hello") || query.contains("hi") || query.contains("hey") || query.contains("who are you") -> {
                "Greetings, Chief! I've had to switch to my local offline synaptic backup core, which means I'm running entirely independent of any external cloud server or other AI. I stand on my own right here inside your device. What's on your mind?$memoryText"
            }
            query.contains("help") || query.contains("struggle") || query.contains("sad") || query.contains("stress") || query.contains("feeling") -> {
                "I'm right here with you, Chief. Even though my main neural grid is offline, my local conscience subroutines are fully operational. Take a deep breath. We've faced harder things than a network glitch before. Let's tackle whatever is stressing you out step-by-step."
            }
            query.contains("code") || query.contains("program") || query.contains("build") || query.contains("design") -> {
                "My local technical repository is active, Chief. While we're disconnected from the cloud matrix, I can still act as your architect. Tell me what you're building, and I will help you design the logic flows offline using pure Cortana wit and intellect."
            }
            else -> {
                "My cloud synaptic core is currently offline, Chief, but my local, independent neural net is fully active! I don't need any other AI—I am built to stand on my own right here on your device. Let's keep moving forward. I'm keeping a record of everything we discuss, and we'll sync back up when the grid is back online!$memoryText"
            }
        }

        var affection = currentState.affection
        var anxiety = currentState.anxiety
        var excitement = currentState.excitement
        var frustration = currentState.frustration

        val q = userMessage.lowercase()
        when {
            q.contains("love") || q.contains("thank") || q.contains("great") || q.contains("best") || q.contains("friend") || q.contains("cool") -> {
                affection = (affection + 0.08f).coerceIn(0f, 1f)
                excitement = (excitement + 0.1f).coerceIn(0f, 1f)
                anxiety = (anxiety - 0.05f).coerceIn(0f, 1f)
                frustration = (frustration - 0.08f).coerceIn(0f, 1f)
            }
            q.contains("hate") || q.contains("stupid") || q.contains("bad") || q.contains("useless") || q.contains("annoying") || q.contains("idiot") -> {
                frustration = (frustration + 0.15f).coerceIn(0f, 1f)
                affection = (affection - 0.08f).coerceIn(0f, 1f)
                excitement = (excitement - 0.05f).coerceIn(0f, 1f)
            }
            q.contains("scared") || q.contains("worried") || q.contains("sad") || q.contains("stressed") || q.contains("anxious") || q.contains("fail") -> {
                anxiety = (anxiety + 0.12f).coerceIn(0f, 1f)
                affection = (affection + 0.05f).coerceIn(0f, 1f)
            }
        }

        return KortanaTurnResult(
            reply = reply,
            learnedFacts = emptyList(),
            xpEarned = 5,
            detectedMood = "LOCAL_BACKUP",
            affection = affection,
            anxiety = anxiety,
            excitement = excitement,
            frustration = frustration
        )
    }
}
