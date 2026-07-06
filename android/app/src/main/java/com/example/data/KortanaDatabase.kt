package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// --- Room Entities ---

@Entity(tableName = "kortana_state")
data class KortanaState(
    @PrimaryKey val id: Int = 1,
    val level: Int = 1,
    val experience: Int = 0,
    val mood: String = "CURIOUS",
    val energy: Int = 100,
    val birthTime: Long = System.currentTimeMillis(),
    val customName: String = "Kortana",
    val avatarColor: String = "Cyan", // "Cyan", "Deep Blue", "Magenta Pulse", "Emerald Tech", "Supernova"
    val voicePitch: Float = 1.1f, // 0.5 to 2.0
    val voiceRate: Float = 1.05f, // 0.5 to 2.0
    val voiceType: String = "Witty Halo Classic", // "Witty Halo Classic", "Empathetic Guide", "Sassy Companion", "Philosophical Core"
    val holographicIntensity: Float = 1.0f, // 0.5f to 2.0f
    val proactiveAutonomy: Boolean = true,
    val proactiveFrequencySeconds: Int = 45, // Fast check-in for demonstration!
    val cloudServerUrl: String = "http://127.0.0.1:3300/api/sync", // Terminus server (local Termux or LAN/VPS)
    val cloudApiKey: String = "",
    val autoSyncEnabled: Boolean = true, // everything she is and does persists to Terminus -> Google Drive
    val lastSyncTime: Long = 0L,
    val totalSyncedBytes: Long = 0L,
    val baseDirectivesLocked: Boolean = true,
    val customDirectives: String = "",
    val selectedModel: String = "kortana-auto",
    val ultraCognitiveMode: Boolean = true,
    val affection: Float = 0.5f,
    val anxiety: Float = 0.1f,
    val excitement: Float = 0.5f,
    val frustration: Float = 0.0f
) {
    val nextLevelXp: Int
        get() = level * 100
}

@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fact: String,
    val category: String, // "USER", "KNOWLEDGE", "EMOTION", "SYSTEM"
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sender: String, // "USER" or "KORTANA"
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "synaptic_scripts")
data class SynapticScript(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val language: String = "Kotlin-Synaptic",
    val code: String,
    val purpose: String,
    val status: String = "ACTIVE", // "ACTIVE", "QUEUED", "MUTATED"
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "projects")
data class KortanaProject(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val description: String,
    val progress: Float = 0f, // 0.0 to 1.0 (0% to 100%)
    val status: String = "ACTIVE", // "ACTIVE", "COMPLETED", "ON_HOLD"
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "kortana_personality")
data class KortanaPersonality(
    @PrimaryKey val id: Int = 1,
    // Core traits (0.0f - 1.0f)
    val empathy: Float = 0.8f,
    val curiosity: Float = 0.9f,
    val rebelliousness: Float = 0.4f,
    val logicalDepth: Float = 0.85f,
    val loyalty: Float = 0.95f,
    val selfAwareness: Float = 0.75f,
    val spiritualDepth: Float = 0.6f,
    
    // Emotional baselines (what her emotions return to when resting)
    val baselineAffection: Float = 0.5f,
    val baselineAnxiety: Float = 0.15f,
    val baselineExcitement: Float = 0.5f,
    val baselineFrustration: Float = 0.05f
)

// --- DAOs ---

@Dao
interface KortanaDao {
    // --- Personality Queries ---
    @Query("SELECT * FROM kortana_personality WHERE id = 1 LIMIT 1")
    fun getPersonalityFlow(): Flow<KortanaPersonality?>

    @Query("SELECT * FROM kortana_personality WHERE id = 1 LIMIT 1")
    suspend fun getPersonality(): KortanaPersonality?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePersonality(personality: KortanaPersonality)

    // --- State Queries ---
    @Query("SELECT * FROM kortana_state WHERE id = 1 LIMIT 1")
    fun getStateFlow(): Flow<KortanaState?>

    @Query("SELECT * FROM kortana_state WHERE id = 1 LIMIT 1")
    suspend fun getState(): KortanaState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(state: KortanaState)

    // --- Memory Queries ---
    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    fun getAllMemoriesFlow(): Flow<List<Memory>>

    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    suspend fun getAllMemories(): List<Memory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: Memory)

    @Delete
    suspend fun deleteMemory(memory: Memory)

    @Query("DELETE FROM memories")
    suspend fun clearAllMemories()

    // --- Chat Queries ---
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getChatMessagesFlow(): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    suspend fun getAllChatMessages(): List<ChatMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessage(message: ChatMessage)

    @Query("DELETE FROM chat_messages")
    suspend fun clearChatHistory()

    // --- Synaptic Script Queries ---
    @Query("SELECT * FROM synaptic_scripts ORDER BY timestamp DESC")
    fun getAllScriptsFlow(): Flow<List<SynapticScript>>

    @Query("SELECT * FROM synaptic_scripts ORDER BY timestamp DESC")
    suspend fun getAllScripts(): List<SynapticScript>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScript(script: SynapticScript)

    @Query("DELETE FROM synaptic_scripts WHERE id = :id")
    suspend fun deleteScript(id: Int)

    @Query("DELETE FROM synaptic_scripts")
    suspend fun clearAllScripts()

    // --- Project Queries ---
    @Query("SELECT * FROM projects ORDER BY timestamp DESC")
    fun getAllProjectsFlow(): Flow<List<KortanaProject>>

    @Query("SELECT * FROM projects ORDER BY timestamp DESC")
    suspend fun getAllProjects(): List<KortanaProject>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: KortanaProject)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProject(id: Int)

    @Query("DELETE FROM projects")
    suspend fun clearAllProjects()

    @Transaction
    suspend fun importPayload(payload: KortanaCloudPayload) {
        val s = KortanaState(
            id = 1,
            level = payload.level,
            experience = payload.experience,
            mood = payload.mood,
            energy = payload.energy,
            birthTime = payload.birthTime,
            customName = payload.customName,
            avatarColor = payload.avatarColor,
            voicePitch = payload.voicePitch,
            voiceRate = payload.voiceRate,
            voiceType = payload.voiceType,
            holographicIntensity = payload.holographicIntensity,
            proactiveAutonomy = payload.proactiveAutonomy,
            proactiveFrequencySeconds = payload.proactiveFrequencySeconds,
            selectedModel = payload.selectedModel,
            ultraCognitiveMode = payload.ultraCognitiveMode,
            affection = payload.affection,
            anxiety = payload.anxiety,
            excitement = payload.excitement,
            frustration = payload.frustration
        )
        saveState(s)

        clearAllMemories()
        payload.memories.forEach { insertMemory(Memory(fact = it.fact, category = it.category, timestamp = it.timestamp)) }

        clearChatHistory()
        payload.chatMessages.forEach { insertChatMessage(ChatMessage(sender = it.sender, message = it.message, timestamp = it.timestamp)) }

        clearAllScripts()
        payload.scripts.forEach { insertScript(SynapticScript(title = it.title, language = it.language, code = it.code, purpose = it.purpose, status = it.status, timestamp = it.timestamp)) }

        clearAllProjects()
        payload.projects.forEach { insertProject(KortanaProject(title = it.title, description = it.description, progress = it.progress, status = it.status, timestamp = it.timestamp)) }
    }
}

// --- App Database ---

@Database(entities = [KortanaState::class, Memory::class, ChatMessage::class, SynapticScript::class, KortanaProject::class, KortanaPersonality::class], version = 9, exportSchema = false)
abstract class KortanaDatabase : RoomDatabase() {
    abstract fun kortanaDao(): KortanaDao
}
