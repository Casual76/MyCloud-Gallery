package dev.pampa.mycloudgallery.feature.integrations.testing

import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentEntry
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentReader
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentRoot
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryGate
import dev.pampa.mycloudgallery.feature.integrations.contract.MediaDocuments
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// Fase 5B (P0-B): i fake delle porte delle integrazioni, per i test di P10 (selettore e provider), P11 e P12 (stati scelti dal test, nessun NAS,
// nessun telefono). Mai nella release: tools/check.ps1 vieta i package `testing` nel dex.

/**
 * Il cancello d'ingresso scelto dal test; di default aperto. Come quello vero, mentre dice [EntryDecision.LOADING] [decideAfterLoading] aspetta che il
 * test lo cambi (IN-3): prima il finto decideva sempre subito, e la corsa del processo freddo non si poteva provare.
 */
class FakeEntryGate(decision: EntryDecision = EntryDecision.ALLOWED) : EntryGate {
  private val current = MutableStateFlow(decision)

  var decision: EntryDecision
    get() = current.value
    set(value) {
      current.value = value
    }

  /** Quante volte e' stato interrogato (un'activity d'ingresso lo chiede prima di mostrare qualunque cosa). */
  var asked: Int = 0
    private set

  override fun decide(): EntryDecision {
    asked++
    return current.value
  }

  /** Quanto aspetta al massimo, qualunque cosa chieda chi lo chiama (per provare lo stato che non arriva mai senza aspettare 10 s). */
  @Volatile
  var loadTimeoutMs: Long? = null

  override suspend fun decideAfterLoading(timeoutMs: Long): EntryDecision {
    asked++
    if (current.value != EntryDecision.LOADING) return current.value
    return withTimeoutOrNull(loadTimeoutMs ?: timeoutMs) { current.first { it != EntryDecision.LOADING } } ?: current.value
  }
}

/** Un documento di prova con i suoi byte; i nascosti li esclude chi costruisce l'elenco, qui non ce ne sono mai. */
class FakeDocument(val entry: DocumentEntry, val bytes: ByteArray = ByteArray(entry.size.toInt()))

/**
 * Il provider dei documenti di prova: una radice con una cartella di documenti. Un documento si apre a intervalli come farebbe il NAS; [openFails]
 * simula il NAS che non risponde (E-X1: `open` risponde null subito, mai un'attesa).
 */
class InMemoryMediaDocuments(documents: List<FakeDocument> = emptyList()) : MediaDocuments {

  private val byId = LinkedHashMap<String, FakeDocument>().apply { documents.forEach { put(it.entry.id.value, it) } }

  /** La radice unica e la sua cartella. */
  val root: DocumentRoot = DocumentRoot("mcg-root", DocumentId("root-recent"))

  /** Se vero, `open` risponde null (NAS irraggiungibile). */
  var openFails: Boolean = false

  /** Quante letture sono state fatte, per provare che una cartella non legge mai i byte. */
  var readCalls: Int = 0
    private set

  override suspend fun roots(): List<DocumentRoot> = listOf(root)

  override suspend fun children(parent: DocumentId, limit: Int): List<DocumentEntry> =
    if (parent == root.rootDocument) byId.values.map { it.entry }.take(limit) else emptyList()

  override suspend fun entry(id: DocumentId): DocumentEntry? = byId[id.value]?.entry

  override suspend fun open(id: DocumentId): DocumentReader? {
    if (openFails) return null
    val document = byId[id.value] ?: return null
    return object : DocumentReader {
      override val size: Long = document.bytes.size.toLong()

      override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        readCalls++
        if (offset >= size) return 0
        val n = minOf(len.toLong(), size - offset).toInt()
        System.arraycopy(document.bytes, offset.toInt(), buf, off, n)
        return n
      }

      override fun close() = Unit
    }
  }
}

/** Esempi per i test e gli screenshot di P10-P12. */
object IntegrationSamples {

  fun document(token: String, name: String = "IMG_1.jpg", mime: String = "image/jpeg", size: Int = 32): FakeDocument =
    FakeDocument(DocumentEntry(DocumentId(token), name, mime, size.toLong(), modifiedAtMillis = 1_000L, isFolder = false), ByteArray(size) { it.toByte() })
}
