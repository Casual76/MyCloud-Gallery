package dev.pampa.mycloudgallery.faces

import dev.pampa.mycloudgallery.ai.CatalogAiShareContent
import dev.pampa.mycloudgallery.ai.ContentFact
import dev.pampa.mycloudgallery.ai.ContentFacts
import dev.pampa.mycloudgallery.ai.ShareRowsCache
import dev.pampa.mycloudgallery.ai.monthOf
import dev.pampa.mycloudgallery.core.database.CatalogPhoneCursor
import dev.pampa.mycloudgallery.core.database.CatalogPhoneOnly
import dev.pampa.mycloudgallery.core.database.CellContent
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.faces.compute.FacesItemLookup
import dev.pampa.mycloudgallery.core.faces.contract.FacesArea
import dev.pampa.mycloudgallery.core.faces.contract.FacesCatalog
import dev.pampa.mycloudgallery.core.faces.contract.FacesItem
import dev.pampa.mycloudgallery.core.faces.contract.GoneContents
import dev.pampa.mycloudgallery.core.faces.sync.FacesAttestation
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// IL CATALOGO PER I VOLTI (Fase 8, P13; docs/fase8-progetto.md §1.4, §1.5: "FacesCatalog sopra MediaCatalog", nessuna query nuova sul catalogo). Le
// stesse letture del calcolo AI (righe dei file presenti per share, media del solo telefono, chiavi di contenuto delle celle), rimesse nella forma che
// i volti vogliono: aree, mesi, sorgente, bit `DOCUMENT`, ritagli. Solo dati locali del catalogo, mai un record remoto. Nessun log (D37).

/**
 * Le chiavi di contenuto delle celle della Libreria, in memoria: per ogni cella (il media o la copertina di un gruppo) le chiavi sue e dei membri;
 * all'incontrario, per ogni chiave la cella che la contiene (D26: una persona compare nella cella che contiene il suo volto). Si rilegge a pagine
 * (`cellContentKeys`) solo se il catalogo e' cambiato (`changeSeq`) e al piu' ogni [REFRESH_NANOS]: una cella nuova entra nelle viste delle persone
 * entro quel tempo. Una lettura che fallisce lascia la mappa di prima.
 */
class FacesCellIndex(
  private val catalog: suspend () -> MediaCatalog?,
  private val nanos: () -> Long = System::nanoTime,
  private val refreshNanos: Long = REFRESH_NANOS,
) {
  private val mutex = Mutex()
  private var seq = -1L
  private var at = 0L
  private var built = false
  private var cells: Map<MediaId, CellContent> = emptyMap()
  private var reverse: Map<ContentKey, MediaId> = emptyMap()

  /** Vero dopo la prima lettura riuscita: finche' non lo e', "nessuna cella" non vuol dire "nessun media" (chi toglie dati non si fida). */
  val isBuilt: Boolean get() = built

  /** Le celle che contengono [keys] (una chiave sconosciuta non c'e'). */
  suspend fun cellsWith(keys: Collection<ContentKey>): Map<ContentKey, Set<MediaId>> {
    refresh()
    val map = reverse
    val out = LinkedHashMap<ContentKey, Set<MediaId>>()
    for (key in keys) map[key]?.let { out[key] = setOf(it) }
    return out
  }

  /** Le chiavi di contenuto di ogni cella di [ids] (membri compresi); una cella che non c'e' piu' non compare. */
  suspend fun keysOf(ids: Collection<MediaId>): Map<MediaId, Set<ContentKey>> {
    refresh()
    val map = cells
    val missing = ids.filter { it !in map }
    val fresh = if (missing.isEmpty()) emptyMap() else catalog()?.cellContentKeysOf(missing).orEmpty().associateBy { it.mediaId }
    val out = LinkedHashMap<MediaId, Set<ContentKey>>()
    for (id in ids) (map[id] ?: fresh[id])?.let { out[id] = it.keys.toSet() }
    return out
  }

  suspend fun cellOf(key: ContentKey): MediaId? {
    refresh()
    return reverse[key]
  }

  private suspend fun refresh() {
    val current = catalog() ?: return
    val now = nanos()
    if (built && now - at in 0 until refreshNanos) return
    mutex.withLock {
      val again = nanos()
      if (built && again - at in 0 until refreshNanos) return
      val newSeq = try {
        current.changeSeq()
      } catch (e: IOException) {
        return
      }
      if (built && newSeq == seq) {
        at = again
        return
      }
      try {
        val forward = LinkedHashMap<MediaId, CellContent>()
        val back = HashMap<ContentKey, MediaId>()
        var after = 0L
        while (true) {
          val page = current.cellContentKeys(after, PAGE)
          for (cell in page.cells) {
            forward[cell.mediaId] = cell
            for (key in cell.keys) back.putIfAbsent(key, cell.mediaId)
          }
          after = page.next ?: break
        }
        cells = forward
        reverse = back
        seq = newSeq
        at = again
        built = true
      } catch (e: IOException) {
        // La mappa di prima resta: si riprova alla chiamata dopo.
      }
    }
  }

  companion object {
    const val PAGE: Int = 2_000

    /** La mappa non si rifa' piu' spesso di cosi', anche se il catalogo cambia di continuo (una scansione): 30 secondi. */
    const val REFRESH_NANOS: Long = 30_000_000_000L
  }
}

