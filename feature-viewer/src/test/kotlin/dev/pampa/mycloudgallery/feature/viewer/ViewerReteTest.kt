package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.imaging.viewer.MobileDataConsent
import dev.pampa.mycloudgallery.core.imaging.viewer.NasMediaLocation
import dev.pampa.mycloudgallery.core.imaging.viewer.NasNetwork
import dev.pampa.mycloudgallery.core.imaging.viewer.testing.ScriptedNasReader
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.viewer.download.DownloadState
import dev.pampa.mycloudgallery.feature.viewer.video.NasVideoSource
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackProblem
import org.junit.Test

/**
 * **D-15** (prove sul campo, `docs/prove-campo-esiti.md`, "Blocco A, resto"): con il Wi-Fi spento il viewer di un video solo-NAS chiedeva "Servono i
 * dati mobili", e **tornato il Wi-Fi il messaggio restava** (dopo 10 e 40 secondi); passava solo chiudendo e riaprendo. Inoltre lo proponeva anche
 * senza VPN, quando il NAS non si raggiunge proprio e i dati mobili non servirebbero a niente.
 *
 * Adesso lo stato del viewer segue la rete di adesso ([dev.pampa.mycloudgallery.core.imaging.viewer.NasLink]): senza VPN fuori casa dice che il NAS non
 * e' raggiungibile (e non propone i dati mobili); a ogni cambio di rete le pagine ferme per colpa sua si rifanno da sole; una pagina che funziona non si
 * tocca. Le sorgenti sono finte, la rete cambia sotto il viewer con [ChangingLink].
 */
class ViewerReteTest : ViewerTestBase() {

  override val fixture: ViewerFixture = ViewerFixture(count = 400)

  private val photoIndex: Int get() = (0 until fixture.library.count).first { fixture.library.snapshot.kindAt(it) == MediaKind.PHOTO }
  private val videoIndex: Int get() = fixture.indicesOf(MediaKind.VIDEO).first()

  private fun nasRequest(index: Int): ViewerRequest {
    fixture.placeCell(index)
    return ViewerRequest(items = NasItems(fixture.library.view(), fixture), start = fixture.idAt(index), anchor = fixture.anchor, secure = false)
  }

  private fun refAt(index: Int) = NasItems(fixture.library.view(), fixture).ref(fixture.idAt(index)) as MediaRef.Nas

