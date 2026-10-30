package dev.pampa.mycloudgallery.feature.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MusicLicense
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackPin
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackState
import dev.pampa.mycloudgallery.feature.memories.contract.AllMemoriesUi
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSettingsUi
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageUi
import dev.pampa.mycloudgallery.feature.memories.contract.MusicTrackRow
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerIntro
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerItem
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerState
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerUi
import dev.pampa.mycloudgallery.feature.memories.contract.UndoKind
import dev.pampa.mycloudgallery.feature.memories.contract.UndoOffer
import dev.pampa.mycloudgallery.feature.memories.nav.MemoryDestination
import dev.pampa.mycloudgallery.feature.memories.nav.MemoryPlayerDestination
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesEntryTarget
import dev.pampa.mycloudgallery.feature.memories.section.SectionData
import dev.pampa.mycloudgallery.feature.memories.section.SectionEntry
import dev.pampa.mycloudgallery.feature.memories.testing.MemoriesSamples
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import dev.antigravity.fluidengine.ui.theme.FluidTone
import org.junit.Test

/**
 * Mai un titolo, un luogo, una data, un id o un nome in un `toString()` (Fase 9, P9; D33): finirebbe in un rapporto o in un log. Tutti i tipi che le
 * schermate dei ricordi aggiungono ai contratti di P0 (stati di schermata, righe, rotte, voci della sezione) dicono solo numeri e codici.
 */
class NoNamesInToStringTest {

  private val secrets = listOf("Persona di prova", "Citta' di prova", "2024", "marzo", "content://", "riferimento", "Autore di prova", "Brano di prova", MemoriesSamples.media(1).value)

  private fun check(text: String) {
    for (secret in secrets) assertThat(text).doesNotContain(secret)
  }

  @Test
  fun gliStatiDelleSchermate() {
    val ready = MemoriesSamples.pages.filterIsInstance<dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageState.Ready>().first()
    check(MemoryPageUi(page = ready, preview = MemoriesSamples.trip, undo = UndoOffer(UndoKind.DISMISS)).toString())
    check(AllMemoriesUi(MemoriesSamples.lists.first()).toString())
    val item = PlayerItem.Photo(MemoriesSamples.media(1))
    val header = ready.header
    check(PlayerIntro(header, item).toString())
    check(PlayerUi(state = PlayerState.Paused(0, item, false, false), header = header, intro = PlayerIntro(header, item), items = listOf(item)).toString())
    check(MemoriesSettingsUi(MemoriesSamples.settings[1]).toString())
    check(MusicTrackRow(MusicTrackPin("brano_uno", "Brano di prova", "Autore di prova", MusicLicense.CC0, 1_000, "a".repeat(64)), MusicTrackState.Ready, true).toString())
    assertThat(header.kind).isEqualTo(MemoryKind.TRIP)
  }

  @Test
  fun leRigheDellaSezioneEIlLoroTitoloNonSiVedono() {
    val entry = SectionEntry("m_0123456789abcdef", "Citta' di prova, marzo 2024", "18 foto · 1 marzo 2024", Icons.Rounded.Person, FluidTone.Primary)
    check(entry.toString())
    check(SectionData("memories", "Ricordi", listOf(entry)).toString())
    assertThat(entry.toString()).contains("m_0123456789abcdef")
  }

  @Test
  fun leRotteNonPortanoMaiLIdDelRicordo() {
    val id = MemoriesSamples.trip.id.value
    assertThat(MemoryDestination(id).toString()).doesNotContain(id)
    assertThat(MemoryPlayerDestination(id).toString()).doesNotContain(id)
    assertThat(MemoriesEntryTarget.Memory("m_0123456789abcdef").toString()).doesNotContain("0123456789abcdef")
  }
}
