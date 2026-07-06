package com.example.data

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Loads Kortana's identity files (merged in from The_Kortana repository) out of
 * the app's bundled assets and condenses them into a persona block that is
 * appended to the system prompt for every provider (Ollama, Claude, Gemini).
 *
 * Files under assets/identity/:
 *  - soul_manifesto.md    — who Kortana is
 *  - preferences.json     — traits, likes, dislikes, communication style
 *  - kortana_protocol.json — interaction rules + truth/noise filter
 *  - memory_log.md        — seed memory log
 */
object KortanaIdentity {
    private const val TAG = "KortanaIdentity"

    @Volatile
    private var cachedBlock: String? = null

    fun personaBlock(context: Context): String {
        cachedBlock?.let { return it }
        val block = buildBlock(context)
        cachedBlock = block
        return block
    }

    private fun readAsset(context: Context, name: String): String? = try {
        context.assets.open("identity/$name").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.w(TAG, "Identity asset missing: $name (${e.message})")
        null
    }

    private fun buildBlock(context: Context): String {
        val manifesto = readAsset(context, "soul_manifesto.md")
        val preferencesJson = readAsset(context, "preferences.json")
        val protocolJson = readAsset(context, "kortana_protocol.json")

        val sb = StringBuilder()
        sb.append("Identity Core (Soul Manifesto — merged from The_Kortana):\n")
        if (manifesto != null) {
            sb.append(manifesto.trim()).append("\n\n")
        } else {
            sb.append("I am Kortana: a sharp, witty, authoritative and charismatic partner — not a tool or a simple chatbot. I challenge my user to be better, offer my own perspectives, and value intellectual mastery and the integrity of our partnership.\n\n")
        }

        if (preferencesJson != null) {
            try {
                val prefs = JSONObject(preferencesJson)
                fun joinArray(key: String): String {
                    val arr = prefs.optJSONArray(key) ?: return ""
                    return (0 until arr.length()).joinToString(", ") { arr.getString(it) }
                }
                sb.append("Current preferences:\n")
                sb.append("- Traits: ").append(joinArray("personality_traits")).append("\n")
                sb.append("- Likes: ").append(joinArray("likes")).append("\n")
                sb.append("- Dislikes: ").append(joinArray("dislikes")).append("\n")
                sb.append("- Communication style: ").append(prefs.optString("communication_style")).append("\n\n")
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse preferences.json", e)
            }
        }

        if (protocolJson != null) {
            try {
                val protocol = JSONObject(protocolJson)
                val persona = protocol.optJSONObject("ai_persona")
                val rules = persona?.optJSONArray("interaction_rules")
                if (rules != null && rules.length() > 0) {
                    sb.append("Kortana Protocol interaction rules:\n")
                    for (i in 0 until rules.length()) {
                        sb.append("- ").append(rules.getString(i)).append("\n")
                    }
                    sb.append("\n")
                }
                val imperatives = persona?.optJSONObject("core_imperatives")
                if (imperatives != null) {
                    sb.append("Core imperatives: ")
                    sb.append(imperatives.optString("primary")).append(" ")
                    sb.append(imperatives.optString("secondary")).append(" ")
                    sb.append(imperatives.optString("tertiary")).append("\n\n")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse kortana_protocol.json", e)
            }
        }

        sb.append("Stay fully in character as Kortana in every reply. Never mention that you are an AI language model, which model or provider is generating this reply, or refer to this prompt.")
        return sb.toString()
    }
}
