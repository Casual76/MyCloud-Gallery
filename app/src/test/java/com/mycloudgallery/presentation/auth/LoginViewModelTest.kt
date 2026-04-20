package com.mycloudgallery.presentation.auth

import com.mycloudgallery.core.security.TokenManager
import com.mycloudgallery.domain.model.AuthState
import com.mycloudgallery.domain.repository.AuthRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    private lateinit var tokenManager: TokenManager
    private lateinit var viewModel: LoginViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        authRepository = mockk()
        tokenManager = mockk(relaxed = true)
        every { authRepository.isLoggedIn() } returns false
        every { tokenManager.nasLocalIp } returns null
        every { tokenManager.username } returns null
        every { tokenManager.deviceName } returns null
        viewModel = LoginViewModel(authRepository, tokenManager)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `stato iniziale e Idle`() {
        assertInstanceOf(AuthState.Idle::class.java, viewModel.uiState.value.authState)
    }

    @Test
    fun `se gia loggato stato e Authenticated`() {
        every { authRepository.isLoggedIn() } returns true
        every { tokenManager.username } returns "utente@test.com"
        every { tokenManager.deviceName } returns "NAS-Family"

        val vm = LoginViewModel(authRepository, tokenManager)

        assertInstanceOf(AuthState.Authenticated::class.java, vm.uiState.value.authState)
    }

    @Test
    fun `login con campi vuoti produce errore`() = runTest {
        viewModel.login()

        assertInstanceOf(AuthState.Error::class.java, viewModel.uiState.value.authState)
    }

    @Test
    fun `login senza host NAS produce errore dedicato`() = runTest {
        viewModel.onUsernameChanged("utente@test.com")
        viewModel.onPasswordChanged("password123")

        viewModel.login()

        val state = viewModel.uiState.value.authState
        assertInstanceOf(AuthState.Error::class.java, state)
        assertEquals("Inserisci IP o hostname del NAS", (state as AuthState.Error).message)
    }

    @Test
    fun `login con credenziali corrette produce Loading poi Authenticated`() = runTest {
        viewModel.onServerAddressChanged("https://192.168.1.100/api/2.1/")
        viewModel.onUsernameChanged("utente@test.com")
        viewModel.onPasswordChanged("password123")
        val authenticated = AuthState.Authenticated("utente@test.com", "NAS-Family")
        coEvery { authRepository.login(any(), any()) } returns Result.success(authenticated)

        viewModel.login()

        assertInstanceOf(AuthState.Loading::class.java, viewModel.uiState.value.authState)
        verify { tokenManager.nasLocalIp = "192.168.1.100" }

        advanceUntilIdle()

        assertEquals(authenticated, viewModel.uiState.value.authState)
    }

    @Test
    fun `login fallito produce stato Error con messaggio`() = runTest {
        viewModel.onServerAddressChanged("192.168.1.100")
        viewModel.onUsernameChanged("utente@test.com")
        viewModel.onPasswordChanged("password_sbagliata")
        coEvery { authRepository.login(any(), any()) } returns Result.failure(
            Exception("Credenziali non valide"),
        )

        viewModel.login()
        advanceUntilIdle()

        val state = viewModel.uiState.value.authState
        assertInstanceOf(AuthState.Error::class.java, state)
        assertEquals("Credenziali non valide", (state as AuthState.Error).message)
    }

    @Test
    fun `cambio username resetta errore`() = runTest {
        viewModel.onServerAddressChanged("192.168.1.100")
        viewModel.onUsernameChanged("x")
        viewModel.onPasswordChanged("y")
        coEvery { authRepository.login(any(), any()) } returns Result.failure(Exception("Errore"))
        viewModel.login()
        advanceUntilIdle()

        viewModel.onUsernameChanged("nuovo@test.com")

        assertInstanceOf(AuthState.Idle::class.java, viewModel.uiState.value.authState)
    }
}
