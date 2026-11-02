package dev.pampa.mycloudgallery.feature.integrations.drag

import android.provider.DocumentsContract
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.DragFlags
import dev.pampa.mycloudgallery.core.library.contract.DragOrigin
import dev.pampa.mycloudgallery.core.library.contract.DragPrepared
import dev.pampa.mycloudgallery.core.library.contract.DragRefusal
import dev.pampa.mycloudgallery.core.library.contract.DragRequest
import dev.pampa.mycloudgallery.core.metadata.contract.LocationStripper
import dev.pampa.mycloudgallery.core.metadata.contract.StripOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.StripUnsupported
import dev.pampa.mycloudgallery.core.metadata.contract.WriteFailure
import dev.pampa.mycloudgallery.core.metadata.strip.HeicConversion
import dev.pampa.mycloudgallery.core.metadata.strip.HeicToJpeg
import dev.pampa.mycloudgallery.core.metadata.strip.LocationTraceRecheck
import dev.pampa.mycloudgallery.core.metadata.strip.LocationVerdict
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentClock
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentIdTable
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentLabels
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounters
import dev.pampa.mycloudgallery.feature.integrations.provider.LibraryMediaDocuments
import dev.pampa.mycloudgallery.feature.integrations.provider.PreparedCopies
import dev.pampa.mycloudgallery.feature.integrations.provider.testing.FakeIntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.share.LocationFreeShare
import dev.pampa.mycloudgallery.feature.integrations.share.ShareCache
import dev.pampa.mycloudgallery.feature.integrations.share.ShareOpen
import dev.pampa.mycloudgallery.feature.integrations.share.ShareSamples
import dev.pampa.mycloudgallery.feature.integrations.share.ShareSource
import dev.pampa.mycloudgallery.feature.integrations.share.testing.FakeCacheUris
import dev.pampa.mycloudgallery.feature.integrations.share.testing.InMemoryShareSource
import dev.pampa.mycloudgallery.feature.integrations.share.testing.RecordingUriRevoker
import dev.pampa.mycloudgallery.feature.integrations.testing.FakeEntryGate
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10 (P6, D20, C436; E-T13, E-X5, E-T14, E-T17): il trascinamento con **"Rimuovi la posizione"** acceso. Con lo stripper vero di core-metadata (lo stesso di "Condividi") e il controllo
 * indipendente `LocationTraces`: cio' che l'altra app legge e' una **copia senza posizione** (JPEG, HEIC diventato JPEG, MP4); se la posizione non si toglie **il trascinamento si rifiuta**
 * e non resta nessuna copia ne' nessun id; con l'impostazione spenta restano gli originali, **senza copie in cache** (un media solo sul NAS passa con il descrittore a richiesta).
 * Le copie temporanee stanno in `cacheDir/share/` e **spariscono al prossimo avvio** (`ShareCache.sweepAtStart`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DragLocationFreeTest {

  @get:Rule val folder = TemporaryFolder()

  private val library = FakeIntegrationLibrary()
  private val authority = "test.pkg.integrations.documents"
  private val source = InMemoryShareSource()
  private val scope = CoroutineScope(Dispatchers.Unconfined)
  private val clock = object : DocumentClock {
    override fun elapsedMs(): Long = 1_000L
  }

  private var setting = true
  private lateinit var cache: ShareCache
  private lateinit var preparer: PreparedCopies
  private lateinit var documents: LibraryMediaDocuments
  private lateinit var table: DocumentIdTable
  private lateinit var leases: DragLeases
  private val gate = FakeEntryGate(EntryDecision.ALLOWED)

  // I tre media: una foto e un HEIC del telefono, un video solo sul NAS (tutti con una posizione).
  private val jpegId = library.phoneIds()[0]
  private val heicId = library.phoneIds()[1]
  private val mp4Id = library.nasOnlyIds()[0]
  private lateinit var jpegBytes: ByteArray
  private val mp4Bytes = ShareSamples.gpsMp4()

  @Before
  fun setUp() {
    IntegrationCounters.reset()
    DragCounters.reset()
    jpegBytes = ShareSamples.gpsJpeg(folder.root, "originale.jpg")
    put(jpegId, jpegBytes, "image/jpeg", "Mare 2024.jpg")
    put(heicId, ShareSamples.heicBytes(), "image/heic", "IMG_1.HEIC")
    put(mp4Id, mp4Bytes, "video/mp4", "Gita.mp4")
    build()
  }

  @After
  fun tearDown() {
    scope.cancel()
    IntegrationCounters.reset()
    DragCounters.reset()
  }

  private fun heicConverter() = HeicToJpeg { _, dst ->
    dst.writeBytes(jpegBytes)
    HeicConversion.Done
  }

  private fun put(id: MediaId, bytes: ByteArray, mime: String, name: String) {
    source.put(id, bytes, mime, name)
    library.mimeOverride[id] = mime
    library.nameOverride[id] = name
    library.sizeOverride[id] = bytes.size.toLong()
  }

  private fun build(stripper: LocationStripper = ShareSamples.stripper(heicConverter()), shareSource: ShareSource = source) {
    cache = ShareCache(File(folder.root, "cache"))
    val share = LocationFreeShare(cache, shareSource, stripper, FakeCacheUris(), RecordingUriRevoker(), Dispatchers.Unconfined)
    preparer = PreparedCopies(share, { setting }, scope, clock, io = Dispatchers.Unconfined)
    table = DocumentIdTable(clock, Random(5))
    documents = LibraryMediaDocuments(library, DocumentLabels("MyCloud Gallery", "Recenti", "Cartelle NAS"), table, clock, preparer)
    leases = DragLeases(scope, graceMs = 600_000L)
  }

  private fun payloads() = DocumentDragPayloads(library, documents, authority, gate::decide, leases, preparer, nasProbeDeadlineMs = 5_000L)

  private fun request(ids: List<MediaId>, removeLocation: Boolean = false) = DragRequest(DragOrigin.LIBRARY, ids, removeLocation)

  private fun leftovers(): List<String> = cache.shareRoot.walkTopDown().filter { it.isFile }.map { it.name }.toList()

  private fun readAll(id: DocumentId): ByteArray = runBlocking {
    val reader = documents.open(id) ?: error("il documento non si apre")
    reader.use { r ->
      val out = ByteArrayOutputStream()
      val buffer = ByteArray(8_192)
      var at = 0L
      while (true) {
        val n = r.read(at, buffer, 0, buffer.size)
        if (n <= 0) break
        out.write(buffer, 0, n)
        at += n
      }
      out.toByteArray()
    }
  }

  private fun verdict(bytes: ByteArray, mime: String): LocationVerdict {
    val file = File(folder.root, "verifica-${System.nanoTime()}").apply { writeBytes(bytes) }
    return try {
      LocationTraceRecheck().check(file, mime)
    } finally {
      file.delete()
    }
  }

  private fun tokens(ready: DragPrepared.Ready): List<DocumentId> = (0 until ready.clip.itemCount).map { DocumentId(DocumentsContract.getDocumentId(ready.clip.getItemAt(it).uri)) }

  // --- La copia senza posizione ----------------------------------------------------------------------------------------------

  @Test
  fun conLaPosizioneToltaIlTrascinamentoPortaSoloCopieSenzaPosizione_JpegHeicEMp4() = runBlocking<Unit> {
    // Le fixture hanno davvero una posizione (altrimenti la prova non proverebbe niente).
    assertThat(verdict(jpegBytes, "image/jpeg")).isEqualTo(LocationVerdict.HAS_LOCATION)
    assertThat(verdict(mp4Bytes, "video/mp4")).isEqualTo(LocationVerdict.HAS_LOCATION)

    val ready = payloads().prepare(request(listOf(jpegId, heicId, mp4Id))) as DragPrepared.Ready

    assertThat(ready.count).isEqualTo(3)
    assertThat(ready.flags).isEqualTo(DragFlags.ALLOWED)
    // Tutti URI del nostro provider (mai l'indirizzo del MediaStore dell'originale), ognuno apre una copia senza posizione.
    val names = HashMap<String, DocumentId>()
    for (token in tokens(ready)) {
      val entry = documents.entry(token)!!
      names[entry.displayName] = token
      val bytes = readAll(token)
      assertThat(entry.size).isEqualTo(bytes.size.toLong())
      assertWithMessage(entry.displayName).that(verdict(bytes, entry.mime)).isEqualTo(LocationVerdict.CLEAR)
    }
    assertThat(names.keys).containsExactly("Mare 2024.jpg", "IMG_1.jpg", "Gita.mp4")
    // Un HEIC e' diventato JPEG (nome e tipo veri, anche nel ClipData); il video ha la stessa dimensione (nessuna ricodifica).
    assertThat(documents.entry(names.getValue("IMG_1.jpg"))!!.mime).isEqualTo("image/jpeg")
    val declared = (0 until ready.clip.description.mimeTypeCount).map { ready.clip.description.getMimeType(it) }
    assertThat(declared).containsExactly("image/jpeg", "video/mp4")
    assertThat(readAll(names.getValue("Gita.mp4")).size).isEqualTo(mp4Bytes.size)
    // Nessuna copia con la posizione resta in cache: solo i tre file da consegnare.
    assertThat(leftovers()).containsExactly("Mare 2024.jpg", "IMG_1.jpg", "Gita.mp4")
    assertThat(preparer.count).isEqualTo(3)
    assertThat(DragCounters.get(DragCounter.OUT_STRIPPED)).isEqualTo(3L)
  }

  @Test
  fun seLaPosizioneNonSiTogliePerUnaSolaCopiaIlTrascinamentoSiRifiutaENienteResta() = runBlocking<Unit> {
    data class Case(val outcome: StripOutcome, val refusal: DragRefusal)
    val cases = listOf(
      Case(StripOutcome.Failed(WriteFailure.IO), DragRefusal.UNAVAILABLE),
      Case(StripOutcome.StillHasLocation, DragRefusal.LOCATION_NOT_REMOVABLE),
      Case(StripOutcome.Unsupported(StripUnsupported.FORMAT), DragRefusal.LOCATION_NOT_REMOVABLE),
    )
    for (case in cases) {
      build(stripper = object : LocationStripper {
        override fun strip(src: File, dst: File, mime: String): StripOutcome = case.outcome
      })

      val result = payloads().prepare(request(listOf(jpegId, mp4Id)))

      assertWithMessage("${case.outcome}").that(result).isEqualTo(DragPrepared.Refused(case.refusal))
      // Niente id, niente prestiti, nessuna copia (nemmeno una con la posizione), nessun file lasciato aperto, e mai l'originale al posto della copia.
      assertThat(table.size).isEqualTo(0)
      assertThat(leases.count).isEqualTo(0)
      assertThat(preparer.count).isEqualTo(0)
      assertThat(leftovers()).isEmpty()
      assertThat(source.closed).isEqualTo(source.opened)
      assertThat(library.phoneReaders).isEmpty()
      assertThat(library.nasReaders).isEmpty()
    }
  }

  @Test
  fun unFormatoNonTrattabileFermaTuttoIlTrascinamentoEIlBuonoNonEsceDaSolo() = runBlocking<Unit> {
    val gifId = library.phoneIds()[2]
    put(gifId, ShareSamples.gifBytes(), "image/gif", "Animata.gif")

    assertThat(payloads().prepare(request(listOf(jpegId, gifId)))).isEqualTo(DragPrepared.Refused(DragRefusal.LOCATION_NOT_REMOVABLE))

    assertThat(preparer.count).isEqualTo(0)
    assertThat(leftovers()).isEmpty()
    assertThat(table.size).isEqualTo(0)
    // Togliendo la GIF dalla scelta, il resto parte.
    assertThat(payloads().prepare(request(listOf(jpegId)))).isInstanceOf(DragPrepared.Ready::class.java)
  }

  @Test
  fun unSoloNasCheNonRispondeFermaIlTrascinamentoSenzaCopie() = runBlocking<Unit> {
    source.nasUnreachable += mp4Id

    assertThat(payloads().prepare(request(listOf(jpegId, mp4Id)))).isEqualTo(DragPrepared.Refused(DragRefusal.NAS_UNREACHABLE))

    assertThat(preparer.count).isEqualTo(0)
    assertThat(leftovers()).isEmpty()
    assertThat(table.size).isEqualTo(0)
  }

  @Test
  fun unMediaNascostoMentreSiPreparaLeCopieNonRestano() = runBlocking<Unit> {
    // Un altro dispositivo nasconde il video mentre si prova il NAS (le copie si fanno dopo): l'ultimo controllo trova il media nascosto, niente esce e le copie si tolgono.
    library.beforeNasReach = { library.hide(setOf(jpegId)) }

    assertThat(payloads().prepare(request(listOf(jpegId, mp4Id)))).isEqualTo(DragPrepared.Refused(DragRefusal.HIDDEN))

    assertThat(table.size).isEqualTo(0)
    assertThat(preparer.count).isEqualTo(0)
    assertThat(leftovers()).isEmpty()
  }

  @Test
  fun ilBloccoCheScattaDopoLeCopieLeToglieENonCreaId() = runBlocking<Unit> {
    // Il cancello si richiude mentre le copie si preparano (si apre il media): all'ultimo controllo non esce niente.
    build(
      shareSource = object : ShareSource {
        override suspend fun open(mediaId: MediaId): ShareOpen {
          gate.decision = EntryDecision.NEEDS_UNLOCK
          return source.open(mediaId)
        }
      },
    )

    assertThat(payloads().prepare(request(listOf(jpegId, mp4Id)))).isEqualTo(DragPrepared.Refused(DragRefusal.LOCKED))

    assertThat(table.size).isEqualTo(0)
    assertThat(preparer.count).isEqualTo(0)
    assertThat(leftovers()).isEmpty()
  }

  // --- Cio' che passa com'e' (impostazione spenta) ------------------------------------------------------------------------------

  @Test
  fun conLImpostazioneSpentaPassanoGliOriginaliSenzaCopieInCache_IlSoloNasConIlDescrittore() = runBlocking<Unit> {
    setting = false

    val ready = payloads().prepare(request(listOf(jpegId, mp4Id))) as DragPrepared.Ready

    // Nessuna copia in cache, nessun file del flusso aperto per intero; il media del telefono si legge dal telefono, il solo-NAS a pezzi dal NAS.
    assertThat(preparer.count).isEqualTo(0)
    assertThat(source.opened).isEqualTo(0)
    assertThat(leftovers()).isEmpty()
    val bytes = tokens(ready).map { readAll(it) }
    assertThat(library.phoneReaders).hasSize(1)
    assertThat(library.nasReaders).hasSize(1)
    assertThat(bytes.all { it.isNotEmpty() }).isTrue()
    assertThat(documents.preparesOnOpen()).isFalse()
    // Una richiesta che chiede la posizione tolta con l'impostazione spenta non parte: il provider servirebbe l'originale.
    assertThat(payloads().prepare(request(listOf(jpegId), removeLocation = true))).isEqualTo(DragPrepared.Refused(DragRefusal.LOCATION_NOT_REMOVABLE))
  }

  // --- La vita delle copie (D20, E-T14, L547) --------------------------------------------------------------------------------

  @Test
  fun unTrascinamentoCheNonPartePortaViaSubitoCopieEId() = runBlocking<Unit> {
    val ready = payloads().prepare(request(listOf(jpegId, mp4Id))) as DragPrepared.Ready
    assertThat(leftovers()).hasSize(2)
    val tokens = tokens(ready)

    ready.lease.release()

    assertThat(leftovers()).isEmpty()
    assertThat(preparer.count).isEqualTo(0)
    assertThat(table.size).isEqualTo(0)
    for (token in tokens) assertThat(documents.entry(token)).isNull()
  }

  @Test
  fun leCopieTemporaneeSparisconoAlRiavvio() = runBlocking<Unit> {
    val ready = payloads().prepare(request(listOf(jpegId, heicId, mp4Id))) as DragPrepared.Ready
    assertThat(ready.count).isEqualTo(3)
    assertThat(leftovers()).hasSize(3)

    // Il processo muore con l'app in primo piano (nessuno ha rilasciato il prestito) e ne nasce un altro: la pulizia dell'avvio svuota `share/`.
    val restarted = ShareCache(File(folder.root, "cache"))
    restarted.sweepAtStart(emptySet())

    assertThat(leftovers()).isEmpty()
    assertThat(restarted.shareRoot.listFiles().orEmpty().toList()).isEmpty()
  }

  @Test
  fun ilPrestitoDelleCopieSiRilasciaSoloUnaVolta() = runBlocking<Unit> {
    val ready = payloads().prepare(request(listOf(jpegId))) as DragPrepared.Ready
    ready.lease.release()
    ready.lease.release()
    assertThat(leftovers()).isEmpty()
    assertThat(DragCounters.get(DragCounter.OUT_DISCARDED)).isEqualTo(1L)
  }
}