/** Le due share di questo account che Persone conosce (D14, D16): l'area privata dichiarata e l'area comune del registro; null = non si sa. */
class FacesShares(private val privateOf: () -> String?, private val commonOf: () -> String?) {
  fun privateShare(): String? = privateOf()

  fun commonShare(): String? = commonOf()

  suspend fun of(area: FacesArea): String? = when (area) {
    FacesArea.PRIVATE -> privateOf()
    FacesArea.COMMON -> commonOf()
    FacesArea.PHONE_ONLY -> null
  }
}

/**
 * [FacesCatalog] e [FacesItemLookup] sopra [MediaCatalog]: i media dell'area privata e dell'area comune dai file **presenti** della share (con la
 * chiave di contenuto di `ContentKey.of(impronta rapida, dimensione)`), quelli del solo telefono dalle loro pagine. I nascosti ci sono (si calcolano,
 * non si mostrano: D28). Ogni elenco ricorda in [facts] a quale versione di quale media appartiene la chiave: le porte che ricevono **solo la
 * chiave** (i byte del telefono) la ritrovano da li'.
 */
class CatalogFacesCatalog(
  private val catalog: suspend () -> MediaCatalog?,
  private val account: suspend () -> AccountId?,
  private val shares: FacesShares,
  private val facts: ContentFacts,
  private val cells: FacesCellIndex,
  /** D13 (debug con un account di prova): una riga del solo telefono fuori dalle cartelle recintate non si calcola. Di serie tutte. */
  private val phoneAllowed: suspend (MediaCatalog, CatalogPhoneOnly) -> Boolean = { _, _ -> true },
  freshNanos: Long = SHARE_FRESH_NANOS,
  nanos: () -> Long = System::nanoTime,
) : FacesCatalog, FacesItemLookup {

  private val rows = ShareRowsCache(freshNanos, nanos)
  private val phoneMutex = Mutex()
  private var phoneSeq = -1L
  private var phoneItems: List<CatalogPhoneOnly> = emptyList()

  override suspend fun months(area: FacesArea): List<String> = when (area) {
    FacesArea.PHONE_ONLY -> phoneOnly().map { monthOf(it.localDay) }.distinct().sortedDescending()
    else -> shareRows(area).map { monthOf(it.second.localDay) }.distinct().sortedDescending()
  }

  override suspend fun items(area: FacesArea, month: String): List<FacesItem> {
    val current = catalog() ?: return emptyList()
    if (area == FacesArea.PHONE_ONLY) {
      return phoneOnly().filter { monthOf(it.localDay) == month }.map { item ->
        facts.put(item.contentKey, ContentFact(item.mediaId, item.versionId, item.size, item.source, item.quickHash))
        FacesItem(item.contentKey, item.mediaId, item.kind, item.source, document = false, cutout = false, month = month, area = area)
      }.let { flagged(current, it) }
    }
    val inMonth = shareRows(area).filter { monthOf(it.second.localDay) == month }
    val items = inMonth.map { (key, row) ->
      facts.put(key, ContentFact(row.mediaId, row.versionId, row.stamp.size, row.source, row.quickHash))
      FacesItem(key, row.mediaId, row.kind, row.source, document = false, cutout = isCutoutPath(row.path.value), month = month, area = area)
    }
    return flagged(current, items)
  }

  override suspend fun cellKeys(media: Collection<MediaId>): Map<MediaId, Set<ContentKey>> = cells.keysOf(media)

  override suspend fun mediaWith(keys: Collection<ContentKey>): Map<ContentKey, Set<MediaId>> = cells.cellsWith(keys)

  /** Gli elementi di un insieme di chiavi (per il buffer: quali anteprime servono ancora); solo i media del NAS di questo account. */
  override suspend fun itemsOf(keys: Collection<ContentKey>): Collection<FacesItem> {
    if (keys.isEmpty()) return emptyList()
    val current = catalog() ?: return emptyList()
    val wanted = keys.toHashSet()
    val out = ArrayList<FacesItem>()
    for (area in listOf(FacesArea.PRIVATE, FacesArea.COMMON)) {
      for ((key, row) in shareRows(area)) {
        if (key !in wanted) continue
        out += FacesItem(key, row.mediaId, row.kind, row.source, document = false, cutout = isCutoutPath(row.path.value), month = monthOf(row.localDay), area = area)
      }
    }
    return flagged(current, out)
  }

  /** Il bit `DOCUMENT` della cella di ogni elemento (derivato dall'indice AI, D34): un documento non si analizza (D18). */
  private suspend fun flagged(current: MediaCatalog, items: List<FacesItem>): List<FacesItem> {
    if (items.isEmpty()) return items
    val flags = HashMap<MediaId, Int>()
    for (row in current.indexRows(items.map { it.media })) flags[MediaId(row.mediaId)] = row.cellFlags
    return items.map { item ->
      val document = (flags[item.media] ?: 0) and CellFlags.DOCUMENT != 0
      if (document == item.document) item else item.copy(document = document)
    }
  }

  private suspend fun shareRows(area: FacesArea): List<Pair<ContentKey, dev.pampa.mycloudgallery.core.database.NasWorkItem>> {
    val current = catalog() ?: return emptyList()
    val owner = account() ?: return emptyList()
    val share = shares.of(area) ?: return emptyList()
    return rows.rowsOf(current, owner, share)
  }

  /** I media del solo telefono (dal piu' recente), riletti solo se il catalogo e' cambiato. */
  private suspend fun phoneOnly(): List<CatalogPhoneOnly> {
    val current = catalog() ?: return emptyList()
    return phoneMutex.withLock {
      val seq = current.changeSeq()
      if (seq == phoneSeq) return@withLock phoneItems
      val out = ArrayList<CatalogPhoneOnly>()
      var cursor: CatalogPhoneCursor? = null
      while (true) {
        val page = current.phoneOnlyWork(cursor, PHONE_PAGE)
        if (page.isEmpty()) break
        for (item in page) if (phoneAllowed(current, item)) out += item
        if (page.size < PHONE_PAGE) break
        cursor = page.last().cursor
      }
      phoneItems = out
      phoneSeq = seq
      out
    }
  }

  companion object {
    const val PHONE_PAGE: Int = 500

    /** Come per il calcolo AI: le righe di una share valgono 10 secondi (e finche' il catalogo non cambia). */
    const val SHARE_FRESH_NANOS: Long = 10_000_000_000L

    /** Un ritaglio del soggetto (D18, D47): una cartella `Ritagli` nel percorso. Il nome della cartella e' quello di `CutoutFence`. */
    fun isCutoutPath(path: String): Boolean = path.split('/').dropLast(1).any { it.equals(CUTOUTS_FOLDER, ignoreCase = true) }

    const val CUTOUTS_FOLDER: String = "Ritagli"
  }
}

