package com.mycloudgallery.presentation.search

import com.mycloudgallery.domain.model.MediaItem
import com.mycloudgallery.domain.model.MediaType
import com.mycloudgallery.domain.model.SearchFilter
import com.mycloudgallery.domain.repository.SearchRepository
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: SearchRepository
    private lateinit var viewModel: SearchViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        coEvery { repository.search(any(), any()) } returns emptyList()
        viewModel = SearchViewModel(repository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `stato iniziale è vuoto e non in caricamento`() {
        val state = viewModel.uiState.value
        assertEquals("", state.query)
        assertTrue(state.results.isEmpty())
        assertFalse(state.isLoading)
    }

    @Test
    fun `onQueryChanged aggiorna la query e avvia ricerca con debounce`() = runTest {
        viewModel.onQueryChanged("cane")
        advanceUntilIdle()

        assertEquals("cane", viewModel.uiState.value.query)
        coVerify { repository.search("cane", any()) }
    }

    @Test
    fun `clearQuery resetta query e risultati`() = runTest {
        viewModel.onQueryChanged("test")
        advanceUntilIdle()

        viewModel.clearQuery()
        assertEquals("", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.results.isEmpty())
    }

    @Test
    fun `onSearchSubmit aggiunge la query alle recenti`() = runTest {
        viewModel.onSearchSubmit("gatto")
        viewModel.onSearchSubmit("cane")

        val recent = viewModel.uiState.value.recentQueries
        assertTrue(recent.contains("gatto"))
        assertTrue(recent.contains("cane"))
    }

    @Test
    fun `query duplicate nelle recenti vengono deduplicate`() = runTest {
        viewModel.onSearchSubmit("gatto")
        viewModel.onSearchSubmit("gatto")

        val recent = viewModel.uiState.value.recentQueries
        assertEquals(1, recent.count { it == "gatto" })
    }

    @Test
    fun `onFilterChanged aggiorna filtro e riavvia ricerca`() = runTest {
        val filter = SearchFilter(mediaType = MediaType.VIDEO)
        viewModel.onFilterChanged(filter)
        advanceUntilIdle()

        assertEquals(MediaType.VIDEO, viewModel.uiState.value.filter.mediaType)
        coVerify { repository.search(any(), filter) }
    }
}
