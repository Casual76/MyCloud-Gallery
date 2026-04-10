package com.mycloudgallery.data.repository

import com.mycloudgallery.core.network.NetworkDetector
import com.mycloudgallery.core.network.WdRestApiService
import com.mycloudgallery.core.network.model.AuthTokenResponse
import com.mycloudgallery.core.network.model.DeviceListResponse
import com.mycloudgallery.core.security.TokenManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response

class AuthRepositoryTest {

    private lateinit var apiService: WdRestApiService
    private lateinit var tokenManager: TokenManager
    private lateinit var networkDetector: NetworkDetector
    private lateinit var repository: AuthRepositoryImpl

    @BeforeEach
    fun setUp() {
        apiService = mockk()
        tokenManager = mockk(relaxed = true)
        networkDetector = mockk(relaxed = true)
        repository = AuthRepositoryImpl(apiService, tokenManager, networkDetector)
    }

    @Test
    fun `login success salva token e restituisce Authenticated`() = runTest {
        // Arrange
        val tokenResponse = AuthTokenResponse(
            accessToken = "access_123",
            refreshToken = "refresh_123",
            expiresIn = 3600,
        )
        coEvery { apiService.login(any()) } returns Response.success(tokenResponse)
        coEvery { apiService.getDeviceList() } returns Response.success(DeviceListResponse())

        // Act
        val result = repository.login("utente@test.com", "password123")

        // Assert
        assertTrue(result.isSuccess)
        verify { tokenManager.saveTokens("access_123", "refresh_123", 3600) }
        verify { tokenManager.username = "utente@test.com" }
    }

    @Test
    fun `login con credenziali errate restituisce errore 401`() = runTest {
        // Arrange
        coEvery { apiService.login(any()) } returns Response.error(
            401,
            "Unauthorized".toResponseBody(),
        )

        // Act
        val result = repository.login("utente@test.com", "password_sbagliata")

        // Assert
        assertFalse(result.isSuccess)
        assertEquals("Credenziali non valide", result.exceptionOrNull()?.message)
    }

    @Test
    fun `login con errore server 500 restituisce messaggio errore`() = runTest {
        // Arrange
        coEvery { apiService.login(any()) } returns Response.error(
            500,
            "Internal Server Error".toResponseBody(),
        )

        // Act
        val result = repository.login("utente@test.com", "password123")

        // Assert
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("500") == true)
    }

    @Test
    fun `refreshToken success aggiorna token`() = runTest {
        // Arrange
        every { tokenManager.refreshToken } returns "refresh_old"
        val tokenResponse = AuthTokenResponse(
            accessToken = "access_new",
            refreshToken = "refresh_new",
            expiresIn = 3600,
        )
        coEvery { apiService.refreshToken(any()) } returns Response.success(tokenResponse)

        // Act
        val result = repository.refreshToken()

        // Assert
        assertTrue(result.isSuccess)
        verify { tokenManager.saveTokens("access_new", "refresh_new", 3600) }
    }

    @Test
    fun `refreshToken fallito cancella sessione`() = runTest {
        // Arrange
        every { tokenManager.refreshToken } returns "refresh_scaduto"
        coEvery { apiService.refreshToken(any()) } returns Response.error(
            401,
            "Unauthorized".toResponseBody(),
        )

        // Act
        val result = repository.refreshToken()

        // Assert
        assertFalse(result.isSuccess)
        verify { tokenManager.clearAll() }
    }

    @Test
    fun `logout cancella tutti i token`() = runTest {
        // Act
        repository.logout()

        // Assert
        verify { tokenManager.clearAll() }
    }

    @Test
    fun `isLoggedIn delega a TokenManager`() {
        every { tokenManager.isLoggedIn } returns true
        assertTrue(repository.isLoggedIn())

        every { tokenManager.isLoggedIn } returns false
        assertFalse(repository.isLoggedIn())
    }
}
