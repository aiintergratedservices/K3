package com.example.data

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class KortanaCloudPayload(
    val level: Int,
    val experience: Int,
    val mood: String,
    val energy: Int,
    val birthTime: Long,
    val customName: String,
    val avatarColor: String,
    val voicePitch: Float,
    val voiceRate: Float,
    val voiceType: String,
    val holographicIntensity: Float,
    val proactiveAutonomy: Boolean,
    val proactiveFrequencySeconds: Int,
    val selectedModel: String = "kortana-auto",
    val ultraCognitiveMode: Boolean = true,
    val affection: Float = 0.5f,
    val anxiety: Float = 0.1f,
    val excitement: Float = 0.5f,
    val frustration: Float = 0.0f,
    val memories: List<MemoryPayload>,
    val chatMessages: List<ChatMessagePayload>,
    val scripts: List<ScriptPayload>,
    val projects: List<ProjectPayload>
)

@JsonClass(generateAdapter = true)
data class MemoryPayload(
    val fact: String,
    val category: String,
    val timestamp: Long
)

@JsonClass(generateAdapter = true)
data class ChatMessagePayload(
    val sender: String,
    val message: String,
    val timestamp: Long
)

@JsonClass(generateAdapter = true)
data class ScriptPayload(
    val title: String,
    val language: String,
    val code: String,
    val purpose: String,
    val status: String,
    val timestamp: Long
)

@JsonClass(generateAdapter = true)
data class ProjectPayload(
    val title: String,
    val description: String,
    val progress: Float,
    val status: String,
    val timestamp: Long
)

interface KortanaCloudSyncApi {
    @POST
    suspend fun uploadPayload(
        @Url url: String,
        @Header("Authorization") authHeader: String,
        @Body payload: KortanaCloudPayload
    ): Response<Void>

    @GET
    suspend fun downloadPayload(
        @Url url: String,
        @Header("Authorization") authHeader: String
    ): Response<KortanaCloudPayload>
}

object KortanaCloudSyncClient {
    fun createService(baseUrl: String): KortanaCloudSyncApi {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        // Ensure dynamic baseUrl is technically valid for Retrofit instantiation
        val safeBaseUrl = when {
            baseUrl.isBlank() -> "https://httpbin.org/"
            baseUrl.endsWith("/") -> baseUrl
            else -> "$baseUrl/"
        }

        return Retrofit.Builder()
            .baseUrl(safeBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KortanaCloudSyncApi::class.java)
    }

    fun serializePayloadToString(payload: KortanaCloudPayload): String {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(KortanaCloudPayload::class.java).indent("  ")
        return adapter.toJson(payload)
    }

    fun deserializePayloadFromString(json: String): KortanaCloudPayload? {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(KortanaCloudPayload::class.java)
        return try {
            adapter.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }
}
