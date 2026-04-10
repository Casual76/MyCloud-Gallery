package com.mycloudgallery.presentation.album

import com.mycloudgallery.domain.model.Album
import com.mycloudgallery.domain.repository.AlbumRepository
import com.mycloudgallery.domain.repository.SharedAlbumRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var albumRepository: AlbumRepository
    private lateinit var sharedAlbumRepository: SharedAlbumRepository
    private lateinit var viewModel: AlbumsViewModel

    private val sampleAlbums = listOf(
        Album("1", "Estate 2024", null, 5, 1000L, false, 0),
        Album("2", "Natale 2023", null, 12, 2000L, false, 1),
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        albumRepository = mockk()
        sharedAlbumRepository = mockk()

        every { albumRepository.getAll() } returns flowOf(sampleAlbums)
        every { albumRepository.getFavoritesCount() } returns flowOf(7)
        every { sharedAlbumRepository.getPendingRequestsCount() } returns flowOf(0)

        viewModel = AlbumsViewModel(albumRepository, sharedAlbumRepository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `stato iniziale ha isLoading true`() {
        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `dopo collect emette album e conteggi corretti`() = runTest {
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.albums.size)
        assertEquals(7, state.favoritesCount)
        assertEquals(0, state.pendingRequestsCount)
        assertFalse(state.isLoading)
    }

    @Test
    fun `onShowCreateDialog apre il dialog`() = runTest {
        advanceUntilIdle()
        viewModel.onShowCreateDialog()

        assertTrue(viewModel.uiState.value.showCreateDialog)
        assertEquals("", viewModel.uiState.value.dialogInputName)
    }

    @Test
    fun `onCreateAlbum chiama repository e chiude il dialog`() = runTest {
        advanceUntilIdle()
        coEvery { albumRepository.createAlbum("Nuovo") } returns
            Album("3", "Nuovo", null, 0, 3000L, false, 0)

        viewModel.onShowCreateDialog()
        viewModel.onDialogNameChanged("Nuovo")
        viewModel.onCreateAlbum()
        advanceUntilIdle()

        coVerify { albumRepository.createAlbum("Nuovo") }
        assertFalse(viewModel.uiState.value.showCreateDialog)
    }

    @Test
    fun `onCreateAlbum non chiama repository se il nome è vuoto`() = runTest {
        advanceUntilIdle()
        viewModel.onShowCreateDialog()
        viewModel.onDialogNameChanged("  ")
        viewModel.onCreateAlbum()

        coVerify(exactly = 0) { albumRepository.createAlbum(any()) }
    }

    @Test
    fun `onRequestDelete e onConfirmDelete eliminano album`() = runTest {
        advanceUntilIdle()
        coEvery { albumRepository.deleteAlbum("1") } returns Unit

        viewModel.onRequestDelete("1")
        assertEquals("1", viewModel.uiState.value.deleteAlbumId)

        viewModel.onConfirmDelete()
        advanceUntilIdle()

        coVerify { albumRepository.deleteAlbum("1") }
        assertNull(viewModel.uiState.value.deleteAlbumId)
    }

    @Test
    fun `onStartRename popola il dialog con il nome corrente`() = runTest {
        advanceUntilIdle()
        viewModel.onStartRename("1")

        assertEquals("1", viewModel.uiState.value.renameAlbumId)
        assertEquals("Estate 2024", viewModel.uiState.value.dialogInputName)
    }
}
