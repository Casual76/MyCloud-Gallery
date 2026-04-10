package com.mycloudgallery.data.repository

import com.mycloudgallery.core.network.NetworkDetector
import com.mycloudgallery.core.network.WdRestApiService
import com.mycloudgallery.core.network.model.AuthTokenRequest
import com.mycloudgallery.core.network.model.RefreshTokenRequest
import com.mycloudgallery.core.security.TokenManager
import com.mycloudgallery.domain.model.AuthState
import com.mycloudgallery.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val apiService: WdRestApiService,
    private val tokenManager: TokenManager,
    private val networkDetector: NetworkDetector,
) : AuthRepository {

    override suspend fun login(username: String, password: String): Result<AuthState.Authenticated> =
        runCatching {
            val response = apiService.login(AuthTokenRequest(username, password))

            if (!response.isSuccessful) {
                val errorMsg = when (response.code()) {
                    401 -> "Credenziali non valide"
                    403 -> "Accesso negato"
                    else -> "Errore del server (${response.code()})"
                }
                throw AuthException(errorMsg)
            }

            val body = response.body() ?: throw AuthException("Risposta vuota dal server")

            tokenManager.saveTokens(
                accessToken = body.accessToken,
                refreshToken = body.refreshToken,
                expiresInSeconds = body.expiresIn,
            )
            tokenManager.username = username

            // Prova a ottenere il nome del dispositivo
            val deviceName = fetchDeviceName()
            tokenManager.deviceName = deviceName

            // Prova a rilevare l'IP locale del NAS
            networkDetector.start()

            AuthState.Authenticated(
                username = username,
                deviceName = deviceName,
            )
        }

    override suspend fun refreshToken(): Result<Unit> = runCatching {
        val currentRefreshToken = tokenManager.refreshToken
            ?: throw AuthException("Nessun refresh token disponibile")

        val response = apiService.refreshToken(
            RefreshTokenRequest(refreshToken = currentRefreshToken),
        )

        if (!response.isSuccessful) {
            tokenManager.clearAll()
            throw AuthException("Sessione scaduta, effettua di nuovo il login")
        }

        val body = response.body() ?: throw AuthException("Risposta vuota dal server")

        tokenManager.saveTokens(
            accessToken = body.accessToken,
            refreshToken = body.refreshToken,
            expiresInSeconds = body.expiresIn,
        )
    }

    override suspend fun logout() {
        tokenManager.clearAll()
    }

    override fun isLoggedIn(): Boolean = tokenManager.isLoggedIn

    private suspend fun fetchDeviceName(): String? = try {
        val response = apiService.getDeviceList()
        if (response.isSuccessful) {
            val devices = response.body()?.devices.orEmpty()
            val device = devices.firstOrNull()
            device?.network?.localIp?.let { tokenManager.nasLocalIp = it }
            device?.name
        } else null
    } catch (_: Exception) {
        null
    }
}

class AuthException(message: String) : Exception(message)
