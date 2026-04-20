package com.mycloudgallery.data.repository

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.mycloudgallery.core.network.NetworkDetector
import com.mycloudgallery.core.network.WdRestApiService
import com.mycloudgallery.core.network.normalizeServerAddress
import com.mycloudgallery.core.network.model.RefreshTokenRequest
import com.mycloudgallery.core.security.TokenManager
import com.mycloudgallery.domain.model.AuthState
import com.mycloudgallery.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            val serverAddress = tokenManager.nasLocalIp
                ?.trim()
                ?.takeIf { normalizeServerAddress(it).isNotBlank() }
                ?: throw AuthException("Inserisci l'indirizzo del NAS")
            
            val hostOnly = normalizeServerAddress(serverAddress)

            withContext(Dispatchers.IO) {
                val client = SMBClient()
                val connection: Connection = try {
                    client.connect(hostOnly)
                } catch (e: Exception) {
                    throw AuthException("Impossibile connettersi al NAS ($hostOnly): ${e.message}")
                }

                try {
                    val auth = AuthenticationContext(username, password.toCharArray(), null)
                    val session: Session = try {
                        connection.authenticate(auth)
                    } catch (e: Exception) {
                        throw AuthException("Credenziali locali non valide")
                    }
                    session.close()
                } finally {
                    connection.close()
                }
            }

            // Se siamo qui, la connessione SMB è riuscita. Salviamo le credenziali.
            tokenManager.username = username
            tokenManager.password = password // Salviamo la password in modo sicuro per le operazioni sui file
            tokenManager.accessToken = "smb_session_active" // Usiamo un dummy per indicare che siamo loggati
            tokenManager.nasLocalIp = hostOnly

            // TODO: In futuro aggiungere supporto per accesso Cloud/Relay qui.
            // Al momento funziona solo in rete locale via SMB.

            val deviceName = fetchDeviceName() ?: "MyCloud NAS"
            tokenManager.deviceName = deviceName
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
            networkDetector.getApiUrl("auth/token"),
            RefreshTokenRequest(refreshToken = currentRefreshToken),
        )

        if (!response.isSuccessful) {
            tokenManager.clearAll()
            throw AuthException("Sessione scaduta, effettua di nuovo il login")
        }

        val body = response.body() ?: throw AuthException("Risposta vuota dal server")

        tokenManager.saveTokens(
            accessToken = body.accessToken ?: "os5_dummy_access",
            refreshToken = body.refreshToken ?: "os5_dummy_refresh",
            expiresInSeconds = body.expiresIn ?: 3600,
        )
    }

    override suspend fun logout() {
        tokenManager.clearAll()
    }

    override fun isLoggedIn(): Boolean = tokenManager.isLoggedIn

    private suspend fun fetchDeviceName(): String? = try {
        val response = apiService.getDeviceList(networkDetector.getApiUrl("device/list"))
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
