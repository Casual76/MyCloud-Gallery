package dev.pampa.mycloudgallery.feature.library.utility

import android.app.Application
import android.content.IntentSender
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.duplicates.CleanupPlan
import dev.pampa.mycloudgallery.core.library.duplicates.CopyPart
import dev.pampa.mycloudgallery.core.library.duplicates.GroupKey
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalStatus
import dev.pampa.mycloudgallery.core.library.duplicates.PhoneTrashRef
import dev.pampa.mycloudgallery.core.library.duplicates.SimilarBatch
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashOutcome
import dev.pampa.mycloudgallery.feature.library.utility.testing.FakeCleanupNas
import dev.pampa.mycloudgallery.feature.library.utility.testing.FakeFullHashSwitch
import dev.pampa.mycloudgallery.feature.library.utility.testing.FakeIdenticalDuplicates
import dev.pampa.mycloudgallery.feature.library.utility.testing.FakeSimilarShots
import dev.pampa.mycloudgallery.feature.library.utility.testing.sampleClusters
import dev.pampa.mycloudgallery.feature.library.utility.testing.sampleMediaId
import dev.pampa.mycloudgallery.feature.library.utility.testing.samplePlacesOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La logica dei Doppioni (Fase 7, P8, D25, D27; E-U1, E-U2, E-U3, §2.1.1): la lettura degli identici e dei simili, la proposta e "Rivedi", il foglio di
 * eliminazione e la pulizia verso i due cestini (NAS finto, dialogo di sistema finto), l'interruttore notturno. Robolectric solo per avere un
 * `IntentSender` vero da restituire; il resto e' JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DuplicatesControllerTest {

  private val sender: IntentSender by lazy { testIntentSender() }

  private val system = FakePhoneMediaIntents(canTrash = true)
  private val identical = FakeIdenticalDuplicates()
  private val similar = FakeSimilarShots()
  private val nas = FakeCleanupNas()
  private val switch = FakeFullHashSwitch()

  private fun TestScope.controller(
    id: FakeIdenticalDuplicates = identical,
    sim: FakeSimilarShots = similar,
    cleanup: FakeCleanupNas = nas,
    fullHash: dev.pampa.mycloudgallery.feature.library.utility.FullHashSwitch = switch,
    canTrash: Boolean = true,
  ): DuplicatesController {
    system.canTrash = canTrash
    return DuplicatesController(id, sim, cleanup, SenderIntents(system, sender), fullHash, backgroundScope)
  }

  private fun DuplicatesController.opened(): DuplicatesController {
    start()
    return this
  }

  private fun DuplicatesController.keyOf(media: Int): GroupKey =
    state.value.identical.groups.first { it.group.media == sampleMediaId(media) }.group.key

  private fun DuplicatesController.locations(media: Int, pick: (dev.pampa.mycloudgallery.core.library.duplicates.GroupProposal) -> List<dev.pampa.mycloudgallery.core.library.duplicates.IdentCopy>) =
    pick(state.value.identical.groups.first { it.group.media == sampleMediaId(media) }).map { it.location.value }

  // --- Identici: la lettura e la proposta (D25) ----------------------------------------------------------------------------

  @Test
  fun allApertura_ComparionoIGruppiIdenticiEGliScattiSimili() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()

    val s = c.state.value
    assertThat(s.identical.scanning).isFalse()
    assertThat(s.identical.groups).hasSize(4)
    assertThat(s.identical.cleanable).hasSize(2)
    assertThat(s.identical.toVerify).hasSize(1)
    assertThat(s.identical.settled).hasSize(1)
    // Il gruppo 1 toglie una copia del telefono; il 2 ne toglie due del NAS: tre in tutto.
    assertThat(s.identical.copiesToRemove).isEqualTo(3)
    assertThat(s.similar.scanning).isFalse()
    assertThat(s.similar.clusters).hasSize(2)
    assertThat(identical.scans).isEqualTo(1)
    assertThat(s.sheet).isNull()
    assertThat(s.notice).isNull()
  }

  @Test
  fun e_u2_siTieneLaCopiaPrivataVerificata_ELeAltreSonoPreselezionate() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()

    val group = c.state.value.identical.groups.first { it.group.media == sampleMediaId(2) }
    assertThat(group.status).isEqualTo(IdenticalStatus.CLEANABLE)
    assertThat(group.keep!!.location.value).isEqualTo(20L)
    assertThat(c.locations(2) { it.remove }).containsExactly(21L, 22L)
  }

  @Test
  fun e_u1_laCopiaNellAreaDiUnAltroNonSiProponeMai_ESiDiceDiChiE() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()

    assertThat(c.state.value.identical.inOthersArea).isEqualTo(1)
    c.prepareIdentical()

    val sheet = c.state.value.sheet!!
    assertThat(sheet.othersAreas).containsExactly("marco")
    // Nel piano non c'e' niente dell'area di Marco, e nessuna richiesta e' partita.
    assertThat(sheet.plan.nas.map { it.path }).containsNoneIn(listOf("Marco/Oslo/IMG_0002.jpg"))
    assertThat(sheet.plan.nas.map { it.share }).doesNotContain("marco")
    assertThat(nas.calls).isEmpty()
    assertThat(system.trashRequests).isEmpty()
  }

  @Test
  fun ilGruppoConUnaCopiaDiAlbumCondivisoELUltimaPrivata_NonHaNienteDaTogliere() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()

    val settled = c.state.value.identical.groups.first { it.group.media == sampleMediaId(4) }
    assertThat(settled.status).isEqualTo(IdenticalStatus.NOTHING_TO_REMOVE)
    assertThat(settled.remove).isEmpty()
    c.prepareIdentical()
    assertThat(identical.planned.single().map { it.group.media }).doesNotContain(sampleMediaId(4))
  }

  @Test
  fun ilVideoSenzaHashCompleto_EDaVerificare_ENonEntraNelPiano() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()

    val video = c.state.value.identical.toVerify.single()
    assertThat(video.group.media).isEqualTo(sampleMediaId(3))
    assertThat(video.remove).isEmpty()
    c.prepareIdentical()
    assertThat(identical.planned.single().map { it.group.media }).containsExactly(sampleMediaId(1), sampleMediaId(2))
  }

  @Test
  fun rivedi_CambiaLaCopiaDaTenere_MaLeRegoleValgonoLoStesso() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()
    val key = c.keyOf(2)

    c.keepCopy(key, LocationId(21))
    var group = c.state.value.identical.groups.first { it.group.key == key }
    assertThat(group.keep!!.location.value).isEqualTo(21L)
    assertThat(c.locations(2) { it.remove }).contains(20L)

    // Una copia nell'area di un altro, o che non e' del gruppo, non si puo' scegliere da tenere.
    c.keepCopy(key, LocationId(23))
    c.keepCopy(key, LocationId(999))
    group = c.state.value.identical.groups.first { it.group.key == key }
    assertThat(group.keep!!.location.value).isEqualTo(21L)
    assertThat(group.blocked.map { it.copy.location.value }).contains(23L)

    c.resetKeep(key)
    assertThat(c.state.value.identical.groups.first { it.group.key == key }.keep!!.location.value).isEqualTo(20L)
  }

  @Test
  fun unaLetturaFallita_LoDice_ERiprovareFunziona() = runTest(UnconfinedTestDispatcher()) {
    identical.throwOnScan = true
    val c = controller().opened()

    assertThat(c.state.value.identical.failed).isTrue()
    assertThat(c.state.value.identical.groups).isEmpty()
    assertThat(c.state.value.identical.isEmpty).isFalse()

    identical.throwOnScan = false
    c.refreshIdentical()
    assertThat(c.state.value.identical.failed).isFalse()
    assertThat(c.state.value.identical.groups).hasSize(4)
  }

  // --- Il foglio e la pulizia degli identici -------------------------------------------------------------------------------

  @Test
  fun preparareLaPuliziaApreIlFoglio_MaNonCancellaNiente() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()

    c.prepareIdentical()

    val sheet = c.state.value.sheet!!
    assertThat(sheet.source).isEqualTo(CleanupSource.IDENTICAL)
    assertThat(sheet.scopes).containsExactly(CleanupScope.EVERYWHERE)
    assertThat(sheet.plan.phone).hasSize(1)
    assertThat(sheet.plan.nas).hasSize(2)
    assertThat(nas.calls).isEmpty()
    assertThat(system.trashRequests).isEmpty()
    // Chiudere il foglio non manda niente.
    c.dismissSheet()
    assertThat(c.state.value.sheet).isNull()
    assertThat(nas.calls).isEmpty()
    assertThat(system.trashRequests).isEmpty()
  }

  @Test
  fun ilTocco_SuElimina_ManodaLeCopieDelNasAlCestinoDelNasEQuelleDelTelefonoAlDialogo() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()
    c.prepareIdentical()

    val request = c.confirm()

    assertThat(request).isNotNull()
    // Il NAS: due copie, una sola conferma (un tocco), nel cestino del NAS.
    assertThat(nas.calls).hasSize(1)
    assertThat(nas.calls.single().first.map { it.path }).containsExactly("Backup/Pixel 9/2026/10/IMG_0002.jpg", "Famiglia/Oslo/IMG_0002.jpg")
    assertThat(nas.calls.single().second).isEqualTo(1)
    // Il telefono: una copia, con la richiesta di mandare nel cestino (mai di eliminare).
    assertThat(system.trashRequests).hasSize(1)
    assertThat(system.trashRequests.single().second).isTrue()
    assertThat(system.deleteRequests).isEmpty()
    assertThat(c.state.value.sheet).isNull()
    assertThat(c.state.value.notice).isEqualTo(DuplicatesNotice.CLEANUP_STARTED)
    assertThat(c.state.value.busy).isFalse()
  }

  @Test
  fun seIlNasNonAccetta_IlTelefonoNonSiTocca() = runTest(UnconfinedTestDispatcher()) {
    for ((outcome, notice) in listOf(
      NasTrashOutcome.REJECTED to DuplicatesNotice.CLEANUP_REJECTED,
      NasTrashOutcome.BLOCKED to DuplicatesNotice.CLEANUP_BLOCKED,
      NasTrashOutcome.FAILED to DuplicatesNotice.CLEANUP_FAILED,
    )) {
      val cleanup = FakeCleanupNas(outcome)
      val c = controller(cleanup = cleanup).opened()
      c.prepareIdentical()

      assertThat(c.confirm()).isNull()

      assertThat(cleanup.calls).hasSize(1)
      assertThat(system.trashRequests).isEmpty()
      assertThat(c.state.value.sheet).isNull()
      assertThat(c.state.value.notice).isEqualTo(notice)
    }
  }

  @Test
  fun unaPortaDelNasCheLancia_EUnFallimento_NonUnaCancellazione() = runTest(UnconfinedTestDispatcher()) {
    val throwing = dev.pampa.mycloudgallery.feature.library.utility.CleanupNasPort { _, _ -> error("nas non raggiungibile") }
    val c = DuplicatesController(identical, similar, throwing, SenderIntents(system, sender), switch, backgroundScope).opened()
    c.prepareIdentical()

    assertThat(c.confirm()).isNull()

    assertThat(c.state.value.notice).isEqualTo(DuplicatesNotice.CLEANUP_FAILED)
    assertThat(system.trashRequests).isEmpty()
  }

  @Test
  fun senzaFoglioNonPartiNiente() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()

    assertThat(c.confirm()).isNull()

    assertThat(nas.calls).isEmpty()
    assertThat(system.trashRequests).isEmpty()
  }

  @Test
  fun senzaIlCestinoDiSistema_IlFoglioHaIlSoloNas_ELaPaginaDicePerche() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(canTrash = false).opened()

    c.prepareIdentical()

    val sheet = c.state.value.sheet!!
    assertThat(sheet.plan.phone).isEmpty()
    assertThat(sheet.plan.nas).hasSize(2)
    assertThat(sheet.plan.unsafePhone).isEqualTo(0)
    assertThat(c.state.value.notice).isEqualTo(DuplicatesNotice.NO_PHONE_TRASH)

    assertThat(c.confirm()).isNull()

    // Il NAS parte; il telefono non si tocca (e mai una richiesta di eliminazione).
    assertThat(nas.calls).hasSize(1)
    assertThat(system.trashRequests).isEmpty()
    assertThat(system.deleteRequests).isEmpty()
    assertThat(c.state.value.notice).isEqualTo(DuplicatesNotice.CLEANUP_STARTED)
  }

  @Test
  fun senzaIlCestinoDiSistema_UnGruppoSoloDelTelefonoNonApreIlFoglio() = runTest(UnconfinedTestDispatcher()) {
    val phoneOnly = FakeIdenticalDuplicates(proposals = identical.proposals.filter { it.group.part == CopyPart.PHONE })
    val c = controller(id = phoneOnly, canTrash = false).opened()

    c.prepareIdentical()

    assertThat(c.state.value.sheet).isNull()
    assertThat(c.state.value.notice).isEqualTo(DuplicatesNotice.NO_PHONE_TRASH)
    assertThat(c.confirm()).isNull()
    assertThat(system.trashRequests).isEmpty()
  }

  @Test
  fun senzaIlCestinoDiSistema_GliScattiSimiliOffronoSoloOvunque() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(canTrash = false).opened()

    c.prepareSimilar()

    val sheet = c.state.value.sheet!!
    assertThat(sheet.scopes).containsExactly(CleanupScope.EVERYWHERE)
    assertThat(sheet.plan.phone).isEmpty()
    assertThat(sheet.plan.nas).isNotEmpty()
    assertThat(c.state.value.notice).isEqualTo(DuplicatesNotice.NO_PHONE_TRASH)
  }

  @Test
  fun seIlDialogoDelTelefonoNonSiApreDopoIlNas_LAvvisoDiceCheLaPuliziaDelNasEPartita() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()
    c.prepareIdentical()
    // Il cestino di sistema sparisce fra il foglio e il tocco (non succede, ma l'avviso non deve mentire).
    system.canTrash = false

    assertThat(c.confirm()).isNull()

    assertThat(nas.calls).hasSize(1)
    assertThat(c.state.value.notice).isEqualTo(DuplicatesNotice.CLEANUP_STARTED)
    assertThat(c.state.value.sheet).isNull()
    assertThat(system.trashRequests).isEmpty()
  }

  @Test
  fun alleCopieDelTelefonoSiRileggeSoloSeIlDialogoEConfermato() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()
    c.prepareIdentical()
    c.confirm()
    val scansAfterNas = identical.scans

    c.onPhoneResult(confirmed = false)
    assertThat(identical.scans).isEqualTo(scansAfterNas)

    c.onPhoneResult(confirmed = true)
    assertThat(identical.scans).isEqualTo(scansAfterNas + 1)
  }

  @Test
  fun unPianoVuoto_Dice_CheNonCeNienteDaTogliere() = runTest(UnconfinedTestDispatcher()) {
    identical.planOverride = CleanupPlan.EMPTY
    val c = controller().opened()

    c.prepareIdentical()

    assertThat(c.state.value.sheet).isNull()
    assertThat(c.state.value.notice).isEqualTo(DuplicatesNotice.NOTHING_TO_CLEAN)
    assertThat(c.state.value.busy).isFalse()
  }

  @Test
  fun unDialogoDiSistemaPortaAlMassimoDuemilaElementi_IlResto_RestaPerUnAltroGiro() = runTest(UnconfinedTestDispatcher()) {
    val phone = (1L..2_500L).map { PhoneTrashRef(MediaRef.Phone("external_primary", it, "image/jpeg"), 1_000) }
    identical.planOverride = CleanupPlan(groups = 1, phone = phone, nas = emptyList(), unsafePhone = 0, skipped = 0)
    val c = controller().opened()

    c.prepareIdentical()

    val sheet = c.state.value.sheet!!
    assertThat(sheet.plan.phone).hasSize(PhoneMediaIntents.MAX_REQUEST_URIS)
    assertThat(sheet.phoneLeft).isEqualTo(500)
    assertThat(sheet.scopes).containsExactly(CleanupScope.PHONE_ONLY)
    c.confirm()
    assertThat(system.trashRequests.single().first).hasSize(PhoneMediaIntents.MAX_REQUEST_URIS)
  }

  // --- Simili (E-U3) ---------------------------------------------------------------------------------------------------------

  @Test
  fun e_u3_LaMiglioreEDaTenere_LePreferiteEQuelleInUnAlbumNonSonoPreselezionate() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()

    val first = c.state.value.similar.clusters[0]
    assertThat(first.cluster.best).isEqualTo(sampleMediaId(102))
    // Il 103 e' preferito e il 104 e' in un album: restano. Solo il 101 e' preselezionato per l'eliminazione.
    assertThat(first.remove).containsExactly(sampleMediaId(101))
    assertThat(first.keep).containsExactly(sampleMediaId(102), sampleMediaId(103), sampleMediaId(104))
    assertThat(c.state.value.similar.clusters[1].remove).containsExactly(sampleMediaId(202))
    assertThat(c.state.value.similar.toRemove).isEqualTo(2)
  }

  @Test
  fun e_u3_UnaPreferitaSiEliminaSoloSeLaScegliTu_ESiAvvisa() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()
    val key = c.state.value.similar.clusters[0].cluster.key

    c.toggleShot(key, sampleMediaId(103))

    val view = c.state.value.similar.clusters[0]
    assertThat(view.remove).containsExactly(sampleMediaId(101), sampleMediaId(103))
    assertThat(view.protectedChosen).containsExactly(sampleMediaId(103))
    c.toggleShot(key, sampleMediaId(103))
    assertThat(c.state.value.similar.clusters[0].protectedChosen).isEmpty()
  }

  @Test
  fun inUnGruppoAlmenoUnoScattoResta() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()
    val key = c.state.value.similar.clusters[0].cluster.key
    c.toggleShot(key, sampleMediaId(103))
    c.toggleShot(key, sampleMediaId(104))
    assertThat(c.state.value.similar.clusters[0].keep).containsExactly(sampleMediaId(102))

    c.toggleShot(key, sampleMediaId(102))

    assertThat(c.state.value.similar.clusters[0].keep).containsExactly(sampleMediaId(102))
    assertThat(c.state.value.notice).isEqualTo(DuplicatesNotice.LAST_SHOT_STAYS)
    // E il toggle di un media che non e' del gruppo, o di un gruppo che non c'e', non fa niente.
    c.toggleShot(key, sampleMediaId(999))
    c.toggleShot("non-esiste", sampleMediaId(102))
    assertThat(c.state.value.similar.clusters[0].keep).containsExactly(sampleMediaId(102))
  }

  @Test
  fun tornareAllaProposta_RiportaLePreselezioni() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()
    val key = c.state.value.similar.clusters[0].cluster.key
    c.toggleShot(key, sampleMediaId(103))

    c.resetCluster(key)

    assertThat(c.state.value.similar.clusters[0].remove).containsExactly(sampleMediaId(101))
  }

  @Test
  fun leScelteSopravvivonoAUnaNuovaRicerca() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()
    val key = c.state.value.similar.clusters[0].cluster.key
    c.toggleShot(key, sampleMediaId(103))

    similar.batches = listOf(SimilarBatch(sampleClusters(), nextBeforeDay = null, undecided = 0))
    c.scanSimilar()

    assertThat(c.state.value.similar.clusters[0].remove).containsExactly(sampleMediaId(101), sampleMediaId(103))
  }

  @Test
  fun laRicercaVaAdAvantiAlottiDalGiornoPiuRecente_EMostraIGruppiManoMano() = runTest(UnconfinedTestDispatcher()) {
    val clusters = sampleClusters()
    similar.batches = listOf(
      SimilarBatch(listOf(clusters[0]), nextBeforeDay = 20_261_012, undecided = 1),
      SimilarBatch(emptyList(), nextBeforeDay = 20_261_005, undecided = 2),
      SimilarBatch(listOf(clusters[1]), nextBeforeDay = null, undecided = 0),
    )
    val c = controller().opened()

    assertThat(similar.calls).containsExactly(null, 20_261_012, 20_261_005).inOrder()
    val view = c.state.value.similar
    assertThat(view.scanning).isFalse()
    assertThat(view.clusters).hasSize(2)
    assertThat(view.undecided).isEqualTo(3)
  }

  @Test
  fun unLottoFallito_FermaLaRicerca_EConservaQuelloGiaTrovato() = runTest(UnconfinedTestDispatcher()) {
    val clusters = sampleClusters()
    similar.batches = listOf(
      SimilarBatch(listOf(clusters[0]), nextBeforeDay = 20_261_012, undecided = 0),
      SimilarBatch(listOf(clusters[1]), nextBeforeDay = null, undecided = 0),
    )
    similar.failAt = 1
    val c = controller().opened()

    val view = c.state.value.similar
    assertThat(view.failed).isTrue()
    assertThat(view.scanning).isFalse()
    assertThat(view.clusters).hasSize(1)
    assertThat(view.isEmpty).isFalse()
  }

  @Test
  fun laPuliziaDeiSimili_ApreIlFoglioConLeDueScelte_ENonCancellaNiente() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()

    c.prepareSimilar()

    assertThat(similar.placesAsked.single()).containsExactly(sampleMediaId(101), sampleMediaId(202))
    val sheet = c.state.value.sheet!!
    assertThat(sheet.source).isEqualTo(CleanupSource.SIMILAR)
    assertThat(sheet.scopes).containsExactly(CleanupScope.PHONE_ONLY, CleanupScope.EVERYWHERE).inOrder()
    assertThat(sheet.chosen).isEqualTo(CleanupScope.PHONE_ONLY)
    assertThat(sheet.plans.getValue(CleanupScope.PHONE_ONLY).nas).isEmpty()
    assertThat(sheet.plans.getValue(CleanupScope.EVERYWHERE).nas).isNotEmpty()
    assertThat(nas.calls).isEmpty()
    assertThat(system.trashRequests).isEmpty()
  }

  @Test
  fun soloDalTelefono_NonToccaIlNas_Ovunque_Si() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()
    c.prepareSimilar()

    c.confirm()
    assertThat(nas.calls).isEmpty()
    assertThat(system.trashRequests.single().first).hasSize(2)

    c.prepareSimilar()
    c.chooseScope(CleanupScope.EVERYWHERE)
    c.confirm()
    assertThat(nas.calls).hasSize(1)
    assertThat(nas.calls.single().second).isEqualTo(1)
    assertThat(system.trashRequests).hasSize(2)
    // Mai una cancellazione definitiva.
    assertThat(system.deleteRequests).isEmpty()
  }

  @Test
  fun senzaCopieSulTelefono_RestaSoloOvunque() = runTest(UnconfinedTestDispatcher()) {
    val noPhone = FakeSimilarShots(places = { media -> samplePlacesOf(media).let { dev.pampa.mycloudgallery.core.library.duplicates.RemovalPlaces(it.media, emptyList(), it.nasPrivate, it.nasCommon, 0) } })
    val c = controller(sim = noPhone).opened()

    c.prepareSimilar()

    val sheet = c.state.value.sheet!!
    assertThat(sheet.scopes).containsExactly(CleanupScope.EVERYWHERE)
    assertThat(sheet.chosen).isEqualTo(CleanupScope.EVERYWHERE)
  }

  @Test
  fun senzaNientePreselezionato_LaPuliziaNonFaNiente() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()
    val clusters = c.state.value.similar.clusters
    // L'utente rimette da tenere tutto cio' che era da eliminare.
    c.toggleShot(clusters[0].cluster.key, sampleMediaId(101))
    c.toggleShot(clusters[1].cluster.key, sampleMediaId(202))
    assertThat(c.state.value.similar.toRemove).isEqualTo(0)

    c.prepareSimilar()

    assertThat(c.state.value.sheet).isNull()
    assertThat(similar.placesAsked).isEmpty()
  }

  @Test
  fun chooseScope_ConUnaScelta_ChePianoNonHa_NonFaNiente() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()
    c.prepareIdentical()

    c.chooseScope(CleanupScope.PHONE_ONLY)

    assertThat(c.state.value.sheet!!.chosen).isEqualTo(CleanupScope.EVERYWHERE)
  }

  // --- L'interruttore notturno (D27) -----------------------------------------------------------------------------------------

  @Test
  fun lInterruttoreNotturnoSiAccendeESiSpegne_ELoStatoSegueLaPorta() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()
    assertThat(c.state.value.nightly.available).isTrue()
    assertThat(c.state.value.nightly.on).isFalse()

    c.setNightly(true)
    assertThat(switch.sets).containsExactly(true)
    assertThat(c.state.value.nightly.on).isTrue()

    c.setNightly(false)
    assertThat(switch.sets).containsExactly(true, false).inOrder()
    assertThat(c.state.value.nightly.on).isFalse()
  }

  @Test
  fun senzaLaPortaDellInterruttore_LaPaginaNonLoMostra() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(fullHash = NoFullHashSwitch).opened()

    assertThat(c.state.value.nightly.available).isFalse()
    c.setNightly(true)
    assertThat(c.state.value.nightly.on).isFalse()
  }

  @Test
  fun lePartiDelGruppoSonoQuelleDellaProposta() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()

    val parts = c.state.value.identical.groups.associate { it.group.media to it.group.part }
    assertThat(parts[sampleMediaId(1)]).isEqualTo(CopyPart.PHONE)
    assertThat(parts[sampleMediaId(2)]).isEqualTo(CopyPart.NAS)
  }

  @Test
  fun selectTab_CambiaScheda() = runTest(UnconfinedTestDispatcher()) {
    val c = controller().opened()
    assertThat(c.state.value.tab).isEqualTo(DuplicatesTab.IDENTICAL)

    c.selectTab(DuplicatesTab.SIMILAR)

    assertThat(c.state.value.tab).isEqualTo(DuplicatesTab.SIMILAR)
  }
}
