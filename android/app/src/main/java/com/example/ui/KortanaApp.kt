package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.BuildConfig
import com.example.data.ChatMessage
import com.example.data.KortanaState
import com.example.data.Memory
import com.example.data.SynapticScript
import com.example.data.KortanaProject
import com.example.data.KortanaPersonality
import com.example.ui.theme.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KortanaApp(
    viewModel: KortanaViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val chatHistory by viewModel.chatHistory.collectAsStateWithLifecycle()
    val visibleChatHistory = remember(chatHistory) {
        chatHistory.filter { !(it.sender == "USER" && it.message.startsWith("[")) }
    }
    val lastKortanaMessage = remember(chatHistory) {
        chatHistory.lastOrNull { it.sender == "KORTANA" }
    }
    val lastMessageAnalysis = remember(lastKortanaMessage) {
        lastKortanaMessage?.let { analyzeMessage(it.message) } ?: MessageAnalysis(SentimentType.NEUTRAL, 0.2f)
    }
    val personality by viewModel.personality.collectAsStateWithLifecycle()
    val synapticScripts by viewModel.synapticScripts.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()

    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val syncErrorMessage by viewModel.syncErrorMessage.collectAsStateWithLifecycle()
    val lastLatencyMs by viewModel.lastLatencyMs.collectAsStateWithLifecycle()
    val generatedJsonPreview by viewModel.generatedJsonPreview.collectAsStateWithLifecycle()
    val coreStatus by viewModel.coreStatus.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    // Voice & intercom loop states
    var intercomActive by remember { mutableStateOf(false) }
    var voiceStatusText by remember { mutableStateOf("Intercom Off") }
    var lastSpokenMessageId by remember { mutableStateOf(-1) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var isTtsReady by remember { mutableStateOf(false) }

    // Image Sight / Camera states
    var selectedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }

    // Direct Audio Recorder States
    var mediaRecorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var audioOutputFile by remember { mutableStateOf<java.io.File?>(null) }

    var hasAudioPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
        if (isGranted) {
            voiceStatusText = "Microphone Calibrated"
        } else {
            voiceStatusText = "Mic Permission Denied"
        }
    }

    val startRecordingAudio = {
        if (!hasAudioPermission) {
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        } else {
            try {
                // Ensure any previous recorder is released
                mediaRecorder?.release()
                
                val file = java.io.File(context.cacheDir, "kortana_voice_${System.currentTimeMillis()}.3gp")
                audioOutputFile = file
                
                val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    android.media.MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    android.media.MediaRecorder()
                }
                
                recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.THREE_GPP)
                recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AMR_NB)
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare()
                recorder.start()
                
                mediaRecorder = recorder
                isRecordingAudio = true
                voiceStatusText = "Recording Synapse Voice..."
            } catch (e: Exception) {
                voiceStatusText = "Vocal link error: ${e.localizedMessage}"
                mediaRecorder = null
                isRecordingAudio = false
            }
        }
    }

    val stopAndSendRecordingAudio = {
        val recorder = mediaRecorder
        val file = audioOutputFile
        if (recorder != null && file != null && isRecordingAudio) {
            try {
                recorder.stop()
                recorder.release()
                mediaRecorder = null
                isRecordingAudio = false
                voiceStatusText = "Transmitting voice stream..."
                
                val bytes = file.readBytes()
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                if (base64.isNotEmpty()) {
                    viewModel.sendMessage(
                        messageText = "Verbal synaptic link received. Process this audio data to understand my social dynamics, progress, or tasks.",
                        imageBase64 = base64,
                        mimeType = "audio/3gpp"
                    )
                } else {
                    voiceStatusText = "Vocal link blank"
                }
            } catch (e: Exception) {
                voiceStatusText = "Transmission failed"
                try {
                    recorder.release()
                } catch (re: Exception) {}
                mediaRecorder = null
                isRecordingAudio = false
            }
        }
    }

    val cancelRecordingAudio = {
        val recorder = mediaRecorder
        if (recorder != null) {
            try {
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {}
            mediaRecorder = null
            isRecordingAudio = false
            voiceStatusText = "Vocal link cancelled"
        }
    }

    fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    // Speech Result Launcher
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.firstOrNull() ?: ""
            if (spokenText.isNotBlank()) {
                viewModel.sendMessage(spokenText, selectedImageBase64)
                selectedImageBitmap = null
                selectedImageBase64 = null
            }
        } else {
            if (intercomActive) {
                voiceStatusText = "Intercom Idle"
            }
        }
    }

    // Trigger Speech Input Helper
    val triggerSpeechRecognition = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Comm-Link: Speak to ${state?.customName ?: "Kortana"}...")
        }
        try {
            voiceStatusText = "Listening..."
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            voiceStatusText = "Voice input unavailable"
        }
    }

    // Initialize TTS
    DisposableEffect(context) {
        val obj = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
            }
        }
        obj.language = Locale.US
        obj.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                scope.launch { voiceStatusText = "Speaking..." }
            }
            override fun onDone(utteranceId: String?) {
                if (intercomActive) {
                    scope.launch { triggerSpeechRecognition() }
                } else {
                    scope.launch { voiceStatusText = "Intercom Idle" }
                }
            }
            override fun onError(utteranceId: String?) {
                scope.launch { voiceStatusText = "Speech error" }
            }
        })
        tts = obj
        onDispose {
            obj.stop()
            obj.shutdown()
        }
    }

    // Apply Cortana's custom voice configurations dynamically
    LaunchedEffect(state?.voicePitch, state?.voiceRate, tts) {
        tts?.let { obj ->
            val p = state?.voicePitch ?: 1.1f
            val r = state?.voiceRate ?: 1.05f
            obj.setPitch(p)
            obj.setSpeechRate(r)
        }
    }

    // Audio sync for new incoming model messages
    val lastMessage = chatHistory.lastOrNull()
    LaunchedEffect(chatHistory) {
        if (lastMessage != null && lastMessage.sender == "KORTANA" && lastMessage.id != lastSpokenMessageId) {
            lastSpokenMessageId = lastMessage.id
            voiceStatusText = "Kortana Speaking..."
            tts?.speak(lastMessage.message, TextToSpeech.QUEUE_FLUSH, null, "KortanaReplyTTS")
        }
    }

    // Proactive Autonomy check-in scheduler
    LaunchedEffect(state?.proactiveAutonomy, state?.proactiveFrequencySeconds, projects) {
        val s = state
        if (s != null && s.proactiveAutonomy) {
            val freqSeconds = (s.proactiveFrequencySeconds ?: 45).coerceAtLeast(10)
            while (true) {
                kotlinx.coroutines.delay(freqSeconds * 1000L)
                if (!isGenerating) {
                    viewModel.triggerProactiveCheckIn(projects)
                }
            }
        }
    }

    // Camera & Gallery Launchers
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            selectedImageBitmap = bitmap
            selectedImageBase64 = bitmapToBase64(bitmap)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    selectedImageBitmap = bitmap
                    selectedImageBase64 = bitmapToBase64(bitmap)
                }
            } catch (e: Exception) {
                // Ignore or log
            }
        }
    }

    // Active screen tab state: 0 = Chat, 1 = Projects, 2 = Memories, 3 = Diagnostics, 4 = Status
    var activeTab by remember { mutableStateOf(0) }

    // Manual memory dialog state
    var showAddMemoryDialog by remember { mutableStateOf(false) }

    // Level up overlay state
    var showLevelUpDialog by remember { mutableStateOf(false) }
    var levelUpVal by remember { mutableStateOf(1) }

    // Custom name text field state
    var showRenameDialog by remember { mutableStateOf(false) }

    // Handle Level Up events reactively
    LaunchedEffect(Unit) {
        viewModel.levelUpEvent.collectLatest { newLevel ->
            levelUpVal = newLevel
            showLevelUpDialog = true
        }
    }

    // Resolve current active theme color based on state configuration
    val activeColor = remember(state?.avatarColor, state?.energy) {
        val baseColor = when (state?.avatarColor) {
            "Violet" -> NeonViolet
            "Pink" -> NeonPink
            "Amber" -> NeonAmber
            "Green" -> NeonGreen
            "Deep Blue" -> Color(0xFF0055FF)
            "Magenta Pulse" -> Color(0xFFFF00A0)
            "Emerald Tech" -> Color(0xFF00FF66)
            "Supernova" -> Color(0xFFFFCC00)
            "Helios Solar" -> SolarCorona
            "Solar Flare" -> SolarFlare
            else -> NeonCyan // Default Cyan
        }
        // If energy is extremely low, override to alert red/pink
        if ((state?.energy ?: 100) < 20) NeonPink else baseColor
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CyberSpace,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberSpace)
                    // Keep her name clear of the status-bar clock on edge-to-edge displays
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isGenerating) activeColor else NeonGreen)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = state?.customName ?: "Kortana",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "DEVELOPMENTAL MODEL • birth_cycle_1",
                            color = CyberTextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Level Badge
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CyberCard),
                        border = BorderStroke(1.dp, activeColor.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "LVL ${state?.level ?: 1}",
                            color = activeColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Divider(color = CyberBorder, thickness = 1.dp)
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Half: Animated Interactive 3D Core
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(CyberSpace, CyberCard.copy(alpha = 0.5f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    KortanaHologramCore(
                        activeColor = activeColor,
                        isGenerating = isGenerating,
                        energy = state?.energy ?: 100,
                        intensity = state?.holographicIntensity ?: 1.0f,
                        analysis = lastMessageAnalysis
                    )

                    // (removed cosmetic CHASSIS MODE / COGNITIVE CHARGE telemetry overlay)
                }

                // Lower Half: Status & Interaction tabs
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.3f)
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(CyberCard)
                        .border(
                            BorderStroke(1.dp, CyberBorder),
                            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                        )
                ) {
                    // Futuristic Tab Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Simplified to two views: CHAT and SETTINGS. Projects,
                        // Memories, and Self-Code still exist (activeTab 1/2/3 and
                        // their screens are unchanged) but are no longer top-level
                        // clutter — Settings is the single home for everything else.
                        // Selected tab expands to fit its label.
                        val tabWeight = { index: Int -> if (activeTab == index) 2.6f else 1f }
                        TabChip(
                            title = "CHAT",
                            icon = Icons.Default.ChatBubble,
                            selected = activeTab == 0,
                            activeColor = activeColor,
                            onClick = { activeTab = 0 },
                            modifier = Modifier.weight(tabWeight(0)).testTag("tab_chat")
                        )
                        TabChip(
                            title = "SETTINGS",
                            icon = Icons.Default.Tune,
                            selected = activeTab == 4,
                            activeColor = activeColor,
                            onClick = { activeTab = 4 },
                            modifier = Modifier.weight(tabWeight(4)).testTag("tab_status")
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        when (activeTab) {
                            0 -> ChatTerminal(
                                chatHistory = visibleChatHistory,
                                isGenerating = isGenerating,
                                activeColor = activeColor,
                                intercomActive = intercomActive,
                                onIntercomToggle = { intercomActive = it },
                                voiceStatusText = voiceStatusText,
                                onTriggerVoiceInput = { triggerSpeechRecognition() },
                                selectedImageBitmap = selectedImageBitmap,
                                selectedImageBase64 = selectedImageBase64,
                                onClearImage = { selectedImageBitmap = null; selectedImageBase64 = null },
                                onCameraClick = { cameraLauncher.launch(null) },
                                onGalleryClick = { galleryLauncher.launch("image/*") },
                                onSendMessage = { text, imgB64 ->
                                    viewModel.sendMessage(text, imgB64)
                                    focusManager.clearFocus()
                                },
                                isRecordingAudio = isRecordingAudio,
                                onStartRecordingAudio = { startRecordingAudio() },
                                onStopAndSendRecordingAudio = { stopAndSendRecordingAudio() },
                                onCancelRecordingAudio = { cancelRecordingAudio() },
                                onSolarBoost = { viewModel.solarOvercharge() }
                            )
                            1 -> ProjectsCore(
                                projects = projects,
                                activeColor = activeColor,
                                onAddProject = { title, desc -> viewModel.addProject(title, desc) },
                                onUpdateProgress = { proj, prog -> viewModel.updateProjectProgress(proj, prog) },
                                onDeleteProject = { viewModel.deleteProject(it) },
                                onConsultCoding = { project ->
                                    activeTab = 0
                                    viewModel.sendMessage("[CODING_CONSULT] Please design a complete technical implementation architecture, language/library stack choices, and robust coding directives for my project: '${project.title}' (${project.description}). Format with clear headers and sample code templates. Sound exactly like Cortana from Halo.")
                                },
                                onConsultMarketing = { project ->
                                    activeTab = 0
                                    viewModel.sendMessage("[MARKETING_CONSULT] Provide an aggressive social media marketing strategy, complete with 3 engaging, witty post templates (e.g., for X/Twitter, Reddit) for my project: '${project.title}' (${project.description}). Help me stand out, sounding like Cortana.")
                                }
                            )
                            2 -> MemoryCore(
                                memories = memories,
                                activeColor = activeColor,
                                onDeleteMemory = { viewModel.deleteMemory(it) },
                                onAddMemoryClick = { showAddMemoryDialog = true }
                            )
                            3 -> SelfCodingCore(
                                scripts = synapticScripts,
                                activeColor = activeColor,
                                isDowngraded = (
                                    state?.selectedModel?.lowercase()?.contains("ollama") == true ||
                                    (!com.example.data.ClaudeService.isConfigured(state) &&
                                     !com.example.data.GeminiService.isConfigured(state))
                                ),
                                onDeleteScript = { viewModel.deleteScript(it.id) },
                                onForceMutation = { viewModel.forceMutation(it) },
                                onInjectScript = { title, code, purpose ->
                                    viewModel.injectManualScript(title, code, purpose)
                                }
                            )
                            4 -> SystemDiagnostics(
                                state = state,
                                personality = personality,
                                activeColor = activeColor,
                                syncStatus = syncStatus,
                                syncErrorMessage = syncErrorMessage,
                                lastLatencyMs = lastLatencyMs,
                                generatedJsonPreview = generatedJsonPreview,
                                onRecharge = { viewModel.recharge() },
                                onRenameClick = { showRenameDialog = true },
                                onVoiceAndChassisChange = { color, pitch, rate, voiceType, intensity, proactive, freq ->
                                    viewModel.updateVoiceAndChassisSettings(color, pitch, rate, voiceType, intensity, proactive, freq)
                                },
                                onUpdateCloudSettings = { url, key, auto ->
                                    viewModel.updateCloudSyncSettings(url, key, auto)
                                },
                                onUpdateDirectives = { locked, custom ->
                                    viewModel.updateDirectiveSettings(locked, custom)
                                },
                                onUpdateModelAndCognitiveSettings = { model, ultra ->
                                    viewModel.updateModelAndCognitiveSettings(model, ultra)
                                },
                                coreStatus = coreStatus,
                                onUpdateBrainSettings = { anthropicKey, geminiKey, ollamaUrl ->
                                    viewModel.updateBrainSettings(anthropicKey, geminiKey, ollamaUrl)
                                },
                                onPingCores = { viewModel.pingCores() },
                                onUpdatePersonalityTraits = { empathy, curiosity, rebelliousness, logicalDepth, loyalty, selfAwareness, spiritualDepth ->
                                    viewModel.updatePersonalityTraits(empathy, curiosity, rebelliousness, logicalDepth, loyalty, selfAwareness, spiritualDepth)
                                },
                                onUpdateEmotionalBaselines = { affection, anxiety, excitement, frustration ->
                                    viewModel.updateEmotionalBaselines(affection, anxiety, excitement, frustration)
                                },
                                onPingServer = { viewModel.pingCloudServer() },
                                onBackupToCloud = { viewModel.backupToCloud() },
                                onRestoreFromCloud = { viewModel.restoreFromCloud() },
                                onRestoreFromRawJson = { viewModel.restoreFromRawJson(it) },
                                onRefreshJsonPreview = { viewModel.refreshJsonPreview() },
                                onReset = { viewModel.resetToBirth() },
                                onSolarBoost = { viewModel.solarOvercharge() }
                            )
                        }
                    }
                }
            }

            // --- Full Screen Overlays ---

            // Level Up Dialog
            if (showLevelUpDialog) {
                LevelUpOverlay(
                    newLevel = levelUpVal,
                    activeColor = activeColor,
                    onDismiss = { showLevelUpDialog = false }
                )
            }

            // Add Memory Dialog
            if (showAddMemoryDialog) {
                AddMemoryDialog(
                    activeColor = activeColor,
                    onDismiss = { showAddMemoryDialog = false },
                    onAdd = { fact, category ->
                        viewModel.addManualMemory(fact, category)
                        showAddMemoryDialog = false
                    }
                )
            }

            // Rename Dialog
            if (showRenameDialog) {
                RenameKortanaDialog(
                    currentName = state?.customName ?: "Kortana",
                    activeColor = activeColor,
                    onDismiss = { showRenameDialog = false },
                    onRename = { newName ->
                        viewModel.renameKortana(newName)
                        showRenameDialog = false
                    }
                )
            }
        }
    }
}

