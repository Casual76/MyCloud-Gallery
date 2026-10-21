package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.feature.library.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fase 6 (P8, D34): il chip "Documenti" e' fra i filtri rapidi della Libreria, con il suo nome e il suo stato vuoto. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "it")
class QuickFiltersTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()

  @Test
  fun ilMenuProponeOgniFiltroCompresoDocumenti() {
    assertThat(QuickFilters).containsExactlyElementsIn(LibraryFilter.entries)
    assertThat(QuickFilters).contains(LibraryFilter.DOCUMENTS)
    assertThat(QuickFilters.first()).isEqualTo(LibraryFilter.ALL)
  }

  @Test
  fun ilChipDocumentiHaIlSuoNomeEIlSuoStatoVuoto() {
    assertThat(context.getString(R.string.library_filter_documents)).isEqualTo("Documenti")
    assertThat(context.getString(R.string.library_filter_empty_documents)).isNotEmpty()
  }

  @Test
  fun ilFiltroDocumentiAccettaSoloLeCelleConIlBitDocumento() {
    val filter = LibraryFilter.DOCUMENTS
    assertThat(filter.accepts(MediaKind.PHOTO, MediaSource.CAMERA, CellFlags.DOCUMENT)).isTrue()
    assertThat(filter.accepts(MediaKind.PHOTO, MediaSource.CAMERA, 0)).isFalse()
    assertThat(filter.accepts(MediaKind.PHOTO, MediaSource.SCREENSHOT, CellFlags.FAVORITE)).isFalse()
  }
}
