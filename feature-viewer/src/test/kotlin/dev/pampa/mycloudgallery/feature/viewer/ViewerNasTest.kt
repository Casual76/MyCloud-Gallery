package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.ui.test.center
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.imaging.viewer.testing.ScriptedNasReader
import dev.pampa.mycloudgallery.core.imaging.viewer.MobileDataConsent
import dev.pampa.mycloudgallery.core.imaging.viewer.NasMediaLocation
import dev.pampa.mycloudgallery.core.imaging.viewer.OriginalFetch
import dev.pampa.mycloudgallery.core.imaging.viewer.OriginalFetcher
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.viewer.download.DownloadFailure
import dev.pampa.mycloudgallery.feature.viewer.download.DownloadState
import dev.pampa.mycloudgallery.feature.viewer.download.PhoneDownload
import dev.pampa.mycloudgallery.feature.viewer.video.NasVideoSource
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackProblem
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackSource
import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import org.junit.Test

/** Gli elementi del viewer con riferimenti del **NAS** (`MediaRef.Nas`) per ogni media, foto e video. */
internal class NasItems(initial: TimelineView, private val fixture: ViewerFixture) : ViewerItems {
  override val view: StateFlow<TimelineView> = MutableStateFlow(initial)

  override fun ref(id: MediaId): MediaRef {
    val index = fixture.library.snapshot.indexOf(id)
    val video = fixture.library.snapshot.kindAt(index) == MediaKind.VIDEO
    return MediaRef.Nas(locationId = LOCATION_BASE + index, contentKey = KEY_BASE + index, mime = if (video) "video/mp4" else "image/jpeg")
  }

  companion object {
    const val LOCATION_BASE = 5_000L
    const val KEY_BASE = 77_000L
  }
}

/** "Scarica sul telefono" finto: registra le richieste e lascia al test lo stato di ogni download. */
internal class FakePhoneDownload(var allowed: Boolean = true) : PhoneDownload {
  val flow = MutableStateFlow<Map<Long, DownloadState>>(emptyMap())
  val started = mutableListOf<MediaRef.Nas>()
  val cancelled = mutableListOf<MediaRef.Nas>()
  val acknowledged = mutableListOf<Long>()

  override val states: StateFlow<Map<Long, DownloadState>> = flow

  override fun canDownload(ref: MediaRef): Boolean = allowed && ref is MediaRef.Nas

  override fun start(ref: MediaRef.Nas) {
    started += ref
  }

  override fun cancel(ref: MediaRef.Nas) {
    cancelled += ref
  }

  override fun acknowledge(locationId: Long) {
    acknowledged += locationId
    flow.update { it - locationId }
  }

  fun set(locationId: Long, state: DownloadState) {
    flow.update { it + (locationId to state) }
  }
}

/** L'originale del NAS per lo zoom, a mano: il test decide cosa arriva e quando. */
internal class FakeOriginals : OriginalFetcher {
  var local: File? = null
  val events = Channel<OriginalFetch>(Channel.UNLIMITED)
  var fetchCalls = 0

  override fun localFile(ref: MediaRef): File? = local

  override fun fetch(ref: MediaRef): Flow<OriginalFetch> {
    fetchCalls++
    return events.receiveAsFlow()
  }
}

/**
 * Il viewer con i media del NAS (P8): la voce "Scarica sul telefono" nel menu a capacita' (D13), il suo stato e il suo esito,
 * le note con "Riprova" e i dati mobili (E-V2), i video in streaming (D19) e l'originale chiesto con lo zoom. Le sorgenti
 * sono finte; la lettura vera dei byte sta nei test di `core-imaging` e di `NasVideoDataSource`.
 */
class ViewerNasTest : ViewerTestBase() {

  override val fixture: ViewerFixture = ViewerFixture(count = 400)

  private val photoIndex: Int get() = (0 until fixture.library.count).first { fixture.library.snapshot.kindAt(it) == MediaKind.PHOTO }
  private val videoIndex: Int get() = fixture.indicesOf(MediaKind.VIDEO).first()

  private fun nasRequest(index: Int): ViewerRequest {
    fixture.placeCell(index)
    return ViewerRequest(items = NasItems(fixture.library.view(), fixture), start = fixture.idAt(index), anchor = fixture.anchor, secure = false)
  }