  private fun hasText(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

  private val nasFiles = InMemoryNasMedia().also { it.addShare("test") }
  private val scriptedReader = ScriptedNasReader(nasFiles.reader)
  private val consent = MobileDataConsent()

  /** Il video solo-NAS dell'area di prova: 2,68 GB, come `VID_mcg_grande_2.mp4`. */
  private fun nasVideo(link: ChangingLink): NasVideoSource = NasVideoSource(
    reader = scriptedReader,
    locator = { NasMediaLocation("test", SharePath.of("Video", "gita.mp4"), 2_680_000_000L) },
    link = link,
    consent = consent,
  )

  private fun videoServices(link: ChangingLink, confirm: ((MediaRef) -> Unit)? = null) =
    services(imageSource = ScriptedImages { listOf(ScriptedImages.preview(it), ScriptedImages.sharp(it)) }, nasVideo = nasVideo(link), link = link, confirmMobileData = confirm)

  private fun waitFor(condition: () -> Boolean) {
    composeRule.waitForIdle()
    composeRule.waitUntil(TIMEOUT, condition)
  }

  // --- Video ---------------------------------------------------------------------------------------------------------------

  /** Il caso del campo: fuori casa, VPN spenta, dati mobili accesi. Il NAS non si raggiunge: la nota lo dice e non offre i dati mobili. */
  @Test
  fun d15_senzaVpnFuoriCasaIlVideoDiceCheIlNasNonSiRaggiungeENonProponeIDatiMobili() {
    val link = ChangingLink(NasNetwork.VPN_OFF)
    show(request = nasRequest(videoIndex), services = videoServices(link, confirm = { consent.confirm(it) }))

    assertThat(player.loads).isEmpty()
    composeRule.onNodeWithText("NAS non raggiungibile fuori casa").assertExists()
    assertThat(hasText("VPN spenta", substring = true)).isTrue()
    assertThat(hasText("Servono i dati mobili")).isFalse()
    assertThat(hasText("Guarda con i dati mobili")).isFalse()
    // Si puo' riprovare a mano (dopo aver acceso la VPN), come per ogni NAS che non risponde.
    assertThat(hasText("Riprova")).isTrue()
    assertThat(scriptedReader.opens.get()).isEqualTo(0)
  }

  /** Il difetto: tornato il Wi-Fi, il video parte da solo e "Servono i dati mobili" non resta. */
  @Test
  fun d15_tornatoIlWifiIlVideoParteDaSoloENonRestaSuServonoIDatiMobili() {
    val link = ChangingLink(NasNetwork.METERED)
    show(request = nasRequest(videoIndex), services = videoServices(link, confirm = { consent.confirm(it) }))
    composeRule.onNodeWithText("Servono i dati mobili").assertExists()
    assertThat(player.loads).isEmpty()

    link.value = NasNetwork.OPEN
    waitFor { player.loads.isNotEmpty() }

    assertThat(player.loads).hasSize(1)
    assertThat(hasText("Servono i dati mobili")).isFalse()
    assertThat(hasText("Guarda con i dati mobili")).isFalse()
  }

  /** Senza VPN, poi la VPN si accende ma sui dati mobili (la nota cambia, con la conferma), poi torna il Wi-Fi (parte). */
  @Test
  fun d15_lanotaSegueLaReteInOgniPassaggio() {
    val link = ChangingLink(NasNetwork.VPN_OFF)
    show(request = nasRequest(videoIndex), services = videoServices(link, confirm = { consent.confirm(it) }))
    composeRule.onNodeWithText("NAS non raggiungibile fuori casa").assertExists()

    link.value = NasNetwork.METERED
    waitFor { hasText("Servono i dati mobili") }
    assertThat(hasText("NAS non raggiungibile fuori casa")).isFalse()
    assertThat(hasText("Guarda con i dati mobili")).isTrue()
    assertThat(player.loads).isEmpty()

    link.value = NasNetwork.OFFLINE
    waitFor { hasText("Nessuna rete") }
    assertThat(hasText("Servono i dati mobili")).isFalse()

    link.value = NasNetwork.NOT_ALLOWED
    waitFor { hasText("Qui non si aprono i file del NAS") }
    assertThat(hasText("Nessuna rete")).isFalse()

    link.value = NasNetwork.OPEN
    waitFor { player.loads.isNotEmpty() }
    assertThat(player.loads).hasSize(1)
    assertThat(hasText("Qui non si aprono i file del NAS")).isFalse()
  }

  /** Una pagina che funziona non si tocca: un video che va non riparte a ogni cambio di rete. */
  @Test
  fun d15_unCambioDiReteNonRilanciaUnVideoCheVa() {
    val link = ChangingLink(NasNetwork.OPEN)
    show(request = nasRequest(videoIndex), services = videoServices(link))
    assertThat(player.loads).hasSize(1)

    for (network in listOf(NasNetwork.METERED, NasNetwork.OPEN, NasNetwork.VPN_OFF, NasNetwork.OPEN)) {
      link.value = network
      composeRule.waitForIdle()
    }

    assertThat(player.loads).hasSize(1)
    assertThat(playersCreated).isEqualTo(1)
  }

  /** Un video fermo da un errore di rete ("Il NAS non risponde") riparte quando il NAS si puo' provare di nuovo; non mentre la rete e' assente. */
  @Test
  fun d15_unVideoFermoDaUnErroreDiReteRipartePerCheLaReteTorna() {
    val link = ChangingLink(NasNetwork.OPEN)
    show(request = nasRequest(videoIndex), services = videoServices(link))
    player.failWith(PlaybackProblem.NAS_UNREACHABLE)
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Il NAS non risponde").assertExists()
    assertThat(player.loads).hasSize(1)

    // La rete va via del tutto: il video resta com'e' (non c'e' niente da riprovare).
    link.value = NasNetwork.OFFLINE
    composeRule.waitForIdle()
    assertThat(player.loads).hasSize(1)

    link.value = NasNetwork.OPEN
    waitFor { player.loads.size == 2 }
    assertThat(player.loads).hasSize(2)
    assertThat(hasText("Il NAS non risponde")).isFalse()
  }

  // --- Foto ----------------------------------------------------------------------------------------------------------------

  /** La nitida di una foto del NAS ferma sui dati mobili si rifa da sola quando torna il Wi-Fi. */
  @Test
  fun d15_unaFotoFermaSuiDatiMobiliSiRifaQuandoTornaIlWifi() {
    val link = ChangingLink(NasNetwork.METERED)
    var open = false
    val source = ScriptedImages { ref ->
      if (open) {
        listOf(ScriptedImages.preview(ref), ScriptedImages.sharp(ref))
      } else {
        listOf(ScriptedImages.preview(ref), Step.Emit(ViewerImageState.Unavailable(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION, 12_400_000)))
      }
    }
    show(request = nasRequest(photoIndex), services = services(imageSource = source, link = link))
    composeRule.onNodeWithText("Servono i dati mobili").assertExists()
    val ref = refAt(photoIndex)
    assertThat(source.requested.count { it == ref }).isEqualTo(1)

    open = true
    link.value = NasNetwork.OPEN
    waitFor { source.requested.count { it == ref } == 2 }

    assertThat(source.requested.count { it == ref }).isEqualTo(2)
    assertThat(hasText("Servono i dati mobili")).isFalse()
  }

  /** Una foto ferma perche' la VPN e' spenta dice la cosa vera, senza il tasto dei dati mobili. */
  @Test
  fun d15_unaFotoSenzaVpnDiceCheServeLaVpn() {
    val source = ScriptedImages { listOf(ScriptedImages.preview(it), Step.Emit(ViewerImageState.Unavailable(UnavailableReason.VPN_OFF_AWAY, 12_400_000))) }
    show(request = nasRequest(photoIndex), services = services(imageSource = source, link = ChangingLink(NasNetwork.VPN_OFF)))

    composeRule.onNodeWithText("NAS non raggiungibile fuori casa").assertExists()
    assertThat(hasText("Servono i dati mobili")).isFalse()
  }

  // --- "Scarica sul telefono" ------------------------------------------------------------------------------------------------

  /** L'attesa dei dati mobili di un download non resta quando torna il Wi-Fi: non e' piu' vera. Non parte da sola: la prossima richiesta la rivaluta. */
  @Test
  fun d15_unaAttesaDeiDatiMobiliDiUnDownloadSiScartaQuandoTornaIlWifi() {
    val download = FakePhoneDownload()
    val link = ChangingLink(NasNetwork.METERED)
    val ref = refAt(photoIndex)
    show(
      request = nasRequest(photoIndex),
      services = services(imageSource = ScriptedImages { listOf(ScriptedImages.preview(it), ScriptedImages.sharp(it)) }, download = download, link = link),
    )
    download.set(ref.locationId, DownloadState.NeedsMobileData(5_000_000))
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Servono i dati mobili").assertExists()

    link.value = NasNetwork.OPEN
    waitFor { !hasText("Servono i dati mobili") }

    assertThat(download.acknowledged).contains(ref.locationId)
    assertThat(download.started).isEmpty()
  }

  /** Un "servono i dati mobili" rimasto da prima di aprire il viewer non si mostra se la rete di adesso non e' a consumo. */
  @Test
  fun d15_unaAttesaRimastaDaPrimaNonSiMostraSeLaReteNonEAConsumo() {
    val download = FakePhoneDownload()
    val ref = refAt(photoIndex)
    download.set(ref.locationId, DownloadState.NeedsMobileData(5_000_000))

    show(
      request = nasRequest(photoIndex),
      services = services(imageSource = ScriptedImages { listOf(ScriptedImages.preview(it), ScriptedImages.sharp(it)) }, download = download, link = ChangingLink(NasNetwork.OPEN)),
    )

    assertThat(hasText("Servono i dati mobili")).isFalse()
    assertThat(download.acknowledged).containsExactly(ref.locationId)
  }

  private fun hasText(text: String, substring: Boolean) =
    composeRule.onAllNodes(androidx.compose.ui.test.hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

  private companion object {
    const val TIMEOUT = 10_000L
  }
}
