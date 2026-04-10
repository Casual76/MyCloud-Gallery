package com.mycloudgallery.desktop.data.repository

import com.mycloudgallery.desktop.data.security.DesktopKeyStore
import com.mycloudgallery.domain.model.AuthState
import com.mycloudgallery.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class DesktopAuthRepository(
    private val keyStore: DesktopKeyStore,
) : AuthRepository {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class LoginRequest(val username: String, val password: String)

    @Serializable
    private data class LoginResponse(
        val access_token: String,
        val refresh_token: String? = null,
        val expires_in: Long = 3600,
    )

    override suspend fun login(username: String, password: String): Result<AuthState.Authenticated> =
        withContext(Dispatchers.IO) {
            try {
                val nasIp = keyStore.nasIp ?: "192.168.1.1"
                val body = json.encodeToString(LoginRequest.serializer(), LoginRequest(username, password))
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("https://$nasIp/api/2.1/auth/token")
                    .post(body)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val loginResponse = json.decodeFromString(
                        LoginResponse.serializer(),
                        response.body?.string() ?: ""
                    )
                    keyStore.accessToken = loginResponse.access_token
                    keyStore.refreshToken = loginResponse.refresh_token
                    keyStore.username = username
                    keyStore.tokenExpiresAt = System.currentTimeMillis() + loginResponse.expires_in * 1000
                    Result.success(AuthState.Authenticated(username, null))
                } else {
                    Result.failure(Exception("Autenticazione fallita: HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun refreshToken(): Result<Unit> = Result.success(Unit)

    override suspend fun logout() = keyStore.clearAll()

    override fun isLoggedIn(): Boolean = keyStore.isLoggedIn
}
