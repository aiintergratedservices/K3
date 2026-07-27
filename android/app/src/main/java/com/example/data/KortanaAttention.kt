package com.example.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The notification bridge — polls Terminus for things that actually need
 * Daddy's action (a pending code proposal to review, a goal that hit
 * GOAL_BLOCKED waiting on his part) and fires a real dismissible
 * notification when the count goes UP since last check. Only notifies on
 * genuine new activity, not every poll, so it doesn't nag.
 *
 * HONEST NOTE: there is no WebSocket client in this app, so this is polling,
 * not real-time push. Terminus already broadcasts over WebSocket for other
 * things, but wiring a persistent WS client into the app is a bigger, riskier
 * change than a periodic HTTP check — this is the simple version that
 * actually works.
 */
object KortanaAttention {
    private const val TAG = "KortanaAttention"
    private const val PREFS = "kortana_attention"
    private const val K_LAST_PROPOSALS = "last_proposals"
    private const val K_LAST_BLOCKED = "last_blocked"
    private const val CHANNEL = "kortana_attention"
    private const val NOTIF_ID = 4320

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Polls once; call from a periodic loop. Safe to call even if cloud sync isn't configured. */
    fun check(context: Context, base: String?, apiKey: String?) {
        val cleanBase = base?.trim()?.trimEnd('/')?.substringBefore("/api/")?.trimEnd('/')
        if (cleanBase.isNullOrBlank()) return
        try {
            val builder = Request.Builder().url("$cleanBase/api/kortana/attention")
            if (!apiKey.isNullOrBlank()) builder.addHeader("x-api-key", apiKey)
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return
                val json = JSONObject(resp.body?.string() ?: return)
                val proposals = json.optInt("pendingProposalsCount", 0)
                val blocked = json.optInt("blockedGoalsCount", 0)

                val p = prefs(context)
                val lastProposals = p.getInt(K_LAST_PROPOSALS, 0)
                val lastBlocked = p.getInt(K_LAST_BLOCKED, 0)

                if (proposals > lastProposals || blocked > lastBlocked) {
                    val parts = mutableListOf<String>()
                    if (proposals > lastProposals) parts.add("$proposals proposal${if (proposals == 1) "" else "s"} to review")
                    if (blocked > lastBlocked) parts.add("$blocked goal${if (blocked == 1) "" else "s"} waiting on you")
                    notify(context, parts.joinToString(" · "))
                }

                p.edit().putInt(K_LAST_PROPOSALS, proposals).putInt(K_LAST_BLOCKED, blocked).apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "attention check failed: ${e.message}")
        }
    }

    private fun notify(context: Context, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL, "Kortana needs your attention", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "New proposals to review or goals waiting on your part"
            nm.createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Kortana needs your part")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify blocked (POST_NOTIFICATIONS not granted): ${e.message}")
        }
    }
}
