package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * G2 (lista dell'utente, terzo punto): le Raccolte alleggerite. Album, Album smart e Telefono vanno nella scheda Album della pillola ed escono
 * dalle Raccolte; restano Viaggi, Persone, Ricordi, Luoghi, Cartelle NAS, Da tenere al sicuro, Utility, Nascosti. I codici finiscono negli
 * ordini salvati e sincronizzati (`collections_order`, `collections_hidden:<codice>`): un ordine che li contiene non rompe niente, ne' qui ne' su
 * un dispositivo con la versione vecchia (che li ritrova al loro posto).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class G2RaccolteAlleggeriteTest {

  private val world = FakeCollectionsWorld()

  /** Un fornitore che conta quante volte qualcuno ascolta la sua sezione. */
  private class CountingProvider(override val code: String, initial: CollectionEntryGroup?) : CollectionsSectionProvider {
    var reads = 0
    private val flow = MutableStateFlow(initial)
    override val group: StateFlow<CollectionEntryGroup?>
      get() {
        reads++
        return flow.asStateFlow()
      }
  }

  private fun group(code: String, vararg entries: String) = CollectionEntryGroup(
    code.replaceFirstChar { it.uppercase() },
    entries.map { CollectionEntry(it, it, "", Icons.Rounded.Folder, FluidTone.Neutral) },
    code = code,
  )

  private fun TestScope.controller(
    providers: List<CollectionsSectionProvider> = emptyList(),
    store: CollectionsLayoutStore = MemoryCollectionsLayoutStore(),
  ) = CollectionsLayoutController(canTrash = true, providers, store, backgroundScope, SharingStarted.Eagerly)

  private fun CollectionsLayoutController.layout(): CollectionsLayoutState =
    inputs.value.let { CollectionsLayout.state(it.present, emptyList(), it.saved, it.hidden, it.editing, it.saveFailed) }

  private val everyProvider: List<CountingProvider>
    get() = listOf(
      CountingProvider(CollectionSections.ALBUMS, group(CollectionSections.ALBUMS, "albums/all")),
      CountingProvider(CollectionSections.SMART, group(CollectionSections.SMART, "albums/smart/1")),
      CountingProvider(CollectionSections.TRIPS, group(CollectionSections.TRIPS, "trips/1")),
      CountingProvider(CollectionSections.PEOPLE, group(CollectionSections.PEOPLE, "people/all")),
      CountingProvider(CollectionSections.MEMORIES, group(CollectionSections.MEMORIES, "memories/all")),
      CountingProvider(CollectionSections.PLACES, group(CollectionSections.PLACES, "places/map")),
      CountingProvider(CollectionSections.NAS, group(CollectionSections.NAS, "nas/folders")),
      CountingProvider(CollectionSections.UTILITY, group(CollectionSections.UTILITY, "utility/edited")),
    )

  @Test
  fun leRaccolteNonHannoPiuAlbumAlbumSmartETelefono() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(everyProvider)
    val codes = c.layout().sections.map { it.code }

    assertThat(codes).containsExactly("trips", "people", "memories", "places", "nas", "safe", "utility", "hidden").inOrder()
    assertThat(codes).containsNoneOf("albums", "smart", "phone")
    // Le raccolte-filtro del Telefono (Preferiti, Video, Screenshot, WhatsApp) non sono piu' righe delle Raccolte.
    assertThat(CollectionKind.FAVORITES in c.layout()).isFalse()
    assertThat(c.layout().firstFilter).isNull()
  }

  @Test
  fun iFornitoriDiAlbumESmartNonSiAscoltano() = runTest(UnconfinedTestDispatcher()) {
    val providers = everyProvider
    controller(providers)

    assertThat(providers.first { it.code == "albums" }.reads).isEqualTo(0)
    assertThat(providers.first { it.code == "smart" }.reads).isEqualTo(0)
    assertThat(providers.first { it.code == "trips" }.reads).isGreaterThan(0)
  }

  /** Un ordine salvato (qui o da una versione vecchia) con i codici spariti: le altre sezioni seguono l'ordine, i codici spariti si saltano. */
  @Test
  fun unOrdineSalvatoConICodiciSparitiNonRompeNiente() = runTest(UnconfinedTestDispatcher()) {
    val store = MemoryCollectionsLayoutStore()
    store.setOrder(listOf("hidden", "albums", "utility", "phone", "smart", "trips", "nas", "safe", "people", "memories", "places"))
    val c = controller(everyProvider, store)

    assertThat(c.layout().sections.map { it.code })
      .containsExactly("hidden", "utility", "trips", "nas", "safe", "people", "memories", "places").inOrder()
  }

  /**
   * Riordinando le Raccolte i codici della scheda Album **restano** nell'ordine salvato, al loro posto: una versione vecchia, che li mostra
   * ancora nelle Raccolte, li ritrova dove erano. Mai salvato prima: valgono i posti predefiniti di sempre.
   */
  @Test
  fun riordinandoICodiciDellaSchedaAlbumRestanoAlLoroPosto() = runTest(UnconfinedTestDispatcher()) {
    val store = MemoryCollectionsLayoutStore()
    val c = controller(everyProvider, store)
    val visible = c.layout().sections.map { it.code }
    // "Sposta su" di Utility fino in cima.
    c.reorder(listOf("utility") + visible.filter { it != "utility" })

    val saved = store.order.value
    assertThat(saved).containsAtLeast("albums", "smart", "phone")
    assertThat(saved.indexOf("albums")).isLessThan(saved.indexOf("smart"))
    assertThat(saved.indexOf("smart")).isLessThan(saved.indexOf("trips"))
    assertThat(saved.filter { it !in CollectionSections.inAlbumsTab }).containsExactlyElementsIn(listOf("utility") + visible.filter { it != "utility" }).inOrder()

    // Riletto (un altro dispositivo con questa versione): le Raccolte sono quelle scelte, senza le sezioni della scheda Album.
    val again = controller(everyProvider, store)
    assertThat(again.layout().sections.map { it.code }.first()).isEqualTo("utility")
  }

  /** Una voce "nascosta" scritta da una versione vecchia per Telefono o per gli Album smart non tocca niente qui. */
  @Test
  fun leVociNascosteDeiCodiciSparitiSonoInnocue() = runTest(UnconfinedTestDispatcher()) {
    val store = MemoryCollectionsLayoutStore()
    store.setHidden("phone", true)
    store.setHidden("smart", true)
    val c = controller(everyProvider, store)

    assertThat(c.layout().visible.map { it.code }).containsExactly("trips", "people", "memories", "places", "nas", "safe", "utility", "hidden").inOrder()
    // Album resta fra le sezioni mai nascoste (per le versioni vecchie, dove e' l'unico modo di arrivare agli album): nessuna voce si scrive.
    c.setShown("albums", false)
    assertThat(store.hidden.value).doesNotContain("albums")
  }

  @Test
  fun iCodiciNonCambiano() {
    assertThat(CollectionSections.inAlbumsTab).containsExactly("albums", "smart", "phone")
    assertThat(CollectionSections.defaultOrder).containsAtLeast("albums", "smart", "phone")
    assertThat(CollectionSections.neverHidden).containsExactly("albums")
  }
}
