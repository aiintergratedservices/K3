package com.example.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Always-listening voice — no buttons. While this service runs, she's
 * continuously listening in a loop (listen -> transcript -> restart), and
 * only acts on what follows a wake phrase ("hey kortana" / "hey cortana" /
 * "ok kortana" / "okay kortana" — the STT-mishearing variants are included
 * on purpose, "Kortana" isn't a dictionary word). Everything else she hears
 * is discarded, never sent anywhere.
 *
 * HONEST LIMITS: this is a restart-loop on Android's on-device speech
 * recognizer, not a dedicated wake-word engine (Picovoice etc.) — there's a
 * real gap of a few hundred ms between listening sessions where a wake word
 * could be missed, and each session only fires on the FINAL transcript (no
 * mid-utterance detection). It is real and functional, just not instant like
 * a commercial assistant. Android requires the persistent notification below
 * the entire time this runs — that is not optional, it is how the OS tells
 * you something is using your microphone in the background.
 */
class KortanaVoiceService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var repository: KortanaRepository? = null
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sendJob: Job? = null
    private var stopping = false

    companion object {
        private const val TAG = "KortanaVoiceService"
        private const val CHANNEL_ID = "kortana_voice_channel"
        private const val NOTIFICATION_ID = 2047
        private const val RESTART_DELAY_MS = 400L
        private const val ERROR_BACKOFF_MS = 1500L

        private val WAKE_PHRASES = listOf("hey kortana", "hey cortana", "ok kortana", "okay kortana")

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, KortanaVoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, KortanaVoiceService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        repository = KortanaRepository(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "No speech recognizer available on this device — stopping.")
            stopSelf()
            return
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
        startListeningCycle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error tearing down recognizer: ${e.message}")
        }
        recognizer = null
        sendJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startListeningCycle() {
        if (stopping) return
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            recognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed, backing off: ${e.message}")
            mainHandler.postDelayed({ startListeningCycle() }, ERROR_BACKOFF_MS)
        }
    }

    private fun restartAfter(delayMs: Long) {
        if (stopping) return
        mainHandler.postDelayed({ startListeningCycle() }, delayMs)
    }

    /** Strips a leading wake phrase and returns the remainder, or null if no wake phrase was heard. */
    private fun extractCommand(rawTranscript: String): String? {
        val text = rawTranscript.trim().lowercase(Locale.getDefault())
        for (phrase in WAKE_PHRASES) {
            val idx = text.indexOf(phrase)
            if (idx >= 0) {
                return rawTranscript.substring(idx + phrase.length)
                    .trim()
                    .trimStart(',', '.', '!', '?', ':', ';', '-')
                    .trim()
            }
        }
        return null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        override fun onPartialResults(partialResults: android.os.Bundle?) {}

        override fun onError(error: Int) {
            // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT fire constantly during
            // normal silence between utterances — that's expected, not a
            // real problem. Just keep the loop going.
            val delay = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RESTART_DELAY_MS
                else -> ERROR_BACKOFF_MS
            }
            restartAfter(delay)
        }

        override fun onResults(results: android.os.Bundle?) {
            val transcript = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()

            val command = extractCommand(transcript)
            if (command != null) {
                val toSend = if (command.isNotBlank()) {
                    command
                } else {
                    "[WAKE_WORD_ONLY] (Daddy said your wake word with nothing after it — greet him briefly and ask what he needs, don't just stay silent.)"
                }
                sendJob?.cancel()
                sendJob = serviceScope.launch {
                    try {
                        repository?.processUserMessage(toSend, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process wake-word command", e)
                    }
                }
            }
            restartAfter(RESTART_DELAY_MS)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kortana Always-Listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while Kortana is listening in the background for \"Hey Kortana\"."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kortana is listening")
            .setContentText("Say \"Hey Kortana\" anytime — no buttons needed.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