  private fun refAt(index: Int) = NasItems(fixture.library.view(), fixture).ref(fixture.idAt(index)) as MediaRef.Nas

  private fun scripted(vararg steps: (MediaRef) -> Step) = ScriptedImages { ref -> steps.map { it(ref) } }

  /** Quante volte la sorgente e' stata chiesta per [ref] (le pagine vicine ne chiedono altre: si conta solo questa). */
  private fun ScriptedImages.loadsOf(ref: MediaRef): Int = requested.count { it == ref }

  private val preview: (MediaRef) -> Step = { ScriptedImages.preview(it) }
  private val sharp: (MediaRef) -> Step = { ScriptedImages.sharp(it) }
  private fun emit(state: ViewerImageState): (MediaRef) -> Step = { Step.Emit(state) }

  private fun openMenu() {
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText("Informazioni").fetchSemanticsNodes().isNotEmpty() }
  }

  private fun hasText(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

  // --- Menu a capacita' ---------------------------------------------------------------------------------------------

  @Test
  fun laVoceScaricaSulTelefonoCompareSoloPerUnMediaDelNasCheSiPuoScaricare() {
    val download = FakePhoneDownload()
    show(request = nasRequest(photoIndex), services = services(imageSource = scripted(preview, sharp), download = download))

    openMenu()

    assertThat(hasText("Scarica sul telefono")).isTrue()
  }

  @Test
  fun senzaIlServizioOSeNonSiPuoLaVoceNonCe_D13() {
    show(request = nasRequest(photoIndex), services = services(imageSource = scripted(preview, sharp), download = null))
    openMenu()
    assertThat(hasText("Scarica sul telefono")).isFalse()
  }

  @Test
  fun seIlServizioDiceDiNoLaVoceNonCe() {
    show(request = nasRequest(photoIndex), services = services(imageSource = scripted(preview, sharp), download = FakePhoneDownload(allowed = false)))
    openMenu()
    assertThat(hasText("Scarica sul telefono")).isFalse()
  }

  @Test
  fun unMediaDelTelefonoNonHaScaricaSulTelefono() {
    show(services = services(download = FakePhoneDownload()))
    openMenu()
    assertThat(hasText("Scarica sul telefono")).isFalse()
    // Le altre voci ci sono: il menu non e' cambiato.
    assertThat(hasText("Informazioni")).isTrue()
  }

  @Test
  fun scegliereLaVoceMetteInCodaIlDownloadDelMediaCorrente() {
    val download = FakePhoneDownload()
    show(request = nasRequest(photoIndex), services = services(imageSource = scripted(preview, sharp), download = download))

    openMenu()
    composeRule.onNodeWithText("Scarica sul telefono").performClick()
    composeRule.waitForIdle()

    assertThat(download.started).containsExactly(refAt(photoIndex))
  }

  // --- Lo stato e l'esito del download ------------------------------------------------------------------------------

  @Test
  fun inCodaEInCorsoMostranoLaNotaConAnnulla() {
    val download = FakePhoneDownload()
    val ref = refAt(photoIndex)
    show(request = nasRequest(photoIndex), services = services(imageSource = scripted(preview, sharp), download = download))

    download.set(ref.locationId, DownloadState.Queued)
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Scarico sul telefono").assertExists()
    assertThat(hasText("In coda: parte appena finisce l’altro scaricamento.")).isTrue()

    download.set(ref.locationId, DownloadState.Running(0.4f))
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Lo trovi in Pictures/MyCloud Gallery.").assertExists()
    composeRule.onNodeWithText("Annulla").performClick()
    composeRule.waitForIdle()

    assertThat(download.cancelled).containsExactly(ref)
  }

  @Test
  fun alTermineUnAvvisoDiceCheEFattoELEsitoSiScarta() {
    val download = FakePhoneDownload()
    val ref = refAt(photoIndex)
    show(request = nasRequest(photoIndex), services = services(imageSource = scripted(preview, sharp), download = download))

    download.set(ref.locationId, DownloadState.Done("vacanza.jpg"))
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Salvato sul telefono").assertExists()
    assertThat(download.acknowledged).containsExactly(ref.locationId)
  }

  @Test
  fun leRagioniDelloScaricamentoFallitoHannoCiascunaLaSuaFrase() {
    val cases = mapOf(
      DownloadFailure.PHONE_FULL to "Il telefono è pieno",
      DownloadFailure.NAS_UNREACHABLE to "Il NAS non risponde",
      DownloadFailure.INTEGRITY to "Il file è cambiato sul NAS",
      DownloadFailure.OTHER to "Non è stato possibile scaricarlo",
      DownloadFailure.NOT_FOUND to "Questo elemento non c’è più",
    )
    val download = FakePhoneDownload()
    val ref = refAt(photoIndex)
    show(request = nasRequest(photoIndex), services = services(imageSource = scripted(preview, sharp), download = download))

    for ((failure, text) in cases) {
      download.set(ref.locationId, DownloadState.Failed(failure))
      composeRule.waitForIdle()
      assertThat(hasText(text)).isTrue()
    }
    download.set(ref.locationId, DownloadState.Cancelled)
    composeRule.waitForIdle()
    assertThat(hasText("Scaricamento annullato")).isTrue()
  }

  @Test
  fun unEsitoGiaConclusoPrimaDiAprireNonSiMostra() {
    val download = FakePhoneDownload()
    val ref = refAt(photoIndex)
    download.set(ref.locationId, DownloadState.Done("vecchio.jpg"))

    show(request = nasRequest(photoIndex), services = services(imageSource = scripted(preview, sharp), download = download))

    assertThat(hasText("Salvato sul telefono")).isFalse()
    assertThat(download.acknowledged).containsExactly(ref.locationId)
  }

  @Test
  fun coiDatiMobiliIlDownloadChiedeLaConfermaConLaDimensione_EV2() {
    val download = FakePhoneDownload()
    val ref = refAt(photoIndex)
    var confirmed: MediaRef? = null
    show(
      request = nasRequest(photoIndex),
      services = services(imageSource = scripted(preview, sharp), download = download, confirmMobileData = { confirmed = it }),
    )

    download.set(ref.locationId, DownloadState.NeedsMobileData(5_000_000))
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Servono i dati mobili").assertExists()
    assertThat(hasText("Questo file pesa", substring = true)).isTrue()
    composeRule.onNodeWithText("Scarica con i dati mobili").performClick()
    composeRule.waitForIdle()

    assertThat(confirmed).isEqualTo(ref)
    assertThat(download.started).containsExactly(ref)
  }

  private fun hasText(text: String, substring: Boolean) =
    composeRule.onAllNodes(androidx.compose.ui.test.hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

  // --- Foto del NAS: irraggiungibile e dati mobili ---------------------------------------------------------------------

  @Test
  fun ilNasIrraggiungibileSiSpiegaEConRiprovaLaPaginaRiparte_EL4() {
    val source = scripted(preview, emit(ViewerImageState.Unavailable(UnavailableReason.NAS_UNREACHABLE, 4_000_000)))
    show(request = nasRequest(photoIndex), services = services(imageSource = source))

    composeRule.onNodeWithText("Il NAS non risponde").assertExists()
    assertThat(source.loadsOf(refAt(photoIndex))).isEqualTo(1)
    composeRule.onNodeWithText("Riprova").performClick()
    composeRule.waitForIdle()

    assertThat(source.loadsOf(refAt(photoIndex))).isEqualTo(2)
  }

  @Test
  fun unaFotoDelTelefonoNonHaRiprovaSullaNota() {
    val source = scripted(preview, emit(ViewerImageState.Unavailable(UnavailableReason.NAS_UNREACHABLE)))
    show(services = services(imageSource = source))

    composeRule.onNodeWithText("Il NAS non risponde").assertExists()
    assertThat(hasText("Riprova")).isFalse()
  }

  @Test
  fun accettareIDatiMobiliRegistraLaConfermaERicaricaLaPagina_EV2() {
    var confirmed: MediaRef? = null
    val source = scripted(preview, emit(ViewerImageState.Unavailable(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION, 12_400_000)))
    show(request = nasRequest(photoIndex), services = services(imageSource = source, confirmMobileData = { confirmed = it }))

    composeRule.onNodeWithText("Scarica con i dati mobili").performClick()
    composeRule.waitForIdle()

    assertThat(confirmed).isEqualTo(refAt(photoIndex))
    assertThat(source.loadsOf(refAt(photoIndex))).isEqualTo(2)
  }

  // --- Video del NAS ------------------------------------------------------------------------------------------------------

  private val nasFiles = InMemoryNasMedia().also { it.addShare("test") }
  private val scriptedReader = ScriptedNasReader(nasFiles.reader)
  private var metered = false
  private val consent = MobileDataConsent()

  private fun nasVideo(sizeBytes: Long = 1_200_000_000L): NasVideoSource = NasVideoSource(
    reader = scriptedReader,
    locator = { NasMediaLocation("test", SharePath.of("Video", "gita.mp4"), sizeBytes) },
    link = { metered },
    consent = consent,
  )

  private fun nasVideoServices(nas: NasVideoSource?, confirm: ((MediaRef) -> Unit)? = null) =
    services(imageSource = scripted(preview, sharp), nasVideo = nas, confirmMobileData = confirm)

  @Test
  fun unVideoDelNasPartePerStreamingQuandoLaPaginaSiFerma_D19() {
    show(request = nasRequest(videoIndex), services = nasVideoServices(nasVideo()))

    val load = player.loads.single()
    val source = load.source as PlaybackSource.Nas
    assertThat(source.ref).isEqualTo(refAt(videoIndex))
    assertThat(source.uri.scheme).isEqualTo("nasmedia")
    // L'indirizzo non contiene percorsi ne' nomi del NAS.
    assertThat(source.uri.toString()).doesNotContain("gita")
    assertThat(source.uri.toString()).doesNotContain("Video")
    assertThat(load.autoplay).isTrue()
    assertThat(load.muted).isFalse()
    // Il NAS non e' stato toccato dal viewer: lo fara' il lettore, a intervalli.
    assertThat(scriptedReader.opens.get()).isEqualTo(0)
  }

  @Test
  fun fintoAlPrimoFotogrammaUnaNotaDiceCheSiLegge() {
    show(request = nasRequest(videoIndex), services = nasVideoServices(nasVideo()))

    composeRule.onNodeWithText("Preparo il video").assertExists()

    player.emit { it.copy(firstFrame = true, playing = true) }
    composeRule.waitForIdle()

    assertThat(hasText("Preparo il video")).isFalse()
  }

  @Test
  fun coiDatiMobiliIlVideoAspettaLaConfermaConLaDimensione_EV2() {
    metered = true
    var confirmed: MediaRef? = null
    show(request = nasRequest(videoIndex), services = nasVideoServices(nasVideo(), confirm = { confirmed = it; consent.confirm(it) }))

    assertThat(player.loads).isEmpty()
    composeRule.onNodeWithText("Servono i dati mobili").assertExists()
    assertThat(hasText("Questo video pesa", substring = true)).isTrue()
    assertThat(hasText("Guardarlo con i dati mobili?", substring = true)).isTrue()

    composeRule.onNodeWithText("Guarda con i dati mobili").performClick()
    composeRule.waitForIdle()

    assertThat(confirmed).isEqualTo(refAt(videoIndex))
    assertThat(player.loads).hasSize(1)
    assertThat(hasText("Servono i dati mobili")).isFalse()
  }

  @Test
  fun unNasCheNonRispondeDuranteLaRiproduzioneSiSpiegaEConRiprovaRiparte_EL4() {
    show(request = nasRequest(videoIndex), services = nasVideoServices(nasVideo()))
    assertThat(player.loads).hasSize(1)

    player.failWith(PlaybackProblem.NAS_UNREACHABLE)
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Il NAS non risponde").assertExists()
    assertThat(hasText("Il video riparte quando il NAS è di nuovo raggiungibile.")).isTrue()

    composeRule.onNodeWithText("Riprova").performClick()
    composeRule.waitForIdle()

    // Il lettore si e' liberato e il video e' stato richiesto di nuovo, con lo stesso lettore.
    assertThat(player.loads).hasSize(2)
    assertThat(playersCreated).isEqualTo(1)
    assertThat(hasText("Riprova")).isFalse()
  }

  @Test
  fun senzaIlCollegamentoAlNasUnVideoDelNasDiceCheIlNasNonRisponde() {
    show(request = nasRequest(videoIndex), services = nasVideoServices(null))

    assertThat(player.loads).isEmpty()
    composeRule.onNodeWithText("Il NAS non risponde").assertExists()
  }

  @Test
  fun unVideoSparitoDalCatalogoDiceCheNonCePiu_EV6() {
    val gone = NasVideoSource(scriptedReader, { null }, link = { false })
    show(request = nasRequest(videoIndex), services = nasVideoServices(gone))

    assertThat(player.loads).isEmpty()
    composeRule.onNodeWithText("Questo elemento non c’è più").assertExists()
  }

  @Test
  fun unaFotoNonCreaIlLettoreNemmenoDalNas() {
    show(request = nasRequest(photoIndex), services = nasVideoServices(nasVideo()))

    assertThat(playersCreated).isEqualTo(0)
    assertThat(player.loads).isEmpty()
  }

  // --- L'originale chiesto con lo zoom ---------------------------------------------------------------------------------

  @Test
  fun l_OriginaleDelNasSiLeggeSoloQuandoSiIngrandisceEConLAvanzamento_D19() {
    val originals = FakeOriginals()
    val source = scripted(preview, sharp)
    show(request = nasRequest(photoIndex), services = services(imageSource = source, originals = originals))

    // La nitida (l'HQ) e' sullo schermo e il viewer non ha letto niente dal NAS.
    assertThat(originals.fetchCalls).isEqualTo(0)
    assertThat(source.loadsOf(refAt(photoIndex))).isEqualTo(1)

    composeRule.onNodeWithTag(ViewerTags.Pager).performTouchInput { doubleClick(center) }
    composeRule.waitForIdle()
    assertThat(originals.fetchCalls).isEqualTo(1)

    originals.events.trySend(OriginalFetch.Reading(0.4f))
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Carico l’originale").assertExists()

    // Arrivato: la nota sparisce e la pagina si rifa dall'originale (la nitida e, per le foto HDR, la gain map).
    originals.events.trySend(OriginalFetch.Ready(File("originale.jpg")))
    composeRule.waitForIdle()
    assertThat(hasText("Carico l’originale")).isFalse()
    assertThat(source.loadsOf(refAt(photoIndex))).isEqualTo(2)
  }

  @Test
  fun seIlNasNonRispondeAllIngrandimentoLaNotaDicePerche_EL4() {
    val originals = FakeOriginals()
    show(request = nasRequest(photoIndex), services = services(imageSource = scripted(preview, sharp), originals = originals))

    composeRule.onNodeWithTag(ViewerTags.Pager).performTouchInput { doubleClick(center) }
    composeRule.waitForIdle()
    originals.events.trySend(OriginalFetch.Blocked(UnavailableReason.NAS_UNREACHABLE, 9_000_000))
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Il NAS non risponde").assertExists()
    // La nitida resta, l'ingrandimento non si e' rotto.
    assertThat(hasText("Riprova")).isTrue()
  }

  @Test
  fun conLOriginaleGiaSulTelefonoIngrandireNonLeggeNiente() {
    val originals = FakeOriginals().apply { local = File("originale.jpg") }
    show(request = nasRequest(photoIndex), services = services(imageSource = scripted(preview, sharp), originals = originals))

    composeRule.onNodeWithTag(ViewerTags.Pager).performTouchInput { doubleClick(center) }
    composeRule.waitForIdle()

    assertThat(originals.fetchCalls).isEqualTo(0)
  }

  @Test
  fun unaFotoDelTelefonoIngrandisceSenzaChiedereNienteAlNas() {
    val originals = FakeOriginals()
    show(services = services(originals = originals))

    composeRule.onNodeWithTag(ViewerTags.Pager).performTouchInput { doubleClick(center) }
    composeRule.waitForIdle()

    assertThat(originals.fetchCalls).isEqualTo(0)
  }

  private companion object {
    const val TIMEOUT = 10_000L
  }
}
