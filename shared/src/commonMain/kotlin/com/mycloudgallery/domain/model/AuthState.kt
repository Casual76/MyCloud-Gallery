package com.mycloudgallery.domain.model

sealed interface AuthState {
    data object Idle : AuthState
    data object Loading : AuthState
    data class Authenticated(val username: String, val deviceName: String?) : AuthState
    data class Error(val message: String) : AuthState
}
