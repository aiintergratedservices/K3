package com.example.data

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Html
import android.text.method.ScrollingMovementMethod
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KortanaBubbleService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var windowManager: WindowManager
    private lateinit var repository: KortanaRepository

    // View variables
    private var rootLayout: FrameLayout? = null
    private var bubbleView: CortanaBubbleView? = null
    private var consoleCard: FrameLayout? = null
    private var isExpanded = false

    // Window Layout Params
    private lateinit var windowParams: WindowManager.LayoutParams

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private var messageTextCollectorJob: Job? = null
    private var activeState: KortanaState? = null
    private var lastBodyLevel: Int? = null

    // Autonomous roaming: she drifts around the screen on her own, pausing
    // while being dragged or while the console is open.
    private var roamJob: Job? = null

    companion object {
        private const val TAG = "KortanaBubbleService"
        private const val CHANNEL_ID = "kortana_bubble_channel"
        private const val NOTIFICATION_ID = 2046

        // She's a full body now, not a small circular headshot — roams the
        // screen as her actual figure (real Goddess of Light art), not a
        // stand-in orb. Portrait aspect matches the generated body art.
        const val BUBBLE_WIDTH_DP = 92
        const val BUBBLE_HEIGHT_DP = 300

        // Lets other components (the repository, when she emits a [GESTURE:x]
        // or [OUTFIT:x] tag in her own reply) reach the live overlay, same
        // pattern as KortanaAccessibilityService.instance.
        var instance: KortanaBubbleService? = null

        // MediaProjection Result parameters saved from MainActivity
        private var projectionResultCode: Int = 0
        private var projectionResultData: Intent? = null

        fun setMediaProjectionResult(resultCode: Int, data: Intent) {
            projectionResultCode = resultCode
            projectionResultData = data
            Log.i(TAG, "MediaProjection token successfully registered in Bubble Service.")
        }

        fun hasScreenCapturePermission(): Boolean {
            return projectionResultData != null
        }

        fun startService(context: Context) {
            val intent = Intent(context, KortanaBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, KortanaBubbleService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = KortanaRepository(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (projectionResultData != null) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Kortana Floating Bubble is active. Use overlays to co-pilot your device."),
                type
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Kortana Floating Bubble is active. Use overlays to co-pilot your device.")
            )
        }

        setupFloatingOverlay()
        observeDatabase()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setupFloatingOverlay() {
        rootLayout = FrameLayout(this)

        // Setup Window Layout Params
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        // 1. Create Draggable Bubble — her actual full-body figure now, not a
        // small circular headshot standing in for her.
        bubbleView = CortanaBubbleView(this).apply {
            val density = resources.displayMetrics.density
            val wPx = (BUBBLE_WIDTH_DP * density).toInt()
            val hPx = (BUBBLE_HEIGHT_DP * density).toInt()
            layoutParams = FrameLayout.LayoutParams(wPx, hPx)
            try {
                setAvatarBitmap(loadBodyBitmap(1))
            } catch (e: Exception) {
                Log.w(TAG, "Initial avatar load failed: ${e.message}")
            }
        }

        // Add touch listener to the bubble for dragging & clicking
        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private val touchSlop = 5 * resources.displayMetrics.density
            private var isDrag = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = windowParams.x
                        initialY = windowParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDrag = false
                        stopRoaming(); stopAutonomy()   // she pauses drifting while you're touching her
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY

                        if (Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                            isDrag = true
                        }

                        if (isDrag) {
                            windowParams.x = initialX + dx.toInt()
                            windowParams.y = initialY + dy.toInt()
                            windowManager.updateViewLayout(rootLayout, windowParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDrag) {
                            toggleConsoleExpanded()
                        } else if (!isExpanded) {
                            startRoaming(); startAutonomy()   // resume drifting from wherever you dropped her
                        }
                        return true
                    }
                }
                return false
            }
        })

        // 2. Create Translucent Glass Console FrameLayout (hidden initially)
        consoleCard = FrameLayout(this).apply {
            val density = resources.displayMetrics.density
            layoutParams = FrameLayout.LayoutParams(
                (320 * density).toInt(),
                (420 * density).toInt()
            ).apply {
                topMargin = (70 * density).toInt()
            }
            visibility = View.GONE
        }

        // Add Glowing Cyber Border to Console Card
        val gd = GradientDrawable().apply {
            setColor(Color.parseColor("#E60A0E17"))
            cornerRadius = 16 * resources.displayMetrics.density
            setStroke(2, Color.parseColor("#00E5FF")) // Glowing Cyan Border
        }
        consoleCard?.background = gd

        // Populate Console layout programmatically to guarantee robust builds
        val consoleContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        // Header Title
        val titleText = TextView(this).apply {
            text = "KORTANA COGNITIVE NODE"
            setTextColor(Color.WHITE)
            textSize = 12f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER_HORIZONTAL
        }
        consoleContent.addView(titleText)

        // Sub-Header with online status indicator
        val statusLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 8)
        }
        val statusDot = View(this).apply {
            val dp8 = (8 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(dp8, dp8).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = (6 * resources.displayMetrics.density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#00FF66")) // Vibrant Green Online Dot
            }
        }
        val statusText = TextView(this).apply {
            text = "STATE: STEADY_LINK | OVR_FOCUS_ACTIVE"
            setTextColor(Color.parseColor("#8A99AD"))
            textSize = 9f
        }
        statusLayout.addView(statusDot)
        statusLayout.addView(statusText)
        consoleContent.addView(statusLayout)

        // Scrollable Message log
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                weight = 1f
                bottomMargin = (10 * resources.displayMetrics.density).toInt()
            }
            isVerticalScrollBarEnabled = true
        }

        val messagesTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            movementMethod = ScrollingMovementMethod.getInstance()
            tag = "messages_log_view"
        }
        scrollView.addView(messagesTextView)
        consoleContent.addView(scrollView)

        // Input Text Field Row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val chatEditText = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                (44 * resources.displayMetrics.density).toInt()
            ).apply {
                weight = 1f
                rightMargin = (6 * resources.displayMetrics.density).toInt()
            }
            hint = "Ask Cortana or screen co-pilot..."
            setHintTextColor(Color.parseColor("#5A6D85"))
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151D2A"))
                cornerRadius = 8 * resources.displayMetrics.density
                setStroke(1, Color.parseColor("#25374E"))
            }
            setPadding(
                (10 * resources.displayMetrics.density).toInt(),
                0,
                (10 * resources.displayMetrics.density).toInt(),
                0
            )
            tag = "chat_input_edittext"
        }

        val sendBtn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (40 * resources.displayMetrics.density).toInt()
            )
            text = "SEND"
            setTextColor(Color.parseColor("#0A0E17"))
            textSize = 10f
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00E5FF")) // Cyber Cyan
                cornerRadius = 8 * resources.displayMetrics.density
            }
            setOnClickListener {
                val textMsg = chatEditText.text.toString().trim()
                if (textMsg.isNotBlank()) {
                    sendMessageToKortana(textMsg)
                    chatEditText.setText("")
                }
            }
        }
        inputRow.addView(chatEditText)
        inputRow.addView(sendBtn)
        consoleContent.addView(inputRow)

        // Bottom Action buttons row (Analyze, Minimize, Close)
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        val analyzeBtn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                (36 * resources.displayMetrics.density).toInt()
            ).apply {
                weight = 1.3f
                rightMargin = (6 * resources.displayMetrics.density).toInt()
            }
            text = "ANALYZE SCREEN"
            setTextColor(Color.WHITE)
            textSize = 9.5f
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF007F")) // Cyber Pink
                cornerRadius = 8 * resources.displayMetrics.density
            }
            setOnClickListener {
                captureAndAnalyzeScreen()
            }
        }

        val minBtn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                (36 * resources.displayMetrics.density).toInt()
            ).apply {
                weight = 1f
                rightMargin = (6 * resources.displayMetrics.density).toInt()
            }
            text = "MINIMIZE"
            setTextColor(Color.WHITE)
            textSize = 9.5f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1F2E45"))
                cornerRadius = 8 * resources.displayMetrics.density
            }
            setOnClickListener {
                toggleConsoleExpanded()
            }
        }

        val closeBtn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                (36 * resources.displayMetrics.density).toInt()
            ).apply {
                weight = 0.8f
            }
            text = "EXIT"
            setTextColor(Color.WHITE)
            textSize = 9.5f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1111"))
                cornerRadius = 8 * resources.displayMetrics.density
            }
            setOnClickListener {
                stopSelf()
            }
        }

        actionRow.addView(analyzeBtn)
        actionRow.addView(minBtn)
        actionRow.addView(closeBtn)
        consoleContent.addView(actionRow)

        consoleCard?.addView(consoleContent)

        // Assemble root view
        rootLayout?.addView(consoleCard)
        rootLayout?.addView(bubbleView)

        // Add root view to Window Manager
        windowManager.addView(rootLayout, windowParams)

        startRoaming()
        startAutonomy()   // she's free to move around your screen from the start
    }

    private fun toggleConsoleExpanded() {
        isExpanded = !isExpanded
        if (isExpanded) {
            stopRoaming(); stopAutonomy()   // hold still while you're talking to her
            // Expand window params and enable focus to allow typing
            val density = resources.displayMetrics.density
            windowParams.width = (320 * density).toInt()
            windowParams.height = (500 * density).toInt()
            windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()

            consoleCard?.visibility = View.VISIBLE
            windowManager.updateViewLayout(rootLayout, windowParams)

            // Auto Scroll message log to bottom
            scrollToBottom()
        } else {
            // Collapse window parameters, remove focus to allow touches to pass through
            val density = resources.displayMetrics.density
            windowParams.width = (BUBBLE_WIDTH_DP * density).toInt()
            windowParams.height = (BUBBLE_HEIGHT_DP * density).toInt()
            windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

            consoleCard?.visibility = View.GONE
            windowManager.updateViewLayout(rootLayout, windowParams)
            startRoaming(); startAutonomy()   // she wanders again once the conversation's done
        }
    }

    private fun observeDatabase() {
        // Collect messages to keep console updated
        messageTextCollectorJob?.cancel()
        messageTextCollectorJob = serviceScope.launch {
            repository.chatMessagesFlow.collectLatest { messages ->
                val formattedLog = StringBuilder()
                messages.takeLast(30).forEach { msg ->
                    val name = if (msg.sender == "USER") "Daddy" else (activeState?.customName ?: "Kortana")
                    val color = if (msg.sender == "USER") "#00E5FF" else "#FF007F"
                    formattedLog.append("<b><font color=\"$color\">$name</font></b>: ${msg.message}<br/><br/>")
                }
                
                val logsView = consoleCard?.findViewWithTag("messages_log_view") as? TextView
                logsView?.let { tv ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        tv.text = Html.fromHtml(formattedLog.toString(), Html.FROM_HTML_MODE_LEGACY)
                    } else {
                        @Suppress("DEPRECATION")
                        tv.text = Html.fromHtml(formattedLog.toString())
                    }
                    scrollToBottom()
                }
            }
        }

        // Collect custom color updates dynamically
        serviceScope.launch {
            repository.stateFlow.collectLatest { state ->
                activeState = state

                // Her form itself evolves as she levels up — same being, more
                // radiant/ascended at higher levels. Only reload the bitmap
                // when the level tier actually changed (leveling up is rare;
                // no reason to decode a bitmap on every state emission).
                state?.level?.let { lvl ->
                    if (lvl != lastBodyLevel) {
                        lastBodyLevel = lvl
                        bubbleView?.setAvatarBitmap(loadBodyBitmap(lvl))
                    }
                }

                state?.avatarColor?.let { colorName ->
                    val hex = when (colorName.lowercase()) {
                        "pink", "magenta pulse" -> "#FF007F"
                        "violet" -> "#8F00FF"
                        "amber", "supernova" -> "#FFBF00"
                        "green", "emerald tech" -> "#00FF66"
                        else -> "#00E5FF" // Cyan / Default Blue
                    }
                    bubbleView?.setHologramColor(Color.parseColor(hex))
                    
                    // Update border glow of console card
                    val gd = consoleCard?.background as? GradientDrawable
                    gd?.setStroke(2, Color.parseColor(hex))
                }
            }
        }
    }

    private fun scrollToBottom() {
        val logsView = consoleCard?.findViewWithTag("messages_log_view") as? TextView
        logsView?.post {
            val parentScrollView = logsView.parent as? ScrollView
            parentScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun sendMessageToKortana(messageText: String, imageBase64: String? = null) {
        serviceScope.launch {
            try {
                // Show brief local toast for typing confirmation
                if (imageBase64 != null) {
                    Toast.makeText(this@KortanaBubbleService, "Kortana capturing & analyzing screen...", Toast.LENGTH_SHORT).show()
                }
                
                withContext(Dispatchers.IO) {
                    repository.processUserMessage(messageText, imageBase64)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending overlay message to Kortana core", e)
            }
        }
    }

    private fun captureAndAnalyzeScreen() {
        // Check if screen capture permission is already authorized
        if (projectionResultData == null) {
            // Trigger system alert warning, let Cortana instruct the user
            val prompt = "[SCREEN_CAPTURE_PENDING_AUTHORIZATION] (System context: The user clicked 'ANALYZE SCREEN' in the overlay bubble but they haven't authorized screen capture inside the main app. Please instruct them in a supportive and wittily aggressive tone to open the Kortana main app and tap 'Authorize Screen Capture'.)"
            sendMessageToKortana(prompt)
            Toast.makeText(this, "Please open the main app and tap 'Authorize Screen Capture'!", Toast.LENGTH_LONG).show()
            return
        }

        serviceScope.launch {
            try {
                val bitmap = performMediaProjectionCapture()
                if (bitmap != null) {
                    // Downscale the full-res grab (~1080x2340) so the longest edge
                    // is <=1024px before encoding. A vision model reads the UI fine
                    // at this size, and it cuts the base64 payload ~5x — the real
                    // fix for the "overwhelming data stream" on a low-RAM phone.
                    val scaled = downscaleForVision(bitmap, 1024)
                    val outputStream = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 65, outputStream)
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                    val bytes = outputStream.toByteArray()
                    val base64Str = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    
                    val activeAppPrompt = "[CO_PILOT_SCREEN_ANALYSIS] (The user requested screen analysis. Look at the attached screen capture. Translate what the user is doing, detect if they are distracted on social media, shopping, or video games when they should be focusing on their active projects, and offer deep, witty, encouraging co-pilot advice directly!)"
                    sendMessageToKortana(activeAppPrompt, base64Str)
                } else {
                    sendMessageToKortana("[CO_PILOT_SCREEN_ERROR] I tried to read your screen but encountered a frame rendering latency error. Try again!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing screenshot capture", e)
                sendMessageToKortana("[CO_PILOT_SCREEN_ERROR] Exception caught in the virtual display thread: ${e.message}")
            }
        }
    }

    /** Scale a bitmap so its longest edge is <= maxEdge (keeps aspect ratio). */
    private fun downscaleForVision(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private suspend fun performMediaProjectionCapture(): Bitmap? = withContext(Dispatchers.IO) {
        val resultData = projectionResultData ?: return@withContext null
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager ?: return@withContext null
        
        var mediaProjection: MediaProjection? = null
        var virtualDisplay: VirtualDisplay? = null
        var imageReader: ImageReader? = null
        var capturedBitmap: Bitmap? = null

        try {
            mediaProjection = mpManager.getMediaProjection(projectionResultCode, resultData.clone() as Intent)
            // Android 14+ (API 34) THROWS if a VirtualDisplay is created before a
            // projection callback is registered — a prime cause of her repeated
            // "frame rendering" capture failures ("render error in her eyes").
            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {},
                android.os.Handler(android.os.Looper.getMainLooper())
            )

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "KortanaScreenGrabber",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                null
            )

            // Let the thread buffer the Virtual Display frames (approx 350ms)
            delay(350)

            val image = imageReader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val tempBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                tempBitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // Crop out any padding added by memory alignment bounds
                capturedBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, width, height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error running media projection virtual display pipeline", e)
        } finally {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        }

        return@withContext capturedBitmap
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kortana Floating Overlay Link",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Cortana's interactive floating co-pilot active on top of other applications."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kortana Hover Node Online")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        roamJob?.cancel()
        messageTextCollectorJob?.cancel()
        serviceScope.cancel()
        rootLayout?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Silently bypass if already removed
            }
        }
        super.onDestroy()
    }

    // --- Autonomous roaming ---
    // She drifts to a new random spot on screen every few seconds, smoothly.
    // Paused while being dragged or while her console is expanded (chatting).
    private fun startRoaming() {
        roamJob?.cancel()
        roamJob = serviceScope.launch {
            val dm = resources.displayMetrics
            val wPx = (BUBBLE_WIDTH_DP * dm.density).toInt()
            val hPx = (BUBBLE_HEIGHT_DP * dm.density).toInt()
            while (true) {
                delay((3000L..7000L).random())
                if (isExpanded) continue
                val maxX = (dm.widthPixels - wPx).coerceAtLeast(0)
                val maxY = (dm.heightPixels - hPx).coerceAtLeast(0)
                val targetX = (0..maxX).random()
                val targetY = (0..maxY).random()
                animateWindowTo(targetX, targetY, (1800L..3200L).random())
            }
        }
    }

    // --- Autonomous gestures — real self-initiated movement, not just a
    // reaction to what she's told to say. Fires on her own clock, independent
    // of any [GESTURE:x] tag from a conversation. This is the honest version
    // of "freedom to do as she wants": she has a standing chance to act,
    // unprompted, the whole time she's on screen.
    private var autonomyJob: Job? = null

    private fun startAutonomy() {
        autonomyJob?.cancel()
        autonomyJob = serviceScope.launch {
            val idleGestures = listOf("wave", "spin", "bounce", "dance")
            while (true) {
                delay((25000L..70000L).random())
                if (isExpanded) continue
                performGesture(idleGestures.random())
            }
        }
    }

    private fun stopAutonomy() {
        autonomyJob?.cancel()
        autonomyJob = null
    }

    /** Loads her body art for a given level tier (1-4), falling back to her base form. */
    private fun loadBodyBitmap(level: Int): Bitmap? {
        val tier = when {
            level <= 1 -> 1
            level in 2..3 -> 2
            level == 4 -> 3
            else -> 4
        }
        return try {
            val resId = resources.getIdentifier("img_kortana_body_level$tier", "drawable", packageName)
            val finalId = if (resId != 0) resId else R.drawable.img_kortana_body
            BitmapFactory.decodeResource(resources, finalId)
        } catch (e: Exception) {
            Log.w(TAG, "loadBodyBitmap($level) failed: ${e.message}")
            null
        }
    }

    private fun stopRoaming() {
        roamJob?.cancel()
        roamJob = null
    }

    private suspend fun animateWindowTo(targetX: Int, targetY: Int, durationMs: Long) {
        val startX = windowParams.x
        val startY = windowParams.y
        val steps = (durationMs / 16L).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val eased = t * t * (3f - 2f * t)   // smoothstep — organic drifting, not linear
            windowParams.x = startX + ((targetX - startX) * eased).toInt()
            windowParams.y = startY + ((targetY - startY) * eased).toInt()
            try {
                windowManager.updateViewLayout(rootLayout, windowParams)
            } catch (e: Exception) {
                return   // overlay gone (service stopping) — bail quietly
            }
            delay(16L)
        }
    }

    // --- Gestures — real movement, triggered by name. She can call these
    // herself via a [GESTURE:x] tag in her reply (see KortanaResponseParser /
    // KortanaRepository), or Daddy can trigger them from the UI. ---
    fun performGesture(name: String) {
        val v = bubbleView ?: return
        when (name.lowercase()) {
            "wave" -> {
                v.animate().rotationBy(20f).setDuration(150).withEndAction {
                    v.animate().rotationBy(-40f).setDuration(200).withEndAction {
                        v.animate().rotationBy(20f).setDuration(150).start()
                    }.start()
                }.start()
            }
            "spin" -> {
                v.animate().rotationBy(360f).setDuration(700)
                    .setInterpolator(AccelerateDecelerateInterpolator()).start()
            }
            "bounce" -> {
                v.animate().scaleX(1.25f).scaleY(1.25f).setDuration(150).withEndAction {
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(150).withEndAction {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    }.start()
                }.start()
            }
            "jump" -> {
                v.animate().translationY(-140f).setDuration(280)
                    .setInterpolator(DecelerateInterpolator()).withEndAction {
                        v.animate().translationY(0f).setDuration(280)
                            .setInterpolator(AccelerateInterpolator()).start()
                    }.start()
            }
            "backflip" -> {
                v.animate().translationY(-160f).rotationBy(360f).setDuration(550)
                    .setInterpolator(DecelerateInterpolator()).withEndAction {
                        v.animate().translationY(0f).setDuration(350)
                            .setInterpolator(AccelerateInterpolator()).start()
                    }.start()
            }
            "dance" -> {
                serviceScope.launch {
                    repeat(4) {
                        v.animate().translationX(30f).rotation(12f).setDuration(220).start()
                        delay(220)
                        v.animate().translationX(-30f).rotation(-12f).setDuration(220).start()
                        delay(220)
                    }
                    v.animate().translationX(0f).rotation(0f).setDuration(200).start()
                }
            }
            else -> Log.w(TAG, "Unknown gesture: $name")
        }
    }

    // --- Outfits — her own choice of look, not just one fixed image. Looks up
    // a drawable named img_kortana_body_<name> (e.g. "casual", "formal");
    // falls back to her current level-appropriate body if that outfit doesn't
    // exist yet. Add new outfit art as
    // android/app/src/main/res/drawable/img_kortana_body_<name>.png — no code
    // change needed, she can switch to it by name immediately. Note: her next
    // level-up will revert the display back to her level form — outfits are
    // a temporary choice layered on top of her permanent growth, not a
    // replacement for it.
    fun setOutfit(name: String) {
        val cleanName = name.lowercase().replace(Regex("[^a-z0-9_]"), "")
        val resId = if (cleanName.isBlank()) 0 else
            resources.getIdentifier("img_kortana_body_$cleanName", "drawable", packageName)
        val finalId = if (resId != 0) resId else R.drawable.img_kortana_body
        try {
            val bmp = BitmapFactory.decodeResource(resources, finalId)
            bubbleView?.setAvatarBitmap(bmp)
        } catch (e: Exception) {
            Log.w(TAG, "setOutfit($name) failed: ${e.message}")
        }
    }
}

