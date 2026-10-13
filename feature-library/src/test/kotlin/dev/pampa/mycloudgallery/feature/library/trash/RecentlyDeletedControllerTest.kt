package dev.pampa.mycloudgallery.feature.library.trash

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.mediastore.contract.RecentlyDeletedSource
import dev.pampa.mycloudgallery.core.mediastore.contract.TrashedItem
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeRecentlyDeletedSource
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.feature.library.collections.FakeAuthenticator
import dev.pampa.mycloudgallery.feature.library.collections.TestClock
import dev.pampa.mycloudgallery.feature.library.collections.idsOf
import dev.pampa.mycloudgallery.feature.library.collections.testing.CountingRecentlyDeletedSource
import dev.pampa.mycloudgallery.feature.library.collections.testing.CountingTrashMask
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeTrashedItems
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenAccessGate
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenController
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenDenial
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Gli Eliminati di recente (D14, D17, E-H1, E-H3): dietro la credenziale dei Nascosti con la tolleranza condivisa, i
 * giorni rimasti, i nascosti **mostrati col segno** solo dopo lo sblocco, la selezione e il ripristino col dialogo di
 * sistema. Su JVM, con il cestino di sistema finto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentlyDeletedControllerTest {

  private val now = 1_800_000_000_000L
  private val items = fakeTrashedItems(now, count = 8)
  private val source = FakeRecentlyDeletedSource(items)
  private val intents = FakePhoneMediaIntents(canTrash = true)
  private val clock = TestClock()
  private val gate = HiddenAccessGate(clock, graceMillis = 60_000L)
  private val auth = FakeAuthenticator()
  private val prompt = AuthPromptText("Sblocca", "prova")

  private fun TestScope.controller(
    mask: TrashHiddenMask = TrashHiddenMask { emptySet() },
    src: RecentlyDeletedSource = source,
    system: FakePhoneMediaIntents = intents,
    g: HiddenAccessGate = gate,
  ) = RecentlyDeletedController(g, src, mask, system, backgroundScope) { now }

  /** Sblocca il cancello come farebbe la schermata (all'ingresso), poi legge. */
  private fun RecentlyDeletedController.open() {
    shown()
    unlock(auth, prompt)
  }

  private fun entries(c: RecentlyDeletedController): List<TrashEntry> = (c.state.value as TrashUiState.Loaded).entries

  @Test
  fun iGiorniRimastiSonoInteriPerDifetto() {
    val day = 86_400_000L
    assertThat(RecentlyDeletedController.daysLeft(now + 29 * day + day / 2, now)).isEqualTo(29)
    assertThat(RecentlyDeletedController.daysLeft(now + 30 * day, now)).isEqualTo(30)
    assertThat(RecentlyDeletedController.daysLeft(now + day - 1, now)).isEqualTo(0)
    assertThat(RecentlyDeletedController.daysLeft(now + 1, now)).isEqualTo(0)
    // Gia' scaduto: il sistema lo togliera' alla prossima pulizia; non si promette tempo.
    assertThat(RecentlyDeletedController.daysLeft(now, now)).isEqualTo(0)
    assertThat(RecentlyDeletedController.daysLeft(now - 5 * day, now)).isEqualTo(0)
  }

  // --- Dietro la credenziale -------------------------------------------------------------------------------------

  @Test
  fun chiusaNonLeggeIlCestinoNeChiedeQualiSonoNascosti() = runTest(UnconfinedTestDispatcher()) {
    val counting = CountingRecentlyDeletedSource(source)
    val mask = CountingTrashMask { emptySet() }
    val c = controller(mask = mask, src = counting)

    c.shown()
    c.refresh()

    assertThat(c.state.value).isEqualTo(TrashUiState.Locked)
    assertThat(counting.reads).isEqualTo(0)
    assertThat(mask.asked).isEqualTo(0)
  }

  @Test
  fun conLaCredenzialeSiLeggeEMostraTuttoAncheINascosti() = runTest(UnconfinedTestDispatcher()) {
    val counting = CountingRecentlyDeletedSource(source)
    val hiddenRef = items[2].ref
    val c = controller(mask = TrashHiddenMask { setOf(hiddenRef) }, src = counting)

    c.open()

    assertThat(counting.reads).isEqualTo(1)
    assertThat(entries(c)).hasSize(items.size)
    // I nascosti ci sono, col segno; gli altri no.
    assertThat(entries(c).filter { it.hidden }.map { it.item.ref }).containsExactly(hiddenRef)
    assertThat(entries(c).filterNot { it.hidden }).hasSize(items.size - 1)
  }

  @Test
  fun unRifiutoLasciaChiusoENonLegge() = runTest(UnconfinedTestDispatcher()) {
    val counting = CountingRecentlyDeletedSource(source)
    val c = controller(src = counting)
    auth.result = AuthResult.Cancelled

    c.open()

    assertThat(c.state.value).isEqualTo(TrashUiState.Denied(HiddenDenial.CANCELLED))
    assertThat(counting.reads).isEqualTo(0)
    assertThat(c.restoreRequest(emptySet())).isNull()
  }

  @Test
  fun uscendoSiDimenticaTuttoERientrandoEntroUnMinutoSiRilegge() = runTest(UnconfinedTestDispatcher()) {
    val counting = CountingRecentlyDeletedSource(source)
    val c = controller(src = counting)
    c.open()
    c.toggle(entries(c)[0].id)

    c.hidden()

    // Fuori dalla pagina non resta niente in memoria: ne' l'elenco ne' la selezione.
    assertThat(c.state.value).isEqualTo(TrashUiState.Loading)
    assertThat(c.selection.value).isEmpty()
    clock.now += 30_000
    c.shown()
    assertThat(auth.calls).isEqualTo(1)
    assertThat(counting.reads).isEqualTo(2)
    assertThat(entries(c)).hasSize(items.size)
  }

  @Test
  fun rientrandoDopoUnMinutoSiRichiedeLaCredenzialeENonSiLegge() = runTest(UnconfinedTestDispatcher()) {
    val counting = CountingRecentlyDeletedSource(source)
    val c = controller(src = counting)
    c.open()
    c.hidden()

    clock.now += 60_000
    c.shown()

    assertThat(c.state.value).isEqualTo(TrashUiState.Locked)
    assertThat(counting.reads).isEqualTo(1)
  }

  @Test
  fun laToleranzaECondivisaConINascosti() = runTest(UnconfinedTestDispatcher()) {
    val world = FakeCollectionsWorld()
    val hiddenPage = HiddenController(gate, world.hiddenLibrary, world.actions, backgroundScope)
    val trashPage = controller()

    // Si sblocca una pagina, si esce, si entra nell'altra entro il minuto: nessuna seconda credenziale.
    hiddenPage.shown()
    hiddenPage.unlock(auth, prompt)
    hiddenPage.hidden()
    clock.now += 20_000
    trashPage.shown()

    assertThat(auth.calls).isEqualTo(1)
    assertThat(entries(trashPage)).hasSize(items.size)

    // E viceversa, ma oltre il minuto dall'ultima uscita si richiede.
    trashPage.hidden()
    clock.now += 60_000
    hiddenPage.shown()
    assertThat(hiddenPage.state.value).isEqualTo(HiddenUiState.Locked)
  }

  @Test
  fun lePagineProtetteVisibiliInsiemeTengonoApertoIlCancello() = runTest(UnconfinedTestDispatcher()) {
    val world = FakeCollectionsWorld()
    val hiddenPage = HiddenController(gate, world.hiddenLibrary, world.actions, backgroundScope)
    val trashPage = controller()
    hiddenPage.shown()
    hiddenPage.unlock(auth, prompt)

    // Il passaggio da una raccolta all'altra sul tablet: la nuova compare prima che la vecchia esca.
    trashPage.shown()
    hiddenPage.hidden()
    clock.now += 5 * 60_000

    // La pagina dei cestino e' ancora visibile: la tolleranza non e' partita, il cancello resta aperto.
    assertThat(gate.isUnlocked).isTrue()
    trashPage.hidden()
    clock.now += 61_000
    assertThat(gate.isUnlocked).isFalse()
  }

  @Test
  fun unaSolaCredenzialeSeDuePagineLaChiedonoInsieme() = runTest(UnconfinedTestDispatcher()) {
    val world = FakeCollectionsWorld()
    val hiddenPage = HiddenController(gate, world.hiddenLibrary, world.actions, backgroundScope)
    val trashPage = controller()
    val hold = CompletableDeferred<Unit>()
    auth.hold = hold
    hiddenPage.shown()
    trashPage.shown()

    hiddenPage.unlock(auth, prompt)
    trashPage.unlock(auth, prompt)
    hold.complete(Unit)

    assertThat(auth.calls).isEqualTo(1)
    assertThat(entries(trashPage)).hasSize(items.size)
    assertThat(idsOf(world.hiddenLibrary.read().view.value)).isNotEmpty()
  }

  // --- Contenuto -------------------------------------------------------------------------------------------------

  @Test
  fun iPiuRecentementeEliminatiVengonoPrimaEConIGiorniGiusti() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()

    c.open()

    val list = entries(c)
    assertThat(list).hasSize(items.size)
    assertThat(list.map { it.item.trashedAtMs }).isInOrder(compareByDescending<Long> { it })
    // Il primo e' stato eliminato un'ora fa: 29 giorni e 23 ore.
    assertThat(list.first().daysLeft).isEqualTo(29)
    assertThat(list.last().daysLeft).isEqualTo(22)
  }

  @Test
  fun ilRiconoscimentoDeiNascostiVieneChiestoUnaVoltaConTuttoIlCestino() = runTest(UnconfinedTestDispatcher()) {
    val seen = mutableListOf<List<MediaRef.Phone>>()
    val c = controller(mask = TrashHiddenMask { refs -> seen += refs; emptySet() })

    c.open()

    assertThat(seen).hasSize(1)
    assertThat(seen.single()).containsExactlyElementsIn(items.map { it.ref })
  }

  @Test
  fun seIlRiconoscimentoNonRispondeNonSiMostraNiente() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(mask = TrashHiddenMask { throw IllegalStateException("catalogo chiuso") })

    c.open()

    assertThat(c.state.value).isEqualTo(TrashUiState.Failed)
  }

  @Test
  fun seIlCestinoNonRispondeDiceFallito() = runTest(UnconfinedTestDispatcher()) {
    val broken = object : RecentlyDeletedSource {
      override suspend fun list(): List<TrashedItem> = throw IllegalStateException("MediaProvider non risponde")
    }
    val c = controller(src = broken)

    c.open()

    assertThat(c.state.value).isEqualTo(TrashUiState.Failed)
  }

  @Test
  fun unCestinoVuotoEUnElencoVuotoNonUnErrore() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(src = FakeRecentlyDeletedSource(emptyList()))

    c.open()

    assertThat(entries(c)).isEmpty()
  }

  @Test
  fun suAndroid10IlCestinoDiSistemaNonCeDiceNonDisponibileSenzaCredenziale() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(system = FakePhoneMediaIntents(canTrash = false))

    assertThat(c.state.value).isEqualTo(TrashUiState.Unsupported)
    c.shown()
    c.refresh()
    assertThat(c.state.value).isEqualTo(TrashUiState.Unsupported)
    assertThat(c.restoreRequest(emptySet())).isNull()
  }

  @Test
  fun laSelezioneSiTogliEPuoPrenderTutto() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.open()
    val first = entries(c)[0].id
    val second = entries(c)[1].id

    c.toggle(first)
    c.toggle(second)
    assertThat(c.selection.value).containsExactly(first, second)
    c.toggle(first)
    assertThat(c.selection.value).containsExactly(second)
    c.selectAll()
    assertThat(c.selection.value).hasSize(items.size)
    c.clearSelection()
    assertThat(c.selection.value).isEmpty()
  }

  @Test
  fun chiusaLaSelezioneNonSiTocca() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.shown()

    c.toggle(RecentlyDeletedController.trashEntryId(items[0].ref))
    c.selectAll()

    assertThat(c.selection.value).isEmpty()
  }

  // --- Ripristino ------------------------------------------------------------------------------------------------

  @Test
  fun ilRipristinoChiedeAlSistemaIRiferimentiScelti_conTrashFalse() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.open()
    val chosen = entries(c).take(3)
    chosen.forEach { c.toggle(it.id) }

    c.restoreRequest()

    // Un solo dialogo di sistema, con solo quello che si e' scelto, e `trash = false`: si ripristina, non si elimina.
    assertThat(intents.trashRequests).hasSize(1)
    val (refs, trash) = intents.trashRequests.single()
    assertThat(refs).containsExactlyElementsIn(chosen.map { it.item.ref })
    assertThat(trash).isFalse()
  }

  @Test
  fun ripristinareUnNascostoLoRiportaNascostoENonInLibreria() = runTest(UnconfinedTestDispatcher()) {
    val world = FakeCollectionsWorld()
    val trashed = fakeTrashedItems(now, count = 4)
    val hiddenRef = trashed[1].ref
    val hiddenId = world.hiddenIds.first()
    val c = RecentlyDeletedController(
      gate, FakeRecentlyDeletedSource(trashed), world.trashMask(hiddenRef), intents, backgroundScope,
    ) { now }
    c.open()
    val hiddenEntry = entries(c).single { it.hidden }
    c.toggle(hiddenEntry.id)
    c.toggle(entries(c).first { !it.hidden }.id)

    c.restoreRequest()
    world.systemRestored()

    // Il sistema riceve anche il nascosto, con `trash = false`...
    val (refs, trash) = intents.trashRequests.single()
    assertThat(refs).contains(hiddenRef)
    assertThat(trash).isFalse()
    // ...e il ripristino non tocca nessun dato personale: resta nascosto, fuori da ogni vista della Libreria.
    assertThat(world.hiddenIds).contains(hiddenId)
    for (filter in LibraryFilter.entries) {
      assertThat(idsOf(world.source.items(filter).view.value).toSet().intersect(world.hiddenIds)).isEmpty()
    }
    // Si ritrova fra i Nascosti.
    assertThat(idsOf(world.hiddenLibrary.read().view.value)).contains(hiddenId)
  }

  @Test
  fun senzaSelezioneNonSiChiedeNienteAlSistema() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.open()

    c.restoreRequest()

    // Niente di scelto: il sistema non riceve nessuna richiesta.
    assertThat(intents.trashRequests).isEmpty()
  }

  @Test
  fun dopoIlRipristinoQuelloCheNonCePiuEsceDallaSelezione() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.open()
    val gone = entries(c)[0]
    val kept = entries(c)[1]
    c.toggle(gone.id)
    c.toggle(kept.id)

    // Il sistema ha ripristinato il primo: il cestino ora non lo contiene.
    source.items = items.filter { it.ref != gone.item.ref }
    c.refresh()

    assertThat(entries(c).map { it.id }).doesNotContain(gone.id)
    assertThat(c.selection.value).containsExactly(kept.id)
  }

  @Test
  fun lIdentitaDellaMiniaturaDipendeDalRiferimentoEBastaEUnUuidValido() {
    val a = MediaRef.Phone("external_primary", 10, "image/jpeg")
    val b = MediaRef.Phone("external_primary", 11, "image/jpeg")

    assertThat(RecentlyDeletedController.trashEntryId(a)).isEqualTo(RecentlyDeletedController.trashEntryId(a))
    assertThat(RecentlyDeletedController.trashEntryId(a)).isNotEqualTo(RecentlyDeletedController.trashEntryId(b))
  }

  @Test
  fun nonCeNessunaStradaPerCancellareDavvero() {
    // D14: l'app non cancella mai da sola. Il controller ha solo il ripristino: nessun metodo che elimini per sempre.
    val names = RecentlyDeletedController::class.java.declaredMethods.map { it.name.lowercase() }
    assertThat(names.filter { "delete" in it || "purge" in it || "erase" in it || "empty" in it }).isEmpty()
  }
}
