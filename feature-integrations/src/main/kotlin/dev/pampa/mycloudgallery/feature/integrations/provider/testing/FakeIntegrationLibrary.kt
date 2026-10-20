package dev.pampa.mycloudgallery.feature.integrations.provider.testing

import android.net.Uri
import androidx.core.net.toUri
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.testing.FakePlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticPixels
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineView
import dev.pampa.mycloudgallery.core.library.testing.NasMix
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentReader
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.provider.MediaFileInfo
import dev.pampa.mycloudgallery.feature.integrations.provider.NasFolder
import dev.pampa.mycloudgallery.feature.integrations.provider.NasFolderKey
import dev.pampa.mycloudgallery.feature.integrations.provider.NasReach
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Fase 5B (P10): il telefono finto e il NAS finto del selettore e del provider (fake per i test di P10, P12 e P14; mai nella release: package
// `testing`). Una Libreria sintetica mista (media sul telefono, solo sul NAS, nascosti che **non** sono nell'indice) con i byte virtuali, i
// guasti del NAS (non risponde, si pianta, risponde piano) e un modo di far "sbagliare" la porta (`leakHidden`): restituisce comunque i nascosti,
// per provare che chi la usa li ferma lo stesso (E-H1).
//
// I nomi dei nascosti iniziano con `SEGRETO_`, quelli dei visibili con `IMG_`: un test che cerca `SEGRETO_` o i loro id in un URI, un id di
// documento, una riga o un messaggio trova subito una fuga.

/** Un lettore con byte virtuali deterministici: nessuna allocazione proporzionale alla dimensione (un file da 3 GiB costa niente). */
class FakeDocumentReader(
  override val size: Long,
  private val seed: Long = 1,
  /** Se vero, ogni lettura si pianta per sempre (un NAS che non risponde): si esce solo annullando. */
  private val hangs: Boolean = false,
  /** Quanto dura ogni lettura (tempo virtuale o reale, secondo il test). */
  private val readDelayMs: Long = 0,
) : DocumentReader {

  val reads = AtomicInteger()
  val bytesServed = AtomicLong()
  val maxChunk = AtomicInteger()

  @Volatile
  var closed: Boolean = false
    private set

  override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
    check(!closed) { "Lettore chiuso." }
    reads.incrementAndGet()
    if (hangs) awaitCancellation()
    if (readDelayMs > 0) delay(readDelayMs)
    if (offset >= size) return 0
    val n = minOf(len.toLong(), size - offset).toInt()
    for (i in 0 until n) buf[off + i] = byteAt(offset + i)
    bytesServed.addAndGet(n.toLong())
    maxChunk.accumulateAndGet(n) { a, b -> maxOf(a, b) }
    return n
  }

  /** Il byte in [position]: lo stesso per chi lo chiede, cosi' un test confronta cio' che ha letto senza tenerlo tutto. */
  fun byteAt(position: Long): Byte = ((position * 31 + seed) and 0xFF).toByte()

  override fun close() {
    closed = true
  }
}

