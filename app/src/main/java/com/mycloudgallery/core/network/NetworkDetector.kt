package com.mycloudgallery.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.mycloudgallery.core.security.TokenManager
import com.mycloudgallery.domain.model.NetworkMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rileva la modalità di connessione al NAS.
 * Testa prima la connessione locale (IP diretto), poi il relay WD.
 * Aggiorna [networkMode] ogni 30 secondi.
 */
@Singleton
class NetworkDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenManager: TokenManager,
) {
    private val _networkMode = MutableStateFlow(NetworkMode.OFFLINE)
    val networkMode: StateFlow<NetworkMode> = _networkMode.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    fun start() {
        registerNetworkCallback()
        scope.launch { periodicProbe() }
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { probeNasConnection() }
                }

                override fun onLost(network: Network) {
                    _networkMode.value = NetworkMode.OFFLINE
                }
            },
        )
    }

    private suspend fun periodicProbe() {
        while (true) {
            probeNasConnection()
            delay(30_000)
        }
    }

    private suspend fun probeNasConnection() {
        val localIp = tokenManager.nasLocalIp
        if (localIp != null && tryReach("https://$localIp/api/2.1/")) {
            _networkMode.value = NetworkMode.LOCAL
            return
        }
        if (tryReach("https://wdmycloud.com/api/2.1/")) {
            _networkMode.value = NetworkMode.RELAY
            return
        }
        _networkMode.value = NetworkMode.OFFLINE
    }

    private fun tryReach(url: String): Boolean = try {
        val request = Request.Builder().url(url).head().build()
        probeClient.newCall(request).execute().use { it.isSuccessful || it.code == 401 }
    } catch (_: Exception) {
        false
    }

    /** URL base corrente per le API REST WD */
    fun getBaseUrl(): String {
        val localIp = tokenManager.nasLocalIp
        return when (_networkMode.value) {
            NetworkMode.LOCAL -> "https://$localIp/api/2.1/"
            NetworkMode.RELAY -> "https://wdmycloud.com/api/2.1/"
            NetworkMode.OFFLINE -> "https://wdmycloud.com/api/2.1/"
        }
    }

    /** URL base WebDAV corrente */
    fun getWebDavBaseUrl(): String {
        val localIp = tokenManager.nasLocalIp
        return when (_networkMode.value) {
            NetworkMode.LOCAL -> "https://$localIp/webdav/"
            NetworkMode.RELAY -> "https://wdmycloud.com/webdav/"
            NetworkMode.OFFLINE -> ""
        }
    }
}
