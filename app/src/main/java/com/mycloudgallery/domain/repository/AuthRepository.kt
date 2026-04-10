package com.mycloudgallery.domain.repository

import com.mycloudgallery.domain.model.AuthState

interface AuthRepository {
    suspend fun login(username: String, password: String): Result<AuthState.Authenticated>
    suspend fun refreshToken(): Result<Unit>
    suspend fun logout()
    fun isLoggedIn(): Boolean
}