class FakeIntegrationLibrary(
  val synthetic: SyntheticLibrary = SyntheticLibrary(seed = 11, count = 600, mix = NasMix(nasOnlyPercent = 50, bothPercentOfPhone = 25)),
) : IntegrationLibrary {

  // --- Cosa c'e' --------------------------------------------------------------------------------------------------------

  /** I media dell'indice (visibili). */
  val visibleIds: List<MediaId> = (0 until synthetic.snapshot.size).map { synthetic.snapshot.idAt(it) }

  /** I nascosti: esistono nel "catalogo" ma mai nell'indice (E-H1). */
  val hiddenIds: List<MediaId> = synthetic.hidden.map { it.id }

  private val hiddenRefs: Map<MediaId, MediaRef> = hiddenIds.withIndex().associate { (n, id) ->
    id to (
      if (n % 2 == 0) MediaRef.Phone("external_primary", 900_000L + n, "image/jpeg") else MediaRef.Nas(900_000L + n, 700L + n, "image/jpeg")
    )
  }

  private val hiddenIndex: Map<MediaId, Int> = hiddenIds.withIndex().associate { (n, id) -> id to n }

  /** Gli indici dei media sul telefono / solo sul NAS fra i visibili. */
  val phoneIndices: List<Int> = (0 until synthetic.snapshot.size).filter { synthetic.phoneRefAt(it) != null }
  val nasOnlyIndices: List<Int> = (0 until synthetic.snapshot.size).filter { synthetic.nasRefAt(it) != null }

  fun phoneIds(): List<MediaId> = phoneIndices.map { visibleIds[it] }

  fun nasOnlyIds(): List<MediaId> = nasOnlyIndices.map { visibleIds[it] }

  // --- Le manopole di un test -------------------------------------------------------------------------------------------

  /** Se vero, la porta "sbaglia": restituisce anche i nascosti (cartelle, informazioni, riferimenti, byte). */
  @Volatile var leakHidden: Boolean = false

  /** Lo stato del NAS per la prova prima della consegna (E-X1). */
  @Volatile var reach: NasReach = NasReach.Reachable

  /** Il NAS non apre: `openNas` risponde null subito. */
  @Volatile var nasOpenFails: Boolean = false

  /** Il NAS si pianta: ogni lettura (e `openNas` se [nasOpenHangs]) non finisce mai. */
  @Volatile var nasReadsHang: Boolean = false
  @Volatile var nasOpenHangs: Boolean = false

  /** Quanto dura ogni lettura del NAS. */
  @Volatile var nasReadDelayMs: Long = 0

  /** Le dimensioni da usare al posto di quelle di serie (E-X6: tanti GiB senza i byte). */
  val sizeOverride: MutableMap<MediaId, Long> = HashMap()

  /** Nomi e tipi da usare al posto di quelli di serie (nomi ostili: percorsi, caratteri di controllo). */
  val nameOverride: MutableMap<MediaId, String> = HashMap()
  val mimeOverride: MutableMap<MediaId, String> = HashMap()

  /** Ganci per fermare una chiamata a metà e provare un annullamento (E-X4). */
  @Volatile var beforeFileInfos: (suspend () -> Unit)? = null
  @Volatile var beforeNasReach: (suspend () -> Unit)? = null

  /** Quello che e' stato aperto, e quanto. */
  val nasReaders: MutableList<FakeDocumentReader> = java.util.Collections.synchronizedList(mutableListOf())
  val phoneReaders: MutableList<FakeDocumentReader> = java.util.Collections.synchronizedList(mutableListOf())
  val fileInfoCalls = AtomicInteger()
  val nasReachCalls = AtomicInteger()

  // --- La porta ---------------------------------------------------------------------------------------------------------

  private val viewFlow = MutableStateFlow<TimelineView>(InMemoryTimelineView(synthetic.snapshot, LibraryFilter.ALL))

  /** Gli elementi: la vista (senza nascosti) e i riferimenti. Con [leakHidden] il riferimento di un nascosto esiste lo stesso. */
  override val items: ViewerItems = object : ViewerItems {
    override val view: StateFlow<TimelineView> = viewFlow

    override fun ref(id: MediaId): MediaRef = refOrNull(id) ?: throw NoSuchElementException("Nessun riferimento.")

    override fun refOrNull(id: MediaId): MediaRef? {
      val i = synthetic.snapshot.indexOf(id)
      if (i >= 0) return if (inView(id) || leakHidden) synthetic.phoneRefAt(i) ?: synthetic.nasRefAt(i) else null
      return if (leakHidden) hiddenRefs[id] else null
    }
  }

  /** Miniature sincrone: un gradiente dal riferimento (telefono o NAS), cosi' le celle degli screenshot non sono tutte uguali. */
  val loader: FakeThumbnailLoader = FakeThumbnailLoader { ref, px ->
    val seed = when (ref) {
      is MediaRef.Phone -> ref.mediaStoreId
      is MediaRef.Nas -> ref.contentKey
      is MediaRef.Synthetic -> ref.seed
    }
    val side = minOf(px, SyntheticPixels.MAX_SIDE)
    SyntheticPixels.bitmap(seed, side, side * 3 / 4)
  }

  override val thumbnails: ThumbnailLoader get() = loader

  override val placeholders: PlaceholderCache = FakePlaceholderCache()

  /** La Libreria cambia (un media viene nascosto): la vista si aggiorna, come il repository vero. */
  fun publish(snapshot: TimelineSnapshot) {
    viewFlow.value = InMemoryTimelineView(snapshot, LibraryFilter.ALL)
  }

  /**
   * Nasconde [ids] **dall'indice**, come fa il catalogo (E-H1): da quel momento non sono piu' nella vista. Per il nascosto contagioso di un
   * gruppo di copie simili (D36) si passano tutti i membri: il catalogo li toglie insieme. La porta continua a conoscerli (sono file sul NAS):
   * e' proprio quello che il selettore e il provider devono reggere.
   */
  fun hide(ids: Set<MediaId>) {
    publish(WithoutMedia(synthetic.snapshot, ids))
  }

  /** Rimette l'indice com'era. */
  fun showAll() {
    publish(synthetic.snapshot)
  }

  /**
   * L'accesso parziale (E-P1, "foto selezionate"), come lo fa `PartialAccessTimeline`: l'**indice** resta intero e la **vista** e' solo la parte che il
   * telefono concede, nello stesso ordine. IN-9 della verifica funzionale 5B: il finto aveva sempre la vista uguale all'indice, e selettore e provider
   * che scorrevano l'indice non se ne accorgevano. Il test di parita' sta in `:app` (`PartialAccessTimelineTest`).
   */
  fun restrictView(allowed: Set<MediaId>) {
    viewFlow.value = SubsetOfSnapshotView(synthetic.snapshot, allowed)
  }

  /** Quante volte e' stato chiesto l'elenco di tutte le cartelle (IN-2: la ricerca di un id stabile dopo un riavvio). */
  val folderIndexCalls = AtomicInteger()

  override suspend fun nasFolderIndex(): List<NasFolder> {
    folderIndexCalls.incrementAndGet()
    return super.nasFolderIndex()
  }

  override fun phoneUri(ref: MediaRef.Phone): Uri = "content://media/${ref.volume}/${ref.mediaStoreId}".toUri()

  override suspend fun fileInfos(ids: List<MediaId>): Map<MediaId, MediaFileInfo> {
    fileInfoCalls.incrementAndGet()
    beforeFileInfos?.invoke()
    val out = LinkedHashMap<MediaId, MediaFileInfo>()
    for (id in ids) infoOf(id)?.let { out[id] = it }
    return out
  }

  /** Il nome, il tipo e la dimensione di un media: dai visibili sempre, dai nascosti solo se la porta "sbaglia". */
  fun infoOf(id: MediaId): MediaFileInfo? {
    val i = synthetic.snapshot.indexOf(id)
    if (i >= 0) {
      if (!inView(id) && !leakHidden) return null
      val kind = synthetic.snapshot.kindAt(i)
      val mime = mimeOverride[id] ?: mimeOf(i, kind)
      val extension = if (kind == MediaKind.VIDEO) "mp4" else if (mime == "image/heic") "heic" else "jpg"
      return MediaFileInfo(nameOverride[id] ?: "IMG_%05d.%s".format(i, extension), mime, sizeOverride[id] ?: (32_000L + i * 17L), 1_700_000_000_000L + i)
    }
    if (!leakHidden) return null
    val n = hiddenIndex[id] ?: return null
    return MediaFileInfo("SEGRETO_%03d.jpg".format(n), "image/jpeg", sizeOverride[id] ?: 20_000L, 1_600_000_000_000L + n)
  }

  override suspend fun openPhone(ref: MediaRef.Phone): DocumentReader? {
    val reader = FakeDocumentReader(size = 40_000L + ref.mediaStoreId % 1000, seed = ref.mediaStoreId)
    phoneReaders += reader
    return reader
  }

  override suspend fun openNas(ref: MediaRef.Nas): DocumentReader? {
    if (nasOpenHangs) awaitCancellation()
    if (nasOpenFails) return null
    val id = idOfNas(ref)
    val size = id?.let { sizeOverride[it] } ?: (30_000L + ref.locationId * 13L)
    val reader = FakeDocumentReader(size = size, seed = ref.locationId, hangs = nasReadsHang, readDelayMs = nasReadDelayMs)
    nasReaders += reader
    return reader
  }

  override suspend fun nasReach(): NasReach {
    nasReachCalls.incrementAndGet()
    beforeNasReach?.invoke()
    return reach
  }

  override suspend fun nasFolders(parent: NasFolderKey?): List<NasFolder> = when (parent) {
    null -> listOf(NasFolder(NasFolderKey("area-privata"), "Area privata"), NasFolder(NasFolderKey("area-comune"), "Area comune"))
    NasFolderKey("area-privata") -> listOf(NasFolder(NasFolderKey("vacanze"), "Vacanze"))
    else -> emptyList()
  }

  /** "Vacanze": i solo-NAS con indice pari. "Area comune": i solo-NAS dispari. Con [leakHidden] ci sono anche i nascosti. */
  override suspend fun mediaIn(folder: NasFolderKey, limit: Int): List<MediaId> {
    val base = when (folder.value) {
      "vacanze" -> nasOnlyIndices.filter { it % 2 == 0 }.map { visibleIds[it] }
      "area-comune" -> nasOnlyIndices.filter { it % 2 == 1 }.map { visibleIds[it] }
      else -> emptyList()
    }
    val all = if (leakHidden && folder.value == "vacanze") hiddenIds + base else base
    return all.take(limit)
  }

  private fun inView(id: MediaId): Boolean = viewFlow.value.snapshot.indexOf(id) >= 0

  private fun idOfNas(ref: MediaRef.Nas): MediaId? {
    val i = ref.locationId.toInt() - 1
    if (i in visibleIds.indices && synthetic.nasRefAt(i)?.locationId == ref.locationId) return visibleIds[i]
    return hiddenRefs.entries.firstOrNull { (it.value as? MediaRef.Nas)?.locationId == ref.locationId }?.key
  }

  private fun mimeOf(i: Int, kind: MediaKind): String = when {
    kind == MediaKind.VIDEO -> "video/mp4"
    kind == MediaKind.ANIMATED_IMAGE -> "image/gif"
    synthetic.phoneRefAt(i) != null -> synthetic.phoneRefAt(i)?.mime ?: "image/jpeg"
    else -> synthetic.nasRefAt(i)?.mime ?: "image/jpeg"
  }

  /** Il flag di presenza di una cella, per i test. */
  fun isNasOnly(index: Int): Boolean = synthetic.snapshot.hasFlag(index, CellFlags.ON_NAS) && !synthetic.snapshot.hasFlag(index, CellFlags.ON_PHONE)
}

