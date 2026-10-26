package dev.pampa.mycloudgallery.feature.library.trash

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeRecentlyDeletedSource
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.feature.library.collections.FakeAuthenticator
import dev.pampa.mycloudgallery.feature.library.collections.TestClock
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeTrashedItems
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenAccessGate
import dev.pampa.mycloudgallery.feature.library.trash.testing.FakeNasTrash
import dev.pampa.mycloudgallery.feature.library.trash.testing.sampleNasRows
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Gli Eliminati di recente con i **due cestini** (Fase 7, P8, D26): quello di Android e quello del NAS insieme, con i giorni rimasti di ognuno,
 * dietro il cancello dei Nascosti. Ripristino e "Elimina subito" (solo le voci proprie del NAS, con la prova dei due tocchi). Su JVM, con il
 * cestino di sistema e quello del NAS finti.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentlyDeletedNasTest {

  private val now = 1_800_000_000_000L
  private val items = fakeTrashedItems(now, count = 4)
  private val phoneSource = FakeRecentlyDeletedSource(items)
  private val intents = FakePhoneMediaIntents(canTrash = true)
  private val clock = TestClock()
  private val gate = HiddenAccessGate(clock, graceMillis = 60_000L)
  private val auth = FakeAuthenticator()
  private val prompt = AuthPromptText("Sblocca", "prova")

  private fun TestScope.controller(
    nas: NasTrashPort,
    system: FakePhoneMediaIntents = intents,
    g: HiddenAccessGate = gate,
  ) = RecentlyDeletedController(g, phoneSource, TrashHiddenMask { emptySet() }, system, backgroundScope, nas) { now }

  private fun RecentlyDeletedController.open() {
    shown()
    unlock(auth, prompt)
  }

  private fun loaded(c: RecentlyDeletedController) = c.state.value as TrashUiState.Loaded

  private fun rows(c: RecentlyDeletedController) = (loaded(c).nas as NasTrashSection.Rows).entries

  private fun idOf(c: RecentlyDeletedController, key: String) = rows(c).single { it.row.key == key }.id

  /** I due tocchi del foglio, come li fa la schermata: il secondo dopo il secondo gesto. */
  private fun proof(): UserTap {
    val confirm = DoubleTapConfirm(shownAtMonotonicMs = 0)
    check(!confirm.tap(1_000))
    check(confirm.tap(2_000))
    return confirm.proof()!!
  }

  // --- Dietro la credenziale -----------------------------------------------------------------------------------------

  @Test
  fun chiusaNonTocca_NeLeggeNeChiede_IlCestinoDelNas() = runTest(UnconfinedTestDispatcher()) {
    val nas = FakeNasTrash(sampleNasRows())
    val c = controller(nas)

    c.shown()
    c.refresh()

    assertThat(c.state.value).isEqualTo(TrashUiState.Locked)
    assertThat(nas.refreshes).isEqualTo(0)
    // Nessun gesto possibile da chiusa.
    assertThat(c.canPurge(emptySet())).isFalse()
    assertThat(c.restoreNas(emptySet())).isNull()
    assertThat(nas.restores).isEmpty()
    assertThat(nas.purges).isEmpty()
  }

  @Test
  fun conLaCredenzialeCompaionoIDueCestiniEIGiorniRimastiDelNas() = runTest(UnconfinedTestDispatcher()) {
    val nas = FakeNasTrash(sampleNasRows())
    val c = controller(nas)

    c.open()

    val page = loaded(c)
    assertThat(page.entries).hasSize(items.size)
    val section = page.nas as NasTrashSection.Rows
    assertThat(section.entries).hasSize(5)
    assertThat(page.total).isEqualTo(items.size + 5)
    // Le voci del NAS vanno dalla piu' vicina alla scadenza: la prima scade fra due giorni.
    assertThat(section.entries.map { it.row.daysLeft }).containsExactly(2, 5, 11, 17, 29).inOrder()
    assertThat(nas.refreshes).isEqualTo(1)
  }

  @Test
  fun uscendoSiDimenticaIlNas_EDopoUnMinutoNonSiRilegge() = runTest(UnconfinedTestDispatcher()) {
    val nas = FakeNasTrash(sampleNasRows())
    val c = controller(nas)
    c.open()
    c.toggle(rows(c).first().id)

    c.hidden()

    assertThat(c.selection.value).isEmpty()
    assertThat(c.state.value).isEqualTo(TrashUiState.Loading)
    clock.now += 60_000
    c.shown()
    assertThat(c.state.value).isEqualTo(TrashUiState.Locked)
    assertThat(nas.refreshes).isEqualTo(1)
  }

  @Test
  fun unAggiornamentoDelNasArrivatoDopoL_UscitaNonSiMostra() = runTest(UnconfinedTestDispatcher()) {
    val nas = FakeNasTrash(sampleNasRows())
    val c = controller(nas)
    c.open()
    c.hidden()

    nas.show(NasTrashFeed.Ready(sampleNasRows().take(1), writable = true))

    assertThat(c.state.value).isEqualTo(TrashUiState.Loading)
  }

  // --- Gli stati del NAS -----------------------------------------------------------------------------------------------

  @Test
  fun senzaNasLaPaginaEQuellaDiPrima() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(NoNasTrash)

    c.open()

    assertThat(loaded(c).nas).isEqualTo(NasTrashSection.None)
    assertThat(loaded(c).entries).hasSize(items.size)
    assertThat(loaded(c).showsEmptyPage()).isFalse()
  }

  @Test
  fun senzaCestinoDiSistemaMaConIlNasLaPaginaMostraSoloIlNas() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(FakeNasTrash(sampleNasRows()), system = FakePhoneMediaIntents(canTrash = false))

    c.open()

    assertThat(loaded(c).entries).isEmpty()
    assertThat(rows(c)).hasSize(5)
  }

  @Test
  fun senzaNeNasNeCestinoDiSistemaNonDisponibile() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(NoNasTrash, system = FakePhoneMediaIntents(canTrash = false))
    assertThat(c.state.value).isEqualTo(TrashUiState.Unsupported)
  }

  @Test
  fun ilNasCheNonRispondeMostraLeVociProprieSenzaLeAzioni() = runTest(UnconfinedTestDispatcher()) {
    val own = sampleNasRows().filter { it.deletableNow }
    val nas = FakeNasTrash(sampleNasRows())
    val c = controller(nas)
    c.open()

    nas.show(NasTrashFeed.Offline(own))

    val section = loaded(c).nas as NasTrashSection.Rows
    assertThat(section.offline).isTrue()
    assertThat(section.writable).isFalse()
    assertThat(section.entries.map { it.row.key }).containsExactly("mio/a1", "mio/a2")
    // Offline: ne' ripristino ne' "Elimina subito".
    c.toggle(section.entries.first().id)
    assertThat(c.canPurge()).isFalse()
    assertThat(c.restoreNas()).isNull()
  }

  @Test
  fun inSolaLetturaSiVedeMaNonSiToccaNiente() = runTest(UnconfinedTestDispatcher()) {
    val nas = FakeNasTrash(sampleNasRows(), writable = false)
    val c = controller(nas)
    c.open()
    c.toggle(idOf(c, "mio/a1"))

    assertThat(c.canPurge()).isFalse()
    assertThat(c.restoreNas()).isNull()
    assertThat(nas.restores).isEmpty()
  }

  @Test
  fun neganegatoEFallitoSiDicono() = runTest(UnconfinedTestDispatcher()) {
    val nas = FakeNasTrash(sampleNasRows())
    val c = controller(nas)
    c.open()

    nas.show(NasTrashFeed.Denied)
    assertThat(loaded(c).nas).isEqualTo(NasTrashSection.Denied)
    nas.show(NasTrashFeed.Failed)
    assertThat(loaded(c).nas).isEqualTo(NasTrashSection.Failed)
    nas.show(NasTrashFeed.Loading)
    assertThat(loaded(c).nas).isEqualTo(NasTrashSection.Loading)
    nas.show(NasTrashFeed.Unavailable)
    assertThat(loaded(c).nas).isEqualTo(NasTrashSection.None)
  }

  @Test
  fun unaPortaCheLanciaFaDirePerIlNasNonPerIlTelefono() = runTest(UnconfinedTestDispatcher()) {
    val broken = object : NasTrashPort by FakeNasTrash(sampleNasRows()) {
      override suspend fun refresh() = throw IllegalStateException("NAS in manutenzione")
    }
    val c = controller(broken)

    c.open()

    assertThat(loaded(c).entries).hasSize(items.size)
    assertThat(loaded(c).nas).isEqualTo(NasTrashSection.Failed)
  }

  // --- Ripristino ------------------------------------------------------------------------------------------------------

  @Test
  fun ripristinaSoloCioCheHaUnRecordValido_ELeVociRipristinateEscono() = runTest(UnconfinedTestDispatcher()) {
    val nas = FakeNasTrash(sampleNasRows())
    val c = controller(nas)
    c.open()
    val valid = idOf(c, "tab/b1")
    val noRecord = idOf(c, "tab/b3")
    c.toggle(valid)
    c.toggle(noRecord)

    val outcome = c.restoreNas()

    assertThat(outcome).isEqualTo(NasTrashOutcome.STARTED)
    // La porta riceve solo la voce con il record valido, con un tocco (il pulsante "Ripristina").
    assertThat(nas.restores).containsExactly(setOf("tab/b1") to 1)
    assertThat(rows(c).map { it.row.key }).doesNotContain("tab/b1")
    // Quello che si e' ripristinato esce dalla selezione; la voce senza record resta scelta (si puo' sempre togliere).
    assertThat(c.selection.value).containsExactly(noRecord)
  }

  @Test
  fun unRipristinoRifiutatoLasciaTuttoDovEra_EDicePerche() = runTest(UnconfinedTestDispatcher()) {
    val nas = FakeNasTrash(sampleNasRows()).apply { restoreOutcome = NasTrashOutcome.BLOCKED }
    val c = controller(nas)
    c.open()
    val chosen = idOf(c, "tab/b1")
    c.toggle(chosen)

    assertThat(c.restoreNas()).isEqualTo(NasTrashOutcome.BLOCKED)

    assertThat(rows(c).map { it.row.key }).contains("tab/b1")
    assertThat(c.selection.value).containsExactly(chosen)
  }

  @Test
  fun ilRipristinoDelTelefonoNonCambiaEIlDialogoDiSistemaResta() = runTest(UnconfinedTestDispatcher()) {
    val nas = FakeNasTrash(sampleNasRows())
    val c = controller(nas)
    c.open()
    c.toggle(loaded(c).entries.first().id)
    c.toggle(idOf(c, "tab/b1"))

    c.restoreRequest()
    val nasOutcome = c.restoreNas()

    // Un solo dialogo di sistema, solo con la foto del telefono; il NAS e' un'altra strada.
    assertThat(intents.trashRequests).hasSize(1)
    assertThat(intents.trashRequests.single().first).hasSize(1)
    assertThat(nasOutcome).isEqualTo(NasTrashOutcome.STARTED)
    assertThat(nas.restores.single().first).containsExactly("tab/b1")
  }

  @Test
  fun senzaNientePerIlNasIlRipristinoNonChiamaLaPorta() = runTest(UnconfinedTestDispatcher()) {
    val nas = FakeNasTrash(sampleNasRows())
    val c = controller(nas)
    c.open()
    c.toggle(loaded(c).entries.first().id)

    assertThat(c.restoreNas()).isNull()
    assertThat(nas.restores).isEmpty()
  }

  // --- Elimina subito ----------------------------------------------------------------------------------------------------

  @Test
  fun eliminaSubitoVaSoloPerLeVociProprieDelNas() = runTest(UnconfinedTestDispatcher()) {
    val nas = FakeNasTrash(sampleNasRows())
    val c = controller(nas)
    c.open()

    // Una voce propria: si puo'.
    c.toggle(idOf(c, "mio/a1"))
    assertThat(c.canPurge()).isTrue()
    // Con una foto del telefono: no.
    c.toggle(loaded(c).entries.first().id)
    assertThat(c.canPurge()).isFalse()
    c.clearSelection()
    // Con una voce di un altro dispositivo: no.
    c.toggle(idOf(c, "mio/a2"))
    c.toggle(idOf(c, "tab/b1"))
    assertThat(c.canPurge()).isFalse()
    // Niente scelto: no.
    c.clearSelection()
    assertThat(c.canPurge()).isFalse()
  }

  @Test
  fun eliminaSubitoVuoleLaProvaDeiDueTocchi() = runTest(UnconfinedTestDispatcher()) {
    val nas = FakeNasTrash(sampleNasRows())
    val c = controller(nas)
    c.open()
    c.toggle(idOf(c, "mio/a1"))

    // Un tocco solo non basta: la porta non viene nemmeno chiamata.
    assertThat(c.purgeNas(UserTap(1))).isNull()
    assertThat(nas.purges).isEmpty()

    val outcome = c.purgeNas(proof())

    assertThat(outcome).isEqualTo(NasTrashOutcome.STARTED)
    assertThat(nas.purges).containsExactly(setOf("mio/a1") to 2)
    assertThat(rows(c).map { it.row.key }).doesNotContain("mio/a1")
    assertThat(c.selection.value).isEmpty()
  }

  @Test
  fun eliminaSubitoNonVaSeLaSelezioneNonLoPermette_NemmenoConLaProva() = runTest(UnconfinedTestDispatcher()) {
    val nas = FakeNasTrash(sampleNasRows())
    val c = controller(nas)
    c.open()
    c.toggle(idOf(c, "tab/b1"))

    assertThat(c.purgeNas(proof())).isNull()
    assertThat(nas.purges).isEmpty()
    assertThat(rows(c).map { it.row.key }).contains("tab/b1")
  }

  @Test
  fun ilRiepilogoDiEliminaSubitoContaGliElementiEIByte() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(FakeNasTrash(sampleNasRows()))
    c.open()
    c.toggle(idOf(c, "mio/a1"))
    c.toggle(idOf(c, "mio/a2"))

    assertThat(c.purgeSummary()).isEqualTo(2 to 186_200_000L)
  }

  @Test
  fun tuttoSelezionaIDueCestini() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(FakeNasTrash(sampleNasRows()))
    c.open()

    c.selectAll()

    assertThat(c.selection.value).hasSize(items.size + 5)
    assertThat(c.canPurge()).isFalse()
  }
}
