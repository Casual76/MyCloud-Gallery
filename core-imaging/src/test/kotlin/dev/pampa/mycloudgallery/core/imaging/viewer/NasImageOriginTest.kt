package dev.pampa.mycloudgallery.core.imaging.viewer

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.viewer.testing.ScriptedNasReader
import dev.pampa.mycloudgallery.core.index.testing.InMemoryReplica
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * L'origine dei byte del NAS per il viewer (P8, D19; E-V1, E-V2, E-V5, E-V6, E-L4) con sorgenti **lente, ferme e
 * irraggiungibili**: tempo virtuale, un NAS finto in memoria avvolto in [ScriptedNasReader]. La decodifica vera di cio'
 * che esce sta in [NasViewerImagesTest].
 */
class NasImageOriginTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val nas = InMemoryNasMedia()
  private lateinit var reader: ScriptedNasReader
  private lateinit var store: NasOriginalStore
  private val locations = HashMap<Long, NasMediaLocation?>()
  private val replica = InMemoryReplica()
  private val consent = MobileDataConsent()
  private var metered = false

  @Before
  fun setUp() {
    nas.addShare("test")
    reader = ScriptedNasReader(nas.reader)
    store = NasOriginalStore(folder.newFolder("viewer-nas"), maxBytes = 64L shl 20) { Long.MAX_VALUE }
  }

  private fun TestScope.dispatchers(): DispatcherProvider {
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    return object : DispatcherProvider {
      override val main = dispatcher
      override val io = dispatcher
      override val default = dispatcher
    }
  }

  private fun TestScope.origin(max: Long = NasImageOrigin.DEFAULT_MAX_ORIGINAL_BYTES, link: NasLink = NasLink { metered }) = NasImageOrigin(
    reader = reader,
    locator = { locations[it.locationId] },
    store = store,
    replica = replica,
    link = link,
    consent = consent,
    dispatchers = dispatchers(),
    maxOriginalBytes = max,
  )

  private val parts: File get() = store.let { File(folder.root, "viewer-nas") }

  private fun leftovers(): List<String> = parts.listFiles()!!.map { it.name }.filter { it.endsWith(".part") }

  private fun contentKeyOf(id: Long) = ContentKey("%064x".format(id))

  /** Un media del NAS: il file nella share, la sua posizione nel catalogo e il riferimento della Libreria. */
  private suspend fun add(id: Long, size: Int = 400_000, mime: String? = "image/jpeg"): MediaRef.Nas {
    val path = SharePath.of("Foto", "p$id.jpg")
    nas.putFile("test", path.value, NasContent.virtual(size.toLong(), seed = id))
    val stat = (nas.reader.stat("test", path, Purpose.INTERACTIVE) as NasResult.Ok).value!!
    locations[id] = NasMediaLocation("test", path, size.toLong(), stat.stamp, width = 4000, height = 3000)
    return MediaRef.Nas(locationId = id, contentKey = contentKeyOf(id).short, mime = mime)
  }

  // --- La strada normale ------------------------------------------------------------------------------------------------

  @Test
  fun senzaHqLOriginaleSiLeggeInUnTemporaneoConLAvanzamentoERestaPerLaVoltaDopo() = runTest {
    val ref = add(1, size = 400_000)
    val origin = origin()
    val progress = CopyOnWriteArrayList<Float?>()

    val first = origin.open(ref) { progress += it } as OriginResult.Opened

    assertThat(first.isOriginal).isTrue()
    assertThat(first.bytes.sizeBytes).isEqualTo(400_000L)
    first.bytes.openStream().use { assertThat(it.readBytes()).isEqualTo(nas.bytes("test", "Foto/p1.jpg")) }
    assertThat(progress).isNotEmpty()
    assertThat(progress.filterNotNull()).isInOrder()
    assertThat(progress.last()).isEqualTo(1f)
    assertThat(leftovers()).isEmpty()
    assertThat(store.count).isEqualTo(1)
    val bytesAfterFirst = reader.bytesDelivered.get()

    // La volta dopo: dal temporaneo, senza toccare il NAS.
    val second = origin.open(ref) as OriginResult.Opened
    assertThat(second.isOriginal).isTrue()
    assertThat(reader.opens.get()).isEqualTo(1)
    assertThat(reader.bytesDelivered.get()).isEqualTo(bytesAfterFirst)
    assertThat(reader.openFiles.get()).isEqualTo(0)
    first.bytes.close()
    second.bytes.close()
  }

  @Test
  fun conLHqNellaReplicaSiApreSubitoAncheOfflineENonSiTocaIlNas_EV1_EL4() = runTest {
    val ref = add(2)
    replica.putBlob(contentKeyOf(2), SegmentClass.HQ, ByteArray(5_000) { it.toByte() })
    reader.openError = NasError.Timeout
    metered = true
    val origin = origin()

    val opened = origin.open(ref) as OriginResult.Opened

    assertThat(opened.isOriginal).isFalse()
    assertThat(opened.bytes.sizeBytes).isEqualTo(5_000L)
    assertThat(opened.bytes.openStream().readBytes()).isEqualTo(ByteArray(5_000) { it.toByte() })
    // Nessuna rete, nessun dato mobile consumato, nessuna conferma chiesta.
    assertThat(reader.opens.get()).isEqualTo(0)
    assertThat(origin.localFile(ref)).isNull()
    opened.bytes.close()
  }

  @Test
  fun lHqNonDecideLoZoomMaLeMisureDelCatalogoSi() = runTest {
    val ref = add(3)
    replica.putBlob(contentKeyOf(3), SegmentClass.HQ, ByteArray(100))
    val origin = origin()
    assertThat(origin.declaredSize(ref)).isNull()

    origin.open(ref)

    val declared = origin.declaredSize(ref)!!
    assertThat(declared.width).isEqualTo(4000)
    assertThat(declared.height).isEqualTo(3000)
    assertThat(origin.declaredSize(MediaRef.Phone("external_primary", 1, "image/jpeg"))).isNull()
  }

  // --- Lenta, ferma, irraggiungibile ------------------------------------------------------------------------------------

  @Test
  fun unaSorgenteLentaTieneAperteLeRispostePiuVeloci_EV1() = runTest {
    val ref = add(4, size = 256 * 1024)
    reader.openDelayMs = 500
    reader.chunkDelayMs = 100
    reader.readAllChunk = 64 * 1024
    val origin = origin()
    val progress = CopyOnWriteArrayList<Pair<Long, Float?>>()
    var result: OriginResult? = null

    val job = launch { result = origin.open(ref) { progress += currentTime to it } }
    runCurrent()
    assertThat(result).isNull()
    advanceTimeBy(499)
    assertThat(progress).isEmpty()

    // 500 ms per aprire, poi un blocco da 64 KiB ogni 100 ms: i quarti arrivano a 600, 700, 800, 900.
    advanceTimeBy(250)
    assertThat(progress.map { it.second }).containsExactly(0.25f, 0.5f)
    assertThat(result).isNull()
    advanceUntilIdle()
    job.join()

    assertThat(result).isInstanceOf(OriginResult.Opened::class.java)
    assertThat(progress.last().second).isEqualTo(1f)
    assertThat(currentTime).isAtLeast(900L)
    (result as OriginResult.Opened).bytes.close()
  }

  @Test
  fun unaSorgenteFermaSiAnnullaEnonLasciaNienteAperto_EV5() = runTest {
    val ref = add(5, size = 512 * 1024)
    reader.stallAfterBytes = 128 * 1024
    val origin = origin()
    var finished = false
    val job = launch {
      origin.open(ref)
      finished = true
    }

    advanceTimeBy(60_000)
    runCurrent()
    // Ferma da un minuto: la pagina e' ancora in attesa, con un file aperto e un .part a meta'.
    assertThat(finished).isFalse()
    assertThat(reader.openFiles.get()).isEqualTo(1)
    assertThat(leftovers()).hasSize(1)

    // Il pager lascia la pagina: la lettura si annulla, il NAS si chiude, il temporaneo sparisce.
    job.cancel()
    job.join()

    assertThat(finished).isFalse()
    assertThat(reader.openFiles.get()).isEqualTo(0)
    assertThat(leftovers()).isEmpty()
    assertThat(store.count).isEqualTo(0)
    assertThat(origin.localFile(ref)).isNull()
  }

  @Test
  fun ilSorveglianteCheDiceStalledDiventaIlNasNonRisponde() = runTest {
    val ref = add(6, size = 512 * 1024)
    reader.stallAfterBytes = 128 * 1024
    reader.stallError = NasError.Stalled
    reader.stallErrorAfterMs = 20_000
    val origin = origin()
    var result: OriginResult? = null

    val job = launch { result = origin.open(ref) }
    advanceUntilIdle()
    job.join()

    val unreachable = result as OriginResult.Unreachable
    assertThat(unreachable.reason).isEqualTo(UnavailableReason.NAS_UNREACHABLE)
    assertThat(unreachable.sizeBytes).isEqualTo(512L * 1024)
    assertThat(currentTime).isAtLeast(20_000L)
    assertThat(reader.openFiles.get()).isEqualTo(0)
    assertThat(leftovers()).isEmpty()
  }

  @Test
  fun unaLetturaCheCadeAMetaVaInIrraggiungibileSenzaTemporanei() = runTest {
    val ref = add(7, size = 512 * 1024)
    reader.failReadsAfterBytes = 200 * 1024
    reader.failReadsWith = NasError.SessionExpired
    val origin = origin()

    val result = origin.open(ref)

    assertThat((result as OriginResult.Unreachable).reason).isEqualTo(UnavailableReason.NAS_UNREACHABLE)
    assertThat(reader.openFiles.get()).isEqualTo(0)
    assertThat(leftovers()).isEmpty()
    assertThat(store.count).isEqualTo(0)
  }

  @Test
  fun ilNasIrraggiungibileEIlMediaSolo_EL4() = runTest {
    val ref = add(8)
    val origin = origin()
    for (error in listOf(NasError.Timeout, NasError.ServiceDown, NasError.SessionExpired, NasError.PermissionDenied, NasError.FileBusy)) {
      reader.openError = error
      val result = origin.open(ref) as OriginResult.Unreachable
      assertThat(result.reason).isEqualTo(UnavailableReason.NAS_UNREACHABLE)
      // La dimensione accompagna sempre la nota: il viewer la mostra.
      assertThat(result.sizeBytes).isEqualTo(400_000L)
    }
    reader.openError = NasError.NotAllowedHere(DenyReason.VPN_OFF_AWAY)
    assertThat((origin.open(ref) as OriginResult.Unreachable).reason).isEqualTo(UnavailableReason.NOT_ALLOWED_HERE)
    reader.openError = NasError.LocalNetworkBlocked
    assertThat((origin.open(ref) as OriginResult.Unreachable).reason).isEqualTo(UnavailableReason.NOT_ALLOWED_HERE)
    assertThat(leftovers()).isEmpty()
  }

  // --- Media sparito, E-V6 ----------------------------------------------------------------------------------------------

  @Test
  fun unMediaSparitoDalCatalogoODalNasEMissing_EV6() = runTest {
    val gone = add(9)
    val origin = origin()

    locations[9] = null
    assertThat(origin.open(gone)).isEqualTo(OriginResult.Missing)

    val removed = add(10)
    nas.delete("test", "Foto/p10.jpg")
    assertThat(origin.open(removed)).isEqualTo(OriginResult.Missing)
    assertThat(origin.open(MediaRef.Phone("external_primary", 1, "image/jpeg"))).isEqualTo(OriginResult.Missing)
    assertThat(leftovers()).isEmpty()
  }

  @Test
  fun unLocatorCheLanciaNonFaCadereIlViewer() = runTest {
    val origin = NasImageOrigin(reader, { error("catalogo non risponde") }, store, dispatchers = dispatchers())

    val result = origin.open(MediaRef.Nas(1, 1, "image/jpeg"))

    assertThat((result as OriginResult.Failed).code).isEqualTo(ImageFailure.DECODE_FAILED)
  }

  // --- Dati mobili, E-V2 ------------------------------------------------------------------------------------------------

  @Test
  fun conIDatiMobiliSenzaConfermaNonSiLeggeNienteEServeLaDimensione_EV2() = runTest {
    val ref = add(11, size = 2_000_000)
    val other = add(12, size = 3_000_000)
    metered = true
    val origin = origin()

    val blocked = origin.open(ref) as OriginResult.Unreachable

    assertThat(blocked.reason).isEqualTo(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION)
    assertThat(blocked.sizeBytes).isEqualTo(2_000_000L)
    assertThat(reader.opens.get()).isEqualTo(0)

    // La conferma vale per quel media, non per gli altri.
    consent.confirm(ref)
    assertThat(origin.open(ref)).isInstanceOf(OriginResult.Opened::class.java)
    assertThat(reader.opens.get()).isEqualTo(1)
    val stillBlocked = origin.open(other) as OriginResult.Unreachable
    assertThat(stillBlocked.sizeBytes).isEqualTo(3_000_000L)
    assertThat(reader.opens.get()).isEqualTo(1)

    // Finita la sessione la conferma va rifatta.
    consent.reset()
    store.clear()
    assertThat((origin.open(ref) as OriginResult.Unreachable).reason).isEqualTo(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION)
  }

  @Test
  fun unOriginaleGiaSulTelefonoNonChiedeConfermaAncheConIDatiMobili() = runTest {
    val ref = add(13)
    val origin = origin()
    origin.open(ref)
    metered = true

    assertThat(origin.open(ref)).isInstanceOf(OriginResult.Opened::class.java)
  }

  // --- D-15: la rete di adesso, non solo "a consumo" ----------------------------------------------------------------------

  /** Un collegamento il cui percorso si decide dal test (e che e' a consumo solo se la rete e' [NasNetwork.METERED]). */
  private class FixedNetwork(var value: NasNetwork) : NasLink {
    override fun isMetered(): Boolean = value == NasNetwork.METERED

    override fun network(): NasNetwork = value
  }

  /**
   * D-15 delle prove sul campo: fuori casa con la VPN spenta e sui dati mobili l'app proponeva "Servono i dati mobili", ma il NAS non si raggiunge
   * proprio e i dati mobili non servirebbero a niente. Prima il percorso: il motivo e' la VPN (o la rete assente, o non consentita), la dimensione
   * resta, il NAS non si tocca nemmeno con la conferma dei dati mobili.
   */
  @Test
  fun d15_senzaVpnFuoriCasaNonSiProponeroIDatiMobiliEIlNasNonSiTocca() = runTest {
    val ref = add(41, size = 2_000_000)
    val link = FixedNetwork(NasNetwork.VPN_OFF)
    metered = true // i dati mobili sono accesi, ma non e' quello il punto
    val origin = origin(link = link)

    val blocked = origin.open(ref) as OriginResult.Unreachable
    assertThat(blocked.reason).isEqualTo(UnavailableReason.VPN_OFF_AWAY)
    assertThat(blocked.sizeBytes).isEqualTo(2_000_000L)
    assertThat(origin.fetch(ref).toList().last()).isEqualTo(OriginalFetch.Blocked(UnavailableReason.VPN_OFF_AWAY, 2_000_000L))
    // Confermare i dati mobili non cambia niente: il NAS non si raggiunge.
    consent.confirm(ref)
    assertThat((origin.open(ref) as OriginResult.Unreachable).reason).isEqualTo(UnavailableReason.VPN_OFF_AWAY)
    assertThat(reader.opens.get()).isEqualTo(0)

    for ((network, reason) in listOf(
      NasNetwork.OFFLINE to UnavailableReason.OFFLINE,
      NasNetwork.NOT_ALLOWED to UnavailableReason.NOT_ALLOWED_HERE,
    )) {
      link.value = network
      assertThat((origin.open(ref) as OriginResult.Unreachable).reason).isEqualTo(reason)
    }
    assertThat(reader.opens.get()).isEqualTo(0)
  }

  /** D-15: tornata la rete l'originale si legge (con i dati mobili solo dopo la conferma, come prima); lo stato e' quello di adesso. */
  @Test
  fun d15_tornataLaReteLOriginaleSiLegge() = runTest {
    val ref = add(42, size = 300_000)
    val link = FixedNetwork(NasNetwork.VPN_OFF)
    val origin = origin(link = link)
    assertThat((origin.open(ref) as OriginResult.Unreachable).reason).isEqualTo(UnavailableReason.VPN_OFF_AWAY)

    // La VPN si accende, ma sui dati mobili: servono la conferma e la dimensione (E-V2).
    link.value = NasNetwork.METERED
    assertThat((origin.open(ref) as OriginResult.Unreachable).reason).isEqualTo(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION)
    assertThat(reader.opens.get()).isEqualTo(0)

    // Torna il Wi-Fi: si legge, senza nessuna conferma.
    link.value = NasNetwork.OPEN
    assertThat(origin.open(ref)).isInstanceOf(OriginResult.Opened::class.java)
    assertThat(reader.opens.get()).isEqualTo(1)
  }

  /** D-15: la tabella di `blockedReason` (prima il percorso, poi il consumo) e i valori di serie di un `NasLink` di prima. */
  @Test
  fun d15_ilMotivoDelBloccoSegueLaRete() {
    fun reason(network: NasNetwork, confirmed: Boolean) = FixedNetwork(network).blockedReason(confirmed)
    assertThat(reason(NasNetwork.OPEN, confirmed = false)).isNull()
    assertThat(reason(NasNetwork.METERED, confirmed = false)).isEqualTo(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION)
    assertThat(reason(NasNetwork.METERED, confirmed = true)).isNull()
    for (confirmed in listOf(false, true)) {
      assertThat(reason(NasNetwork.VPN_OFF, confirmed)).isEqualTo(UnavailableReason.VPN_OFF_AWAY)
      assertThat(reason(NasNetwork.OFFLINE, confirmed)).isEqualTo(UnavailableReason.OFFLINE)
      assertThat(reason(NasNetwork.NOT_ALLOWED, confirmed)).isEqualTo(UnavailableReason.NOT_ALLOWED_HERE)
    }
    // Un NasLink scritto come prima (solo `isMetered`) vale "a consumo o libera" e non avvisa di cambiamenti.
    assertThat(NasLink { true }.network()).isEqualTo(NasNetwork.METERED)
    assertThat(NasLink { false }.network()).isEqualTo(NasNetwork.OPEN)
    assertThat(NasLink.Unmetered.blockedReason(confirmed = false)).isNull()
    assertThat(kotlinx.coroutines.runBlocking { NasLink { true }.changes.toList() }).isEmpty()
  }

  // --- Casi limite -------------------------------------------------------------------------------------------------------

  @Test
  fun unOriginaleOltreIlTettoNonSiLegge() = runTest {
    val ref = add(14, size = 300_000)
    val origin = origin(max = 100_000)

    val result = origin.open(ref)

    assertThat((result as OriginResult.Failed).code).isEqualTo(ImageFailure.OUT_OF_MEMORY)
    assertThat(reader.opens.get()).isEqualTo(0)
  }

  @Test
  fun senzaPostoSulTelefonoEUnaFraseASe() = runTest {
    val ref = add(15)
    store = NasOriginalStore(folder.newFolder("poco-posto"), maxBytes = 64L shl 20) { 1_000 }
    val origin = origin()

    val result = origin.open(ref)

    assertThat((result as OriginResult.Failed).code).isEqualTo(ImageFailure.STORAGE_FULL)
    assertThat(reader.openFiles.get()).isEqualTo(0)
  }

  @Test
  fun unFileModificatoMentreSiLeggeNonDiventaUnOriginale() = runTest {
    val ref = add(16, size = 300_000)
    reader.shortBy = 50_000
    val origin = origin()

    val result = origin.open(ref)

    assertThat(result).isInstanceOf(OriginResult.Failed::class.java)
    assertThat(store.count).isEqualTo(0)
    assertThat(leftovers()).isEmpty()
    assertThat(reader.openFiles.get()).isEqualTo(0)
  }

  @Test
  fun unFileModificatoDalPcDopoLaLetturaSiRilegge() = runTest {
    val ref = add(17, size = 100_000)
    val origin = origin()
    origin.open(ref)
    assertThat(reader.opens.get()).isEqualTo(1)

    // Il PC lo riscrive (altra data): il catalogo si aggiorna, il temporaneo vecchio non vale piu'.
    nas.modify("test", "Foto/p17.jpg", NasContent.virtual(100_000, seed = 99), modifiedAt = 5_000_000_000_000L)
    val path = SharePath.of("Foto", "p17.jpg")
    val stat = (nas.reader.stat("test", path, Purpose.INTERACTIVE) as NasResult.Ok).value!!
    locations[17] = NasMediaLocation("test", path, 100_000, stat.stamp)

    val reread = origin.open(ref) as OriginResult.Opened
    assertThat(reader.opens.get()).isEqualTo(2)
    reread.bytes.openStream().use { assertThat(it.readBytes()).isEqualTo(nas.bytes("test", "Foto/p17.jpg")) }
    assertThat(store.count).isEqualTo(1)
  }

  @Test
  fun dueRichiesteInParalleloPerLoStessoMediaLeggonoUnaVolta() = runTest {
    val ref = add(18, size = 256 * 1024)
    reader.chunkDelayMs = 50
    val origin = origin()
    val results = CopyOnWriteArrayList<OriginResult>()

    val a = launch { results += origin.open(ref) }
    val b = launch { results += origin.open(ref) }
    advanceUntilIdle()
    a.join()
    b.join()

    assertThat(results).hasSize(2)
    assertThat(results.all { it is OriginResult.Opened }).isTrue()
    assertThat(reader.opens.get()).isEqualTo(1)
    results.forEach { (it as OriginResult.Opened).bytes.close() }
  }

  @Test
  fun treMediaInParalleloNonSiAspettanoFraLoro() = runTest {
    val refs = (20L..22L).map { add(it, size = 128 * 1024) }
    reader.chunkDelayMs = 100
    val origin = origin()
    val done = CopyOnWriteArrayList<Long>()

    val jobs = refs.map { ref -> launch { origin.open(ref); done += currentTime } }
    advanceUntilIdle()
    jobs.forEach { it.join() }

    // Due blocchi da 64 KiB a 100 ms: tutti e tre finiscono insieme, a 200 ms (non 200, 400, 600).
    assertThat(done).containsExactly(200L, 200L, 200L)
  }

  // --- Lo zoom: il flusso fetch ------------------------------------------------------------------------------------------

  @Test
  fun fetchRaccontaLAvanzamentoEConsegnaIlFileCompleto() = runTest {
    val ref = add(30, size = 256 * 1024)
    reader.chunkDelayMs = 10
    val origin = origin()

    val states = origin.fetch(ref).toList()

    // I Reading intermedi si accorpano (conflate): conta che prima del Ready ci siano solo Reading.
    assertThat(states.dropLast(1)).isNotEmpty()
    assertThat(states.dropLast(1).all { it is OriginalFetch.Reading }).isTrue()
    val ready = states.last() as OriginalFetch.Ready
    assertThat(ready.file.length()).isEqualTo(256L * 1024)
    assertThat(origin.localFile(ref)).isEqualTo(ready.file)
    assertThat(origin.openLocal(ref)).isNotNull()
    // Gia' sul telefono: niente piu' letture.
    assertThat(origin.fetch(ref).toList()).containsExactly(OriginalFetch.Ready(ready.file))
    assertThat(reader.opens.get()).isEqualTo(1)
  }

  @Test
  fun fetchConIDatiMobiliDaConfermareESenzaNas() = runTest {
    val ref = add(31, size = 1_000_000)
    val origin = origin()
    metered = true

    assertThat(origin.fetch(ref).toList().last()).isEqualTo(OriginalFetch.Blocked(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION, 1_000_000L))
    metered = false
    reader.openError = NasError.Timeout
    assertThat(origin.fetch(ref).toList().last()).isEqualTo(OriginalFetch.Blocked(UnavailableReason.NAS_UNREACHABLE, 1_000_000L))
    assertThat(origin.fetch(MediaRef.Nas(999, 1, null)).toList().last()).isEqualTo(OriginalFetch.Missing)
  }

  @Test
  fun annullareLaRaccoltaDiFetchFermaLaLetturaEToglieIlTemporaneo() = runTest {
    val ref = add(32, size = 512 * 1024)
    reader.stallAfterBytes = 100 * 1024
    val origin = origin()
    val seen = CopyOnWriteArrayList<OriginalFetch>()
    val job = launch { origin.fetch(ref).collect { seen += it } }

    advanceTimeBy(30_000)
    runCurrent()
    assertThat(reader.openFiles.get()).isEqualTo(1)
    job.cancel()
    job.join()

    assertThat(seen.last()).isInstanceOf(OriginalFetch.Reading::class.java)
    assertThat(reader.openFiles.get()).isEqualTo(0)
    assertThat(leftovers()).isEmpty()
    assertThat(origin.localFile(ref)).isNull()
  }
}