/**
 * "M3 dei volti" (§2.4): delle chiavi date, quelle che sono la chiave di contenuto di un file **presente** del NAS di questo account (area privata o
 * area comune) con timbro e impronta rapida dell'ultimo elenco. Solo per quelle si pubblica una voce `faces`; i media del solo telefono restano locali.
 */
class CatalogFacesAttestation(
  private val catalog: suspend () -> MediaCatalog?,
  private val account: suspend () -> AccountId?,
  private val shares: FacesShares,
) : FacesAttestation {
  private val content = CatalogAiShareContent(catalog, account)

  override suspend fun publishable(keys: Collection<ContentKey>): Set<ContentKey> {
    if (keys.isEmpty()) return emptySet()
    val out = LinkedHashSet<ContentKey>()
    for (share in listOfNotNull(shares.privateShare(), shares.commonShare()).distinctBy { it.lowercase(Locale.ROOT) }) {
      out += content.owned(share, keys)
    }
    return out
  }
}

/**
 * I contenuti i cui volti escono dal raggruppamento (E-F16, E-F39): fra le chiavi date, quelle senza piu' una cella nel catalogo di questo
 * dispositivo o la cui cella e' diventata un documento. **Fallisce chiuso**: finche' la mappa delle celle non e' stata letta (o se il catalogo non
 * risponde) non esce nessuno; i nascosti non escono mai (si raggruppano, non si mostrano).
 */
class CatalogGoneContents(private val catalog: suspend () -> MediaCatalog?, private val cells: FacesCellIndex) : GoneContents {
  override suspend fun gone(keys: Collection<ContentKey>): Set<ContentKey> {
    if (keys.isEmpty()) return emptySet()
    val current = catalog() ?: return emptySet()
    val located = cells.cellsWith(keys)
    if (!cells.isBuilt) return emptySet()
    val out = LinkedHashSet<ContentKey>()
    val cellsOf = LinkedHashSet<MediaId>()
    for (key in keys) {
      val cell = located[key]?.firstOrNull()
      if (cell == null) out += key else cellsOf += cell
    }
    if (cellsOf.isNotEmpty()) {
      val documents = current.indexRows(cellsOf).filter { it.cellFlags and CellFlags.DOCUMENT != 0 }.mapTo(HashSet()) { MediaId(it.mediaId) }
      if (documents.isNotEmpty()) for (key in keys) if (located[key]?.firstOrNull() in documents) out += key
    }
    return out
  }
}
