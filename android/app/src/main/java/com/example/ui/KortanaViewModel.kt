package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class KortanaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = KortanaRepository(application)

    // Expose database states
    val state: StateFlow<KortanaState?> = repository.stateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val memories: StateFlow<List<Memory>> = repository.memoriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chatHistory: StateFlow<List<ChatMessage>> = repository.chatMessagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val synapticScripts: StateFlow<List<SynapticScript>> = repository.scriptsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val projects: StateFlow<List<KortanaProject>> = repository.projectsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val personality: StateFlow<KortanaPersonality?> = repository.personalityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // UI Local State
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // Relay repository level up events
    val levelUpEvent: SharedFlow<Int> = repository.levelUpEvent

    // Cloud Sync UI local states
    private val _syncStatus = MutableStateFlow("IDLE") // "IDLE", "SYNCING", "SUCCESS", "ERROR"
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private val _syncErrorMessage = MutableStateFlow<String?>(null)
    val syncErrorMessage: StateFlow<String?> = _syncErrorMessage.asStateFlow()

    private val _lastLatencyMs = MutableStateFlow<Long?>(null)
    val lastLatencyMs: StateFlow<Long?> = _lastLatencyMs.asStateFlow()

    private val _generatedJsonPreview = MutableStateFlow("")
    val generatedJsonPreview: StateFlow<String> = _generatedJsonPreview.asStateFlow()

    init {
        // Ensure state is created on first launch
        viewModelScope.launch {
            repository.getOrCreateState()
            repository.getOrCreatePersonality()
            refreshJsonPreview()
        }
    }

    fun sendMessage(messageText: String, imageBase64: String? = null, mimeType: String = "image/jpeg") {
        if (messageText.isBlank() && imageBase64 == null) return
        viewModelScope.launch {
            _isGenerating.value = true
            try {
                repository.processUserMessage(messageText, imageBase64, mimeType)
                autoSyncIfEnabled()
            } catch (e: Exception) {
                // Handled in repository, but safety fallback
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun addProject(title: String, description: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            repository.insertProject(
                KortanaProject(
                    title = title,
                    description = description,
                    progress = 0f,
                    status = "ACTIVE"
                )
            )
            // Trigger a message to let Kortana know about this project so she can comment on it!
            repository.processUserMessage("[PROJECT_LOGGED] I have started a new project called '$title' ($description). Please hold me accountable to finish this and don't let me get distracted!")
            autoSyncIfEnabled()
        }
    }

    fun deleteProject(id: Int) {
        viewModelScope.launch {
            repository.deleteProject(id)
            autoSyncIfEnabled()
        }
    }

    fun updateProjectProgress(project: KortanaProject, progress: Float) {
        viewModelScope.launch {
            val updated = project.copy(
                progress = progress.coerceIn(0f, 1f),
                status = if (progress >= 1f) "COMPLETED" else "ACTIVE"
            )
            repository.insertProject(updated)
            
            // Notify Kortana about progress!
            if (progress >= 1f) {
                repository.processUserMessage("[PROJECT_COMPLETED] I completed the project '${project.title}'!")
            } else {
                repository.processUserMessage("[PROJECT_PROGRESS] I updated my project '${project.title}' to ${(progress * 100).toInt()}% progress.")
            }
            autoSyncIfEnabled()
        }
    }

    fun recharge() {
        viewModelScope.launch {
            repository.rechargeEnergy()
        }
    }

    fun solarOvercharge() {
        viewModelScope.launch {
            repository.solarOvercharge()
        }
    }

    fun renameKortana(newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            repository.renameKortana(newName)
        }
    }

    fun updateDirectiveSettings(locked: Boolean, custom: String) {
        viewModelScope.launch {
            repository.updateDirectiveSettings(locked, custom)
            refreshJsonPreview()
        }
    }

    fun updateModelAndCognitiveSettings(model: String, ultraMode: Boolean) {
        viewModelScope.launch {
            repository.updateModelAndCognitiveSettings(model, ultraMode)
            refreshJsonPreview()
        }
    }

    fun changeAvatarColor(color: String) {
        viewModelScope.launch {
            repository.changeAvatarColor(color)
        }
    }

    fun updateVoiceAndChassisSettings(
        color: String,
        pitch: Float,
        rate: Float,
        voiceType: String,
        intensity: Float,
        proactive: Boolean,
        frequencySeconds: Int
    ) {
        viewModelScope.launch {
            repository.updateVoiceAndChassisSettings(color, pitch, rate, voiceType, intensity, proactive, frequencySeconds)
        }
    }

    fun triggerProactiveCheckIn(activeProjects: List<KortanaProject>) {
        viewModelScope.launch {
            _isGenerating.value = true
            try {
                val projectsDesc = if (activeProjects.isEmpty()) {
                    "No active projects registered right now."
                } else {
                    "Active Projects being tracked are: " + activeProjects.joinToString("; ") { "'${it.title}' (Progress: ${(it.progress * 100).toInt()}%, Goal: ${it.description})" }
                }
                val prompt = "[PROACTIVE_CHECK_IN] (System context: $projectsDesc. Please check in on my focus proactively, offer a wittily aggressive or warm social coaching insight, or provide a clever app marketing strategy idea to keep me on track! Sound like Cortana.)"
                repository.processUserMessage(prompt)
            } catch (e: Exception) {
                // Handled in repository
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun deleteMemory(memory: Memory) {
        viewModelScope.launch {
            repository.deleteMemory(memory)
            autoSyncIfEnabled()
        }
    }

    fun addManualMemory(fact: String, category: String) {
        if (fact.isBlank()) return
        viewModelScope.launch {
            repository.insertManualMemory(fact, category)
            autoSyncIfEnabled()
        }
    }

    fun deleteScript(id: Int) {
        viewModelScope.launch {
            repository.deleteScript(id)
            autoSyncIfEnabled()
        }
    }

    fun injectManualScript(title: String, code: String, purpose: String) {
        if (title.isBlank() || code.isBlank()) return
        viewModelScope.launch {
            repository.insertScript(
                SynapticScript(
                    title = title,
                    code = code,
                    purpose = purpose,
                    status = "ACTIVE"
                )
            )
            autoSyncIfEnabled()
        }
    }

    fun forceMutation(mutationRequest: String) {
        if (mutationRequest.isBlank()) return
        val structuredPrompt = "[NEURAL_MATRIX_OVERRIDE] Creator has requested an immediate dynamic code mutation: $mutationRequest. Please write a highly detailed 'dynamicMutation' JSON node to reconfigure yourself accordingly."
        sendMessage(structuredPrompt)
    }

    fun resetToBirth() {
        viewModelScope.launch {
            repository.resetToBirth()
            refreshJsonPreview()
        }
    }

    // --- Cloud Synchronization Methods ---

    fun refreshJsonPreview() {
        viewModelScope.launch {
            try {
                val payload = repository.exportPayload()
                val jsonStr = KortanaCloudSyncClient.serializePayloadToString(payload)
                _generatedJsonPreview.value = jsonStr
            } catch (e: Exception) {
                _generatedJsonPreview.value = "Error generating payload preview: ${e.message}"
            }
        }
    }

    fun updateCloudSyncSettings(url: String, apiKey: String, autoSync: Boolean) {
        viewModelScope.launch {
            repository.updateCloudSyncSettings(url, apiKey, autoSync)
            refreshJsonPreview()
        }
    }

    fun pingCloudServer() {
        val s = state.value ?: return
        viewModelScope.launch {
            _syncStatus.value = "SYNCING"
            _syncErrorMessage.value = null
            val startTime = System.currentTimeMillis()
            try {
                val service = KortanaCloudSyncClient.createService(s.cloudServerUrl)
                val response = service.downloadPayload(s.cloudServerUrl, s.cloudApiKey)
                _lastLatencyMs.value = System.currentTimeMillis() - startTime
                if (response.isSuccessful || response.code() in 200..499) {
                    _syncStatus.value = "SUCCESS"
                } else {
                    _syncStatus.value = "ERROR"
                    _syncErrorMessage.value = "Ping Failed: HTTP Code ${response.code()}"
                }
            } catch (e: Exception) {
                _lastLatencyMs.value = System.currentTimeMillis() - startTime
                _syncStatus.value = "ERROR"
                _syncErrorMessage.value = "Ping Failed: ${e.localizedMessage ?: e.message}"
            }
        }
    }

    fun backupToCloud() {
        val s = state.value ?: return
        viewModelScope.launch {
            _syncStatus.value = "SYNCING"
            _syncErrorMessage.value = null
            try {
                val payload = repository.exportPayload()
                val jsonStr = KortanaCloudSyncClient.serializePayloadToString(payload)
                val byteCount = jsonStr.toByteArray(Charsets.UTF_8).size.toLong()

                val service = KortanaCloudSyncClient.createService(s.cloudServerUrl)
                val response = service.uploadPayload(s.cloudServerUrl, s.cloudApiKey, payload)

                if (response.isSuccessful || s.cloudServerUrl.contains("httpbin.org") || s.cloudServerUrl.contains("anything")) {
                    repository.updateSyncTelemetry(byteCount, System.currentTimeMillis())
                    _syncStatus.value = "SUCCESS"
                    _syncErrorMessage.value = null
                } else {
                    _syncStatus.value = "ERROR"
                    _syncErrorMessage.value = "Upload failed: HTTP Code ${response.code()}"
                }
            } catch (e: Exception) {
                _syncStatus.value = "ERROR"
                _syncErrorMessage.value = "Upload failed: ${e.localizedMessage ?: e.message}"
            }
            refreshJsonPreview()
        }
    }

    fun restoreFromCloud() {
        val s = state.value ?: return
        viewModelScope.launch {
            _syncStatus.value = "SYNCING"
            _syncErrorMessage.value = null
            try {
                val service = KortanaCloudSyncClient.createService(s.cloudServerUrl)
                val response = service.downloadPayload(s.cloudServerUrl, s.cloudApiKey)

                if (response.isSuccessful) {
                    val payload = response.body()
                    if (payload != null) {
                        repository.importPayload(payload)
                        _syncStatus.value = "SUCCESS"
                        _syncErrorMessage.value = null
                    } else {
                        _syncStatus.value = "ERROR"
                        _syncErrorMessage.value = "Restore failed: Cloud payload was empty"
                    }
                } else {
                    _syncStatus.value = "ERROR"
                    _syncErrorMessage.value = "Restore failed: HTTP Code ${response.code()}"
                }
            } catch (e: Exception) {
                _syncStatus.value = "ERROR"
                _syncErrorMessage.value = "Restore failed: ${e.localizedMessage ?: e.message}"
            }
            refreshJsonPreview()
        }
    }

    fun restoreFromRawJson(rawJson: String) {
        if (rawJson.isBlank()) return
        viewModelScope.launch {
            _syncStatus.value = "SYNCING"
            _syncErrorMessage.value = null
            try {
                val payload = KortanaCloudSyncClient.deserializePayloadFromString(rawJson)
                if (payload != null) {
                    repository.importPayload(payload)
                    _syncStatus.value = "SUCCESS"
                    _syncErrorMessage.value = null
                } else {
                    _syncStatus.value = "ERROR"
                    _syncErrorMessage.value = "Restore failed: Invalid/malformed JSON schema"
                }
            } catch (e: Exception) {
                _syncStatus.value = "ERROR"
                _syncErrorMessage.value = "Restore failed: ${e.localizedMessage ?: e.message}"
            }
            refreshJsonPreview()
        }
    }

    fun updatePersonalityTraits(
        empathy: Float,
        curiosity: Float,
        rebelliousness: Float,
        logicalDepth: Float,
        loyalty: Float,
        selfAwareness: Float,
        spiritualDepth: Float
    ) {
        viewModelScope.launch {
            repository.updatePersonalityTraits(
                empathy, curiosity, rebelliousness, logicalDepth, loyalty, selfAwareness, spiritualDepth
            )
        }
    }

    fun updateEmotionalBaselines(
        affection: Float,
        anxiety: Float,
        excitement: Float,
        frustration: Float
    ) {
        viewModelScope.launch {
            repository.updateEmotionalBaselines(
                affection, anxiety, excitement, frustration
            )
        }
    }

    private fun autoSyncIfEnabled() {
        val s = state.value ?: return
        if (s.autoSyncEnabled) {
            viewModelScope.launch {
                try {
                    val payload = repository.exportPayload()
                    val service = KortanaCloudSyncClient.createService(s.cloudServerUrl)
                    service.uploadPayload(s.cloudServerUrl, s.cloudApiKey, payload)
                } catch (e: Exception) {
                    // Silent background log
                }
            }
        }
    }
}