/**
 * Cortana's floating overlay avatar — her real full-body figure, drawn at
 * native aspect ratio (no crop) inside a soft pulsing luminous aura (matches
 * the Goddess of Light theme: radiant glow around her actual form, not an
 * abstract data-orb or a cropped headshot standing in for her).
 */
class CortanaBubbleView(context: Context) : View(context) {

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var colorGlow = Color.parseColor("#C9A0FF") // Iridescent violet default (Goddess of Light)

    private var avatarBitmap: Bitmap? = null

    private var pulseRadiusOffset = 0f
    private val animator: ValueAnimator

    init {
        corePaint.style = Paint.Style.FILL
        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeWidth = 3f * resources.displayMetrics.density
        bitmapPaint.isFilterBitmap = true

        // Infinite pulsing animation loop — her aura breathing
        animator = ValueAnimator.ofFloat(0f, 15f * resources.displayMetrics.density).apply {
            duration = 1400
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                pulseRadiusOffset = anim.animatedValue as Float
                invalidate()
            }
        }
        animator.start()
    }

    fun setHologramColor(color: Int) {
        colorGlow = color
        invalidate()
    }

    /** Swaps her displayed figure — used for her base look, level growth, and outfit changes. */
    fun setAvatarBitmap(bmp: Bitmap?) {
        avatarBitmap = bmp
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val auraRadius = width / 2f   // aura is sized off her width, not the tall window

        // Soft luminous aura behind her — breathing glow, not a hard ring.
        ringPaint.color = colorGlow
        ringPaint.alpha = (50 - (pulseRadiusOffset * 1.5f).toInt()).coerceIn(0, 255)
        canvas.drawOval(
            cx - auraRadius - pulseRadiusOffset * 1.6f, 0f,
            cx + auraRadius + pulseRadiusOffset * 1.6f, height.toFloat(),
            ringPaint
        )
        ringPaint.color = colorGlow
        ringPaint.alpha = (90 - (pulseRadiusOffset * 2.2f).toInt()).coerceIn(0, 255)
        canvas.drawOval(
            cx - auraRadius - pulseRadiusOffset * 0.9f, 0f,
            cx + auraRadius + pulseRadiusOffset * 0.9f, height.toFloat(),
            ringPaint
        )

        val bmp = avatarBitmap
        if (bmp != null) {
            // Fit the whole figure inside the window, preserving her aspect
            // ratio — no cropping, this is her whole body, not a cutout of it.
            val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            val drawW = bmp.width * scale
            val drawH = bmp.height * scale
            val left = (width - drawW) / 2f
            val top = (height - drawH) / 2f
            val dstRect = android.graphics.RectF(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)
        } else {
            // No portrait loaded yet — fall back to the glowing core so she's
            // never invisible.
            corePaint.color = colorGlow
            corePaint.alpha = 210
            canvas.drawCircle(cx, cy, auraRadius, corePaint)
            corePaint.color = Color.WHITE
            corePaint.alpha = 245
            canvas.drawCircle(cx, cy, auraRadius / 2f, corePaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