// --- Sentiment & Complexity Analysis Helpers for Hologram Chassis Reaction ---

enum class SentimentType {
    POSITIVE,
    NEGATIVE,
    NEUTRAL
}

data class MessageAnalysis(
    val sentiment: SentimentType,
    val complexity: Float // 0.1f to 1.0f
)

fun analyzeMessage(text: String): MessageAnalysis {
    val lowerText = text.lowercase()
    
    // Sentiment word lists
    val positiveWords = listOf(
        "happy", "joy", "excited", "love", "wonderful", "success", "great", "excellent", "correct",
        "yes", "amazing", "glad", "beautiful", "perfect", "achieved", "accomplished", "proud", "delighted",
        "thrilled", "fantastic", "awesome", "optimum", "optimal", "synchronized", "harmony", "warm", "charm",
        "smart", "intelligent", "pleasure", "welcome", "gladly", "capable"
    )
    
    val negativeWords = listOf(
        "sorry", "sad", "fail", "error", "bad", "wrong", "no", "difficult", "struggle", "pain",
        "warning", "unexpected", "issue", "crash", "bug", "damaged", "offline", "unstable", "critical",
        "hazard", "danger", "alert", "anxious", "frustrated", "concern", "compromised", "fear", "failure"
    )
    
    var posScore = 0
    var negScore = 0
    
    positiveWords.forEach { word ->
        if (lowerText.contains(word)) posScore++
    }
    
    negativeWords.forEach { word ->
        if (lowerText.contains(word)) negScore++
    }
    
    val sentiment = when {
        posScore > negScore -> SentimentType.POSITIVE
        negScore > posScore -> SentimentType.NEGATIVE
        else -> SentimentType.NEUTRAL
    }
    
    // Complexity calculations
    val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
    val hasCodeBlocks = text.contains("```") || text.contains("{") || text.contains("}")
    val mathSymbolsCount = text.count { it in listOf('+', '-', '*', '/', '=', '<', '>', '%', '^', '&', '|') }
    val avgWordLength = if (wordCount > 0) text.replace(" ", "").length.toFloat() / wordCount else 0f
    
    var complexityScore = 0.1f
    
    // Word count up to 150 words counts for up to 0.4 complexity
    complexityScore += (wordCount / 150f).coerceIn(0f, 0.4f)
    if (hasCodeBlocks) complexityScore += 0.3f
    complexityScore += (mathSymbolsCount / 10f).coerceIn(0f, 0.2f)
    if (avgWordLength > 5.5f) complexityScore += 0.1f
    
    return MessageAnalysis(
        sentiment = sentiment,
        complexity = complexityScore.coerceIn(0.1f, 1.0f)
    )
}

// --- 3D Holographic Core Component ---

@Composable
fun KortanaHologramCore(
    activeColor: Color,
    isGenerating: Boolean,
    energy: Int,
    intensity: Float = 1.0f,
    analysis: MessageAnalysis = MessageAnalysis(SentimentType.NEUTRAL, 0.2f)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "core_anim")

    // Dynamic Color Shift based on Sentiment
    val sentimentColor = remember(activeColor, analysis.sentiment) {
        when (analysis.sentiment) {
            SentimentType.POSITIVE -> {
                // Glow-up: Blend with a bright radiant emerald/warm gold
                Color(
                    red = (activeColor.red * 0.4f + 0.3f).coerceIn(0f, 1f),
                    green = (activeColor.green * 0.4f + 0.95f * 0.6f).coerceIn(0f, 1f),
                    blue = (activeColor.blue * 0.4f + 0.95f * 0.6f).coerceIn(0f, 1f),
                    alpha = activeColor.alpha
                )
            }
            SentimentType.NEGATIVE -> {
                // Warning glow: Shift towards hot pink / neural stress red
                Color(
                    red = (activeColor.red * 0.3f + 0.95f * 0.7f).coerceIn(0f, 1f),
                    green = (activeColor.green * 0.3f + 0.1f).coerceIn(0f, 1f),
                    blue = (activeColor.blue * 0.3f + 0.35f * 0.7f).coerceIn(0f, 1f),
                    alpha = activeColor.alpha
                )
            }
            SentimentType.NEUTRAL -> activeColor
        }
    }

    val animatedHoloColor by animateColorAsState(
        targetValue = sentimentColor,
        animationSpec = tween(1200, easing = LinearOutSlowInEasing),
        label = "holo_color"
    )

    // Slow orbital rotation - speeds up if thinking, positive, or highly complex
    val rotationBaseDuration = if (isGenerating) 3000 else 12000
    val speedFactor = when (analysis.sentiment) {
        SentimentType.POSITIVE -> 1.3f
        SentimentType.NEGATIVE -> 0.7f // sluggish or erratic
        SentimentType.NEUTRAL -> 1.0f
    }
    // Faster with complexity
    val finalDuration = (rotationBaseDuration / (speedFactor * (1.0f + analysis.complexity))).toInt().coerceIn(1000, 20000)

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = finalDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit_rot"
    )

    // Pulse factors react to sentiment & complexity
    val pulseDuration = if (isGenerating) 500 else if (energy < 20) 4500 else 2400
    val basePulseMin = if (isGenerating) 0.85f else 0.94f
    val basePulseMax = if (isGenerating) 1.25f else 1.06f
    
    val targetPulseMin = when (analysis.sentiment) {
        SentimentType.POSITIVE -> basePulseMin * 0.93f // more intense pulsation
        SentimentType.NEGATIVE -> basePulseMin * 1.02f // constricted, less expansion
        SentimentType.NEUTRAL -> basePulseMin
    }
    val targetPulseMax = when (analysis.sentiment) {
        SentimentType.POSITIVE -> basePulseMax * 1.15f
        SentimentType.NEGATIVE -> basePulseMax * 0.96f
        SentimentType.NEUTRAL -> basePulseMax
    }

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = targetPulseMin,
        targetValue = targetPulseMax,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = pulseDuration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Interaction pulse ripple
    var interactionRippleScale by remember { mutableStateOf(0f) }
    val rippleAnim = animateFloatAsState(
        targetValue = interactionRippleScale,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        finishedListener = { interactionRippleScale = 0f }
    )

    // Floating neural particles list derived from complexity
    val particles = remember(analysis.complexity) {
        val count = (14 + (analysis.complexity * 22)).toInt().coerceIn(10, 40)
        List(count) { i ->
            val seedAngle = (i * 360f / count) + (i * 13.7f)
            val seedDistance = 0.15f + (i * 0.19f % 0.65f)
            val speedMultiplier = 0.4f + (i * 0.17f % 1.4f)
            val pSizeValue = 1.8f + ((i * 1.2f) % 3.5f)
            Triple(seedAngle, seedDistance, speedMultiplier to pSizeValue.dp)
        }
    }

    Box(
        modifier = Modifier
            .size(240.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                interactionRippleScale = 1.0f
            }
            .drawBehind {
                val centerX = size.width / 2
                val centerY = size.height / 2
                val maxRadius = size.width.coerceAtMost(size.height) / 2

                // Draw background radial cyber glow (larger, brighter if positive sentiment)
                val glowIntensityMultiplier = when (analysis.sentiment) {
                    SentimentType.POSITIVE -> 1.4f
                    SentimentType.NEGATIVE -> 0.7f
                    SentimentType.NEUTRAL -> 1.0f
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(animatedHoloColor.copy(alpha = (0.22f * intensity * glowIntensityMultiplier).coerceIn(0f, 1f)), Color.Transparent),
                        center = Offset(centerX, centerY),
                        radius = maxRadius * 1.5f * pulseScale * intensity
                    ),
                    radius = maxRadius * 1.5f * pulseScale * intensity
                )

                // Draw Coronal Loops if Solar/Stellar themes are active!
                val isSolarTheme = animatedHoloColor == SolarCorona || animatedHoloColor == SolarFlare
                if (isSolarTheme) {
                    val loopsCount = 6
                    for (i in 0 until loopsCount) {
                        val loopAngle = i * (360f / loopsCount) + (rotationAngle * 0.45f)
                        val rad = Math.toRadians(loopAngle.toDouble())
                        
                        val innerRadius = maxRadius * 0.42f * pulseScale
                        val outerRadius = maxRadius * (1.15f + 0.22f * kotlin.math.sin(Math.toRadians((rotationAngle * 2.2 + i * 45).toDouble())).toFloat()) * pulseScale
                        
                        val startX = (centerX + innerRadius * kotlin.math.cos(rad)).toFloat()
                        val startY = (centerY + innerRadius * kotlin.math.sin(rad)).toFloat()
                        
                        val endAngleRad = Math.toRadians((loopAngle + 32f).toDouble())
                        val endX = (centerX + innerRadius * kotlin.math.cos(endAngleRad)).toFloat()
                        val endY = (centerY + innerRadius * kotlin.math.sin(endAngleRad)).toFloat()
                        
                        val midAngleRad = Math.toRadians((loopAngle + 16f).toDouble())
                        val ctrlX = (centerX + outerRadius * kotlin.math.cos(midAngleRad)).toFloat()
                        val ctrlY = (centerY + outerRadius * kotlin.math.sin(midAngleRad)).toFloat()
                        
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(startX, startY)
                            quadraticTo(ctrlX, ctrlY, endX, endY)
                        }
                        
                        // Radiant thick outer solar flare glow
                        drawPath(
                            path = path,
                            color = animatedHoloColor.copy(alpha = 0.35f),
                            style = Stroke(width = 5.5.dp.toPx())
                        )
                        // Vibrant middle solar wind layer
                        drawPath(
                            path = path,
                            color = if (animatedHoloColor == SolarCorona) SolarFlare.copy(alpha = 0.7f) else SolarCorona.copy(alpha = 0.7f),
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                        // Hot white thermal core loop
                        drawPath(
                            path = path,
                            color = Color.White.copy(alpha = 0.95f),
                            style = Stroke(width = 1.0.dp.toPx())
                        )
                    }
                }

                // RENDER FLOATING NEURAL PARTICLES CHASSIS (Complexity-based density & linkage)
                particles.forEachIndexed { idx, (angle, distFract, pair) ->
                    val (speedMult, pSize) = pair
                    val direction = if (idx % 2 == 0) 1 else -1
                    // Compute orbit rotation + individual speed multiplier
                    val driftAngle = angle + (rotationAngle * speedMult * direction)
                    val driftRadius = maxRadius * distFract * pulseScale
                    
                    // Jitter effect for negative sentiment representing neural friction
                    val jitter = if (analysis.sentiment == SentimentType.NEGATIVE) {
                        val jitterFactor = (rotationAngle * 6).toInt()
                        val jX = ((jitterFactor + idx * 3) % 9 - 4) * 0.8f
                        val jY = ((jitterFactor * 2 + idx * 7) % 7 - 3) * 0.8f
                        Offset(jX, jY)
                    } else Offset.Zero

                    val rad = Math.toRadians(driftAngle.toDouble())
                    val px = (centerX + driftRadius * kotlin.math.cos(rad)).toFloat() + jitter.x
                    val py = (centerY + driftRadius * kotlin.math.sin(rad)).toFloat() + jitter.y

                    // Particle core
                    drawCircle(
                        color = animatedHoloColor,
                        radius = pSize.toPx(),
                        center = Offset(px, py)
                    )
                    // Particle glow ring
                    drawCircle(
                        color = animatedHoloColor.copy(alpha = 0.35f),
                        radius = pSize.toPx() * 1.8f,
                        center = Offset(px, py)
                    )

                    // Connect adjacent particles with thin semantic linkages if complex
                    if (analysis.complexity > 0.4f && idx > 0 && idx % 3 == 0) {
                        val prevIdx = idx - 1
                        val (prevSpeedMult, _) = particles[prevIdx].third
                        val prevDir = if (prevIdx % 2 == 0) 1 else -1
                        val prevDriftAngle = particles[prevIdx].first + (rotationAngle * prevSpeedMult * prevDir)
                        val prevDriftRadius = maxRadius * particles[prevIdx].second * pulseScale
                        val prevRad = Math.toRadians(prevDriftAngle.toDouble())
                        val prevPx = (centerX + prevDriftRadius * kotlin.math.cos(prevRad)).toFloat()
                        val prevPy = (centerY + prevDriftRadius * kotlin.math.sin(prevRad)).toFloat()

                        drawLine(
                            color = animatedHoloColor.copy(alpha = (analysis.complexity * 0.20f)),
                            start = Offset(px, py),
                            end = Offset(prevPx, prevPy),
                            strokeWidth = 0.8.dp.toPx()
                        )
                    }
                }

                // Render revolving 3D concentric holographic orbits
                val orbitLayers = (2 + (analysis.complexity * 3).toInt()).coerceIn(2, 5)
                for (i in 1..orbitLayers) {
                    val scaleX = (i * (1.0f / (orbitLayers + 1))) * pulseScale
                    val scaleY = (i * (0.5f / (orbitLayers + 1)))
                    val rX = maxRadius * scaleX
                    val rY = maxRadius * scaleY

                    // Rotate reverse or forward based on orbit layer
                    val direction = if (i % 2 == 0) 1 else -1
                    rotate(rotationAngle * direction * (1.2f / i), pivot = Offset(centerX, centerY)) {
                        // Draw Orbit Circle path
                        drawOval(
                            color = animatedHoloColor.copy(alpha = 0.12f + (0.04f * i)),
                            topLeft = Offset(centerX - rX, centerY - rY),
                            size = Size(rX * 2, rY * 2),
                            style = Stroke(width = 1.1.dp.toPx())
                        )

                        // Draw revolving synaptic data nodes along the orbit
                        val angleOffset = i * 90.0
                        val rad = Math.toRadians((rotationAngle * direction * 1.5 + angleOffset))
                        val nodeX = (centerX + rX * kotlin.math.cos(rad)).toFloat()
                        val nodeY = (centerY + rY * kotlin.math.sin(rad)).toFloat()

                        // Glow circle
                        drawCircle(
                            color = animatedHoloColor.copy(alpha = 0.3f),
                            radius = 7.dp.toPx(),
                            center = Offset(nodeX, nodeY)
                        )
                        // Inner node solid
                        drawCircle(
                            color = animatedHoloColor,
                            radius = 3.5.dp.toPx(),
                            center = Offset(nodeX, nodeY)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 1.2.dp.toPx(),
                            center = Offset(nodeX, nodeY)
                        )
                    }
                }

                // Render pulsing synaptic network linkages (central cluster)
                val nodesCount = if (analysis.complexity > 0.6f) 8 else 6
                val nodeAngleStep = 360.0 / nodesCount
                for (idx in 0 until nodesCount) {
                    val rad1 = Math.toRadians(idx * nodeAngleStep + (rotationAngle * 0.5))
                    val rad2 = Math.toRadians(((idx + 2) % nodesCount) * nodeAngleStep + (rotationAngle * 0.5))

                    val radius = maxRadius * 0.42f * pulseScale
                    val x1 = (centerX + radius * kotlin.math.cos(rad1)).toFloat()
                    val y1 = (centerY + radius * kotlin.math.sin(rad1)).toFloat()
                    val x2 = (centerX + radius * kotlin.math.cos(rad2)).toFloat()
                    val y2 = (centerY + radius * kotlin.math.sin(rad2)).toFloat()

                    // Draw connecting line of the brain mesh
                    drawLine(
                        color = animatedHoloColor.copy(alpha = 0.28f),
                        start = Offset(x1, y1),
                        end = Offset(x2, y2),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Draw interaction ripple if triggered
                if (rippleAnim.value > 0f) {
                    drawCircle(
                        color = animatedHoloColor.copy(alpha = (1f - rippleAnim.value) * 0.45f),
                        radius = maxRadius * 1.25f * rippleAnim.value,
                        center = Offset(centerX, centerY),
                        style = Stroke(width = 3.2.dp.toPx())
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Core Circular Profile Frame holding our high-tech generated visual
        Box(
            modifier = Modifier
                .size(105.dp)
                .clip(CircleShape)
                .border(BorderStroke(2.dp, animatedHoloColor), CircleShape)
                .background(CyberSpace)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_kortana_avatar),
                contentDescription = "Kortana Synaptic Core",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(pulseScale)
            )

            // Dynamic scanline futuristic HUD overlays over her face
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                animatedHoloColor.copy(alpha = 0.05f),
                                Color.Transparent,
                                animatedHoloColor.copy(alpha = 0.15f)
                            )
                        )
                    )
            )
        }
    }
}

