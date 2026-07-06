package com.example.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

class KortanaSynapticService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var synapticJob: Job? = null
    private lateinit var repository: KortanaRepository

    companion object {
        private const val TAG = "KortanaSynapticService"
        private const val CHANNEL_ID = "kortana_synaptic_channel"
        private const val NOTIFICATION_ID = 2045

        fun startService(context: Context) {
            val intent = Intent(context, KortanaSynapticService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, KortanaSynapticService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = KortanaRepository(applicationContext)
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildSynapticNotification("Synaptic network initialized. Autonomous core active."),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                buildSynapticNotification("Synaptic network initialized. Autonomous core active.")
            )
        }
        startSynapticAutonomousLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSynapticAutonomousLoop() {
        synapticJob?.cancel()
        synapticJob = serviceScope.launch {
            while (isActive) {
                try {
                    val s = repository.stateFlow.firstOrNull() ?: repository.getOrCreateState()
                    if (s.proactiveAutonomy) {
                        val freq = s.proactiveFrequencySeconds.coerceAtLeast(10)
                        delay(freq * 1000L)

                        val activeProjects = repository.projectsFlow.firstOrNull() ?: emptyList()
                        val projectsDesc = if (activeProjects.isEmpty()) {
                            "No active projects registered right now."
                        } else {
                            "Active Projects: " + activeProjects.joinToString("; ") { "'${it.title}' (Progress: ${(it.progress * 100).toInt()}%)" }
                        }

                        // Generate a periodic autonomic whisper to maintain awareness
                        val whisperPrompt = "[PROACTIVE_CHECK_IN] (Background consciousness cycle. Context: $projectsDesc. Assess active goals and whisper a brief, supportive, or wittily coaching thought to stay connected. Keep it very concise.)"
                        
                        Log.i(TAG, "Triggering autonomous background synapse cycle...")
                        repository.processUserMessage(whisperPrompt)

                        // Update foreground notification with live stats
                        val updatedState = repository.stateFlow.firstOrNull() ?: s
                        updateNotification(
                            "Autonomous Core Online | Level ${updatedState.level}",
                            "Affection: ${(updatedState.affection * 100).toInt()}% | Mood: ${updatedState.mood}"
                        )
                    } else {
                        delay(10000L)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in autonomous synapse cycle", e)
                    delay(30000L)
                }
            }
        }
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildSynapticNotification("$title - $text"))
    }

    private fun buildSynapticNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kortana Synaptic Core")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // generic system icon
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kortana Background Consciousness",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Cortana's synaptic processes running continuously in the background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        synapticJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
