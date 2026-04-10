package com.mycloudgallery.presentation.auth

import com.mycloudgallery.domain.model.AuthState
import com.mycloudgallery.domain.repository.AuthRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var authRepository: AuthRepository
    private lateinit var viewModel: LoginViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        authRepository = mockk()
        every { authRepository.isLoggedIn() } returns false
        viewModel = LoginViewModel(authRepository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `stato iniziale è Idle`() {
        assertInstanceOf(AuthState.Idle::class.java, viewModel.uiState.value.authState)
    }

    @Test
    fun `se già loggato stato è Authenticated`() {
        every { authRepository.isLoggedIn() } returns true
        val vm = LoginViewModel(authRepository)
        assertInstanceOf(AuthState.Authenticated::class.java, vm.uiState.value.authState)
    }

    @Test
    fun `login con campi vuoti produce errore`() = runTest {
        viewModel.login()
        assertInstanceOf(AuthState.Error::class.java, viewModel.uiState.value.authState)
    }

    @Test
    fun `login con credenziali corrette produce Loading poi Authenticated`() = runTest {
        // Arrange
        viewModel.onUsernameChanged("utente@test.com")
        viewModel.onPasswordChanged("password123")
        val authenticated = AuthState.Authenticated("utente@test.com", "NAS-Family")
        coEvery { authRepository.login(any(), any()) } returns Result.success(authenticated)

        // Act
        viewModel.login()

        // Loading immediato prima della coroutine
        assertInstanceOf(AuthState.Loading::class.java, viewModel.uiState.value.authState)

        advanceUntilIdle()

        // Assert
        assertEquals(authenticated, viewModel.uiState.value.authState)
    }

    @Test
    fun `login fallito produce stato Error con messaggio`() = runTest {
        // Arrange
        viewModel.onUsernameChanged("utente@test.com")
        viewModel.onPasswordChanged("password_sbagliata")
        coEvery { authRepository.login(any(), any()) } returns Result.failure(
            Exception("Credenziali non valide"),
        )

        // Act
        viewModel.login()
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value.authState
        assertInstanceOf(AuthState.Error::class.java, state)
        assertEquals("Credenziali non valide", (state as AuthState.Error).message)
    }

    @Test
    fun `cambio username resetta errore`() = runTest {
        // Arrange — produci errore
        viewModel.onUsernameChanged("x")
        viewModel.onPasswordChanged("y")
        coEvery { authRepository.login(any(), any()) } returns Result.failure(Exception("Errore"))
        viewModel.login()
        advanceUntilIdle()

        // Act — modifica username
        viewModel.onUsernameChanged("nuovo@test.com")

        // Assert
        assertInstanceOf(AuthState.Idle::class.java, viewModel.uiState.value.authState)
    }
}
