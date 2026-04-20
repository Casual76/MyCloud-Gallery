package com.mycloudgallery.core.network

import com.mycloudgallery.core.security.TokenManager
import com.mycloudgallery.domain.repository.AuthRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenManager: TokenManager,
    private val authRepositoryProvider: Provider<AuthRepository>
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val originalToken = response.request.header("Authorization")?.removePrefix("Bearer ")

        // Check if another thread already refreshed the token
        val currentToken = tokenManager.accessToken
        if (currentToken != null && currentToken != originalToken) {
            return response.request.newBuilder()
                .header("Authorization", "Bearer $currentToken")
                .build()
        }

        // Prevent infinite retry loops
        if (responseCount(response) >= 3) {
            return null
        }

        // Synchronously call refreshToken via runBlocking
        val authRepository = authRepositoryProvider.get()
        val result = runBlocking {
            authRepository.refreshToken()
        }

        if (result.isSuccess) {
            val newToken = tokenManager.accessToken
            if (newToken != null) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .build()
            }
        }

        return null
    }

    private fun responseCount(response: Response?): Int {
        var result = 1
        var priorResponse = response?.priorResponse
        while (priorResponse != null) {
            result++
            priorResponse = priorResponse.priorResponse
        }
        return result
    }
}