/** La vista di un accesso parziale: gli elementi dell'indice che stanno in [allowed], nell'ordine dell'indice (come `SubsetView` di `:app`). */
private class SubsetOfSnapshotView(override val snapshot: TimelineSnapshot, allowed: Set<MediaId>) : TimelineView {
  private val map: IntArray = allowed.map { snapshot.indexOf(it) }.filter { it >= 0 }.sorted().toIntArray()

  override val filter: LibraryFilter get() = LibraryFilter.ALL
  override val size: Int get() = map.size

  override fun snapshotIndex(viewIndex: Int): Int = map[viewIndex]

  override fun viewIndexOf(id: MediaId): Int {
    val index = snapshot.indexOf(id)
    if (index < 0) return -1
    val found = map.binarySearch(index)
    return if (found >= 0) found else -1
  }
}

/** Un indice senza alcuni media, come lo costruirebbe il catalogo dopo averli nascosti. */
private class WithoutMedia(private val base: TimelineSnapshot, excluded: Set<MediaId>) : TimelineSnapshot {
  private val positions: IntArray = (0 until base.size).filter { base.idAt(it) !in excluded }.toIntArray()

  override val size: Int = positions.size
  override val seq: Long = base.seq + 1

  override fun idAt(i: Int) = base.idAt(positions[i])

  override fun sortTimeAt(i: Int) = base.sortTimeAt(positions[i])

  override fun localDayAt(i: Int) = base.localDayAt(positions[i])

  override fun kindAt(i: Int) = base.kindAt(positions[i])

  override fun sourceAt(i: Int) = base.sourceAt(positions[i])

  override fun flagsAt(i: Int) = base.flagsAt(positions[i])

  override fun aspectMilliAt(i: Int) = base.aspectMilliAt(positions[i])

  override fun durationMsAt(i: Int) = base.durationMsAt(positions[i])

  override fun placeAt(i: Int) = base.placeAt(positions[i])

  override fun thumbHashAt(i: Int) = base.thumbHashAt(positions[i])

  override fun groupSizeAt(i: Int) = base.groupSizeAt(positions[i])

  override fun nasRefAt(i: Int) = base.nasRefAt(positions[i])

  override fun indexOf(id: MediaId): Int {
    val i = base.indexOf(id)
    if (i < 0) return -1
    val found = positions.binarySearch(i)
    return if (found >= 0) found else -1
  }
}
