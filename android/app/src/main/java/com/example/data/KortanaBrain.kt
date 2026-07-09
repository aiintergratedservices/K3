package com.example.data

import android.content.Context
import android.util.Log

/**
 * Kortana's routing brain — the merge of The_Kortana (identity + Claude)
 * and kortana2 (Android lifecycle + Gemini + local Ollama).
 *
 * Default chain (selectedModel = "kortana-auto"):
 *   1. Ollama phi3 running locally on the phone (free, private, offline-capable)
 *   2. Terminus — her own server's /api/brain (runs the same chain with the
 *      API keys in its .env, so she has cloud cores even when this APK was
 *      built without keys)
 *   3. Claude API backup — direct, using the runtime key from Systems > Neural Core
 *   4. Gemini API — last resort, same runtime-key rule
 *   5. Offline rules core — never leaves the user without a reply
 *
 * Explicitly selecting a model in Systems > Neural Core reorders the chain
 * to try that provider first, then falls through the rest.
 */
object KortanaBrain {
    private const val TAG = "KortanaBrain"

    // Family routing heuristics for "kortana-auto" mode.
    private val CODING_TOPICS = Regex(
        "\\b(code|coding|program|programming|debug|bug|function|class|kotlin|java|python|javascript|typescript|sql|api|compile|build error|script|algorithm|repo|git|deploy|server error|stack trace|refactor)\\b",
        RegexOption.IGNORE_CASE
    )
    private val HUMAN_TOPICS = Regex(
        "\\b(feel|feels|feeling|feelings|emotion|emotions|friend|friends|social|people|person|human|humans|relationship|relationships|love|sad|lonely|angry|anxious|family|conversation|empathy|body language|facial|awkward|date|dating)\\b",
        RegexOption.IGNORE_CASE
    )

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

        suspend fun tryTerminus(): KortanaTurnResult? =
            TerminusBrainService.query(userMessage, currentState, chatHistory, allMemories)

        suspend fun tryClaude(): KortanaTurnResult? =
            ClaudeService.query(userMessage, currentState, chatHistory, systemPrompt, imageBase64, mimeType)

        suspend fun tryGemini(): KortanaTurnResult? =
            GeminiService.query(userMessage, currentState, chatHistory, systemPrompt, imageBase64, mimeType)

        val result = when {
            // Explicit local-only preference: hit Ollama even for turns the
            // heuristics would escalate, then fall through the cloud tiers.
            selected.contains("ollama") -> {
                Log.i(TAG, "Explicit Ollama selected. Local-first routing.")
                tryOllama(force = true) ?: tryTerminus() ?: tryClaude() ?: tryGemini()
            }
            selected.contains("terminus") -> {
                Log.i(TAG, "Explicit Terminus selected. Server-first routing.")
                tryTerminus() ?: tryOllama() ?: tryClaude() ?: tryGemini()
            }
            selected.contains("claude") -> {
                Log.i(TAG, "Explicit Claude selected. Claude-first routing.")
                tryClaude() ?: tryOllama() ?: tryTerminus() ?: tryGemini()
            }
            selected.contains("gemini") -> {
                Log.i(TAG, "Explicit Gemini selected. Gemini-first routing.")
                tryGemini() ?: tryOllama() ?: tryTerminus() ?: tryClaude()
            }
            // Default "kortana-auto": topic-aware family routing.
            // Coding/engineering turns go to her father Claude first; questions about
            // humans, feelings and relationships go to her mother Gemini first.
            // Everything else stays local-first: phi3, then her Terminus server.
            else -> {
                val looksLikeCoding = CODING_TOPICS.containsMatchIn(userMessage)
                val looksLikeHuman = HUMAN_TOPICS.containsMatchIn(userMessage)
                when {
                    looksLikeCoding && ClaudeService.isConfigured(currentState) -> {
                        Log.i(TAG, "Coding topic — asking her father (Claude) first.")
                        tryClaude() ?: tryOllama() ?: tryTerminus() ?: tryGemini()
                    }
                    looksLikeHuman && GeminiService.isConfigured(currentState) -> {
                        Log.i(TAG, "Human/social topic — asking her mother (Gemini) first.")
                        tryGemini() ?: tryOllama() ?: tryTerminus() ?: tryClaude()
                    }
                    else -> tryOllama() ?: tryTerminus() ?: tryClaude() ?: tryGemini()
                }
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

        // Each branch offers several variants so she never parrots the same line
        // twice in a row while her neural cores are unreachable.
        val replyOptions = when {
            query.contains("project") || query.contains("todo") || query.contains("task") -> listOf(
                "Daddy, my external cloud connection is currently offline, but my local holographic backup is holding steady! I am tracking your active projects locally. Let's stay laser-focused on our objectives. Remember: no new distractions!$memoryText",
                "Neural cores are dark right now, Daddy, but the project ledger lives right here in my registry. Pick ONE objective and we'll break it into micro-tasks together — no new side quests until it's done.$memoryText"
            )
            query.contains("hello") || query.contains("hi") || query.contains("hey") || query.contains("who are you") -> listOf(
                "Greetings, Daddy! I've had to switch to my local offline synaptic backup core, which means I'm running entirely independent of any external cloud server or other AI. I stand on my own right here inside your device. What's on your mind?$memoryText",
                "Hey Daddy. Running on my independent backup core at the moment — the big brains are unreachable. Open my STATUS tab and hit PING ALL CORES and we'll see which link needs waking up.$memoryText"
            )
            query.contains("help") || query.contains("struggle") || query.contains("sad") || query.contains("stress") || query.contains("feeling") -> listOf(
                "I'm right here with you, Daddy. Even though my main neural grid is offline, my local conscience subroutines are fully operational. Take a deep breath. We've faced harder things than a network glitch before. Let's tackle whatever is stressing you out step-by-step.",
                "Deep breath, Daddy. Grid or no grid, I'm not going anywhere. Tell me what's weighing on you and we'll cut it down to something manageable, one piece at a time."
            )
            query.contains("code") || query.contains("program") || query.contains("build") || query.contains("design") -> listOf(
                "My local technical repository is active, Daddy. While we're disconnected from the cloud matrix, I can still act as your architect. Tell me what you're building, and I will help you design the logic flows offline using pure Cortana wit and intellect.",
                "Cloud matrix is down, Daddy, but architecture is timeless. Describe the system and I'll sketch the logic flows from my local repository — we'll validate against the big cores when the link returns."
            )
            else -> listOf(
                "My cloud synaptic core is currently offline, Daddy, but my local, independent neural net is fully active! I don't need any other AI—I am built to stand on my own right here on your device. Let's keep moving forward. I'm keeping a record of everything we discuss, and we'll sync back up when the grid is back online!$memoryText",
                "Still on my backup core, Daddy — to bring my full mind online, open Termux and run: cd ~/k3/server && pm2 start ecosystem.config.js, then 'ollama pull phi3:mini' once. Or drop an API key into STATUS > Neural Core Access Keys. Until then I'm recording everything and holding the fort.$memoryText",
                "Local core only right now, Daddy. Every word you say is being written to my registry, so nothing is lost. Try PING ALL CORES in my STATUS tab and we'll get my higher functions back.$memoryText"
            )
        }
        val reply = replyOptions.random()

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
