package dev.pampa.mycloudgallery.feature.viewer.menu

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.ViewerActionHandlers
import dev.pampa.mycloudgallery.feature.viewer.ViewerCapabilities
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.viewerMenuEntries
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le voci del backup nel menu ⋮ del viewer (`docs/fase5-progetto.md` §1.3 "viewer", §2.4, §2.7, D13): compaiono solo se si possono fare, nell'ordine
 * del progetto, e ogni voce e' una richiesta a `:app` (mai un'azione del viewer). Il controller non scrive niente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "it")
class BackupActionsTest {

  private val resources get() = BackupActionsSamples.resources
  private val none = BackupMenuHandlers {}

  private fun entries(
    capabilities: BackupMenuCapabilities = BackupActionsSamples.everything,
    plan: dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan? = BackupActionsSamples.plan(),
    isVideo: Boolean = false,
    handlers: BackupMenuHandlers = none,
  ) = backupMenuEntries(resources, capabilities, BackupMenuTarget(plan, isVideo), handlers)

  private fun labels(entries: List<GalleryMenuEntry>) = BackupActionsSamples.labels(entries)

  private fun text(id: Int) = resources.getString(id)

  // --- Le voci ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun conPiuVersioniCompaionoTutteLeVociNellOrdineDelProgetto() {
    // L'originale non e' quello di adesso: si puo' anche guardare.
    assertThat(labels(entries())).containsExactly(
      "Versioni", "Vedi originale", "Ripristina originale", "Correggi data e luogo", "Ruota", "Modifica con…",
    ).inOrder()
  }

  @Test
  fun conUnaSolaVersioneNonCiSonoVersioniNeRipristino() {
    assertThat(labels(entries(plan = BackupActionsSamples.single()))).containsExactly("Correggi data e luogo", "Ruota", "Modifica con…").inOrder()
  }

  @Test
  fun senzaIlCustodeDelleVersioniRestanoLeVociCheNonDipendonoDaLui() {
    assertThat(labels(entries(plan = null))).containsExactly("Correggi data e luogo", "Ruota", "Modifica con…").inOrder()
  }

  @Test
  fun vediOriginaleServeLaCapacitaEUnOriginaleDiverso() {
    val withoutView = BackupActionsSamples.everything.copy(viewOriginal = false)
    assertThat(labels(entries(capabilities = withoutView))).doesNotContain("Vedi originale")
    // Dopo "Ripristina originale" l'originale e' quello di adesso: niente da guardare a parte.
    assertThat(labels(entries(plan = BackupActionsSamples.swapped()))).doesNotContain("Vedi originale")
  }

  @Test
  fun ilRipristinoSiOffreSoloQuandoHaSenso() {
    val offered = listOf(
      RestoreAvailability.AVAILABLE, RestoreAvailability.ORIGINAL_NOT_SAFE, RestoreAvailability.NAS_UNREACHABLE, RestoreAvailability.FROZEN,
      // P14: un lavoro in corso sul media (una versione, uno scambio): la voce c'e', il foglio dice "riprova fra poco".
      RestoreAvailability.READ_ONLY_AREA, RestoreAvailability.BUSY,
      // R3, VE-7: la versione di adesso non al sicuro, la pausa, la prova: la voce c'e' e il foglio dice perche' adesso no.
      RestoreAvailability.CURRENT_NOT_SAFE, RestoreAvailability.PAUSED, RestoreAvailability.TEST_ONLY,
    )
    val notOffered = listOf(
      RestoreAvailability.ONLY_ONE_VERSION, RestoreAvailability.ALREADY_ORIGINAL, RestoreAvailability.NOT_MINE, RestoreAvailability.NOT_AVAILABLE,
    )
    assertThat(offered + notOffered).containsExactlyElementsIn(RestoreAvailability.entries)
    for (restore in offered) assertThat(labels(entries(plan = BackupActionsSamples.plan(restore)))).contains("Ripristina originale")
    for (restore in notOffered) assertThat(labels(entries(plan = BackupActionsSamples.plan(restore)))).doesNotContain("Ripristina originale")
  }

  /** VE-4: un formato che non si scrive (HEIC, RAW) non si ruota: la voce non c'e' (diceva "fatto" e la foto restava storta). */
  @Test
  fun ve4_unFormatoCheNonSiScriveNonSiRuota() {
    val heic = backupMenuEntries(resources, BackupActionsSamples.everything, BackupMenuTarget(BackupActionsSamples.plan(), isVideo = false, rotatable = false), none)
    assertThat(labels(heic)).doesNotContain("Ruota")
    assertThat(labels(heic)).contains("Correggi data e luogo")
  }

  /** MC-5: "Scarica e modifica in Galleria" c'e' per un solo-NAS (prima non la chiamava nessuno), non per un media del telefono. */
  @Test
  fun mc5_scaricaEModificaSoloPerUnSoloNas() {
    val caps = BackupActionsSamples.everything.copy(editDownload = true)
    val nasOnly = BackupActionsSamples.plan(
      versions = listOf(
        BackupActionsSamples.entry(1, dev.pampa.mycloudgallery.core.backup.contract.VersionRole.ORIGINAL, current = false, places = setOf(dev.pampa.mycloudgallery.core.backup.contract.VersionPlace.NAS_VERSIONS_FOLDER)),
        BackupActionsSamples.entry(2, dev.pampa.mycloudgallery.core.backup.contract.VersionRole.EDITED, current = true, places = setOf(dev.pampa.mycloudgallery.core.backup.contract.VersionPlace.NAS_USUAL_PATH)),
      ),
    )
    assertThat(labels(entries(capabilities = caps, plan = nasOnly))).contains("Scarica e modifica in Galleria")
    assertThat(labels(entries(capabilities = caps))).doesNotContain("Scarica e modifica in Galleria")
    assertThat(labels(entries(capabilities = caps, plan = null))).doesNotContain("Scarica e modifica in Galleria")
    assertThat(labels(entries(plan = nasOnly))).doesNotContain("Scarica e modifica in Galleria")
  }

  @Test
  fun unVideoNonSiRuota() {
    assertThat(labels(entries(isVideo = true))).doesNotContain("Ruota")
    assertThat(labels(entries(isVideo = true))).contains("Correggi data e luogo")
  }

  @Test
  fun unMediaDiUnAltroNonSiCorreggeNonSiRipristinaNonSiModifica() {
    val shown = labels(entries(plan = BackupActionsSamples.plan(RestoreAvailability.NOT_MINE)))
    assertThat(shown).containsExactly("Versioni", "Vedi originale")
  }

  @Test
  fun senzaCapacitaNeCustodeNonCeNiente() {
    assertThat(entries(capabilities = BackupMenuCapabilities(), plan = null)).isEmpty()
    // Con il solo custode delle versioni (nessun servizio di correzione ne' di modifica) restano le voci che dipendono dal piano.
    assertThat(labels(entries(capabilities = BackupMenuCapabilities()))).containsExactly("Versioni", "Ripristina originale").inOrder()
  }

  @Test
  fun ogniVoceChiamaLAzioneGiustaUnaVoltaSola() {
    val asked = ArrayList<BackupMenuAction>()
    val shown = entries(handlers = BackupMenuHandlers { asked += it }).filterIsInstance<GalleryMenuEntry.Item>()
    shown.forEach { it.onClick() }
    assertThat(asked).containsExactly(
      BackupMenuAction.VERSIONS, BackupMenuAction.VIEW_ORIGINAL, BackupMenuAction.RESTORE_ORIGINAL, BackupMenuAction.CORRECT,
      BackupMenuAction.ROTATE, BackupMenuAction.EDIT_WITH,
    ).inOrder()
    // Mostrare le voci non chiama niente: solo il tocco.
    val untouched = ArrayList<BackupMenuAction>()
    entries(handlers = BackupMenuHandlers { untouched += it })
    assertThat(untouched).isEmpty()
  }

  @Test
  fun nessunaVoceDelBackupEDistruttiva() {
    assertThat(entries().filterIsInstance<GalleryMenuEntry.Item>().none { it.destructive }).isTrue()
  }

  // --- Dentro il menu del viewer ---------------------------------------------------------------------------------------------------

  private val handlers = ViewerActionHandlers({}, {}, {}, {}, {}, {}, {})

  @Test
  fun leVociDelBackupStannoNelGruppoDiCondividiPrimaDiInformazioni() {
    val caps = ViewerCapabilities(favorite = true, hide = true, share = true, copy = true, trash = true)
    val menu = viewerMenuEntries(resources, caps, isFavorite = false, secure = false, handlers = handlers, backupActions = entries())
    val shown = labels(menu)
    val info = text(R.string.viewer_action_info)
    assertThat(shown.indexOf("Versioni")).isGreaterThan(shown.indexOf(text(R.string.viewer_action_copy)))
    assertThat(shown.indexOf("Modifica con…")).isLessThan(shown.indexOf(info))
    // L'unica voce che elimina resta ultima, dopo l'interruzione.
    assertThat(shown.last()).isEqualTo(text(R.string.viewer_action_delete))
  }

  @Test
  fun senzaVociDelBackupIlMenuNonCambia() {
    val caps = ViewerCapabilities(favorite = true, hide = true, share = true, copy = true, trash = true)
    val before = viewerMenuEntries(resources, caps, isFavorite = true, secure = false, handlers = handlers)
    val after = viewerMenuEntries(resources, caps, isFavorite = true, secure = false, handlers = handlers, backupActions = emptyList(), shareWithoutLocation = false)
    assertThat(labels(after)).isEqualTo(labels(before))
  }

  @Test
  fun conRimuoviLaPosizioneAccesaCondividiLoDice() {
    val caps = ViewerCapabilities(favorite = false, hide = false, share = true, copy = false, trash = false)
    val off = labels(viewerMenuEntries(resources, caps, isFavorite = false, secure = false, handlers = handlers))
    val on = labels(viewerMenuEntries(resources, caps, isFavorite = false, secure = false, handlers = handlers, shareWithoutLocation = true))
    assertThat(off).contains("Condividi")
    assertThat(on).contains("Condividi senza posizione")
    assertThat(on).doesNotContain("Condividi")
  }

  @Test
  fun conIlBackupLegatoUnSoloNasSiPuoCondividere() {
    val nasOnly = dev.pampa.mycloudgallery.core.model.MediaRef.Nas(locationId = 7, contentKey = 99, mime = "image/jpeg")
    val without = ViewerCapabilities.of(ViewerServices(), nasOnly)
    assertThat(without.share).isFalse()
    val with = ViewerCapabilities.of(ViewerServices(backupActions = FakeBackupMediaActions()), nasOnly)
    assertThat(with.share).isTrue()
    val noShare = ViewerCapabilities.of(ViewerServices(backupActions = FakeBackupMediaActions(capabilities = BackupMenuCapabilities())), nasOnly)
    assertThat(noShare.share).isFalse()
  }

  // --- Il controller ---------------------------------------------------------------------------------------------------------------

  private fun TestScope.controller(fake: FakeBackupMediaActions) = BackupActionsController(fake, backgroundScope)

  @Test
  fun ilControllerLeggeIlPianoDelMediaDiAdesso() = runTest(UnconfinedTestDispatcher()) {
    val fake = FakeBackupMediaActions()
    val controller = controller(fake)
    assertThat(controller.target.value).isNull()
    controller.bind(BackupActionsSamples.media, isVideo = false)
    assertThat(controller.target.value!!.versions).isEqualTo(3)
    assertThat(fake.planRequests).isEqualTo(1)
    controller.bind(null, isVideo = false)
    assertThat(controller.target.value).isNull()
  }

  /** VE-4: il controller chiede a `:app` se il media si ruota davvero e lo mette nel bersaglio del menu. */
  @Test
  fun ve4_ilControllerChiedeSeSiRuota() = runTest(UnconfinedTestDispatcher()) {
    val fake = FakeBackupMediaActions(rotatable = false)
    val controller = controller(fake)
    controller.bind(BackupActionsSamples.media, isVideo = false)
    assertThat(controller.target.value!!.rotatable).isFalse()
  }

  @Test
  fun unaVoceChiamaIlBackupConIlMediaDiAdesso_ESenzaMediaNonFaNiente() = runTest(UnconfinedTestDispatcher()) {
    val fake = FakeBackupMediaActions()
    val controller = controller(fake)
    controller.perform(BackupMenuAction.VERSIONS)
    assertThat(fake.performed).isEmpty()
    controller.bind(BackupActionsSamples.media, isVideo = false)
    controller.perform(BackupMenuAction.RESTORE_ORIGINAL)
    assertThat(fake.performed).containsExactly(BackupMenuAction.RESTORE_ORIGINAL to BackupActionsSamples.media)
  }

  @Test
  fun condividiPassaDalBackupConIlMediaDiAdesso() = runTest(UnconfinedTestDispatcher()) {
    val fake = FakeBackupMediaActions()
    val controller = controller(fake)
    controller.bind(BackupActionsSamples.media, isVideo = false)
    controller.share()
    assertThat(fake.shared).containsExactly(listOf(BackupActionsSamples.media))
    // Il rifiuto non rilancia niente da solo: solo il tocco su "Condividi con la posizione".
    fake.shareState.value = BackupShareUi.Refused("no", canShareWithLocation = true)
    assertThat(fake.shareWithLocationCalls).isEqualTo(0)
    controller.shareWithLocation()
    assertThat(fake.shareWithLocationCalls).isEqualTo(1)
    controller.dismissShare()
    assertThat(fake.dismissShareCalls).isEqualTo(1)
  }

  @Test
  fun eliminaSoloDalTelefonoPassaDallaVerificaSeIlBackupSaFarlo() = runTest(UnconfinedTestDispatcher()) {
    val fake = FakeBackupMediaActions()
    val controller = controller(fake)
    controller.bind(BackupActionsSamples.media, isVideo = false)
    var fallback = 0
    controller.deleteFromPhone { fallback++ }
    assertThat(fake.performed).containsExactly(BackupMenuAction.PHONE_ONLY_DELETE to BackupActionsSamples.media)
    assertThat(fallback).isEqualTo(0)

    val without = FakeBackupMediaActions(capabilities = BackupMenuCapabilities())
    val plain = controller(without)
    plain.bind(BackupActionsSamples.media, isVideo = false)
    plain.deleteFromPhone { fallback++ }
    assertThat(without.performed).isEmpty()
    assertThat(fallback).isEqualTo(1)
  }

  @Test
  fun ovunqueChiedeLeCopieInPiuAlBackup() = runTest(UnconfinedTestDispatcher()) {
    val fake = FakeBackupMediaActions(everywhereSummary = EverywhereSummary(previousVersions = 2))
    val controller = controller(fake)
    assertThat(controller.everywhere()).isNull()
    controller.bind(BackupActionsSamples.media, isVideo = false)
    assertThat(controller.everywhere()).isEqualTo(EverywhereSummary(previousVersions = 2))
  }

  @Test
  fun leFrasiDelBackupNonPortanoDatiPersonali() {
    assertThat(BackupNotice("Salvata come versione nuova", ok = true).toString()).doesNotContain("Salvata")
    assertThat(BackupMenuTarget(BackupActionsSamples.plan(), false).toString()).doesNotContain(BackupActionsSamples.media.value)
  }
}
