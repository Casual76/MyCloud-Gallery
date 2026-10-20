package dev.pampa.mycloudgallery.feature.integrations.provider

import android.database.Cursor
import android.media.MediaDataSource
import android.os.Bundle
import android.os.Looper
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.LocationStripper
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.StripOutcome
import dev.pampa.mycloudgallery.core.metadata.strip.LocationTraceRecheck
import dev.pampa.mycloudgallery.core.metadata.strip.LocationVerdict
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.IntegrationPorts
import dev.pampa.mycloudgallery.feature.integrations.provider.testing.FakeIntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.share.LocationFreeShare
import dev.pampa.mycloudgallery.feature.integrations.share.OpenedForShare
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
import java.io.FileNotFoundException
import java.util.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/**
 * Decisione dell'utente del 20/10/2026 (`LocationFreeDeliveryTest` per il resto): `MediaDocumentsProvider` dall'esterno, con "Rimuovi la posizione"
 * acceso. Un'altra app che apre un documento riceve la copia senza posizione (con la sua dimensione vera, non l'originale); se la posizione non si
 * toglie, o ci vuole troppo, riceve un errore con il motivo, l'utente lo legge, e **nessun descrittore si apre**: mai l'originale al posto della copia.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocationFreeProviderTest {

  @get:Rule val folder = TemporaryFolder()

  private val library = FakeIntegrationLibrary()
  private val labels = DocumentLabels("MyCloud Gallery", "Recenti", "Cartelle NAS")
  private val source = InMemoryShareSource()
  private val scope = CoroutineScope(Dispatchers.Unconfined)
  private val clock = object : DocumentClock {
    var now = 1_000L
    override fun elapsedMs(): Long = now
  }
  private val gate = FakeEntryGate(EntryDecision.ALLOWED)
  private val caller = ScriptedCaller()
  private val opener = FakeProxyOpener()
  private val handlers = CountingHandlers()

  private val jpegId = library.phoneIds()[0]
  private lateinit var jpegBytes: ByteArray
  private lateinit var cache: ShareCache
  private lateinit var documents: LibraryMediaDocuments

  @Before
  fun setUp() {
    IntegrationCounters.reset()
    jpegBytes = ShareSamples.gpsJpeg(folder.root, "originale.jpg")
    source.put(jpegId, jpegBytes, "image/jpeg", "Mare 2024.jpg")
    library.mimeOverride[jpegId] = "image/jpeg"
    library.nameOverride[jpegId] = "Mare 2024.jpg"
    library.sizeOverride[jpegId] = jpegBytes.size.toLong()
    build(source)
    ProviderTuning.reset()
    ProviderTuning.callerChecks = { caller }
    ProviderTuning.proxyOpener = { opener }
    ProviderTuning.handlers = handlers
    ProviderTuning.openDeadlineMs = 400
    ProviderTuning.readDeadlineMs = 200
    caller.documentsUi()
  }

  @After
  fun tearDown() {
    scope.cancel()
    IntegrationPorts.reset()
    ProviderTuning.reset()
    IntegrationCounters.reset()
    opener.cleanup()
  }

  private fun build(shareSource: ShareSource, stripper: LocationStripper = ShareSamples.stripper()) {
    cache = ShareCache(File(folder.root, "cache"))
    val share = LocationFreeShare(cache, shareSource, stripper, FakeCacheUris(), RecordingUriRevoker(), Dispatchers.Unconfined)
    val preparer = PreparedCopies(share, { true }, scope, clock, io = Dispatchers.Unconfined)
    documents = LibraryMediaDocuments(library, labels, DocumentIdTable(clock, Random(9)), clock, preparer)
    IntegrationPorts.install(gate, documents)
  }

  private fun leftovers(): List<String> = File(cache.shareRoot, "").walkTopDown().filter { it.isFile }.map { it.name }.toList()

  private fun provider() = ProviderTestSupport.provider()

  private class Row(val id: String, val name: String, val mime: String, val size: Long?)

  private fun rows(cursor: Cursor): List<Row> = buildList {
    val id = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
    val name = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
    val mime = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
    val size = cursor.getColumnIndexOrThrow(Document.COLUMN_SIZE)
    while (cursor.moveToNext()) add(Row(cursor.getString(id), cursor.getString(name), cursor.getString(mime), if (cursor.isNull(size)) null else cursor.getLong(size)))
  }

  private fun children(provider: MediaDocumentsProvider, id: String): List<Row> =
    provider.query(ProviderTestSupport.childrenUri(id), null, Bundle.EMPTY, null)!!.use { rows(it) }

  private fun rootDocumentId(provider: MediaDocumentsProvider): String =
    provider.query(ProviderTestSupport.rootsUri(), null, Bundle.EMPTY, null)!!.use { it.moveToFirst(); it.getString(it.getColumnIndexOrThrow(Root.COLUMN_DOCUMENT_ID)) }

  /** L'id da sfoglio di "Mare 2024.jpg" nei Recenti. */
  private fun browseId(provider: MediaDocumentsProvider): Row {
    val recent = children(provider, rootDocumentId(provider)).first { it.name == "Recenti" }
    return children(provider, recent.id).first { it.name == "Mare 2024.jpg" }
  }

  private fun verdict(bytes: ByteArray, mime: String): LocationVerdict {
    val file = File(folder.root, "verifica-${System.nanoTime()}").apply { writeBytes(bytes) }
    return try {
      LocationTraceRecheck().check(file, mime)
    } finally {
      file.delete()
    }
  }

  @Test
  fun unaltraAppApreLaCopiaSenzaPosizioneConLaSuaDimensioneVera() {
    val provider = provider()
    val row = browseId(provider)
    // Prima della preparazione la colonna non dichiara una dimensione (la copia e' piu' piccola: una dimensione sbagliata farebbe leggere un file troncato).
    assertThat(row.size).isNull()
    assertThat(row.mime).isEqualTo("image/jpeg")

    ProviderTestSupport.within { provider.openDocument(row.id, "r", null).close() }

    val callback = opener.callbacks.single()
    val size = callback.onGetSize()
    val out = ByteArrayOutputStream()
    var at = 0L
    while (at < size) {
      val buffer = ByteArray(4_096)
      val n = callback.onRead(at, buffer.size, buffer)
      if (n <= 0) break
      out.write(buffer, 0, n)
      at += n
    }
    val bytes = out.toByteArray()
    assertThat(bytes.size.toLong()).isEqualTo(size)
    assertThat(verdict(bytes, "image/jpeg")).isEqualTo(LocationVerdict.CLEAR)
    assertThat(verdict(jpegBytes, "image/jpeg")).isEqualTo(LocationVerdict.HAS_LOCATION)
    // Adesso la colonna dice la dimensione della copia.
    provider.query(ProviderTestSupport.documentUri(row.id), null, Bundle.EMPTY, null)!!.use { cursor ->
      assertThat(rows(cursor).single().size).isEqualTo(size)
    }
    assertThat(library.phoneReaders).isEmpty()
  }

  @Test
  fun senzaLaPosizioneTolta_LAltraAppRiceveUnErroreChiaroENessunDescrittoreSiApre() {
    build(source, stripper = object : LocationStripper {
      override fun strip(src: File, dst: File, mime: String): StripOutcome = StripOutcome.StillHasLocation
    })
    val provider = provider()
    val row = browseId(provider)

    val error = ProviderTestSupport.within { assertThrows(FileNotFoundException::class.java) { provider.openDocument(row.id, "r", null) } }

    assertThat(error.message).isEqualTo("Questo file non si può condividere senza posizione.")
    // Mai l'originale: nessun descrittore, nessuna lettura del telefono, niente in cache.
    assertThat(opener.callbacks).isEmpty()
    assertThat(library.phoneReaders).isEmpty()
    assertThat(leftovers()).isEmpty()
    assertThat(IntegrationCounters.get(IntegrationCounter.PROVIDER_OPEN_REFUSED)).isEqualTo(1L)
    // E l'utente, che e' nell'altra app, lo legge.
    shadowOf(Looper.getMainLooper()).idle()
    assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("Questo file non si può condividere senza posizione.")
  }

  /** Un solo-NAS che non finisce mai di scaricarsi. */
  private class NeverEndingSource(private val bytes: ByteArray, private val started: CompletableDeferred<Unit>) : ShareSource {
    override suspend fun open(mediaId: MediaId): ShareOpen = ShareOpen.Opened(
      OpenedForShare(
        object : MediaBytes {
          override val size: Long = bytes.size.toLong()

          override suspend fun read(range: LongRange): ByteArray {
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
            return bytes
          }

          override fun dataSource(): MediaDataSource = throw UnsupportedOperationException()
        },
        "image/jpeg",
        "Mare 2024.jpg",
        null,
      ),
    )
  }

  @Test
  fun seCiVuoleTroppoLAltraAppRiceveUnErroreEUnaCopiaAMetaNonResta() {
    val started = CompletableDeferred<Unit>()
    build(NeverEndingSource(jpegBytes, started))
    ProviderTuning.prepareDeadlineMs = 250
    val provider = provider()
    val row = browseId(provider)

    val error = ProviderTestSupport.within { assertThrows(FileNotFoundException::class.java) { provider.openDocument(row.id, "r", null) } }

    assertThat(started.isCompleted).isTrue()
    assertThat(error.message).contains("selettore di MyCloud Gallery")
    assertThat(opener.callbacks).isEmpty()
    assertThat(leftovers()).isEmpty()
  }
}
