package dev.pampa.mycloudgallery.feature.integrations.provider

import android.media.MediaDataSource
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.metadata.contract.LocationStripper
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.StripOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.StripUnsupported
import dev.pampa.mycloudgallery.core.metadata.contract.WriteFailure
import dev.pampa.mycloudgallery.core.metadata.strip.HeicConversion
import dev.pampa.mycloudgallery.core.metadata.strip.HeicToJpeg
import dev.pampa.mycloudgallery.core.metadata.strip.LocationTraceRecheck
import dev.pampa.mycloudgallery.core.metadata.strip.LocationVerdict
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentEntry
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerAction
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerDeliverer
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerDelivery
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerNotice
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerProgress
import dev.pampa.mycloudgallery.feature.integrations.picker.PickerRequest
import dev.pampa.mycloudgallery.feature.integrations.provider.testing.FakeIntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.share.LocationFreeShare
import dev.pampa.mycloudgallery.feature.integrations.share.OpenedForShare
import dev.pampa.mycloudgallery.feature.integrations.share.ShareCache
import dev.pampa.mycloudgallery.feature.integrations.share.ShareOpen
import dev.pampa.mycloudgallery.feature.integrations.share.ShareSamples
import dev.pampa.mycloudgallery.feature.integrations.share.ShareSource
import dev.pampa.mycloudgallery.feature.integrations.share.ShareStop
import dev.pampa.mycloudgallery.feature.integrations.share.testing.FakeCacheUris
import dev.pampa.mycloudgallery.feature.integrations.share.testing.InMemoryShareSource
import dev.pampa.mycloudgallery.feature.integrations.share.testing.RecordingUriRevoker
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Decisione dell'utente del 20/10/2026: **"Rimuovi la posizione" vale anche per il selettore e per il provider dei documenti.** Con lo stripper vero di
 * core-metadata (lo stesso di "Condividi") e il controllo indipendente `LocationTraces`: con l'impostazione accesa cio' che un'altra app riceve non ha
 * posizione (JPEG, HEIC, MP4); se la posizione non si toglie non esce niente e l'originale non prende mai il posto della copia; con l'impostazione
 * spenta resta l'originale. Le copie stanno nella cache, con i suoi tetti, e se ne vanno con l'id.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocationFreeDeliveryTest {

  @get:Rule val folder = TemporaryFolder()

  private val library = FakeIntegrationLibrary()
  private val labels = DocumentLabels("MyCloud Gallery", "Recenti", "Cartelle NAS")
  private val authority = "test.pkg.integrations.documents"
  private val source = InMemoryShareSource()
  private val scope = CoroutineScope(Dispatchers.Unconfined)
  private val clock = object : DocumentClock {
    var now = 1_000L
    override fun elapsedMs(): Long = now
  }

  private var setting = true
  private lateinit var cache: ShareCache
  private lateinit var preparer: PreparedCopies
  private lateinit var documents: LibraryMediaDocuments

  // I tre media: una foto e un HEIC del telefono, un video solo sul NAS (tutti con una posizione).
  private val jpegId = library.phoneIds()[0]
  private val heicId = library.phoneIds()[1]
  private val mp4Id = library.nasOnlyIds()[0]
  private lateinit var jpegBytes: ByteArray
  private val mp4Bytes = ShareSamples.gpsMp4()

  @Before
  fun setUp() {
    IntegrationCounters.reset()
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
  }

  /** Il convertitore HEIC finto: i "pixel" sono un JPEG che si porta dietro una posizione (un convertitore sbagliato non la deve far passare). */
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

  /** Lo stack di prova: la cache, `LocationFreeShare` con [stripper] e [shareSource], le copie vive e i documenti. */
  private fun build(
    stripper: LocationStripper = ShareSamples.stripper(heicConverter()),
    shareSource: ShareSource = source,
    maxBytes: Long = Long.MAX_VALUE,
  ) {
    cache = ShareCache(File(folder.root, "cache"))
    val share = LocationFreeShare(cache, shareSource, stripper, FakeCacheUris(), RecordingUriRevoker(), Dispatchers.Unconfined)
    preparer = PreparedCopies(share, { setting }, scope, clock, maxBytes = maxBytes, io = Dispatchers.Unconfined)
    documents = LibraryMediaDocuments(library, labels, DocumentIdTable(clock, Random(5)), clock, preparer)
  }

  private fun request() = PickerRequest(PickerAction.GET_CONTENT, images = true, videos = true, allowMultiple = true, localOnly = false, mimeFilters = listOf("*/*"))

  private fun deliverer() = PickerDeliverer(library, documents, request(), authority, preparer = preparer)

  private fun leftovers(): List<String> = File(cache.shareRoot, "").walkTopDown().filter { it.isFile }.map { it.name }.toList()

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

  /** Il verdetto indipendente (`LocationTraces`, scritto da zero e non riusato dallo stripper) su dei byte. */
  private fun verdict(bytes: ByteArray, mime: String): LocationVerdict {
    val file = File(folder.root, "verifica-${System.nanoTime()}").apply { writeBytes(bytes) }
    return try {
      LocationTraceRecheck().check(file, mime)
    } finally {
      file.delete()
    }
  }

  private fun idOf(uri: android.net.Uri) = DocumentId(android.provider.DocumentsContract.getDocumentId(uri))

  private fun recentEntries(): List<DocumentEntry> = runBlocking {
    val root = documents.roots().single().rootDocument
    val recent = documents.children(root, 10).first { it.displayName == "Recenti" }
    documents.children(recent.id, DocumentPolicy.MAX_CHILDREN)
  }

  // --- Il selettore ----------------------------------------------------------------------------------------------------------

  @Test
  fun ilSelettoreConsegnaSoloCopieSenzaPosizione_JpegHeicEMp4() = runBlocking<Unit> {
    // Le fixture hanno davvero una posizione (altrimenti la prova non proverebbe niente).
    assertThat(verdict(jpegBytes, "image/jpeg")).isEqualTo(LocationVerdict.HAS_LOCATION)
    assertThat(verdict(mp4Bytes, "video/mp4")).isEqualTo(LocationVerdict.HAS_LOCATION)
    val progress = ArrayList<PickerProgress>()

    val result = deliverer().prepare(setOf(jpegId, heicId, mp4Id)) { progress += it } as PickerDelivery.Ready

    assertThat(result.uris).hasSize(3)
    assertThat(result.minted).hasSize(3)
    assertThat(result.nasOnly).isEqualTo(1)
    // Mai l'indirizzo del MediaStore (l'originale del telefono): tutti del nostro provider, ognuno con il suo id opaco.
    result.uris.forEach { assertThat(it.authority).isEqualTo(authority) }
    val names = HashMap<String, DocumentId>()
    for (uri in result.uris) {
      val id = idOf(uri)
      val entry = documents.entry(id)!!
      names[entry.displayName] = id
      val bytes = readAll(id)
      // Il file consegnato ha la dimensione che l'altra app legge dalla colonna, ed e' senza posizione per il controllo indipendente.
      assertThat(entry.size).isEqualTo(bytes.size.toLong())
      assertWithMessage(entry.displayName).that(verdict(bytes, entry.mime)).isEqualTo(LocationVerdict.CLEAR)
    }
    assertThat(names.keys).containsExactly("Mare 2024.jpg", "IMG_1.jpg", "Gita.mp4")
    // Un HEIC e' diventato JPEG (nome e tipo veri), il video ha la stessa dimensione (nessuna ricodifica).
    assertThat(documents.entry(names.getValue("IMG_1.jpg"))!!.mime).isEqualTo("image/jpeg")
    assertThat(readAll(names.getValue("Gita.mp4")).size).isEqualTo(mp4Bytes.size)
    assertThat(readAll(names.getValue("IMG_1.jpg")).take(2)).containsExactly(0xFF.toByte(), 0xD8.toByte()).inOrder()
    // L'avanzamento: tre media, e i byte del media in corso (la barra di un video grande non resta ferma).
    assertThat(progress.last().done).isEqualTo(3)
    assertThat(progress.any { it.bytesTotal > 0 && it.bytesDone == it.bytesTotal }).isTrue()
    assertThat(progress.all { it.bytesDone <= it.bytesTotal }).isTrue()
    // Nessuna copia con la posizione resta in cache: solo i tre file consegnati.
    assertThat(leftovers()).containsExactly("Mare 2024.jpg", "IMG_1.jpg", "Gita.mp4")
    assertThat(preparer.count).isEqualTo(3)
  }

  @Test
  fun conLImpostazioneSpentaSiConsegnaLOriginale() = runBlocking<Unit> {
    setting = false

    val result = deliverer().prepare(setOf(jpegId, mp4Id)) as PickerDelivery.Ready

    // Il telefono: l'indirizzo del MediaStore, nessuna copia. Il solo-NAS: il provider con l'originale (la porta finta del NAS).
    assertThat(result.uris.map { it.authority }).containsExactly("media", authority)
    assertThat(preparer.count).isEqualTo(0)
    assertThat(source.opened).isEqualTo(0)
    assertThat(leftovers()).isEmpty()
    val nas = result.uris.single { it.authority == authority }
    readAll(idOf(nas))
    assertThat(library.nasReaders).hasSize(1)
    assertThat(documents.preparesOnOpen()).isFalse()
  }

  // --- Fallire chiusi ---------------------------------------------------------------------------------------------------------

  @Test
  fun seLaPosizioneNonSiToglieNonEsceNienteEdLOriginaleNonPrendeIlPostoDellaCopia() = runBlocking<Unit> {
    data class Case(val outcome: StripOutcome, val stop: ShareStop, val aboutOne: Boolean)
    val cases = listOf(
      Case(StripOutcome.Failed(WriteFailure.IO), ShareStop.FAILED, aboutOne = false),
      Case(StripOutcome.StillHasLocation, ShareStop.LOCATION_REMAINS, aboutOne = true),
      Case(StripOutcome.Unsupported(StripUnsupported.FORMAT), ShareStop.CANNOT_REMOVE_LOCATION, aboutOne = true),
    )
    for (case in cases) {
      build(stripper = object : LocationStripper {
        override fun strip(src: File, dst: File, mime: String): StripOutcome = case.outcome
      })

      val refused = deliverer().prepare(setOf(jpegId, mp4Id)) as PickerDelivery.Refused

      val notice = refused.notice as PickerNotice.NoLocation
      assertWithMessage("${case.stop}").that(notice.stop).isEqualTo(case.stop)
      assertThat(notice.removedFromChoice).isEqualTo(case.aboutOne)
      assertThat(refused.culprit != null).isEqualTo(case.aboutOne)
      // Niente consegnato, niente in cache, nessuna copia viva: e nessun file aperto e lasciato aperto.
      assertThat(preparer.count).isEqualTo(0)
      assertThat(leftovers()).isEmpty()
      assertThat(source.closed).isEqualTo(source.opened)

      // Dal provider: un id da sfoglio non apre niente (e soprattutto non apre l'originale).
      val entry = recentEntries().first { it.displayName == "Mare 2024.jpg" }
      val error = assertThrows(LocationRemovalRefused::class.java) { runBlocking { documents.open(entry.id) } }
      assertThat(error.stop).isEqualTo(case.stop)
      assertThat(library.phoneReaders).isEmpty()
      assertThat(library.nasReaders).isEmpty()
    }
  }

  @Test
  fun unFormatoNonTrattabileFermaTuttaLaConsegnaEIlBuonoNonEsceDaSolo() = runBlocking<Unit> {
    val gifId = library.phoneIds()[2]
    put(gifId, ShareSamples.gifBytes(), "image/gif", "Animata.gif")

    val refused = deliverer().prepare(setOf(jpegId, gifId)) as PickerDelivery.Refused

    val notice = refused.notice as PickerNotice.NoLocation
    assertThat(notice.stop).isEqualTo(ShareStop.CANNOT_REMOVE_LOCATION)
    assertThat(refused.culprit).isEqualTo(gifId)
    assertThat(preparer.count).isEqualTo(0)
    assertThat(leftovers()).isEmpty()
    // E' la GIF che si toglie dalla scelta: gli altri, con un altro "Scegli", escono.
    val ready = deliverer().prepare(setOf(jpegId)) as PickerDelivery.Ready
    assertThat(ready.uris).hasSize(1)
  }

  @Test
  fun senzaChiCreaGliIdNonSiConsegnaMaiLOriginale() = runBlocking<Unit> {
    val refused = PickerDeliverer(library, null, request(), authority, preparer = preparer).prepare(setOf(jpegId))

    assertThat((refused as PickerDelivery.Refused).notice).isEqualTo(PickerNotice.NothingAvailable)
    assertThat(source.opened).isEqualTo(0)
  }

  @Test
  fun unSoloNasCheNonRispondeFermaLaConsegna() = runBlocking<Unit> {
    source.nasUnreachable += mp4Id

    val refused = deliverer().prepare(setOf(jpegId, mp4Id)) as PickerDelivery.Refused

    assertThat(refused.notice).isInstanceOf(PickerNotice.NasUnreachable::class.java)
    assertThat(preparer.count).isEqualTo(0)
    assertThat(leftovers()).isEmpty()
  }

  // --- Tetti ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun conLImpostazioneAccesaSiScelgonoAlPiuCentoElementi() = runBlocking<Unit> {
    val many = library.phoneIds().take(101).toSet()

    val refused = deliverer().prepare(many) as PickerDelivery.Refused

    assertThat(refused.notice).isEqualTo(PickerNotice.TooMany(100))
    assertThat(source.opened).isEqualTo(0)
    // Spenta, il tetto e' quello di sempre (500) e i telefoni escono com'erano.
    setting = false
    assertThat(deliverer().prepare(many)).isInstanceOf(PickerDelivery.Ready::class.java)
  }

  @Test
  fun ilTettoSullaCacheFaPostoAlleCopieDelloSfoglioMaNonScalzaQuelleConsegnate() = runBlocking<Unit> {
    val other = library.phoneIds()[3]
    put(other, ShareSamples.gpsJpeg(folder.root, "altra.jpg"), "image/jpeg", "Altra.jpg")
    // Quanto pesa una copia senza posizione; poi una cache che ne tiene una e mezza (una sola).
    preparer.prepare(listOf(jpegId), delivered = false)
    val one = preparer.infoOf(jpegId)!!.size
    preparer.releaseAll()
    build(maxBytes = one * 3 / 2)

    // Due copie dello sfoglio: la seconda fa posto alla prima.
    assertThat(preparer.prepare(listOf(jpegId), delivered = false)).isInstanceOf(PreparedBatch.Ready::class.java)
    assertThat(preparer.prepare(listOf(other), delivered = false)).isInstanceOf(PreparedBatch.Ready::class.java)
    assertThat(preparer.infoOf(jpegId)).isNull()
    assertThat(preparer.infoOf(other)).isNotNull()
    assertThat(leftovers()).containsExactly("Altra.jpg")

    // Una copia consegnata non esce per far posto: la nuova si rifiuta (niente consegnato, niente di nuovo in cache).
    assertThat(preparer.prepare(listOf(other), delivered = true)).isInstanceOf(PreparedBatch.Ready::class.java)
    val refused = preparer.prepare(listOf(jpegId), delivered = true) as PreparedBatch.Refused
    assertThat(refused.stop).isEqualTo(ShareStop.TOO_BIG)
    assertThat(preparer.infoOf(other)).isNotNull()
    assertThat(leftovers()).containsExactly("Altra.jpg")
  }

  // --- Il provider -----------------------------------------------------------------------------------------------------------------

  @Test
  fun dalProviderUnIdDaSfoglioApreLaCopiaEPrimaNonDichiaraUnaDimensione() = runBlocking<Unit> {
    val before = recentEntries()
    val jpeg = before.first { it.displayName == "Mare 2024.jpg" }
    val heic = before.first { it.displayName == "IMG_1.jpg" } // il nome come sara': un HEIC diventa JPEG
    assertThat(jpeg.size).isEqualTo(DocumentEntry.UNKNOWN_SIZE)
    assertThat(heic.size).isEqualTo(DocumentEntry.UNKNOWN_SIZE)
    assertThat(heic.mime).isEqualTo("image/jpeg")
    assertThat(documents.preparesOnOpen()).isTrue()
    // Sfogliare non legge un byte ne' prepara niente.
    assertThat(source.opened).isEqualTo(0)

    val bytes = readAll(jpeg.id)

    assertThat(verdict(bytes, "image/jpeg")).isEqualTo(LocationVerdict.CLEAR)
    assertThat(documents.entry(jpeg.id)!!.size).isEqualTo(bytes.size.toLong())
    // L'originale del telefono non e' mai stato aperto dal provider.
    assertThat(library.phoneReaders).isEmpty()
    // Una seconda apertura ritrova la copia: non si rifa'.
    readAll(jpeg.id)
    assertThat(source.opened).isEqualTo(1)
  }

  @Test
  fun ilProviderFallisceChiusoAncheConUnIdConsegnato() = runBlocking<Unit> {
    build(stripper = object : LocationStripper {
      override fun strip(src: File, dst: File, mime: String): StripOutcome = StripOutcome.StillHasLocation
    })
    // Un id consegnato quando l'impostazione era spenta e poi accesa: all'apertura vale l'impostazione di adesso.
    setting = false
    val delivered = documents.issue(jpegId)!!
    setting = true

    assertThrows(LocationRemovalRefused::class.java) { runBlocking { documents.open(delivered) } }
    assertThat(library.phoneReaders).isEmpty()
  }

  // --- Quando le copie se ne vanno ----------------------------------------------------------------------------------------------

  @Test
  fun leCopieSiTolgonoConLIdChePassaLaScadenzaEConLAzzeramento() = runBlocking<Unit> {
    val ready = deliverer().prepare(setOf(jpegId, heicId, mp4Id)) as PickerDelivery.Ready
    assertThat(leftovers()).hasSize(3)

    // Revocare un id toglie la sua copia (il blocco si e' richiuso, il selettore annulla).
    documents.revoke(listOf(ready.minted[0]))
    assertThat(preparer.count).isEqualTo(2)
    assertThat(leftovers()).hasSize(2)

    // Passate sei ore senza che nessuno la apra, la copia consegnata scade e se ne va.
    clock.now += DocumentPolicy.DELIVERED_ID_TTL_MS + 1
    assertThat(listOf(jpegId, heicId, mp4Id).map { preparer.infoOf(it) }).containsExactly(null, null, null)
    assertThat(leftovers()).isEmpty()

    // L'azzeramento dell'app le toglie tutte.
    val again = deliverer().prepare(setOf(jpegId, mp4Id)) as PickerDelivery.Ready
    assertThat(again.uris).hasSize(2)
    assertThat(leftovers()).hasSize(2)
    documents.revokeAll()
    assertThat(preparer.count).isEqualTo(0)
    assertThat(leftovers()).isEmpty()
  }

  @Test
  fun unaCopiaAperta_SiRinnovaAdOgniUso_EQuelleDelloSfogliovalgonoMenoDelleConsegnate() = runBlocking<Unit> {
    preparer.prepare(listOf(jpegId), delivered = false)
    clock.now += DocumentPolicy.BROWSE_ID_TTL_MS - 1_000
    preparer.open(jpegId)!!.close() // un uso rinnova la scadenza
    clock.now += DocumentPolicy.BROWSE_ID_TTL_MS - 1_000
    assertThat(preparer.infoOf(jpegId)).isNotNull()
    clock.now += 2_000
    assertThat(preparer.infoOf(jpegId)).isNull()
    assertThat(leftovers()).isEmpty()
  }

  @Test
  fun unMediaNascostoDopoLaPreparazioneNonLasciaLaCopia() = runBlocking<Unit> {
    val entry = recentEntries().first { it.displayName == "Mare 2024.jpg" }
    readAll(entry.id)
    assertThat(preparer.infoOf(jpegId)).isNotNull()

    library.hide(setOf(jpegId))

    assertThat(documents.open(entry.id)).isNull()
    assertThat(preparer.infoOf(jpegId)).isNull()
    assertThat(leftovers()).isEmpty()
  }

  // --- Annullare --------------------------------------------------------------------------------------------------------------------

  /** Un video che si ferma alla seconda lettura finche' il test non lo sblocca: un solo-NAS lento. */
  private class StallingSource(private val bytes: ByteArray, private val started: CompletableDeferred<Unit>, private val gate: CompletableDeferred<Unit>) : ShareSource {
    override suspend fun open(mediaId: MediaId): ShareOpen = ShareOpen.Opened(
      OpenedForShare(
        object : MediaBytes {
          override val size: Long = bytes.size.toLong()

          override suspend fun read(range: LongRange): ByteArray {
            if (range.first > 0) {
              started.complete(Unit)
              gate.await()
            }
            return bytes.copyOfRange(range.first.toInt(), (minOf(range.last, size - 1) + 1).toInt())
          }

          override fun dataSource(): MediaDataSource = throw UnsupportedOperationException()
        },
        "video/mp4",
        "Lento.mp4",
        null,
      ),
    )
  }

  @Test
  fun annullareMentreSiPreparaToglieLeCopieENonLasciaIdNeFileAMeta() = runBlocking<Unit> {
    val started = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    build(shareSource = StallingSource(ShareSamples.gpsMp4(mdatBytes = 3_000_000), started, gate))
    val progress = ArrayList<PickerProgress>()

    val job = async(start = CoroutineStart.UNDISPATCHED) { deliverer().prepare(setOf(mp4Id)) { progress += it } }
    kotlinx.coroutines.withTimeout(10_000) { started.await() } // la prima parte (1 MiB) e' copiata, la seconda aspetta (se la preparazione non parte, il test fallisce)
    assertThat(progress.any { it.bytesDone > 0 && it.bytesDone < it.bytesTotal }).isTrue() // l'avanzamento a byte si vede
    assertThat(leftovers()).isNotEmpty() // il file di lavoro esiste
    job.cancelAndJoin()

    assertThat(leftovers()).isEmpty()
    assertThat(preparer.count).isEqualTo(0)
    gate.complete(Unit)
  }
}
