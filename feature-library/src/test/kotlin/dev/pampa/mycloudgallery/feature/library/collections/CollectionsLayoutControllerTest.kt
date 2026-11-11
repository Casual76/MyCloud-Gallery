package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.library.collections.testing.CollectionsSectionSamples
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le sezioni come dati e le scelte dell'utente (Fase 7, P11, D33): dei fornitori registrati dagli altri pacchetti, un ordine e una
 * visibilita' salvati nelle voci sincronizzate, una sezione nuova in coda (E-L14). Sul controllore vero, con il deposito in memoria e un
 * deposito che fallisce; i fornitori sono i finti di `testing/`.
 *
 * G2 (fase grafica): Album, Album smart e Telefono stanno nella scheda Album della pillola, non piu' nelle Raccolte (`CollectionSections.inAlbumsTab`):
 * le prove che li usavano come sezioni qualunque usano adesso Viaggi, Persone, Cartelle NAS, Da tenere al sicuro e Nascosti. Le prove dedicate ai
 * codici spariti sono in `G2RaccolteAlleggeriteTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsLayoutControllerTest {

  private val world = FakeCollectionsWorld()

  private fun TestScope.controller(
    providers: List<CollectionsSectionProvider> = emptyList(),
    store: CollectionsLayoutStore = MemoryCollectionsLayoutStore(),
    canTrash: Boolean = true,
  ) = CollectionsLayoutController(canTrash, providers, store, backgroundScope, SharingStarted.Eagerly)

  /** Lo stato come lo vede la scheda (senza i gruppi di `:app`). */
  private fun CollectionsLayoutController.layout(legacy: List<CollectionEntryGroup> = emptyList()): CollectionsLayoutState =
    inputs.value.let { CollectionsLayout.state(it.present, legacy, it.saved, it.hidden, it.editing, it.saveFailed) }

  private fun CollectionsLayoutController.codes() = layout().sections.map { it.code }

  private fun group(vararg entries: String, title: String = "Gruppo") = CollectionEntryGroup(
    title, entries.map { CollectionEntry(it, it, "", Icons.Rounded.Folder, FluidTone.Neutral) },
  )

  private class FailingStore(private val real: CollectionsLayoutStore = MemoryCollectionsLayoutStore()) : CollectionsLayoutStore by real {
    var failing = false
    var orderWrites = 0
    var hiddenWrites = 0

    override suspend fun setOrder(codes: List<String>) {
      orderWrites++
      if (failing) throw IllegalStateException("disco pieno")
      real.setOrder(codes)
    }

    override suspend fun setHidden(code: String, hidden: Boolean) {
      hiddenWrites++
      if (failing) throw IllegalStateException("disco pieno")
      real.setHidden(code, hidden)
    }
  }

  // --- Cio' che c'e' senza nessun fornitore --------------------------------------------------------------------------------------------------

  @Test
  fun senzaFornitoriLaSchedaFaDaSolaLeRaccolteDiSempre() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    val state = c.layout()

    // G2: il Telefono e' nella scheda Album (CS-17: la scheda non lo costruisce piu').
    assertThat(state.sections.map { it.code }).containsExactly("safe", "hidden").inOrder()
    val rows = state.visibleRows.map { (it as SectionRow.Kind).row.kind }
    assertThat(rows).containsExactly(CollectionKind.RECENTLY_DELETED, CollectionKind.HIDDEN).inOrder()
    assertThat(state.editing).isFalse()
  }

  @Test
  fun senzaIlCestinoDiSistemaLaSezioneDaTenereAlSicuroNonCe() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(canTrash = false)

    assertThat(c.codes()).containsExactly("hidden").inOrder()
    assertThat(CollectionKind.RECENTLY_DELETED in c.layout()).isFalse()
  }

  @Test
  fun iNascostiNonDiconoQuantiSonoNeInUnaSezioneChePoiSiNasconde() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    val hidden = c.layout().sections.first { it.code == "hidden" }.rows.single() as SectionRow.Kind
    assertThat(hidden.row.count).isNull()
    assertThat(hidden.row.kind).isEqualTo(CollectionKind.HIDDEN)
  }

  // --- Le sezioni come dati ------------------------------------------------------------------------------------------------------------------

  @Test
  fun leSezioniDeiFornitoriSiMettonoNelPostoDelPianoEUnaSezioneVuotaNonCe() = runTest(UnconfinedTestDispatcher()) {
    val providers = CollectionsSectionSamples.all() + NoCollectionsSection("memories")
    val c = controller(providers)

    // G2: senza Album, Album smart e Telefono (scheda Album).
    assertThat(c.codes()).containsExactly("trips", "people", "nas", "safe", "utility", "hidden").inOrder()
  }

  @Test
  fun unFornitoreConUnGruppoSenzaVociTogliAncheLaSezioneDellaSchedaEUnoCheNonDaNienteLaLascia() = runTest(UnconfinedTestDispatcher()) {
    val safe = FixedCollectionsSection("safe", null)
    val c = controller(listOf(safe))
    // Il fornitore non ha niente da dire (non e' ancora pronto): la sezione della scheda resta.
    assertThat(c.codes()).contains("safe")

    // P8 porta gli Eliminati di recente in Utility: la sezione e' sua e oggi e' vuota.
    safe.set(group())
    assertThat(c.codes()).doesNotContain("safe")
    assertThat(CollectionKind.RECENTLY_DELETED in c.layout()).isFalse()

    safe.set(null)
    assertThat(c.codes()).contains("safe")
  }

  @Test
  fun unFornitoreChePassaDaNullAUnaSezioneLaFaComparireEViceversa() = runTest(UnconfinedTestDispatcher()) {
    val trips = FixedCollectionsSection("trips", null)
    val c = controller(listOf(trips))
    assertThat(c.codes()).doesNotContain("trips")

    trips.set(group("trips/a"))
    assertThat(c.codes()).contains("trips")
    assertThat(c.codes().indexOf("trips")).isLessThan(c.codes().indexOf("safe"))

    trips.set(group())
    assertThat(c.codes()).doesNotContain("trips")
  }

  @Test
  fun unFornitoreConIlCodiceDiUnaSezioneDellaSchedaLaSostituisce() = runTest(UnconfinedTestDispatcher()) {
    val mine = FixedCollectionsSection("hidden", group("hidden/own", title = "Nascosti di P9"))
    val c = controller(listOf(mine))

    val hidden = c.layout().sections.first { it.code == "hidden" }
    assertThat(hidden.rows.map { (it as SectionRow.Entry).entry.code }).containsExactly("hidden/own")
    assertThat((hidden.title as SectionText.Raw).text).isEqualTo("Nascosti di P9")
    // Le altre sezioni della scheda restano.
    assertThat(c.codes()).contains("safe")
  }

  @Test
  fun unFornitoreConUnCodiceChePuoScrivereNelleImpostazioniEIgnoratoEDueConLoStessoCodiceVaIlPrimo() = runTest(UnconfinedTestDispatcher()) {
    val providers = listOf(
      FixedCollectionsSection("Non_valido", group("x/1")),
      FixedCollectionsSection("a,b", group("x/2")),
      FixedCollectionsSection("trips", group("trips/primo")),
      FixedCollectionsSection("trips", group("trips/secondo")),
    )
    val c = controller(providers)

    val trips = c.layout().sections.filter { it.code == "trips" }
    assertThat(trips).hasSize(1)
    assertThat((trips.single().rows.single() as SectionRow.Entry).entry.code).isEqualTo("trips/primo")
    assertThat(c.codes()).containsExactly("trips", "safe", "hidden").inOrder()
  }

  @Test
  fun iGruppiDiAppDiPrimaDiP13SonoSezioniEUnFornitoreLiSostituisce() = runTest(UnconfinedTestDispatcher()) {
    val nas = group("nas/folders", "nas/space", title = "Sul NAS")
    val c = controller()
    assertThat(c.layout(listOf(nas)).sections.map { it.code }).containsExactly("nas", "safe", "hidden").inOrder()

    val withProvider = controller(listOf(FixedCollectionsSection("nas", group("nas/nuovo", title = "Cartelle NAS"))))
    val section = withProvider.layout(listOf(nas)).sections.first { it.code == "nas" }
    assertThat(section.rows.map { (it as SectionRow.Entry).entry.code }).containsExactly("nas/nuovo")
  }

  // --- Ordine e visibilita' ----------------------------------------------------------------------------------------------------------------

  @Test
  fun riordinareLeSezioniSiVedeSubitoEsiSalva() = runTest(UnconfinedTestDispatcher()) {
    val store = MemoryCollectionsLayoutStore()
    val c = controller(CollectionsSectionSamples.all(), store)
    val before = c.codes()
    assertThat(before.take(3)).containsExactly("trips", "people", "nas").inOrder()

    c.reorder(listOf("nas", "trips") + before.filter { it != "nas" && it != "trips" })

    assertThat(c.codes().take(3)).containsExactly("nas", "trips", "people").inOrder()
    // G2: i codici della scheda Album restano nell'ordine salvato, al loro posto (le versioni vecchie li mostrano ancora).
    assertThat(store.order.value.filter { it !in CollectionSections.inAlbumsTab }.take(3)).containsExactly("nas", "trips", "people").inOrder()
    // Le sezioni di fase futura non erano nell'elenco: Persone c'e' (il fornitore finto la da') e ha il suo posto scelto.
    assertThat(store.order.value).containsAtLeastElementsIn(c.codes())
  }

  @Test
  fun leSezioniCheOggiNonCiSonoTenganoIlPostoQuandoSiRiordina() = runTest(UnconfinedTestDispatcher()) {
    val store = MemoryCollectionsLayoutStore(order = listOf("nas", "phone", "safe", "hidden"))
    val c = controller(emptyList(), store)
    assertThat(c.codes()).containsExactly("safe", "hidden").inOrder()

    c.reorder(listOf("hidden", "safe"))

    // Cartelle NAS (oggi assente) e Telefono (nella scheda Album, G2) tengono il loro posto.
    assertThat(store.order.value).containsExactly("nas", "phone", "hidden", "safe").inOrder()
  }

  @Test
  fun unaSezioneNuovaVaInCodaENonRompeLOrdineSalvato_E_L14() = runTest(UnconfinedTestDispatcher()) {
    val store = MemoryCollectionsLayoutStore(order = listOf("hidden", "phone", "safe"))
    val people = FixedCollectionsSection("people", null)
    val c = controller(listOf(people), store)
    assertThat(c.codes()).containsExactly("hidden", "safe").inOrder()

    people.set(CollectionsSectionSamples.people().group.value)

    assertThat(c.codes()).containsExactly("hidden", "safe", "people").inOrder()
    // L'ordine salvato non e' stato toccato: nessuna scrittura per una sezione che arriva.
    assertThat(store.order.value).containsExactly("hidden", "phone", "safe").inOrder()
  }

  @Test
  fun nascondereUnaSezioneLaTogliDallElencoEMostrarlaLaRimette() = runTest(UnconfinedTestDispatcher()) {
    val store = MemoryCollectionsLayoutStore()
    val c = controller(CollectionsSectionSamples.all(), store)

    c.setShown("trips", false)
    assertThat(c.layout().visible.map { it.code }).doesNotContain("trips")
    assertThat(c.layout().sections.map { it.code }).contains("trips")
    assertThat(store.hidden.value).containsExactly("trips")

    c.setShown("trips", true)
    assertThat(c.layout().visible.map { it.code }).contains("trips")
    assertThat(store.hidden.value).isEmpty()
  }

  @Test
  fun albumNonSiNascondeEUnCodiceStranoNonScriveNiente() = runTest(UnconfinedTestDispatcher()) {
    val store = FailingStore()
    val c = controller(CollectionsSectionSamples.all(), store)

    c.setShown("albums", false)
    c.setShown("Non valido", false)
    c.setShown("", false)

    assertThat(store.hiddenWrites).isEqualTo(0)
    // G2: Album non e' piu' nelle Raccolte (scheda Album); il vincolo resta per le versioni vecchie (nessuna voce scritta).
    assertThat(c.layout().visible.map { it.code }).doesNotContain("albums")
  }

  @Test
  fun unaSceltaDiUnAltroDispositivoArrivaDalDeposito() = runTest(UnconfinedTestDispatcher()) {
    val store = MemoryCollectionsLayoutStore()
    val c = controller(CollectionsSectionSamples.all(), store)

    // Sul tablet l'utente ha nascosto Viaggi e messo Utility e Cartelle NAS per prime: arriva con la sincronizzazione (anche con i codici della scheda Album in mezzo).
    store.setHidden("trips", true)
    store.setOrder(listOf("utility", "phone", "nas", "albums"))

    assertThat(c.layout().visible.map { it.code }).doesNotContain("trips")
    assertThat(c.codes().take(2)).containsExactly("utility", "nas").inOrder()
  }

  @Test
  fun dueSpostamentiDiFilaPartonoOgnunoDaQuelloPrima() = runTest(UnconfinedTestDispatcher()) {
    val store = MemoryCollectionsLayoutStore()
    val c = controller(CollectionsSectionSamples.all(), store)
    val start = c.codes()

    // "Sposta giu'" di Viaggi due volte: la seconda parte da dove ha lasciato la prima, come fa TalkBack senza aspettare la scheda.
    c.reorder(CollectionsLayout.moved(c.codes(), "trips", +1)!!)
    c.reorder(CollectionsLayout.moved(c.codes(), "trips", +1)!!)

    assertThat(c.codes().indexOf("trips")).isEqualTo(2)
    assertThat(c.codes() - "trips").containsExactlyElementsIn(start - "trips").inOrder()
  }

  // --- Il salvataggio che non riesce ---------------------------------------------------------------------------------------------------------

  @Test
  fun unSalvataggioCheFallisceRiportaLaSceltaECHiDiceLaScheda() = runTest(UnconfinedTestDispatcher()) {
    val store = FailingStore()
    val c = controller(CollectionsSectionSamples.all(), store)
    val before = c.codes()
    store.failing = true

    c.reorder(listOf("trips") + before.filter { it != "trips" })
    assertThat(c.codes()).isEqualTo(before)
    assertThat(c.inputs.value.saveFailed).isTrue()

    c.setShown("people", false)
    assertThat(c.layout().visible.map { it.code }).contains("people")
    assertThat(c.inputs.value.saveFailed).isTrue()

    // La volta dopo riesce: l'avviso sparisce.
    store.failing = false
    c.setShown("people", false)
    assertThat(c.layout().visible.map { it.code }).doesNotContain("people")
    assertThat(c.inputs.value.saveFailed).isFalse()
  }

  @Test
  fun lAvvisoDelSalvataggioSiChiudeConFine() = runTest(UnconfinedTestDispatcher()) {
    val store = FailingStore().also { it.failing = true }
    val c = controller(CollectionsSectionSamples.all(), store)
    c.startEditing()
    c.setShown("trips", false)
    assertThat(c.inputs.value.saveFailed).isTrue()
    assertThat(c.inputs.value.editing).isTrue()

    c.stopEditing()

    assertThat(c.inputs.value.editing).isFalse()
    assertThat(c.inputs.value.saveFailed).isFalse()
  }

  @Test
  fun unOrdineSenzaNienteDaCambiareNonScriveNiente() = runTest(UnconfinedTestDispatcher()) {
    val store = FailingStore()
    val c = controller(CollectionsSectionSamples.all(), store)

    c.reorder(c.codes())
    c.reorder(emptyList())
    c.reorder(listOf("Non valido"))

    // Il primo e' lo stesso ordine del piano ma mai salvato: si salva (la scelta e' dell'utente); gli altri due non scrivono.
    assertThat(store.orderWrites).isAtMost(1)
  }

  /** Un fornitore con una sezione che cambia: lo stato lo segue senza che nessuno lo chieda. */
  @Test
  fun unaSezioneCheCambiaAggiornaLoStato() = runTest(UnconfinedTestDispatcher()) {
    val flow = MutableStateFlow<CollectionEntryGroup?>(group("a/1"))
    val provider = object : CollectionsSectionProvider {
      override val code = "trips"
      override val group: StateFlow<CollectionEntryGroup?> = flow
    }
    val c = controller(listOf(provider))
    assertThat(c.layout().sections.first { it.code == "trips" }.rows).hasSize(1)

    flow.value = group("a/1", "a/2", "a/3")

    assertThat(c.layout().sections.first { it.code == "trips" }.rows).hasSize(3)
  }
}
