package com.mycloudgallery.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        if (authRepository.isLoggedIn()) {
            _uiState.update { it.copy(authState = AuthState.Authenticated("", null)) }
        }
    }

    fun onUsernameChanged(username: String) {
        _uiState.update { it.copy(username = username, authState = AuthState.Idle) }
    }

    fun onPasswordChanged(password: String) {
        _uiState.update { it.copy(password = password, authState = AuthState.Idle) }
    }

    fun login() {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(authState = AuthState.Error("Inserisci email e password")) }
            return
        }

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
    val username: String = "",
    val password: String = "",
    val authState: AuthState = AuthState.Idle,
)