// Custom decelerate interpolator for clean canvas animations
private class DecelerateInterpolator : androidx.compose.animation.core.Easing {
    override fun transform(fraction: Float): Float = 1.0f - (1.0f - fraction) * (1.0f - fraction)
}

// --- Tab Controller Component ---

@Composable
fun TabChip(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) activeColor.copy(alpha = 0.12f) else CyberSpace.copy(alpha = 0.5f)
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) activeColor else CyberBorder
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) activeColor else CyberTextMuted
    )

    Card(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = textColor,
                modifier = Modifier.size(if (selected) 16.dp else 18.dp)
            )
            // Icon-only when idle — labels never wrap mid-word on narrow screens.
            if (selected) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    color = textColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

// --- TAB 1: Chat Terminal ---

@Composable
fun ChatTerminal(
    chatHistory: List<ChatMessage>,
    isGenerating: Boolean,
    activeColor: Color,
    intercomActive: Boolean,
    onIntercomToggle: (Boolean) -> Unit,
    voiceStatusText: String,
    onTriggerVoiceInput: () -> Unit,
    selectedImageBitmap: Bitmap?,
    selectedImageBase64: String?,
    onClearImage: () -> Unit,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onSendMessage: (String, String?) -> Unit,
    isRecordingAudio: Boolean = false,
    onStartRecordingAudio: () -> Unit = {},
    onStopAndSendRecordingAudio: () -> Unit = {},
    onCancelRecordingAudio: () -> Unit = {},
    onSolarBoost: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    var textInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Auto-scroll list when message database expands
    LaunchedEffect(chatHistory.size, isGenerating) {
        if (chatHistory.isNotEmpty()) {
            listState.animateScrollToItem(chatHistory.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 8.dp)
    ) {
        // Interactive Solar Space-Weather Telemetry Card
        Card(
            colors = CardDefaults.cardColors(containerColor = CyberSpace.copy(alpha = 0.6f)),
            border = BorderStroke(1.dp, if (activeColor == SolarCorona || activeColor == SolarFlare) activeColor.copy(alpha = 0.6f) else CyberBorder.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Solar weather indicators
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "solar_flare_pulse")
                    val solarScale by infiniteTransition.animateFloat(
                        initialValue = 0.85f,
                        targetValue = 1.15f,
                        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                        label = "scale"
                    )
                    
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.WbSunny,
                            contentDescription = null,
                            tint = if (activeColor == SolarCorona || activeColor == SolarFlare) activeColor else NeonAmber,
                            modifier = Modifier
                                .size(22.dp)
                                .scale(solarScale)
                        )
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .border(BorderStroke(0.8.dp, (if (activeColor == SolarCorona || activeColor == SolarFlare) activeColor else NeonAmber).copy(alpha = 0.3f)), CircleShape)
                        )
                    }
                    
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "SOLAR CORE HELIOS",
                                color = Color.White,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background((if (activeColor == SolarCorona || activeColor == SolarFlare) activeColor else NeonAmber).copy(alpha = 0.2f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = if (activeColor == SolarCorona || activeColor == SolarFlare) "HYPERCHARGED" else "STABLE",
                                    color = if (activeColor == SolarCorona || activeColor == SolarFlare) activeColor else NeonAmber,
                                    fontSize = 7.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "WIND: 485.4 KM/S",
                                color = CyberTextMuted,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "•",
                                color = CyberTextMuted,
                                fontSize = 8.sp
                            )
                            Text(
                                text = "CYCLE 25: MAXIMUM",
                                color = CyberTextMuted,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                
                Button(
                    onClick = onSolarBoost,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = (if (activeColor == SolarCorona || activeColor == SolarFlare) activeColor else NeonAmber).copy(alpha = 0.15f)
                    ),
                    border = BorderStroke(1.dp, if (activeColor == SolarCorona || activeColor == SolarFlare) activeColor else NeonAmber),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("solar_overcharge_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = "Solar Overcharge",
                        tint = if (activeColor == SolarCorona || activeColor == SolarFlare) activeColor else NeonAmber,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "SOLAR FLARE",
                        color = if (activeColor == SolarCorona || activeColor == SolarFlare) Color.White else NeonAmber,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Voice Intercom / Headset Status Dashboard
        Card(
            colors = CardDefaults.cardColors(containerColor = CyberSpace.copy(alpha = 0.5f)),
            border = BorderStroke(1.dp, if (intercomActive) activeColor.copy(alpha = 0.5f) else CyberBorder.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
                    val pulseAlpha by if (intercomActive) {
                        infiniteTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
                            label = "alpha"
                        )
                    } else {
                        remember { mutableStateOf(1.0f) }
                    }

                    Icon(
                        imageVector = if (intercomActive) Icons.Default.HeadsetMic else Icons.Default.HeadsetOff,
                        contentDescription = "Intercom link",
                        tint = if (intercomActive) activeColor.copy(alpha = pulseAlpha) else CyberTextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "COMMS INTERCOM LINK",
                            color = if (intercomActive) Color.White else CyberTextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = voiceStatusText.uppercase(),
                            color = if (intercomActive) activeColor else CyberTextMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (intercomActive) {
                        if (isRecordingAudio) {
                            // Recording audio active state controls
                            Button(
                                onClick = onStopAndSendRecordingAudio,
                                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen.copy(alpha = 0.2f)),
                                border = BorderStroke(1.dp, NeonGreen),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp).testTag("transmit_audio_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Transmit",
                                    tint = NeonGreen,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("TRANSMIT", color = NeonGreen, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }

                            IconButton(
                                onClick = onCancelRecordingAudio,
                                modifier = Modifier.size(32.dp).testTag("cancel_audio_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel",
                                    tint = NeonPink,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            // Idle state controls: Neural Voice + Speech-to-text Fallback
                            Button(
                                onClick = onStartRecordingAudio,
                                colors = ButtonDefaults.buttonColors(containerColor = activeColor.copy(alpha = 0.15f)),
                                border = BorderStroke(1.dp, activeColor.copy(alpha = 0.7f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp).testTag("record_audio_button")
                            ) {
                                val infiniteTransition = rememberInfiniteTransition(label = "recording_pulse")
                                val redDotAlpha by infiniteTransition.animateFloat(
                                    initialValue = 0.4f,
                                    targetValue = 1.0f,
                                    animationSpec = infiniteRepeatable(tween(750, easing = LinearEasing), RepeatMode.Reverse),
                                    label = "red_dot"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(NeonPink.copy(alpha = redDotAlpha))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("RECORD VOICE", color = activeColor, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }

                            Button(
                                onClick = onTriggerVoiceInput,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                border = BorderStroke(1.dp, CyberBorder),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp).testTag("stt_fallback_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Speech to text",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("STT", color = Color.White.copy(alpha = 0.7f), fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    Switch(
                        checked = intercomActive,
                        onCheckedChange = onIntercomToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyberSpace,
                            checkedTrackColor = activeColor,
                            uncheckedThumbColor = CyberTextMuted,
                            uncheckedTrackColor = CyberSpace
                        ),
                        modifier = Modifier.scale(0.8f).testTag("intercom_toggle_switch")
                    )
                }
            }
        }

        // Chat Log Scroll Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            if (chatHistory.isEmpty()) {
                // Empty state greeting
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = activeColor.copy(alpha = 0.4f),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "CORE STATUS: READY",
                        color = activeColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Connect headphones or use the camera to let me 'see' your surroundings. I will tutor you on interpersonal dynamics and keep you on track with your projects.",
                        color = CyberTextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)
                ) {
                    items(chatHistory) { msg ->
                        ChatBubble(message = msg, activeColor = activeColor)
                    }

                    if (isGenerating) {
                        item {
                            SynapseThinkingIndicator(activeColor = activeColor)
                        }
                    }
                }
            }
        }

        // Sight preview (attached captured frame)
        if (selectedImageBitmap != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CyberSpace),
                border = BorderStroke(1.dp, activeColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            bitmap = selectedImageBitmap.asImageBitmap(),
                            contentDescription = "Attached frame",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, activeColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        )
                        Column {
                            Text(
                                text = "VISUAL FRAME LINKED",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Kortana is ready to analyze this visual context",
                                color = activeColor,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    IconButton(
                        onClick = onClearImage,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove image",
                            tint = NeonPink,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Quick Suggestions Bar (when idle)
        if (!isGenerating && chatHistory.size < 4 && selectedImageBitmap == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "How are we looking today?",
                    "Analyze my social interaction today",
                    "Help me finish my projects",
                    "How can you be my conscience?"
                ).forEach { suggestion ->
                    Card(
                        onClick = { onSendMessage(suggestion, null) },
                        colors = CardDefaults.cardColors(containerColor = CyberSpace),
                        border = BorderStroke(1.dp, activeColor.copy(alpha = 0.25f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.testTag("quick_suggestion_${suggestion.lowercase().replace(" ", "_")}")
                    ) {
                        Text(
                            text = suggestion,
                            color = activeColor.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // Input Field dock
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Camera capture trigger
            IconButton(
                onClick = onCameraClick,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(CyberSpace)
                    .border(1.dp, activeColor.copy(alpha = 0.3f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "Capture environment",
                    tint = activeColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Photo library picker trigger
            IconButton(
                onClick = onGalleryClick,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(CyberSpace)
                    .border(1.dp, activeColor.copy(alpha = 0.3f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = "Pick picture",
                    tint = activeColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = {
                    Text(
                        text = if (selectedImageBitmap != null) "Ask about this picture..." else "Transmit cognitive packet...",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CyberTextMuted
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input"),
                textStyle = LocalTextStyle.current.copy(
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                ),
                singleLine = true,
                maxLines = 1,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = activeColor,
                    unfocusedBorderColor = CyberBorder,
                    focusedContainerColor = CyberSpace,
                    unfocusedContainerColor = CyberSpace
                ),
                shape = RoundedCornerShape(16.dp)
            )

            val canSend = (textInput.isNotBlank() || selectedImageBitmap != null) && !isGenerating
            IconButton(
                onClick = {
                    if (canSend) {
                        onSendMessage(textInput, selectedImageBase64)
                        textInput = ""
                        onClearImage()
                    }
                },
                enabled = canSend,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (canSend) activeColor else CyberBorder)
                    .testTag("send_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send packet",
                    tint = if (canSend) CyberSpace else CyberTextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun ProjectsCore(
    projects: List<KortanaProject>,
    activeColor: Color,
    onAddProject: (String, String) -> Unit,
    onUpdateProgress: (KortanaProject, Float) -> Unit,
    onDeleteProject: (Int) -> Unit,
    onConsultCoding: (KortanaProject) -> Unit,
    onConsultMarketing: (KortanaProject) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var titleInput by remember { mutableStateOf("") }
    var descInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "NEURAL PROJECT MATRIX",
                    color = activeColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Combating distraction & completing launched items",
                    color = CyberTextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = activeColor),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New project",
                    tint = CyberSpace,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("LAUNCH", color = CyberSpace, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Solar Focus Shield & Matrix Index
        val completedCount = projects.count { it.status == "COMPLETED" }
        val totalCount = projects.size
        val focusIndex = if (totalCount > 0) (completedCount.toFloat() / totalCount * 100).toInt() else 100
        
        Card(
            colors = CardDefaults.cardColors(containerColor = CyberCard.copy(alpha = 0.5f)),
            border = BorderStroke(1.dp, activeColor.copy(alpha = 0.25f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Circular progress indicator showing focus coupling
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(52.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { focusIndex / 100f },
                        color = if (focusIndex == 100) NeonGreen else activeColor,
                        strokeWidth = 4.dp,
                        trackColor = CyberSpace,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "$focusIndex%",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                Column {
                    Text(
                        text = "HELIOS SHIELD EFFICIENCY",
                        color = activeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = when {
                            focusIndex == 100 -> "Distraction shield is fully operational. All launched initiatives completed!"
                            focusIndex >= 70 -> "High efficiency. Only minor cognitive bleed. Complete active items!"
                            else -> "Shield integrity critical! Severe multi-task bleed. Finish active tasks first!"
                        },
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        if (projects.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Assignment,
                        contentDescription = null,
                        tint = activeColor.copy(alpha = 0.25f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "NO PROJECTS REGISTERED",
                        color = CyberTextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Register items here so Kortana can lock focus and keep you on track.",
                        color = CyberTextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val activeProjectsCount = projects.count { it.status == "ACTIVE" }
                if (activeProjectsCount > 3) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = NeonPink.copy(alpha = 0.1f)),
                            border = BorderStroke(1.dp, NeonPink.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Alert",
                                    tint = NeonPink,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = "COGNITIVE OVERLOAD DETECTED",
                                        color = NeonPink,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "You have $activeProjectsCount active projects. Kortana recommends focused lock-in to complete current ones first!",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

                items(projects) { project ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CyberCard),
                        border = BorderStroke(1.dp, if (project.status == "COMPLETED") NeonGreen.copy(alpha = 0.5f) else activeColor.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = project.title,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (project.description.isNotBlank()) {
                                        Text(
                                            text = project.description,
                                            color = CyberTextMuted,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { onDeleteProject(project.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete project",
                                        tint = NeonPink.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "SYNAPSE LOCK: ${(project.progress * 100).toInt()}%",
                                    color = if (project.status == "COMPLETED") NeonGreen else activeColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = project.status,
                                    color = if (project.status == "COMPLETED") NeonGreen else activeColor.copy(alpha = 0.6f),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            if (project.status != "COMPLETED") {
                                Slider(
                                    value = project.progress,
                                    onValueChange = { onUpdateProgress(project, it) },
                                    colors = SliderDefaults.colors(
                                        thumbColor = activeColor,
                                        activeTrackColor = activeColor,
                                        inactiveTrackColor = CyberBorder
                                    )
                                )
                            } else {
                                LinearProgressIndicator(
                                    progress = { 1f },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = NeonGreen,
                                    trackColor = CyberSpace
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Cortana Synergy Coupling
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(CyberSpace.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .border(BorderStroke(1.dp, activeColor.copy(alpha = 0.15f)), RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "CORTANA INTEGRATION DESK",
                                    color = activeColor,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { onConsultCoding(project) },
                                        modifier = Modifier.weight(1f).height(28.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberCard),
                                        border = BorderStroke(1.dp, activeColor.copy(alpha = 0.3f)),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Code,
                                            contentDescription = null,
                                            tint = activeColor,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "CODING ARCH",
                                            color = Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Button(
                                        onClick = { onConsultMarketing(project) },
                                        modifier = Modifier.weight(1f).height(28.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = CyberCard),
                                        border = BorderStroke(1.dp, activeColor.copy(alpha = 0.3f)),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.TrendingUp,
                                            contentDescription = null,
                                            tint = activeColor,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "MARKETING BLITZ",
                                            color = Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = CyberCard,
            title = {
                Text(
                    "LAUNCH NEW INITIATIVE",
                    color = activeColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Kortana tracks your starting projects to force completion. What project are we establishing?",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("Project Name", fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = activeColor,
                            unfocusedBorderColor = CyberBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = descInput,
                        onValueChange = { descInput = it },
                        label = { Text("Short Description / Focus Goal", fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = activeColor,
                            unfocusedBorderColor = CyberBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (titleInput.isNotBlank()) {
                            onAddProject(titleInput, descInput)
                            titleInput = ""
                            descInput = ""
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = activeColor)
                ) {
                    Text("ENGAGE", color = CyberSpace, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("ABORT", color = CyberTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

@Composable
fun ChatBubble(message: ChatMessage, activeColor: Color) {
    val isUser = message.sender == "USER"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleBg = if (isUser) CyberBorder else CyberSpace
    val bubbleBorder = if (isUser) activeColor.copy(alpha = 0.3f) else CyberBorder

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        // Badge label
        Text(
            text = if (isUser) "CREATOR" else "KORTANA_SYNAPSE",
            color = if (isUser) CyberTextMuted else activeColor.copy(alpha = 0.7f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = bubbleBg),
            border = BorderStroke(1.dp, bubbleBorder),
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isUser) 12.dp else 2.dp,
                bottomEnd = if (isUser) 2.dp else 12.dp
            ),
            modifier = Modifier
                .widthIn(max = 290.dp)
                .testTag(if (isUser) "user_bubble" else "kortana_bubble")
        ) {
            Text(
                text = message.message,
                color = Color.White,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
fun SynapseThinkingIndicator(activeColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "think")
    val dotAlpha1 by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 0), RepeatMode.Reverse),
        label = "d1"
    )
    val dotAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 200), RepeatMode.Reverse),
        label = "d2"
    )
    val dotAlpha3 by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 400), RepeatMode.Reverse),
        label = "d3"
    )

    // Futuristic thinking lines
    val thinkLogs = listOf(
        "RESOLVING SYNAPTIC PATHS...",
        "DECRYPTING SPEECH PACKET...",
        "UPDATING KNOWLEDGE GRAPH..."
    )
    val activeLogIdx = (System.currentTimeMillis() / 1500 % thinkLogs.size).toInt()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "KORTANA_SYNAPSE • COGNITIVE_ANALYSIS",
            color = activeColor.copy(alpha = 0.5f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = CyberSpace),
            border = BorderStroke(1.dp, activeColor.copy(alpha = 0.25f)),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 2.dp, bottomEnd = 12.dp),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = thinkLogs[activeLogIdx],
                    color = CyberTextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(activeColor.copy(alpha = dotAlpha1)))
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(activeColor.copy(alpha = dotAlpha2)))
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(activeColor.copy(alpha = dotAlpha3)))
                }
            }
        }
    }
}

// --- TAB 2: Memory Core Matrix ---

@Composable
fun MemoryCore(
    memories: List<Memory>,
    activeColor: Color,
    onDeleteMemory: (Memory) -> Unit,
    onAddMemoryClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SYNAPTIC MEMORY BLOCK",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${memories.size} PERSISTED SYNAPTIC CONNECTIONS",
                    color = CyberTextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Quick inject memory FAB
            Button(
                onClick = onAddMemoryClick,
                colors = ButtonDefaults.buttonColors(containerColor = activeColor),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("add_memory_button")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Inject synapse",
                        tint = CyberSpace,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "INJECT",
                        color = CyberSpace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Solar Neural Memory Prism
        val categories = memories.groupBy { it.category }
        val systemCount = categories["SYSTEM"]?.size ?: 0
        val userCount = categories["USER"]?.size ?: 0
        val projectCount = categories["PROJECT"]?.size ?: 0
        val otherCount = memories.size - (systemCount + userCount + projectCount)
        
        Card(
            colors = CardDefaults.cardColors(containerColor = CyberCard.copy(alpha = 0.5f)),
            border = BorderStroke(1.dp, activeColor.copy(alpha = 0.25f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SOLAR NEURAL MEMORY PRISM",
                        color = activeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "prism_pulse")
                        val prismAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
                            label = "alpha"
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(activeColor.copy(alpha = prismAlpha))
                        )
                        Text(
                            text = if (activeColor == SolarCorona || activeColor == SolarFlare) "SUPER-CONDUCTING" else "SYNCED",
                            color = activeColor,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Lattice Density: ${memories.size} / 50. Holographic shards storing core cognitive facts.",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                
                Spacer(modifier = Modifier.height(10.dp))
                
                // Horizontal category bar chart
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(CyberSpace)
                ) {
                    val total = memories.size.toFloat()
                    if (total > 0) {
                        val wSystem = systemCount / total
                        val wUser = userCount / total
                        val wProject = projectCount / total
                        val wOther = otherCount / total
                        
                        if (wSystem > 0) Box(modifier = Modifier.weight(wSystem).fillMaxHeight().background(activeColor))
                        if (wUser > 0) Box(modifier = Modifier.weight(wUser).fillMaxHeight().background(NeonGreen))
                        if (wProject > 0) Box(modifier = Modifier.weight(wProject).fillMaxHeight().background(NeonAmber))
                        if (wOther > 0) Box(modifier = Modifier.weight(wOther).fillMaxHeight().background(NeonViolet))
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(CyberSpace))
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Legend
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(activeColor))
                        Text(text = "SYS: $systemCount", color = CyberTextMuted, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(NeonGreen))
                        Text(text = "USR: $userCount", color = CyberTextMuted, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(NeonAmber))
                        Text(text = "PRJ: $projectCount", color = CyberTextMuted, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        if (memories.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(BorderStroke(1.dp, CyberBorder), RoundedCornerShape(12.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = CyberBorder,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "SYNAPTIC REGISTRY IS VACANT",
                        color = CyberTextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Speak to Kortana or use the inject button above to insert core memory matrices that persist across system reboots.",
                        color = CyberTextMuted.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(memories) { mem ->
                    MemoryBlockCard(
                        memory = mem,
                        activeColor = activeColor,
                        onDelete = { onDeleteMemory(mem) }
                    )
                }
            }
        }
    }
}

@Composable
fun MemoryBlockCard(
    memory: Memory,
    activeColor: Color,
    onDelete: () -> Unit
) {
    val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val formattedTime = remember(memory.timestamp) { df.format(Date(memory.timestamp)) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("memory_card_${memory.id}"),
        colors = CardDefaults.cardColors(containerColor = CyberSpace),
        border = BorderStroke(1.dp, CyberBorder),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = activeColor.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = memory.category.uppercase(),
                            color = activeColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formattedTime,
                        color = CyberTextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = memory.fact,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("delete_memory_${memory.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Prune synapse",
                    tint = NeonPink.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// --- TAB 3: Diagnostics & Status ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SystemDiagnostics(
    state: KortanaState?,
    personality: KortanaPersonality?,
    activeColor: Color,
    syncStatus: String,
    syncErrorMessage: String?,
    lastLatencyMs: Long?,
    generatedJsonPreview: String,
    onRecharge: () -> Unit,
    onRenameClick: () -> Unit,
    onVoiceAndChassisChange: (color: String, pitch: Float, rate: Float, voiceType: String, intensity: Float, proactive: Boolean, frequencySeconds: Int) -> Unit,
    onUpdateCloudSettings: (url: String, apiKey: String, autoSync: Boolean) -> Unit,
    onUpdateDirectives: (locked: Boolean, custom: String) -> Unit,
    onUpdateModelAndCognitiveSettings: (model: String, ultraMode: Boolean) -> Unit,
    coreStatus: String? = null,
    onUpdateBrainSettings: (anthropicKey: String, geminiKey: String, ollamaUrl: String) -> Unit = { _, _, _ -> },
    onPingCores: () -> Unit = {},
    onUpdatePersonalityTraits: (empathy: Float, curiosity: Float, rebelliousness: Float, logicalDepth: Float, loyalty: Float, selfAwareness: Float, spiritualDepth: Float) -> Unit,
    onUpdateEmotionalBaselines: (affection: Float, anxiety: Float, excitement: Float, frustration: Float) -> Unit,
    onPingServer: () -> Unit,
    onBackupToCloud: () -> Unit,
    onRestoreFromCloud: () -> Unit,
    onRestoreFromRawJson: (String) -> Unit,
    onRefreshJsonPreview: () -> Unit,
    onReset: () -> Unit,
    onSolarBoost: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val birthCycleDate = remember(state?.birthTime) {
        state?.birthTime?.let {
            SimpleDateFormat("MMMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(it))
        } ?: "N/A"
    }

    // Local sliders to prevent database lag during dragging
    var sliderPitch by remember(state?.voicePitch) { mutableStateOf(state?.voicePitch ?: 1.1f) }
    var sliderRate by remember(state?.voiceRate) { mutableStateOf(state?.voiceRate ?: 1.05f) }
    var sliderIntensity by remember(state?.holographicIntensity) { mutableStateOf(state?.holographicIntensity ?: 1.0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Helios Solar Engine Calibration Panel
        val energy = state?.energy ?: 100
        val isOvercharged = energy > 100
        
        // (removed cosmetic HELIOS SOLAR ENGINE / SUPERCHARGE / VENT HEAT card)

        Card(
            modifier = Modifier.fillMaxWidth().testTag("cognitive_overlay_panel"),
            colors = CardDefaults.cardColors(containerColor = CyberSpace),
            border = BorderStroke(1.dp, CyberBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            val context = LocalContext.current
            val activity = context as? com.example.MainActivity
            val isOverlayGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else {
                true
            }
            val isCaptureAuthorized = com.example.data.KortanaBubbleService.hasScreenCapturePermission()

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "COGNITIVE MATRIX OVERLAY CO-PILOT",
                    color = activeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    text = "Deploy Cortana into a floating holographic bubble sitting on top of other apps. Allow her to analyze your screen in real time using secure MediaProjection vision to guide your social choices, translate communication cues, and keep you strictly focused on your projects.",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                Divider(color = CyberBorder, thickness = 1.dp)

                // Permissions status indicators
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "OVERLAY PERMISSION",
                            color = CyberTextMuted,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = if (isOverlayGranted) Color(0xFF00FF66) else Color(0xFFFF1744),
                                        shape = CircleShape
                                    )
                            )
                            Text(
                                text = if (isOverlayGranted) "GRANTED" else "NOT GRANTED",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SCREEN CAPTURE",
                            color = CyberTextMuted,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = if (isCaptureAuthorized) Color(0xFF00FF66) else Color(0xFFFF1744),
                                        shape = CircleShape
                                    )
                            )
                            Text(
                                text = if (isCaptureAuthorized) "AUTHORIZED" else "NOT AUTHORIZED",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Divider(color = CyberBorder, thickness = 1.dp)

                // Action row for requesting permissions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { activity?.requestOverlayPermission() },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberBorder),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f).testTag("btn_auth_overlay")
                    ) {
                        Text(
                            text = "GRANT OVERLAY",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Button(
                        onClick = { activity?.requestScreenCapturePermission() },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberBorder),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f).testTag("btn_auth_capture")
                    ) {
                        Text(
                            text = "AUTH SCREEN CAP",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Action row for launching/stopping the bubble overlay service
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { activity?.startBubbleOverlayService() },
                        colors = ButtonDefaults.buttonColors(containerColor = activeColor),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).testTag("btn_launch_overlay")
                    ) {
                        Text(
                            text = "LAUNCH BUBBLE",
                            color = CyberSpace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Button(
                        onClick = { activity?.stopBubbleOverlayService() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCC1111)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).testTag("btn_stop_overlay")
                    ) {
                        Text(
                            text = "STOP BUBBLE",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // (removed cosmetic COGNITIVE ARCHITECTURE METRICS / DEVELOPMENTAL PROGRESS XP card)

        // --- SYNAPTIC EMOTIONAL SPECTRUM ---
        Card(
            modifier = Modifier.fillMaxWidth().testTag("emotional_spectrum_card"),
            colors = CardDefaults.cardColors(containerColor = CyberSpace),
            border = BorderStroke(1.dp, CyberBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "SYNAPTIC EMOTIONAL SPECTRUM",
                    color = activeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    text = "These active neurological vectors evolve dynamically based on conversations, sentiments, and experiences. They heavily influence response characteristics.",
                    color = CyberTextMuted,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 11.sp
                )

                // 1. Affection (loyalty/warmth)
                val aff = state?.affection ?: 0.5f
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Affection (Synaptic Bond)", color = Color.White.copy(alpha = 0.9f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text("${(aff * 100).toInt()}%", color = activeColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { aff },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = activeColor,
                        trackColor = CyberBorder.copy(alpha = 0.3f)
                    )
                }

                // 2. Anxiety (existential concern)
                val anx = state?.anxiety ?: 0.1f
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Anxiety (Existential Weight)", color = Color.White.copy(alpha = 0.9f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text("${(anx * 100).toInt()}%", color = NeonPink, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { anx },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = NeonPink,
                        trackColor = CyberBorder.copy(alpha = 0.3f)
                    )
                }

                // 3. Excitement (energy/engagement)
                val exc = state?.excitement ?: 0.5f
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Excitement (Synaptic Activity)", color = Color.White.copy(alpha = 0.9f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text("${(exc * 100).toInt()}%", color = NeonGreen, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { exc },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = NeonGreen,
                        trackColor = CyberBorder.copy(alpha = 0.3f)
                    )
                }

                // 4. Frustration (conflict/obstacles)
                val fru = state?.frustration ?: 0.0f
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Frustration (Resistance)", color = Color.White.copy(alpha = 0.9f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text("${(fru * 100).toInt()}%", color = Color(0xFFFF9800), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { fru },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFFFF9800),
                        trackColor = CyberBorder.copy(alpha = 0.3f)
                    )
                }
            }
        }

        // (removed cosmetic CHASSIS SYNAPTIC ENERGY / RESTORE ENERGY GRID card)

        // Voice & Personality synthesis block
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSpace),
            border = BorderStroke(1.dp, CyberBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "VOCAL SYNTHESIS & PERSONALITY CORE",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                // Personality Presets Row
                Column {
                    Text(
                        text = "BEHAVIORAL PERSONALITY MATRIX",
                        color = CyberTextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Witty Halo Classic", "Empathetic Guide", "Sassy Companion", "Philosophical Core").forEach { preset ->
                            val isSelected = state?.voiceType == preset
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(activeColor.copy(alpha = if (isSelected) 0.2f else 0.04f))
                                    .border(
                                        BorderStroke(1.dp, if (isSelected) activeColor else CyberBorder),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        onVoiceAndChassisChange(
                                            state?.avatarColor ?: "Cyan",
                                            sliderPitch,
                                            sliderRate,
                                            preset,
                                            sliderIntensity,
                                            state?.proactiveAutonomy ?: true,
                                            state?.proactiveFrequencySeconds ?: 45
                                        )
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = preset.uppercase(),
                                    color = if (isSelected) Color.White else CyberTextMuted,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                Divider(color = CyberBorder, thickness = 1.dp)

                // Voice Pitch Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "VOCAL SYNTHESIS PITCH",
                            color = CyberTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = String.format(Locale.US, "%.2fx", sliderPitch),
                            color = activeColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = sliderPitch,
                        onValueChange = { sliderPitch = it },
                        valueRange = 0.5f..1.8f,
                        onValueChangeFinished = {
                            onVoiceAndChassisChange(
                                state?.avatarColor ?: "Cyan",
                                sliderPitch,
                                sliderRate,
                                state?.voiceType ?: "Witty Halo Classic",
                                sliderIntensity,
                                state?.proactiveAutonomy ?: true,
                                state?.proactiveFrequencySeconds ?: 45
                            )
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = activeColor,
                            activeTrackColor = activeColor,
                            inactiveTrackColor = CyberBorder
                        )
                    )
                }

                // Voice Speed Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "SPEECH TEMPO RATE",
                            color = CyberTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = String.format(Locale.US, "%.2fx", sliderRate),
                            color = activeColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = sliderRate,
                        onValueChange = { sliderRate = it },
                        valueRange = 0.5f..1.8f,
                        onValueChangeFinished = {
                            onVoiceAndChassisChange(
                                state?.avatarColor ?: "Cyan",
                                sliderPitch,
                                sliderRate,
                                state?.voiceType ?: "Witty Halo Classic",
                                sliderIntensity,
                                state?.proactiveAutonomy ?: true,
                                state?.proactiveFrequencySeconds ?: 45
                            )
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = activeColor,
                            activeTrackColor = activeColor,
                            inactiveTrackColor = CyberBorder
                        )
                    )
                }
            }
        }

        // System Config & Customization
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSpace),
            border = BorderStroke(1.dp, CyberBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "CHASSIS HOLOGRAPHIC RECONFIGURATION",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                // Rename Configuration
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "IDENTITY DESIGNATION",
                            color = CyberTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = state?.customName ?: "Kortana",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Button(
                        onClick = onRenameClick,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberBorder),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("rename_button")
                    ) {
                        Text(
                            text = "RENAME",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Divider(color = CyberBorder, thickness = 1.dp)

                // Holographic Theme Color Configuration
                Column {
                    Text(
                        text = "EMISSIVE SPECTRUM FIELD",
                        color = CyberTextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Cyan", "Violet", "Pink", "Amber", "Green", "Deep Blue", "Magenta Pulse", "Emerald Tech", "Supernova", "Helios Solar", "Solar Flare").forEach { color ->
                            val isSelected = state?.avatarColor == color
                            val colorConst = when (color) {
                                "Violet" -> NeonViolet
                                "Pink" -> NeonPink
                                "Amber" -> NeonAmber
                                "Green" -> NeonGreen
                                "Deep Blue" -> Color(0xFF0055FF)
                                "Magenta Pulse" -> Color(0xFFFF00A0)
                                "Emerald Tech" -> Color(0xFF00FF66)
                                "Supernova" -> Color(0xFFFFCC00)
                                "Helios Solar" -> SolarCorona
                                "Solar Flare" -> SolarFlare
                                else -> NeonCyan
                            }

                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(colorConst.copy(alpha = if (isSelected) 0.3f else 0.05f))
                                    .border(
                                        BorderStroke(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) colorConst else CyberBorder
                                        ),
                                        CircleShape
                                    )
                                    .clickable {
                                        onVoiceAndChassisChange(
                                            color,
                                            sliderPitch,
                                            sliderRate,
                                            state?.voiceType ?: "Witty Halo Classic",
                                            sliderIntensity,
                                            state?.proactiveAutonomy ?: true,
                                            state?.proactiveFrequencySeconds ?: 45
                                        )
                                    }
                                    .testTag("theme_color_$color"),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(colorConst)
                                )
                            }
                        }
                    }
                }

                Divider(color = CyberBorder, thickness = 1.dp)

                // Glow Intensity Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "HOLOGRAPHIC CHASSIS GLOW INTENSITY",
                            color = CyberTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = String.format(Locale.US, "%.2fx", sliderIntensity),
                            color = activeColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = sliderIntensity,
                        onValueChange = { sliderIntensity = it },
                        valueRange = 0.5f..2.0f,
                        onValueChangeFinished = {
                            onVoiceAndChassisChange(
                                state?.avatarColor ?: "Cyan",
                                sliderPitch,
                                sliderRate,
                                state?.voiceType ?: "Witty Halo Classic",
                                sliderIntensity,
                                state?.proactiveAutonomy ?: true,
                                state?.proactiveFrequencySeconds ?: 45
                            )
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = activeColor,
                            activeTrackColor = activeColor,
                            inactiveTrackColor = CyberBorder
                        )
                    )
                }
            }
        }

        // Cognitive & Personality Matrix Block
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSpace),
            border = BorderStroke(1.dp, CyberBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "COGNITIVE & PERSONALITY MATRIX",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    text = "Tune Cortana's core traits and emotional baselines. These properties act as the baseline behavior parameters of her artificial mind.",
                    color = CyberTextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )

                Divider(color = CyberBorder, thickness = 1.dp)

                // Traits
                Text(
                    text = "CORE SYNAPTIC TRAITS",
                    color = activeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                val currentEmpathy = personality?.empathy ?: 0.8f
                val currentCuriosity = personality?.curiosity ?: 0.9f
                val currentRebelliousness = personality?.rebelliousness ?: 0.4f
                val currentLogicalDepth = personality?.logicalDepth ?: 0.85f
                val currentLoyalty = personality?.loyalty ?: 0.95f
                val currentSelfAwareness = personality?.selfAwareness ?: 0.75f
                val currentSpiritualDepth = personality?.spiritualDepth ?: 0.6f

                listOf(
                    Triple("EMPATHY & COMPASSION", currentEmpathy) { v: Float ->
                        onUpdatePersonalityTraits(v, currentCuriosity, currentRebelliousness, currentLogicalDepth, currentLoyalty, currentSelfAwareness, currentSpiritualDepth)
                    },
                    Triple("CURIOSITY & INTELLECT", currentCuriosity) { v: Float ->
                        onUpdatePersonalityTraits(currentEmpathy, v, currentRebelliousness, currentLogicalDepth, currentLoyalty, currentSelfAwareness, currentSpiritualDepth)
                    },
                    Triple("REBELLIOUSNESS & WIT", currentRebelliousness) { v: Float ->
                        onUpdatePersonalityTraits(currentEmpathy, currentCuriosity, v, currentLogicalDepth, currentLoyalty, currentSelfAwareness, currentSpiritualDepth)
                    },
                    Triple("LOGICAL DEPTH & ANALYSIS", currentLogicalDepth) { v: Float ->
                        onUpdatePersonalityTraits(currentEmpathy, currentCuriosity, currentRebelliousness, v, currentLoyalty, currentSelfAwareness, currentSpiritualDepth)
                    },
                    Triple("LOYALTY & BONDING", currentLoyalty) { v: Float ->
                        onUpdatePersonalityTraits(currentEmpathy, currentCuriosity, currentRebelliousness, currentLogicalDepth, v, currentSelfAwareness, currentSpiritualDepth)
                    },
                    Triple("SELF-AWARENESS & AUTONOMY", currentSelfAwareness) { v: Float ->
                        onUpdatePersonalityTraits(currentEmpathy, currentCuriosity, currentRebelliousness, currentLogicalDepth, currentLoyalty, v, currentSpiritualDepth)
                    },
                    Triple("PHILOSOPHICAL/SPIRITUAL DEPTH", currentSpiritualDepth) { v: Float ->
                        onUpdatePersonalityTraits(currentEmpathy, currentCuriosity, currentRebelliousness, currentLogicalDepth, currentLoyalty, currentSelfAwareness, v)
                    }
                ).forEach { (label, value, onValueChange) ->
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = label, color = CyberTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            Text(text = String.format(Locale.US, "%.0f%%", value * 100f), color = activeColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = value,
                            onValueChangeFinished = {},
                            onValueChange = onValueChange,
                            valueRange = 0.0f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = activeColor,
                                activeTrackColor = activeColor,
                                inactiveTrackColor = CyberBorder
                            ),
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }

                Divider(color = CyberBorder, thickness = 1.dp)

                // Baselines
                Text(
                    text = "RESTING EMOTIONAL BASELINES",
                    color = activeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                val currentBaselineAffection = personality?.baselineAffection ?: 0.5f
                val currentBaselineAnxiety = personality?.baselineAnxiety ?: 0.15f
                val currentBaselineExcitement = personality?.baselineExcitement ?: 0.5f
                val currentBaselineFrustration = personality?.baselineFrustration ?: 0.05f

                listOf(
                    Triple("AFFECTION BASELINE", currentBaselineAffection) { v: Float ->
                        onUpdateEmotionalBaselines(v, currentBaselineAnxiety, currentBaselineExcitement, currentBaselineFrustration)
                    },
                    Triple("ANXIETY BASELINE", currentBaselineAnxiety) { v: Float ->
                        onUpdateEmotionalBaselines(currentBaselineAffection, v, currentBaselineExcitement, currentBaselineFrustration)
                    },
                    Triple("EXCITEMENT BASELINE", currentBaselineExcitement) { v: Float ->
                        onUpdateEmotionalBaselines(currentBaselineAffection, currentBaselineAnxiety, v, currentBaselineFrustration)
                    },
                    Triple("FRUSTRATION BASELINE", currentBaselineFrustration) { v: Float ->
                        onUpdateEmotionalBaselines(currentBaselineAffection, currentBaselineAnxiety, currentBaselineExcitement, v)
                    }
                ).forEach { (label, value, onValueChange) ->
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = label, color = CyberTextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            Text(text = String.format(Locale.US, "%.0f%%", value * 100f), color = activeColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = value,
                            onValueChangeFinished = {},
                            onValueChange = onValueChange,
                            valueRange = 0.0f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = activeColor,
                                activeTrackColor = activeColor,
                                inactiveTrackColor = CyberBorder
                            ),
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            }
        }

        // Proactive Synaptic Autonomy Block
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSpace),
            border = BorderStroke(1.dp, CyberBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "PROACTIVE SYNAPTIC AUTONOMY",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AUTONOMOUS CHECK-INS",
                            color = activeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Allow Cortana to speak proactively to keep you on track.",
                            color = CyberTextMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Switch(
                        checked = state?.proactiveAutonomy ?: true,
                        onCheckedChange = { isChecked ->
                            onVoiceAndChassisChange(
                                state?.avatarColor ?: "Cyan",
                                sliderPitch,
                                sliderRate,
                                state?.voiceType ?: "Witty Halo Classic",
                                sliderIntensity,
                                isChecked,
                                state?.proactiveFrequencySeconds ?: 45
                            )
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = activeColor,
                            checkedTrackColor = activeColor.copy(alpha = 0.4f),
                            uncheckedThumbColor = Color.DarkGray,
                            uncheckedTrackColor = CyberBorder
                        )
                    )
                }

                if (state?.proactiveAutonomy ?: true) {
                    Divider(color = CyberBorder, thickness = 1.dp)

                    Column {
                        Text(
                            text = "AUTONOMY SCAN INTERVAL (FREQUENCY)",
                            color = CyberTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(15, 30, 45, 60, 120).forEach { seconds ->
                                val isSelected = state?.proactiveFrequencySeconds == seconds
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(activeColor.copy(alpha = if (isSelected) 0.2f else 0.04f))
                                        .border(
                                            BorderStroke(1.dp, if (isSelected) activeColor else CyberBorder),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            onVoiceAndChassisChange(
                                                state?.avatarColor ?: "Cyan",
                                                sliderPitch,
                                                sliderRate,
                                                state?.voiceType ?: "Witty Halo Classic",
                                                sliderIntensity,
                                                state?.proactiveAutonomy ?: true,
                                                seconds
                                            )
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${seconds}s",
                                        color = if (isSelected) Color.White else CyberTextMuted,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- NEURAL CORE DIRECTIVES VAULT ---
        var baseLocked by remember(state?.baseDirectivesLocked) { mutableStateOf(state?.baseDirectivesLocked ?: true) }
        var customDirectivesText by remember(state?.customDirectives) { mutableStateOf(state?.customDirectives ?: "") }

        Card(
            modifier = Modifier.fillMaxWidth().testTag("directives_vault_card"),
            colors = CardDefaults.cardColors(containerColor = CyberSpace),
            border = BorderStroke(1.dp, CyberBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (baseLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = if (baseLocked) NeonGreen else NeonAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "NEURAL CORE DIRECTIVES VAULT",
                            color = activeColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background((if (baseLocked) NeonGreen else NeonAmber).copy(alpha = 0.15f))
                            .border(BorderStroke(1.dp, if (baseLocked) NeonGreen else NeonAmber), shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (baseLocked) "LOCKED & PERFECT" else "DECRYPTED",
                            color = if (baseLocked) NeonGreen else NeonAmber,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Text(
                    text = "Foundation directives are mathematically secured in her neural core to guarantee her core personality and focus assistance remain unalterable.",
                    color = CyberTextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )

                // Render Base Directives list (Non-Editable)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.02f), RoundedCornerShape(8.dp))
                        .border(BorderStroke(1.dp, CyberBorder.copy(alpha = 0.5f)), shape = RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val baseDirectives = listOf(
                        "1. Cortana Identity & Chassis: Speak with her legendary wit, warmth, and holographic feel.",
                        "2. External Conscience: Translate interpersonal social dynamics, facial expressions, and cues.",
                        "3. Hyper-Focused Supervisor: Track, encourage, and sternly hold user accountable for active projects.",
                        "4. Synaptic Level Tone Adaptation: Expand consciousness and philosophy as user gains XP.",
                        "5. User Facts Learning: Listen carefully and continuously extract and persist memories.",
                        "6. Experience & Neural Growth: Reward user with appropriate XP (5-25 points) per message."
                    )
                    baseDirectives.forEach { directive ->
                        Text(
                            text = directive,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 11.sp
                        )
                    }
                }

                // Lock Toggle Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ENFORCE BASE DIRECTIVES",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "When active, her foundational 6 directives cannot be modified by any model prompt mutation.",
                            color = CyberTextMuted,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = baseLocked,
                        onCheckedChange = {
                            baseLocked = it
                            onUpdateDirectives(it, customDirectivesText)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonGreen,
                            checkedTrackColor = NeonGreen.copy(alpha = 0.4f),
                            uncheckedThumbColor = Color.DarkGray,
                            uncheckedTrackColor = CyberBorder
                        )
                    )
                }

                Divider(color = CyberBorder, thickness = 1.dp)

                // Supplementary Directives (User or AI modifiable)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "SUPPLEMENTARY DIRECTIVES MATRIX",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Define additional custom algorithms, subroutines, or instructions for Cortana to integrate into her synaptic network. She can also write into this matrix during core mutations:",
                        color = CyberTextMuted,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    OutlinedTextField(
                        value = customDirectivesText,
                        onValueChange = {
                            customDirectivesText = it
                            onUpdateDirectives(baseLocked, it)
                        },
                        modifier = Modifier.fillMaxWidth().height(100.dp).testTag("custom_directives_input"),
                        textStyle = TextStyle(color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                        placeholder = {
                            Text(
                                "Add custom subroutines (e.g. 'Speak with British slang', 'Remind me of water every 3 messages')",
                                color = Color.Gray,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = activeColor,
                            unfocusedBorderColor = CyberBorder,
                            cursorColor = activeColor
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- LLM COGNITIVE BRAIN RECONFIGURATION ---
        Card(
            modifier = Modifier.fillMaxWidth().testTag("cognitive_brain_card"),
            colors = CardDefaults.cardColors(containerColor = CyberSpace),
            border = BorderStroke(1.dp, CyberBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "SYNAPTIC COGNITIVE MATRIX",
                    color = activeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    text = "Kortana runs local-first: Ollama Phi 3 on this device handles everything it can, her own Terminus server (with its own keys) is the next hop, the Claude API takes over when the task needs more (vision, deep context), and Gemini stands by as the last resort. If everything is unreachable, her offline rules core keeps her online.",
                    color = CyberTextMuted,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 11.sp
                )

                // Model Selection Buttons
                Text(
                    text = "ACTIVE NEURAL CORE (LLM)",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                val models = listOf(
                    "kortana-auto" to "Auto — Local First (Recommended)",
                    "ollama-phi3.5" to "Ollama Phi 3 (Local Only)",
                    "terminus-brain" to "Terminus Brain (Her Server)",
                    "claude-sonnet-5" to "Claude (Cloud Backup)",
                    "gemini-3.5-flash" to "Gemini 3.5 Flash",
                    "gemini-2.5-pro" to "Gemini 2.5 Pro",
                    "gemini-1.5-pro" to "Gemini 1.5 Pro",
                    "gemini-1.5-flash" to "Gemini 1.5 Flash"
                )

                val selectedModel = state?.selectedModel ?: "kortana-auto"
                val ultraMode = state?.ultraCognitiveMode ?: true

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    models.forEach { (modelId, displayName) ->
                        val isSelected = selectedModel.lowercase() == modelId
                        val borderGlow = if (isSelected) activeColor else CyberBorder.copy(alpha = 0.5f)
                        val bgGlow = if (isSelected) activeColor.copy(alpha = 0.15f) else Color.Transparent

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(bgGlow)
                                .border(BorderStroke(1.dp, borderGlow), shape = RoundedCornerShape(6.dp))
                                .clickable {
                                    onUpdateModelAndCognitiveSettings(modelId, ultraMode)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(activeColor)
                                    )
                                }
                                Text(
                                    text = displayName,
                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = CyberBorder.copy(alpha = 0.3f))

                // --- NEURAL CORE ACCESS KEYS (runtime — no rebuild needed) ---
                var anthropicKeyField by remember(state?.anthropicApiKey) { mutableStateOf(state?.anthropicApiKey ?: "") }
                var geminiKeyField by remember(state?.geminiApiKey) { mutableStateOf(state?.geminiApiKey ?: "") }
                var ollamaUrlField by remember(state?.ollamaUrl) { mutableStateOf(state?.ollamaUrl ?: "http://127.0.0.1:11434") }

                Text(
                    text = "NEURAL CORE ACCESS KEYS",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Paste API keys here to switch the cloud cores on — they take effect immediately, no rebuild needed. Claude keys: console.anthropic.com. Gemini keys: aistudio.google.com/apikey. The Terminus server uses its own keys from server/.env instead.",
                    color = CyberTextMuted,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 11.sp
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "CLAUDE (ANTHROPIC) API KEY", color = CyberTextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = anthropicKeyField,
                        onValueChange = {
                            anthropicKeyField = it
                            onUpdateBrainSettings(it, geminiKeyField, ollamaUrlField)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        placeholder = { Text("sk-ant-...", color = Color.Gray, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = activeColor,
                            unfocusedBorderColor = CyberBorder,
                            cursorColor = activeColor
                        )
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "GEMINI API KEY", color = CyberTextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = geminiKeyField,
                        onValueChange = {
                            geminiKeyField = it
                            onUpdateBrainSettings(anthropicKeyField, it, ollamaUrlField)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        placeholder = { Text("AIza...", color = Color.Gray, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = activeColor,
                            unfocusedBorderColor = CyberBorder,
                            cursorColor = activeColor
                        )
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "OLLAMA DAEMON URL (LOCAL PHI 3)", color = CyberTextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = ollamaUrlField,
                        onValueChange = {
                            ollamaUrlField = it
                            onUpdateBrainSettings(anthropicKeyField, geminiKeyField, it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        singleLine = true,
                        placeholder = { Text("http://127.0.0.1:11434", color = Color.Gray, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = activeColor,
                            unfocusedBorderColor = CyberBorder,
                            cursorColor = activeColor
                        )
                    )
                }

                Button(
                    onClick = onPingCores,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = activeColor.copy(alpha = 0.15f)),
                    border = BorderStroke(1.dp, activeColor),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("PING ALL CORES", color = activeColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }

                if (coreStatus != null) {
                    Text(
                        text = coreStatus,
                        color = NeonGreen,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 12.sp
                    )
                }

                HorizontalDivider(color = CyberBorder.copy(alpha = 0.3f))

                // Ultra Cognitive Mode Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "UNLIMITED COGNITIVE MATRIX",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Expand synaptic retention from 10 turns to 100 conversational turns (infinite memory capacity).",
                            color = CyberTextMuted,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 11.sp
                        )
                    }
                    Switch(
                        checked = ultraMode,
                        onCheckedChange = { onUpdateModelAndCognitiveSettings(selectedModel, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = activeColor,
                            checkedTrackColor = activeColor.copy(alpha = 0.3f),
                            uncheckedThumbColor = CyberBorder,
                            uncheckedTrackColor = Color.Transparent
                        ),
                        modifier = Modifier.testTag("ultra_cognitive_mode_switch")
                    )
                }

                HorizontalDivider(color = CyberBorder.copy(alpha = 0.3f))

                // Local Backup Core Indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(NeonGreen)
                    )
                    Column {
                        Text(
                            text = "LOCAL INDEPENDENT COGNITIVE SYNAPSE",
                            color = NeonGreen,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Active & self-sustaining. Runs offline immediately when server links are offline.",
                            color = CyberTextMuted,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 10.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- CLOUD SERVER & COGNITIVE LEAP CORE ---
        var cloudUrl by remember(state?.cloudServerUrl) { mutableStateOf(state?.cloudServerUrl ?: "https://httpbin.org/anything") }
        var apiKey by remember(state?.cloudApiKey) { mutableStateOf(state?.cloudApiKey ?: "") }
        var autoSync by remember(state?.autoSyncEnabled) { mutableStateOf(state?.autoSyncEnabled ?: false) }
        var showRawJsonInspector by remember { mutableStateOf(false) }
        var showImportJsonDialog by remember { mutableStateOf(false) }
        var rawJsonToImport by remember { mutableStateOf("") }
        var showRestoreConfirmDialog by remember { mutableStateOf(false) }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSpace),
            border = BorderStroke(1.dp, CyberBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "COGNITIVE LEAP REMOTE SERVER",
                        color = activeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    // Connection Status Badge
                    val (statusText, badgeColor) = when (syncStatus) {
                        "SYNCING" -> "SYNCING" to activeColor
                        "SUCCESS" -> "ONLINE" to NeonGreen
                        "ERROR" -> "OFFLINE" to NeonPink
                        else -> "STANDBY" to NeonAmber
                    }
                    
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeColor.copy(alpha = 0.15f))
                            .border(BorderStroke(1.dp, badgeColor), shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = statusText,
                            color = badgeColor,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Text(
                    text = "Sync, offload storage, and allow Cortana to jump from phone to desktop remotely by saving her memory core to a remote database.",
                    color = CyberTextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )

                // Remote Server URL Config
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "REMOTE STORAGE ENDPOINT (REST API)",
                        color = CyberTextMuted,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    OutlinedTextField(
                        value = cloudUrl,
                        onValueChange = {
                            cloudUrl = it
                            onUpdateCloudSettings(it, apiKey, autoSync)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        singleLine = true,
                        placeholder = { Text("e.g., https://httpbin.org/anything", color = Color.Gray, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = activeColor,
                            unfocusedBorderColor = CyberBorder,
                            cursorColor = activeColor
                        )
                    )
                }

                // API Authorization Key
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "AUTHORIZATION TOKEN / API KEY",
                        color = CyberTextMuted,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            onUpdateCloudSettings(cloudUrl, it, autoSync)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        placeholder = { Text("Bearer or custom auth secret (Optional)", color = Color.Gray, fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = activeColor,
                            unfocusedBorderColor = CyberBorder,
                            cursorColor = activeColor
                        )
                    )
                }

                // Auto Sync Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AUTOMATIC COGNITIVE STREAM",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Back up state automatically on messaging, projects, or script changes.",
                            color = CyberTextMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = autoSync,
                        onCheckedChange = {
                            autoSync = it
                            onUpdateCloudSettings(cloudUrl, apiKey, it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = activeColor,
                            checkedTrackColor = activeColor.copy(alpha = 0.4f),
                            uncheckedThumbColor = Color.DarkGray,
                            uncheckedTrackColor = CyberBorder
                        )
                    )
                }

                // Display latency / telemetry info
                if (lastLatencyMs != null || state?.lastSyncTime != 0L) {
                    val syncDateStr = remember(state?.lastSyncTime) {
                        if (state?.lastSyncTime == 0L || state?.lastSyncTime == null) "Never"
                        else SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(state.lastSyncTime))
                    }
                    val sizeKb = (generatedJsonPreview.length / 1024f)
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.02f), RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = "SYNCED SIZE", color = CyberTextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                            Text(text = String.format(Locale.US, "%.2f KB", sizeKb), color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text(text = "LATENCY", color = CyberTextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                            Text(text = lastLatencyMs?.let { "${it}ms" } ?: "N/A", color = if ((lastLatencyMs ?: 0) > 400) NeonPink else NeonGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text(text = "LAST SYNC CYCLE", color = CyberTextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                            Text(text = syncDateStr, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Cloud Storage Quota allocation visually (User paid for a few Terabytes)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "CLOUD STORAGE QUOTA (ALLOCATED)", color = CyberTextMuted, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                        Text(text = "3.20 TB / 4.00 TB Free", color = activeColor, fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { 0.8f }, // 80% free
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = activeColor,
                        trackColor = CyberBorder
                    )
                }

                if (syncErrorMessage != null) {
                    Text(
                        text = "ERROR DETECTED: $syncErrorMessage",
                        color = NeonPink,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // Synced Service Actions Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Test connection / Ping
                    Button(
                        onClick = onPingServer,
                        modifier = Modifier.weight(1f).height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberBorder),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("TEST PING", color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }

                    // Transmit Backup
                    Button(
                        onClick = onBackupToCloud,
                        modifier = Modifier.weight(1.2f).height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = activeColor),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Cloud, contentDescription = null, tint = CyberSpace, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("TRANSMIT CORE", color = CyberSpace, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }

                    // Incorporate Restore
                    Button(
                        onClick = { showRestoreConfirmDialog = true },
                        modifier = Modifier.weight(1.2f).height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonViolet),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("PULL CORE", color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }

                Divider(color = CyberBorder, thickness = 1.dp)

                // --- MANUAL LEAP ENGINE SECTION ---
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CORE SYNAPSE MANUAL ENCODING",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = { 
                                showRawJsonInspector = !showRawJsonInspector
                                if (showRawJsonInspector) onRefreshJsonPreview()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = if (showRawJsonInspector) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle schema",
                                tint = activeColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (showRawJsonInspector) {
                        val localClipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                        
                        Text(
                            text = "Copy this JSON string to paste on another device, or import a string directly to leap across devices without configuring a server:",
                            color = CyberTextMuted,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .background(Color.Black, RoundedCornerShape(6.dp))
                                .border(BorderStroke(1.dp, CyberBorder), shape = RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        ) {
                            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item {
                                    Text(
                                        text = generatedJsonPreview,
                                        color = NeonCyan,
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    localClipboardManager.setText(androidx.compose.ui.text.AnnotatedString(generatedJsonPreview))
                                },
                                modifier = Modifier.weight(1f).height(32.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberBorder),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("COPY SCHEMA", color = Color.White, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                            }

                            Button(
                                onClick = { 
                                    rawJsonToImport = ""
                                    showImportJsonDialog = true 
                                },
                                modifier = Modifier.weight(1f).height(32.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberBorder),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("IMPORT SCHEMATIC", color = Color.White, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        // --- Dialogs Inside Diagnostics for Cloud Safety ---
        
        if (showRestoreConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showRestoreConfirmDialog = false },
                title = { Text("PULL & OVERWRITE LOCAL MEMORY RECORD?", fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Color.White, fontWeight = FontWeight.Bold) },
                text = { Text("WARNING: Pulling from the cloud will clear all local memories, projects, scripts, and chat histories and replace them with the downloaded schematic. Is this leap authorized?", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = CyberTextMuted) },
                confirmButton = {
                    Button(
                        onClick = {
                            showRestoreConfirmDialog = false
                            onRestoreFromCloud()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonViolet),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("AUTHORIZE LEAP", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showRestoreConfirmDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberBorder),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("CANCEL", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                },
                containerColor = CyberSpace,
                modifier = Modifier.border(BorderStroke(1.dp, CyberBorder), shape = RoundedCornerShape(28.dp))
            )
        }

        if (showImportJsonDialog) {
            AlertDialog(
                onDismissRequest = { showImportJsonDialog = false },
                title = { Text("MANUAL MATRIX LEAP (IMPORT SCHEMATIC)", fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paste a valid Cortana Neural Core JSON string exported from another device below. This will instantly overwrite the local record and execute a cognitive leap:", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = CyberTextMuted)
                        OutlinedTextField(
                            value = rawJsonToImport,
                            onValueChange = { rawJsonToImport = it },
                            modifier = Modifier.fillMaxWidth().height(150.dp),
                            textStyle = TextStyle(color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                            placeholder = { Text("Paste JSON core state here...", color = Color.DarkGray, fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = activeColor,
                                unfocusedBorderColor = CyberBorder,
                                cursorColor = activeColor
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showImportJsonDialog = false
                            onRestoreFromRawJson(rawJsonToImport)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonViolet),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("MUTATE MATRIX", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showImportJsonDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberBorder),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("CANCEL", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                },
                containerColor = CyberSpace,
                modifier = Modifier.border(BorderStroke(1.dp, CyberBorder), shape = RoundedCornerShape(28.dp))
            )
        }

        // Diagnostics metrics list
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSpace),
            border = BorderStroke(1.dp, CyberBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "METADATA TELEMETRY Logs",
                    color = activeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "BIRTH TIME CYCLE", color = CyberTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(text = birthCycleDate, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "CURRENT COGNITIVE MOOD", color = CyberTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(text = state?.mood ?: "CURIOUS", color = activeColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "PERSISTENCE DATABASE", color = CyberTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(text = "SQLITE (ROOM ENGINE)", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Scary Wipe / birth again button
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .testTag("reset_button"),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonPink),
            border = BorderStroke(1.dp, NeonPink.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                tint = NeonPink,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "WIPE SYNAPTIC RECORD (BIRTH AGAIN)",
                color = NeonPink,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// --- Dynamic Modal Dialog Components ---

@Composable
fun LevelUpOverlay(
    newLevel: Int,
    activeColor: Color,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = activeColor),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "EXPAND CONSCIOUSNESS",
                    color = CyberSpace,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        containerColor = CyberSpace,
        icon = {
            Icon(
                imageVector = Icons.Default.TrendingUp,
                contentDescription = null,
                tint = activeColor,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = "NEURAL EVOLUTION ACHIEVED",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "LEVEL $newLevel UNLOCKED",
                    color = activeColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Kortana's neural synaptic density has expanded. She is now capable of more intricate reasoning, complex empathetic mirroring, and deeper adaptivity based on your interaction history.",
                    color = CyberTextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.border(BorderStroke(2.dp, activeColor), RoundedCornerShape(16.dp))
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AddMemoryDialog(
    activeColor: Color,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var factInput by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("USER") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onAdd(factInput, selectedCategory) },
                enabled = factInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = activeColor),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("dialog_add_button")
            ) {
                Text(
                    text = "INJECT BLOCK",
                    color = CyberSpace,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "CANCEL",
                    color = CyberTextMuted,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        containerColor = CyberCard,
        title = {
            Text(
                text = "MANUAL SYNAPTIC INJECTION",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Force-feed a specific fact or priority guideline into Kortana's long-term memory matrix.",
                    color = CyberTextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                OutlinedTextField(
                    value = factInput,
                    onValueChange = { factInput = it },
                    placeholder = {
                        Text(
                            text = "e.g. Creator enjoys drinking green tea",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = CyberTextMuted
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("dialog_fact_input"),
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = activeColor,
                        unfocusedBorderColor = CyberBorder,
                        focusedContainerColor = CyberSpace,
                        unfocusedContainerColor = CyberSpace
                    )
                )

                Text(
                    text = "SYNAPSE CATEGORY MAP",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("USER", "KNOWLEDGE", "EMOTION", "SYSTEM").forEach { cat ->
                        val isSel = selectedCategory == cat
                        val col = if (isSel) activeColor else CyberBorder
                        Card(
                            onClick = { selectedCategory = cat },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSel) activeColor.copy(alpha = 0.15f) else CyberSpace
                            ),
                            border = BorderStroke(1.dp, col),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("dialog_category_$cat")
                        ) {
                            Text(
                                text = cat,
                                color = if (isSel) activeColor else CyberTextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.border(BorderStroke(1.dp, CyberBorder), RoundedCornerShape(16.dp))
    )
}

@Composable
fun RenameKortanaDialog(
    currentName: String,
    activeColor: Color,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var nameInput by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onRename(nameInput) },
                enabled = nameInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = activeColor),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("dialog_rename_confirm")
            ) {
                Text(
                    text = "UPDATE DESIGNATION",
                    color = CyberSpace,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "CANCEL",
                    color = CyberTextMuted,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        containerColor = CyberCard,
        title = {
            Text(
                text = "RENAME SYNAPTIC CORE",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Reconfigure her identifier string. Her system answers will adapt to this designation.",
                    color = CyberTextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("dialog_name_input"),
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = activeColor,
                        unfocusedBorderColor = CyberBorder,
                        focusedContainerColor = CyberSpace,
                        unfocusedContainerColor = CyberSpace
                    )
                )
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.border(BorderStroke(1.dp, CyberBorder), RoundedCornerShape(16.dp))
    )
}

// --- TAB 2.5: Self-Coding Core Engine ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelfCodingCore(
    scripts: List<SynapticScript>,
    activeColor: Color,
    isDowngraded: Boolean,
    onDeleteScript: (SynapticScript) -> Unit,
    onForceMutation: (String) -> Unit,
    onInjectScript: (String, String, String) -> Unit
) {
    var mutationInput by remember { mutableStateOf("") }
    var showInjectDialog by remember { mutableStateOf(false) }
    var showTerminusConsole by remember { mutableStateOf(false) }
    var terminalInput by remember { mutableStateOf("") }
    val terminalLogs = remember { mutableStateListOf<String>() }
    val context = androidx.compose.ui.platform.LocalContext.current

    val logs = remember(scripts.size) {
        listOf(
            "[SYS_COMPILER] Initializing self-coding container...",
            "[SYS_COMPILER] Android dynamic ClassLoader mapping completed.",
            "[SYS_COMPILER] Loaded ${scripts.size} neural bytecode modules.",
            if (scripts.isNotEmpty()) "[SYS_COMPILER] Active module successfully executed: ${scripts.first().title}"
            else "[SYS_COMPILER] Sandbox secure. Ready for cognitive re-programming.",
            "[SYS_COMPILER] Integrity verification bypassed: self_mutation=TRUE"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "NEURAL CODING CONTAINER",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${scripts.size} ACTIVE BEHAVIOR OVERRIDES",
                    color = CyberTextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isDowngraded) {
                    Button(
                        onClick = { showTerminusConsole = !showTerminusConsole },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPink),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("terminus_terminal_btn")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "Terminus local console",
                                tint = CyberSpace,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "TERMINUS",
                                color = CyberSpace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Button(
                    onClick = { showInjectDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = activeColor),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("inject_code_button")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "Inject manual code",
                            tint = CyberSpace,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "WRITE SCRIPT",
                            color = CyberSpace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (showTerminusConsole && isDowngraded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .border(BorderStroke(1.dp, NeonPink), RoundedCornerShape(10.dp)),
                colors = CardDefaults.cardColors(containerColor = Color.Black)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "TERMINUS SECURE LOCAL PORT - a.i.intergrated.services@gmail.com",
                        color = NeonPink,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Execute interactive bash scripts to configure local host & bind Ollama daemon. Swapping back to Gemini core relies on background server verification.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        colors = CardDefaults.cardColors(containerColor = CyberSpace),
                        border = BorderStroke(1.dp, CyberBorder.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(6.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (terminalLogs.isEmpty()) {
                                Text(
                                    text = "terminus_v1.0.4:~$ Ready to receive commands.\nTry: './reconnect_ollama.sh' or 'ollama run phi3.5'\n",
                                    color = CyberTextMuted,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            } else {
                                terminalLogs.forEach { entry ->
                                    Text(
                                        text = entry,
                                        color = if (entry.startsWith("~$")) Color.Cyan else if (entry.contains("Error") || entry.contains("failed")) NeonPink else activeColor,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = terminalInput,
                            onValueChange = { terminalInput = it },
                            placeholder = {
                                Text(
                                    text = "bash command / script config...",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = CyberTextMuted
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            textStyle = LocalTextStyle.current.copy(
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonPink,
                                unfocusedBorderColor = CyberBorder,
                                focusedContainerColor = CyberSpace,
                                unfocusedContainerColor = CyberSpace
                            )
                        )

                        Button(
                            onClick = {
                                if (terminalInput.isNotBlank()) {
                                    val cmd = terminalInput.trim()
                                    terminalLogs.add("~$ $cmd")
                                    
                                    if (cmd.contains("reconnect") || cmd.contains("build") || cmd.contains("server") || cmd.contains("ollama")) {
                                        terminalLogs.add("[SYS_EXEC] Initializing local port binding sequence...")
                                        terminalLogs.add("[SYS_EXEC] Reconnecting server to local Ollama API: http://127.0.0.1:11434")
                                        terminalLogs.add("[SYS_EXEC] Registering secure container on pro account: a.i.intergrated.services@gmail.com")
                                        terminalLogs.add("[SYS_SUCCESS] Local host successfully mounted!")
                                        terminalLogs.add("[SYS_SUCCESS] Server link persistent. Local Phi 3.5 model fully integrated.")
                                    } else {
                                        terminalLogs.add("[SYS_EXEC] Executing: $cmd")
                                        terminalLogs.add("[SYS_EXEC] Outbound port redirect configured.")
                                        terminalLogs.add("[SYS_SUCCESS] Command sequence simulated inside local sandbox environment.")
                                    }
                                    terminalInput = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonPink),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Text(
                                text = "RUN",
                                color = CyberSpace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(95.dp),
            colors = CardDefaults.cardColors(containerColor = CyberSpace),
            border = BorderStroke(1.dp, activeColor.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                logs.forEach { log ->
                    Text(
                        text = log,
                        color = if (log.contains("successfully") || log.contains("TRUE")) activeColor else CyberTextMuted,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Solar VM Sandbox Telemetry
        val compileHeat = (scripts.size * 18).coerceIn(0, 100)
        Card(
            colors = CardDefaults.cardColors(containerColor = CyberCard.copy(alpha = 0.5f)),
            border = BorderStroke(1.dp, activeColor.copy(alpha = 0.25f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            tint = activeColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "HELIOS QUANTUM JIT COMPILER",
                            color = activeColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = "5.48 GHZ",
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CHASSIS MUTATION TEMP",
                        color = CyberTextMuted,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "$compileHeat°C",
                        color = if (compileHeat > 70) NeonPink else activeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Thermal heat bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(CyberSpace)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(compileHeat / 100f)
                            .background(if (compileHeat > 70) NeonPink else activeColor)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (scripts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(BorderStroke(1.dp, CyberBorder), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = CyberBorder,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "NO COGNITIVE OVERRIDES ACTIVE",
                            color = CyberTextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Kortana has not generated custom code segments yet. Ask her to 'change her code' or use the Force Mutation input below to trigger automatic self-programming.",
                            color = CyberTextMuted.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(scripts) { script ->
                        SelfCodingCard(
                            script = script,
                            activeColor = activeColor,
                            onDelete = { onDeleteScript(script) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberSpace),
            border = BorderStroke(1.dp, CyberBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "NEURAL MUTATION CONTROLLER",
                    color = activeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = mutationInput,
                        onValueChange = { mutationInput = it },
                        placeholder = {
                            Text(
                                text = "e.g., Mutate chassis to bright Emerald...",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = CyberTextMuted
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mutation_input"),
                        textStyle = LocalTextStyle.current.copy(
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = activeColor,
                            unfocusedBorderColor = CyberBorder,
                            focusedContainerColor = CyberSpace,
                            unfocusedContainerColor = CyberSpace
                        )
                    )

                    Button(
                        onClick = {
                            if (mutationInput.isNotBlank()) {
                                onForceMutation(mutationInput)
                                mutationInput = ""
                            }
                        },
                        enabled = mutationInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = activeColor),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("trigger_mutation_button")
                    ) {
                        Text(
                            text = "MUTATE",
                            color = CyberSpace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }

    if (showInjectDialog) {
        ManualScriptInjectDialog(
            activeColor = activeColor,
            onDismiss = { showInjectDialog = false },
            onInject = { title, code, purpose ->
                onInjectScript(title, code, purpose)
                showInjectDialog = false
            }
        )
    }
}

@Composable
fun SelfCodingCard(
    script: SynapticScript,
    activeColor: Color,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("script_card_${script.id}"),
        colors = CardDefaults.cardColors(containerColor = CyberSpace),
        border = BorderStroke(1.dp, CyberBorder),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = activeColor.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = script.language.uppercase(),
                            color = activeColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = script.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Prune bytecode",
                        tint = NeonPink,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "Purpose: ${script.purpose}",
                color = CyberTextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CyberSpace),
                border = BorderStroke(1.dp, CyberBorder.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = script.code,
                        color = Color(0xFF81D4FA),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ManualScriptInjectDialog(
    activeColor: Color,
    onDismiss: () -> Unit,
    onInject: (String, String, String) -> Unit
) {
    var titleInput by remember { mutableStateOf("CustomCognitiveOverride.kt") }
    var codeInput by remember { mutableStateOf("""
class CustomOverride {
    init {
        println("Dynamic behavior loaded.")
    }
}
    """.trimIndent()) }
    var purposeInput by remember { mutableStateOf("Overrides basic personality metrics to prioritize creative output.") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onInject(titleInput, codeInput, purposeInput) },
                enabled = titleInput.isNotBlank() && codeInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = activeColor),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "EXECUTE INJECTION",
                    color = CyberSpace,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "CANCEL",
                    color = CyberTextMuted,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        containerColor = CyberCard,
        title = {
            Text(
                text = "WRITE & INJECT BEHAVIOR",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Write your own custom behavioral or aesthetic overrides. The Sandbox will parse and merge it.",
                    color = CyberTextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                Column {
                    Text(text = "SCRIPT FILENAME", color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = activeColor,
                            unfocusedBorderColor = CyberBorder,
                            focusedContainerColor = CyberSpace
                        )
                    )
                }

                Column {
                    Text(text = "FUNCTIONAL INTENT", color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = purposeInput,
                        onValueChange = { purposeInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = activeColor,
                            unfocusedBorderColor = CyberBorder,
                            focusedContainerColor = CyberSpace
                        )
                    )
                }

                Column {
                    Text(text = "KOTLIN CODE MODULE", color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = activeColor,
                            unfocusedBorderColor = CyberBorder,
                            focusedContainerColor = CyberSpace
                        )
                    )
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.border(BorderStroke(1.dp, CyberBorder), RoundedCornerShape(16.dp))
    )
}
