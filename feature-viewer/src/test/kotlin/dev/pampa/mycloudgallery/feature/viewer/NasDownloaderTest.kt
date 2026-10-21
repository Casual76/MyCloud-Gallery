package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.viewer.testing.ScriptedNasReader
import dev.pampa.mycloudgallery.core.imaging.viewer.MobileDataConsent
import dev.pampa.mycloudgallery.core.imaging.viewer.NasLink
import dev.pampa.mycloudgallery.core.imaging.viewer.NasMediaLocation
import dev.pampa.mycloudgallery.core.imaging.viewer.NasNetwork
import dev.pampa.mycloudgallery.core.mediastore.contract.PendingCopy
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaWriter
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.viewer.download.CopyLinker
import dev.pampa.mycloudgallery.feature.viewer.download.DownloadFailure
import dev.pampa.mycloudgallery.feature.viewer.download.DownloadState
import dev.pampa.mycloudgallery.feature.viewer.download.NasDownloader
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Scarica sul telefono" (P8, D20, E-L2): dal NAS al telefono con lo stato di ogni passo. Il writer e' quello finto di P0
 * (che gia' garantisce "il collegamento prima del file visibile" e "niente a meta'"); qui si prova cio' che il
 * downloader ci mette: la coda, i dati mobili, l'integrita', l'annullamento e gli errori del NAS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NasDownloaderTest {

  private val nas = InMemoryNasMedia()
  private lateinit var reader: ScriptedNasReader
  private val writer = FakePhoneMediaWriter()
  private val locations = HashMap<Long, NasMediaLocation?>()
  private val linked = CopyOnWriteArrayList<Triple<Long, PendingCopy, Set<String>>>()
  private var linkFails = false
  private var metered = false
  private val consent = MobileDataConsent()

  @Before
  fun setUp() {
    nas.addShare("test")
    reader = ScriptedNasReader(nas.reader)
  }

  /**
   * Lo scope e' quello del test stesso, non `backgroundScope`: `advanceUntilIdle` non aspetta i lavori in background, e un
   * download che parte da `start` e' proprio il lavoro che il test vuole veder finire.
   */
  private fun TestScope.downloader(network: NasLink = NasLink { metered }) = NasDownloader(
    scope = this,
    reader = reader,
    locator = { locations[it.locationId] },
    writer = writer,
    linker = CopyLinker { ref, copy ->
      // Il file non e' ancora visibile al momento del collegamento (D20).
      linked += Triple(ref.locationId, copy, writer.visible.keys.toSet())
      if (linkFails) error("catalogo non scrive")
    },
    link = network,
    consent = consent,
  )

  private fun add(id: Long, name: String = "vacanza.jpg", size: Long = 300_000, sha: Sha256? = null): MediaRef.Nas {
    // I duplicati di nome stanno in cartelle diverse del NAS ("Foto/<id>/<nome>").
    val path = SharePath.of("Foto", "$id", name)
    nas.putFile("test", path.value, NasContent.virtual(size, seed = id))
    locations[id] = NasMediaLocation("test", path, size, knownSha256 = sha)
    return MediaRef.Nas(locationId = id, contentKey = id * 11, mime = "image/jpeg")
  }

  private fun contentOf(id: Long): ByteArray = nas.bytes("test", locations.getValue(id)!!.path.value)!!

  // --- La strada normale ---------------------------------------------------------------------------------------------

  @Test
  fun scaricaIlFileLoCollegaPrimaDiRenderloVisibileEDiceFatto() = runTest {
    val ref = add(1)
    reader.chunkDelayMs = 10
    val downloader = downloader()
    val seen = CopyOnWriteArrayList<DownloadState?>()
    val watcher = launch { downloader.states.collect { seen += it[1] } }
    runCurrent()

    downloader.start(ref)
    advanceUntilIdle()
    watcher.cancel()

    val done = downloader.states.value.getValue(1) as DownloadState.Done
    assertThat(done.displayName).isEqualTo("vacanza.jpg")
    assertThat(writer.visible.getValue("vacanza.jpg")).isEqualTo(contentOf(1))
    assertThat(writer.pendingLeft).isEqualTo(0)
    // Il collegamento e' arrivato quando il file non era ancora visibile, con la posizione e l'impronta dei byte arrivati.
    val (id, copy, visibleAtLink) = linked.single()
    assertThat(id).isEqualTo(1)
    assertThat(visibleAtLink).isEmpty()
    assertThat(copy.sha256).isEqualTo(Sha256.of(contentOf(1)))
    assertThat(copy.size).isEqualTo(300_000)
    // I passi: in coda, in corso (con l'avanzamento), fatto.
    assertThat(seen.filterNotNull().first()).isEqualTo(DownloadState.Queued)
    assertThat(seen.filterIsInstance<DownloadState.Running>().any { it.progress != null && it.progress!! > 0f }).isTrue()
    assertThat(seen.last()).isInstanceOf(DownloadState.Done::class.java)
    assertThat(reader.openFiles.get()).isEqualTo(0)
  }

  @Test
  fun dueDownloadVannoUnoDopoLAltroEUnNomeInConflittoDiventaConDue() = runTest {
    val a = add(1, name = "mare.jpg", size = 200_000)
    val b = add(2, name = "mare.jpg", size = 100_000)
    reader.chunkDelayMs = 100
    val downloader = downloader()

    downloader.start(a)
    downloader.start(b)
    runCurrent()
    advanceTimeBy(50)
    // Il primo e' in corso, il secondo aspetta il suo turno.
    assertThat(downloader.states.value.getValue(1)).isInstanceOf(DownloadState.Running::class.java)
    assertThat(downloader.states.value.getValue(2)).isEqualTo(DownloadState.Queued)

    advanceUntilIdle()

    assertThat((downloader.states.value.getValue(1) as DownloadState.Done).displayName).isEqualTo("mare.jpg")
    assertThat((downloader.states.value.getValue(2) as DownloadState.Done).displayName).isEqualTo("mare (2).jpg")
    assertThat(writer.visible.keys).containsExactly("mare.jpg", "mare (2).jpg")
    assertThat(writer.visible.getValue("mare (2).jpg")).isEqualTo(contentOf(2))
  }

  @Test
  fun unDownloadGiaInCodaOInCorsoNonSiRipete() = runTest {
    val ref = add(1)
    reader.chunkDelayMs = 50
    val downloader = downloader()

    downloader.start(ref)
    downloader.start(ref)
    runCurrent()
    downloader.start(ref)
    advanceUntilIdle()

    assertThat(reader.opens.get()).isEqualTo(1)
    assertThat(writer.visible).hasSize(1)
  }

  @Test
  fun lEsitoSiScartaConAcknowledgeESoloSeConcluso() = runTest {
    val ref = add(1)
    reader.chunkDelayMs = 50
    val downloader = downloader()

    downloader.start(ref)
    runCurrent()
    downloader.acknowledge(1)
    assertThat(downloader.states.value).containsKey(1L)

    advanceUntilIdle()
    assertThat(downloader.states.value.getValue(1).isTerminal).isTrue()
    downloader.acknowledge(1)
    assertThat(downloader.states.value).isEmpty()
  }

  @Test
  fun siScaricaSoloUnMediaDelNas() = runTest {
    val downloader = downloader()

    assertThat(downloader.canDownload(MediaRef.Nas(1, 1, null))).isTrue()
    assertThat(downloader.canDownload(MediaRef.Phone("external_primary", 1, "image/jpeg"))).isFalse()
    assertThat(downloader.canDownload(MediaRef.Synthetic(1, 10, 10))).isFalse()
  }

  // --- Interruzioni: mai un file a meta' -------------------------------------------------------------------------------

  @Test
  fun ilNasCheSiFermaAMetaNonLasciaNienteSulTelefono_F11() = runTest {
    val ref = add(1, size = 500_000)
    reader.failReadsAfterBytes = 200_000
    reader.failReadsWith = NasError.SessionExpired
    val downloader = downloader()

    downloader.start(ref)
    advanceUntilIdle()

    assertThat(downloader.states.value.getValue(1)).isEqualTo(DownloadState.Failed(DownloadFailure.NAS_UNREACHABLE))
    assertThat(writer.visible).isEmpty()
    assertThat(writer.pendingLeft).isEqualTo(0)
    assertThat(linked).isEmpty()
    assertThat(reader.openFiles.get()).isEqualTo(0)

    // Il NAS torna: si riprova e riesce, con il nome libero (nessun resto del tentativo prima).
    reader.failReadsAfterBytes = Long.MAX_VALUE
    downloader.acknowledge(1)
    downloader.start(ref)
    advanceUntilIdle()
    assertThat((downloader.states.value.getValue(1) as DownloadState.Done).displayName).isEqualTo("vacanza.jpg")
  }

  @Test
  fun ognErroreDelNasInAperturaHaLaSuaRagione() = runTest {
    val ref = add(1)
    val downloader = downloader()
    val cases = mapOf(
      NasError.Timeout to DownloadFailure.NAS_UNREACHABLE,
      NasError.ServiceDown to DownloadFailure.NAS_UNREACHABLE,
      NasError.NotFound to DownloadFailure.NOT_FOUND,
      NasError.NotAllowedHere(DenyReason.VPN_OFF_AWAY) to DownloadFailure.NOT_ALLOWED_HERE,
      NasError.LocalNetworkBlocked to DownloadFailure.NOT_ALLOWED_HERE,
    )
    for ((error, expected) in cases) {
      reader.openError = error
      downloader.acknowledge(1)
      downloader.start(ref)
      advanceUntilIdle()
      assertThat(downloader.states.value.getValue(1)).isEqualTo(DownloadState.Failed(expected))
    }
    assertThat(writer.visible).isEmpty()
  }

  @Test
  fun unMediaSparitoDalCatalogoENonTrovato_EV6() = runTest {
    val ref = add(1)
    locations[1] = null
    val downloader = downloader()

    downloader.start(ref)
    advanceUntilIdle()

    assertThat(downloader.states.value.getValue(1)).isEqualTo(DownloadState.Failed(DownloadFailure.NOT_FOUND))
    assertThat(reader.opens.get()).isEqualTo(0)
  }

  @Test
  fun annullareFermaLaLetturaEToglieTutto() = runTest {
    val ref = add(1, size = 500_000)
    reader.stallAfterBytes = 100_000
    val downloader = downloader()
    downloader.start(ref)
    advanceTimeBy(30_000)
    runCurrent()
    assertThat(downloader.states.value.getValue(1)).isInstanceOf(DownloadState.Running::class.java)
    assertThat(reader.openFiles.get()).isEqualTo(1)

    downloader.cancel(ref)
    advanceUntilIdle()

    assertThat(downloader.states.value.getValue(1)).isEqualTo(DownloadState.Cancelled)
    assertThat(reader.openFiles.get()).isEqualTo(0)
    // Niente di visibile. (La riga in attesa la toglie il writer vero: `MediaStorePhoneMediaWriterTest`; il finto di P0 non
    // gestisce l'annullamento e non e' cio' che qui si prova.)
    assertThat(writer.visible).isEmpty()

    // E si puo' ricominciare.
    reader.stallAfterBytes = Long.MAX_VALUE
    downloader.acknowledge(1)
    downloader.start(ref)
    advanceUntilIdle()
    assertThat(downloader.states.value.getValue(1)).isInstanceOf(DownloadState.Done::class.java)
  }

  @Test
  fun annullareUnoInCodaNonAprePerNienteIlNas() = runTest {
    val a = add(1, size = 200_000)
    val b = add(2, size = 200_000)
    reader.chunkDelayMs = 100
    val downloader = downloader()
    downloader.start(a)
    downloader.start(b)
    runCurrent()

    downloader.cancel(b)
    advanceUntilIdle()

    assertThat(downloader.states.value.getValue(2)).isEqualTo(DownloadState.Cancelled)
    assertThat(downloader.states.value.getValue(1)).isInstanceOf(DownloadState.Done::class.java)
    assertThat(reader.opens.get()).isEqualTo(1)
    assertThat(writer.visible).hasSize(1)
  }

  // --- Dati mobili, integrita', collegamento ---------------------------------------------------------------------------

  @Test
  fun coiDatiMobiliSenzaConfermaNonSiLeggeNienteEServeLaDimensione_EV2() = runTest {
    val ref = add(1, size = 700_000)
    metered = true
    val downloader = downloader()

    downloader.start(ref)
    advanceUntilIdle()

    assertThat(downloader.states.value.getValue(1)).isEqualTo(DownloadState.NeedsMobileData(700_000))
    assertThat(reader.opens.get()).isEqualTo(0)
    assertThat(writer.visible).isEmpty()

    consent.confirm(ref)
    downloader.acknowledge(1)
    downloader.start(ref)
    advanceUntilIdle()
    assertThat(downloader.states.value.getValue(1)).isInstanceOf(DownloadState.Done::class.java)
  }

  /**
   * D-15: con la VPN spenta fuori casa il NAS non si raggiunge: il download lo dice (non "servono i dati mobili", che non servirebbero a niente) e non
   * apre il NAS. Con i dati mobili e la VPN accesa chiede la conferma, con il Wi-Fi parte; "servono i dati mobili" rimasto si scarta con `acknowledge`.
   */
  @Test
  fun d15_ilDownloadSegueLaReteDiAdessoEPrimaVieneIlPercorso() = runTest {
    val ref = add(1, size = 700_000)
    val link = ChangingLink(NasNetwork.VPN_OFF)
    val downloader = downloader(link)

    downloader.start(ref)
    advanceUntilIdle()
    assertThat(downloader.states.value.getValue(1)).isEqualTo(DownloadState.Failed(DownloadFailure.VPN_OFF_AWAY))
    consent.confirm(ref)
    downloader.acknowledge(1)
    downloader.start(ref)
    advanceUntilIdle()
    assertThat(downloader.states.value.getValue(1)).isEqualTo(DownloadState.Failed(DownloadFailure.VPN_OFF_AWAY))
    link.value = NasNetwork.OFFLINE
    downloader.acknowledge(1)
    downloader.start(ref)
    advanceUntilIdle()
    assertThat(downloader.states.value.getValue(1)).isEqualTo(DownloadState.Failed(DownloadFailure.NAS_UNREACHABLE))
    link.value = NasNetwork.NOT_ALLOWED
    downloader.acknowledge(1)
    downloader.start(ref)
    advanceUntilIdle()
    assertThat(downloader.states.value.getValue(1)).isEqualTo(DownloadState.Failed(DownloadFailure.NOT_ALLOWED_HERE))
    assertThat(reader.opens.get()).isEqualTo(0)
    assertThat(writer.visible).isEmpty()

    // Sui dati mobili senza conferma: la dimensione e l'attesa. Il Wi-Fi torna: l'attesa si scarta e il download riparte senza conferma.
    consent.reset()
    link.value = NasNetwork.METERED
    downloader.acknowledge(1)
    downloader.start(ref)
    advanceUntilIdle()
    assertThat(downloader.states.value.getValue(1)).isEqualTo(DownloadState.NeedsMobileData(700_000))
    link.value = NasNetwork.OPEN
    downloader.acknowledge(1)
    assertThat(downloader.states.value).doesNotContainKey(1L)
    downloader.start(ref)
    advanceUntilIdle()
    assertThat(downloader.states.value.getValue(1)).isInstanceOf(DownloadState.Done::class.java)
  }

  @Test
  fun unHashDelIndiceDiversoDaQuelloArrivatoNonDiventaUnFile() = runTest {
    val wrong = add(1, sha = Sha256.of(ByteArray(5)))
    val right = add(2)
    // L'indice conosce l'hash giusto di questo (calcolato dai byte che stanno sul NAS).
    locations[2] = locations.getValue(2)!!.let { NasMediaLocation(it.share, it.path, it.sizeBytes, knownSha256 = Sha256.of(contentOf(2))) }
    val downloader = downloader()

    downloader.start(wrong)
    downloader.start(right)
    advanceUntilIdle()

    assertThat(downloader.states.value.getValue(1)).isEqualTo(DownloadState.Failed(DownloadFailure.INTEGRITY))
    assertThat(downloader.states.value.getValue(2)).isInstanceOf(DownloadState.Done::class.java)
    assertThat(writer.visible).hasSize(1)
  }

  @Test
  fun seIlCatalogoRifiutaIlCollegamentoIlFileNonCompare() = runTest {
    val ref = add(1)
    linkFails = true
    val downloader = downloader()

    downloader.start(ref)
    advanceUntilIdle()

    assertThat(downloader.states.value.getValue(1)).isEqualTo(DownloadState.Failed(DownloadFailure.OTHER))
    assertThat(writer.visible).isEmpty()
    assertThat(downloader.states.value.getValue(1).toString()).doesNotContain("vacanza")
  }

  @Test
  fun lEsitoFatoNonPortaIlNomeNeiLog() = runTest {
    val ref = add(1, name = "segreto.jpg")
    val downloader = downloader()
    downloader.start(ref)
    advanceUntilIdle()

    assertThat(downloader.states.value.getValue(1).toString()).doesNotContain("segreto")
  }
}
