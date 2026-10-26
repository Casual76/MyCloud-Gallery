package dev.pampa.mycloudgallery.feature.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.feature.albums.editor.DateInput
import dev.pampa.mycloudgallery.feature.albums.sections.AlbumEntryTarget
import java.time.LocalDate
import org.junit.Test

/** Le parti piccole e pure: le date scritte a mano dell'editor (D36) e il riconoscimento dei codici delle voci delle Raccolte (D33). */
class SmallPartsTest {

  // --- Date ------------------------------------------------------------------------------------------------------------------

  @Test
  fun unGiornoSiScriveConOSenzaZeroIniziale() {
    assertThat(DateInput.parse("14/3/2026")).isEqualTo(LocalDate.of(2026, 3, 14))
    assertThat(DateInput.parse("14/03/2026")).isEqualTo(LocalDate.of(2026, 3, 14))
    assertThat(DateInput.parse("  01/12/2025  ")).isEqualTo(LocalDate.of(2025, 12, 1))
  }

  @Test
  fun ungiornoCheNonEsisteNonVale() {
    assertThat(DateInput.parse("31/02/2026")).isNull()
    assertThat(DateInput.parse("29/02/2026")).isNull()
    assertThat(DateInput.parse("29/02/2028")).isEqualTo(LocalDate.of(2028, 2, 29))
    assertThat(DateInput.parse("0/1/2026")).isNull()
    assertThat(DateInput.parse("1/13/2026")).isNull()
  }

  @Test
  fun unTestoScrittoMaleNonVale() {
    for (text in listOf("", "   ", "14-03-2026", "2026-03-14", "14/3", "14/3/26", "ieri", "14/3/2026 10:00")) {
      assertThat(DateInput.parse(text)).isNull()
    }
  }

  @Test
  fun siMostraNellaFormaDelCampoEsiRilegge() {
    val day = LocalDate.of(2026, 3, 4)
    assertThat(DateInput.show(day)).isEqualTo("04/03/2026")
    assertThat(DateInput.parse(DateInput.show(day))).isEqualTo(day)
  }

  @Test
  fun unPeriodoHaIDueGiorniEIlPrimoNonViene_DopoIlSecondo() {
    val range = DateInput.range("1/3/2026", "5/3/2026")!!
    assertThat(range.from).isEqualTo(LocalDate.of(2026, 3, 1))
    assertThat(range.to).isEqualTo(LocalDate.of(2026, 3, 5))
    // Un giorno solo e' un periodo valido.
    assertThat(DateInput.range("5/3/2026", "5/3/2026")).isNotNull()
    assertThat(DateInput.range("6/3/2026", "5/3/2026")).isNull()
    assertThat(DateInput.range("x", "5/3/2026")).isNull()
    assertThat(DateInput.range("1/3/2026", "31/02/2026")).isNull()
  }

  // --- Codici delle voci --------------------------------------------------------------------------------------------------------

  private val id = AlbumId("00000000-0000-4000-8000-0000000000a1")

  @Test
  fun iCodiciDelleVociSiRiconoscono() {
    assertThat(AlbumEntryTarget.parse("album/${id.value}")).isEqualTo(AlbumEntryTarget.Album(id))
    assertThat(AlbumEntryTarget.parse("smart/${id.value}")).isEqualTo(AlbumEntryTarget.SmartAlbum(id))
    assertThat(AlbumEntryTarget.parse("albums/all")).isEqualTo(AlbumEntryTarget.AllAlbums)
    assertThat(AlbumEntryTarget.parse("albums/new")).isEqualTo(AlbumEntryTarget.NewAlbum)
    assertThat(AlbumEntryTarget.parse("trips/all")).isEqualTo(AlbumEntryTarget.Trip(null))
    assertThat(AlbumEntryTarget.parse("trip/abc123")).isEqualTo(AlbumEntryTarget.Trip("abc123"))
  }

  @Test
  fun unCodiceDiUnAltraSezioneODiUnaVersionePiuNuovaNonSiRiconosce() {
    for (code in listOf("", "nas/folders", "album/", "album/non-un-id", "smart/", "trip/", "albums", "album", "persone/1", "backup/free-space")) {
      assertThat(AlbumEntryTarget.parse(code)).isNull()
    }
  }

  @Test
  fun iCodiciNonPortanoMaiIlTitoloDiUnAlbum() {
    // Il codice finisce nello stato salvato dei pannelli (e nelle impostazioni sincronizzate): solo prefisso e id, mai un nome dell'utente.
    val code = AlbumEntryTarget.ALBUM_PREFIX + id.value
    assertThat(code).matches("album/[0-9a-f-]{36}")
  }
}
