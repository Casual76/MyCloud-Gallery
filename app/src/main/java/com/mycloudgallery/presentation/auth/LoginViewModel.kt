package com.mycloudgallery.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mycloudgallery.core.network.normalizeServerAddress
import com.mycloudgallery.core.security.TokenManager
import com.mycloudgallery.domain.model.AuthState
import com.mycloudgallery.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LoginUiState(
            serverAddress = tokenManager.nasLocalIp.orEmpty(),
        ),
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        if (authRepository.isLoggedIn()) {
            _uiState.update {
                it.copy(
                    authState = AuthState.Authenticated(
                        username = tokenManager.username.orEmpty(),
                        deviceName = tokenManager.deviceName,
                    ),
                )
            }
        }
    }

    fun onServerAddressChanged(serverAddress: String) {
        _uiState.update { it.copy(serverAddress = serverAddress, authState = AuthState.Idle) }
    }

    fun onUsernameChanged(username: String) {
        _uiState.update { it.copy(username = username, authState = AuthState.Idle) }
    }

    fun onPasswordChanged(password: String) {
        _uiState.update { it.copy(password = password, authState = AuthState.Idle) }
    }

    fun login() {
        val state = _uiState.value
        val serverAddress = normalizeServerAddress(state.serverAddress)

        if (serverAddress.isBlank()) {
            _uiState.update { it.copy(authState = AuthState.Error("Inserisci IP o hostname del NAS")) }
            return
        }

        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(authState = AuthState.Error("Inserisci username e password")) }
            return
        }

        tokenManager.nasLocalIp = serverAddress
        _uiState.update { it.copy(authState = AuthState.Loading) }

        viewModelScope.launch {
            authRepository.login(state.username, state.password)
                .onSuccess { authenticated ->
                    _uiState.update { it.copy(authState = authenticated) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(authState = AuthState.Error(error.message ?: "Errore sconosciuto"))
                    }
                }
        }
    }
}

data class LoginUiState(
    val serverAddress: String = "",
    val username: String = "",
    val password: String = "",
    val authState: AuthState = AuthState.Idle,
)
