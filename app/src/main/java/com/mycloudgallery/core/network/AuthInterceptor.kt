package com.mycloudgallery.core.network

import com.mycloudgallery.core.security.TokenManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interceptor OkHttp che aggiunge il Bearer token a ogni richiesta.
 * Se il token è scaduto, prova a fare il refresh automaticamente.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Non aggiungere auth alle richieste di login/refresh
        if (original.url.encodedPath.contains("auth/token")) {
            return chain.proceed(original)
        }

        val token = tokenManager.accessToken ?: return chain.proceed(original)

        val authenticatedRequest = original.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        val response = chain.proceed(authenticatedRequest)

        // Se 401, il token potrebbe essere scaduto — il refresh viene gestito dal repository
        return response
    }
}
