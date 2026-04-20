package com.mycloudgallery.core.network

import com.mycloudgallery.domain.model.NetworkMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.InputStream

class TripleModeClientTest {

    private val networkDetector = mockk<NetworkDetector>()
    private val smbClient = mockk<SmbClientImpl>()
    private val webDavClient = mockk<WebDavClientImpl>()
    private lateinit var tripleModeClient: TripleModeClient

    private val networkModeFlow = MutableStateFlow(NetworkMode.OFFLINE)

    @BeforeEach
    fun setup() {
        every { networkDetector.networkMode } returns networkModeFlow
        tripleModeClient = TripleModeClient(networkDetector, smbClient, webDavClient)
    }

    @Test
    fun `propFind calls smbClient when in LOCAL mode`() = runBlocking {
        networkModeFlow.value = NetworkMode.LOCAL
        val expected = listOf<WebDavResource>()
        coEvery { smbClient.propFind(any(), any()) } returns expected

        val result = tripleModeClient.propFind("/", "1")

        assertEquals(expected, result)
        coVerify { smbClient.propFind("/", "1") }
        coVerify(exactly = 0) { webDavClient.propFind(any(), any()) }
    }

    @Test
    fun `propFind calls webDavClient when in RELAY mode`() = runBlocking {
        networkModeFlow.value = NetworkMode.RELAY
        val expected = listOf<WebDavResource>()
        coEvery { webDavClient.propFind(any(), any()) } returns expected

        val result = tripleModeClient.propFind("/", "1")

        assertEquals(expected, result)
        coVerify { webDavClient.propFind("/", "1") }
        coVerify(exactly = 0) { smbClient.propFind(any(), any()) }
    }

    @Test
    fun `propFind throws OfflineException when in OFFLINE mode`() {
        networkModeFlow.value = NetworkMode.OFFLINE

        assertThrows(OfflineException::class.java) {
            runBlocking {
                tripleModeClient.propFind("/", "1")
            }
        }
    }

    @Test
    fun `get calls smbClient when in LOCAL mode`() = runBlocking {
        networkModeFlow.value = NetworkMode.LOCAL
        val inputStream = mockk<InputStream>()
        coEvery { smbClient.get(any()) } returns inputStream

        val result = tripleModeClient.get("/test.jpg")

        assertEquals(inputStream, result)
        coVerify { smbClient.get("/test.jpg") }
    }
}
