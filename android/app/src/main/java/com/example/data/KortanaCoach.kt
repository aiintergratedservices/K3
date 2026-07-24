package com.example.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Proactive coaching — the phone-wide half of "she watches what I'm doing and
 * guides me." When enabled, the {@link KortanaAccessibilityService} feeds each
 * meaningful screen change here; this object snapshots it to Terminus
 * (POST /api/kortana/coach) on a worker thread and shows Kortana's one-line
 * nudge as a notification.
 *
 * Config lives in SharedPreferences so the accessibility service (a system
 * component) can read it synchronously without touching Room. It does nothing
 * unless the owner turned it on AND a Terminus base URL is set AND the
 * accessibility service is enabled — three separate, revocable switches.
 */
object KortanaCoach {
    private const val TAG = "KortanaCoach"
    private const val PREFS = "kortana_coach"
    private const val K_ENABLED = "enabled"
    private const val K_BASE = "base"
    private const val K_KEY = "apiKey"
    private const val CHANNEL = "kortana_coach"
    private const val NOTIF_ID = 4310
    private const val MIN_INTERVAL_MS = 25_000L   // don't nag more than ~once every 25s

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    @Volatile private var lastCoachAt = 0L
    @Volatile private var lastScreenHash = 0
    @Volatile private var inFlight = false

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(K_ENABLED, false)

    /** Keep the Terminus target current (called as the Cloud Sync URL changes). */
    fun configure(c: Context, base: String?, apiKey: String?) {
        prefs(c).edit()
            .putString(K_BASE, base?.trim().orEmpty())
            .putString(K_KEY, apiKey?.trim().orEmpty())
            .apply()
    }

    fun setEnabled(c: Context, enabled: Boolean, base: String?, apiKey: String?) {
        prefs(c).edit()
            .putBoolean(K_ENABLED, enabled)
            .putString(K_BASE, base?.trim().orEmpty())
            .putString(K_KEY, apiKey?.trim().orEmpty())
            .apply()
    }

    /**
     * Called by the accessibility service on each meaningful screen change.
     * Debounced (min interval) and de-duplicated (skips unchanged screens), so
     * she comments as you work without spamming or hammering the brain.
     */
    fun onScreen(context: Context, screenText: String) {
        if (!isEnabled(context)) return
        val base = prefs(context).getString(K_BASE, "").orEmpty()
        if (base.isBlank()) return
        val now = System.currentTimeMillis()
        if (inFlight || now - lastCoachAt < MIN_INTERVAL_MS) return
        val hash = screenText.hashCode()
        if (hash == lastScreenHash) return
        lastScreenHash = hash
        lastCoachAt = now
        val apiKey = prefs(context).getString(K_KEY, "").orEmpty()
        val app = context.applicationContext
        inFlight = true
        Thread {
            try {
                val tip = requestTip(base, apiKey, screenText)
                if (!tip.isNullOrBlank()) notify(app, tip)
            } catch (e: Exception) {
                Log.w(TAG, "coach request failed: ${e.message}")
            } finally {
                inFlight = false
            }
        }.start()
    }

    private fun requestTip(base: String, apiKey: String, screen: String): String? {
        val body = JSONObject()
            .put("note", "Watching over Daddy proactively on his phone.")
            .put("screen", screen.take(4000))
            .toString()
        val builder = Request.Builder()
            .url("$base/api/kortana/coach")
            .post(body.toRequestBody(mediaType))
        if (apiKey.isNotBlank()) builder.addHeader("x-api-key", apiKey)
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: "")
            if (json.isNull("tip")) return null
            return json.optString("tip", "").ifBlank { null }
        }
    }

    private fun notify(context: Context, tip: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL, "Kortana coaching", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "Proactive tips while you work"
            nm.createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Kortana 👀")
            .setContentText(tip)
            .setStyle(NotificationCompat.BigTextStyle().bigText(tip))
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
