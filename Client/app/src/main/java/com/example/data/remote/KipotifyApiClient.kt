package com.example.data.remote

import com.example.BuildConfig
import com.example.data.local.SettingsManager
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

object KipotifyApiClient {
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Volatile
    private var activeBaseUrl: String = BuildConfig.KIPOTIFY_BASE_URL

    fun absoluteUrl(value: String): String {
        if (value.isBlank() || value.startsWith("http://") || value.startsWith("https://") || value.startsWith("file://")) {
            return value
        }
        val base = activeBaseUrl.trimEnd('/')
        return if (value.startsWith("/")) "$base$value" else "$base/$value"
    }

    fun create(settingsManager: SettingsManager): KipotifyApiService {
        val backendBaseUrls = buildList {
            add(BuildConfig.KIPOTIFY_BASE_URL)
            BuildConfig.KIPOTIFY_FALLBACK_BASE_URLS
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach(::add)
        }.distinct()

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val token = settingsManager.getAuthToken()
                val request = if (token.isNullOrBlank()) {
                    original
                } else {
                    original.newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                }
                chain.proceed(request)
            }
            .addInterceptor { chain ->
                val original = chain.request()
                var lastFailure: IOException? = null

                backendBaseUrls.forEach { baseUrl ->
                    val request = original.retarget(baseUrl)
                    try {
                        val response = chain.proceed(request)
                        activeBaseUrl = baseUrl
                        return@addInterceptor response
                    } catch (error: IOException) {
                        lastFailure = error
                    }
                }

                throw lastFailure ?: IOException("Could not connect to Kipotify backend.")
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.KIPOTIFY_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KipotifyApiService::class.java)
    }

    private fun Request.retarget(baseUrl: String): Request {
        val parsedBase = baseUrl.toHttpUrlOrNull() ?: return this
        val newUrl = url.newBuilder()
            .scheme(parsedBase.scheme)
            .host(parsedBase.host)
            .port(parsedBase.port)
            .build()
        return newBuilder().url(newUrl).build()
    }
}
