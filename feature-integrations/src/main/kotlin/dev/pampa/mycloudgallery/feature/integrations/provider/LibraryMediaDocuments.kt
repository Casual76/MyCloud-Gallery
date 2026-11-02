package dev.pampa.mycloudgallery.feature.integrations.provider

import android.content.Context
import android.provider.DocumentsContract
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.integrations.R
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentEntry
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentReader
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentRoot
import dev.pampa.mycloudgallery.feature.integrations.contract.MediaDocuments
import dev.pampa.mycloudgallery.feature.integrations.share.ShareNames
import kotlinx.coroutines.CancellationException

// Fase 5B (P10, docs/fase5-progetto.md §2.7, D23, C17; E-H1, E-X1): i documenti del provider, sopra la Libreria per le altre app.
// Implementa la porta `MediaDocuments` di P0-B (radice, cartelle, documenti, apertura) e ci aggiunge cio' che serve al selettore per
// **consegnare** un media solo-NAS ([DocumentIssuer]).
//
// Le regole, tutte provate da `LibraryMediaDocumentsTest` e `HiddenNeverLeavesTest`:
//  - **la vista di adesso e' l'unico elenco** (E-H1): un media si elenca, si descrive e si apre solo se e' nella vista della Libreria ora
//    (che non ha i nascosti, ne' i membri di un gruppo di copie simili con un nascosto, ne' cio' che il telefono non concede). Gli id che
//    la porta del NAS dovesse restituire per sbaglio ("Cartelle NAS") non escono comunque; un media nascosto dopo che il suo id e' nato non
//    si apre piu' e il suo id si revoca;
//  - **id opachi**: solo token casuali ([DocumentIdTable]); i nomi dei file sono dati dell'utente e non entrano mai nell'id;
//  - **nomi sicuri**: il nome visualizzato non porta mai un separatore di percorso ne' caratteri di controllo (le app che lo usano come
//    nome di file non possono essere portate fuori dalla loro cartella);
//  - **scadenza** (E-X1): un id scaduto o mai emesso in questo avvio non apre niente.

/** I nomi delle cartelle virtuali, nella lingua dell'utente (stringhe dell'app, non dati). */
class DocumentLabels(val root: String, val recent: String, val nasFolders: String) {
  companion object {
    /** I tre nomi dalle stringhe di questo modulo: e' cio' che `:app` passa a [LibraryMediaDocuments]. */
    fun from(context: Context): DocumentLabels = DocumentLabels(
      root = context.getString(R.string.integrations_documents_root_title),
      recent = context.getString(R.string.integrations_documents_recent),
      nasFolders = context.getString(R.string.integrations_documents_nas_folders),
    )
  }
}

/**
 * Chi puo' consegnare un media a un'altra app come documento (il selettore, per i media solo-NAS) e dire se un id e' stato consegnato.
 * L'implementa [LibraryMediaDocuments]; `:app` la installa come `IntegrationPorts.documents`.
 */
interface DocumentIssuer {

  /**
   * Un id **consegnato** per [id], o null se il media non e' nella vista della Libreria adesso (nascosto, sparito, non concesso). Un id
   * consegnato vale anche quando l'app si richiude e vale piu' a lungo di uno da sfoglio: l'utente ha sbloccato e ha toccato.
   */
  fun issue(id: MediaId): DocumentId?

  /** Se [id] e' stato consegnato da [issue] e non e' scaduto. */
  fun isDelivered(id: DocumentId): Boolean

  /** Revoca [ids] (un annullamento a meta': E-X4). Da quel momento non aprono piu' niente. */
  fun revoke(ids: Collection<DocumentId>)

  /**
   * Revoca ogni id (consegnato o da sfoglio) dei media [ids] e toglie le loro copie senza posizione: un "Nascondi" fatto adesso non aspetta che la
   * vista della Libreria si aggiorni (I4 della revisione di sicurezza della Fase 10). Un descrittore che un'altra app ha gia' aperto non si puo'
   * richiamare: resta suo.
   */
  fun revokeMedia(ids: Collection<MediaId>)

  /** Revoca ogni id non fisso (azzeramento dell'app, famiglia che cambia). */
  fun revokeAll()
}

class LibraryMediaDocuments(
  private val library: IntegrationLibrary,
  private val labels: DocumentLabels,
  private val table: DocumentIdTable = DocumentIdTable(),
  private val clock: DocumentClock = DocumentClock.Monotonic,
  /**
   * "Rimuovi la posizione" (decisione del 20/10/2026): con l'impostazione accesa un documento si apre **solo** come copia senza posizione preparata da
   * qui; di serie ([NoPreparation]) l'impostazione non c'e' e si apre l'originale, come prima.
   */
  private val preparer: DeliveryPreparer = NoPreparation,
  /**
   * Il filtro finale dei nascosti (I4 della revisione di sicurezza della Fase 10): la vista della Libreria si aggiorna dopo la calma (2-3 secondi), quindi
   * un media nascosto un attimo fa e' ancora nella vista; elenchi, descrizioni e aperture lo chiedono **anche** al catalogo (`:app` lo lega a
   * `GalleryGraph.hiddenMask`, che conosce anche i gruppi e i nascosti arrivati da un altro dispositivo). Di serie non nasconde niente (le prove).
   */
  private val hiddenMask: HiddenMask = HiddenMask { emptySet() },
) : MediaDocuments, DocumentIssuer {

  /** Quando e' stata l'ultima ricerca di una cartella fra tutte quelle note (IN-2): non piu' di una ogni [REDISCOVER_GAP_MS]. */
  private var lastRediscoveryAt: Long = Long.MIN_VALUE

  // --- MediaDocuments ---------------------------------------------------------------------------------------------------

  override suspend fun roots(): List<DocumentRoot> = listOf(DocumentRoot(ROOT_ID, table.idFor(DocumentTarget.Root)))

  override suspend fun children(parent: DocumentId, limit: Int): List<DocumentEntry> {
    val cap = limit.coerceIn(0, DocumentPolicy.MAX_CHILDREN)
    if (cap == 0) return emptyList()
    val found = found(parent) ?: return emptyList()
    return when (val target = found.target) {
      DocumentTarget.Root -> listOf(directory(table.idFor(DocumentTarget.Recent), labels.recent), directory(table.idFor(DocumentTarget.NasFolders), labels.nasFolders))
      DocumentTarget.Recent -> recent(cap)
      DocumentTarget.NasFolders -> folders(null, cap)
      is DocumentTarget.Folder -> {
        val sub = folders(target.key, cap)
        sub + mediaIn(target.key, cap - sub.size)
      }
      is DocumentTarget.Media -> emptyList()
    }
  }

  override suspend fun entry(id: DocumentId): DocumentEntry? {
    val found = found(id) ?: return null
    return when (val target = found.target) {
      DocumentTarget.Root -> directory(id, labels.root)
      DocumentTarget.Recent -> directory(id, labels.recent)
      DocumentTarget.NasFolders -> directory(id, labels.nasFolders)
      is DocumentTarget.Folder -> directory(id, found.label ?: labels.nasFolders)
      is DocumentTarget.Media -> {
        if (!stillVisible(id, target.id)) return null
        library.fileInfos(listOf(target.id))[target.id]?.let { file(id, target.id, it) }
      }
    }
  }

  override suspend fun open(id: DocumentId): DocumentReader? {
    val target = (found(id)?.target as? DocumentTarget.Media) ?: return null
    // La vista di ADESSO: un media nascosto dopo la nascita dell'id, o sparito, non si apre piu' (E-H1).
    if (!stillVisible(id, target.id)) return null
    // "Rimuovi la posizione" acceso: si apre solo la copia senza posizione (se non c'e' la si prepara, e se non si riesce non si apre niente: mai
    // l'originale al suo posto).
    if (preparer.removeLocation()) {
      return when (val batch = preparer.prepare(listOf(target.id), delivered = table.isDelivered(id))) {
        is PreparedBatch.Ready -> preparer.open(target.id)
        is PreparedBatch.Refused -> throw LocationRemovalRefused(batch.stop)
      }
    }
    return when (val ref = library.items.refOrNull(target.id)) {
      is MediaRef.Phone -> library.openPhone(ref)
      is MediaRef.Nas -> library.openNas(ref)
      is MediaRef.Synthetic, null -> null
    }
  }

  // --- DocumentIssuer ---------------------------------------------------------------------------------------------------

  override fun issue(id: MediaId): DocumentId? {
    if (!isVisible(id)) {
      IntegrationCounters.add(IntegrationCounter.HIDDEN_STOPPED)
      return null
    }
    return table.idFor(DocumentTarget.Media(id), delivered = true)
  }

  override fun isDelivered(id: DocumentId): Boolean = table.isDelivered(id)

  override fun preparesOnOpen(): Boolean = preparer.removeLocation()

  override fun revoke(ids: Collection<DocumentId>) {
    releaseCopiesOf(ids)
    table.revoke(ids)
  }

  override fun revokeMedia(ids: Collection<MediaId>) {
    if (ids.isEmpty()) return
    table.revokeMedia(ids)
    preparer.release(ids)
  }

  override fun revokeAll() {
    table.revokeAll()
    preparer.releaseAll()
  }

  /** Le copie senza posizione degli id [ids] (se sono id di media) si tolgono con loro. */
  private fun releaseCopiesOf(ids: Collection<DocumentId>) {
    val media = ids.mapNotNull { (table.resolve(it) as? DocumentResolution.Found)?.target as? DocumentTarget.Media }.map { it.id }
    if (media.isNotEmpty()) preparer.release(media)
  }

  // --- Elenchi ----------------------------------------------------------------------------------------------------------

  private suspend fun recent(limit: Int): List<DocumentEntry> {
    // IN-9: la vista della Libreria, non l'indice intero (con l'accesso parziale la vista e' una parte dell'indice).
    val view = view()
    val snapshot = view.snapshot
    val ids = ArrayList<MediaId>(minOf(limit, view.size))
    var i = 0
    while (i < view.size && ids.size < limit) ids += snapshot.idAt(view.snapshotIndex(i++))
    return files(ids)
  }

  private suspend fun folders(parent: NasFolderKey?, limit: Int): List<DocumentEntry> =
    library.nasFolders(parent).take(limit).map { folder ->
      directory(table.idFor(DocumentTarget.Folder(folder.key), label = safeName(folder.name, FALLBACK_FOLDER)), safeName(folder.name, FALLBACK_FOLDER))
    }

  private suspend fun mediaIn(folder: NasFolderKey, limit: Int): List<DocumentEntry> {
    if (limit <= 0) return emptyList()
    // La porta puo' sbagliare (restituire un nascosto, un gruppo contagiato): si tiene solo cio' che la vista di adesso mostra (E-H1).
    val offered = library.mediaIn(folder, limit)
    val visible = offered.filter { isVisible(it) }
    IntegrationCounters.add(IntegrationCounter.HIDDEN_STOPPED, (offered.size - visible.size).toLong())
    return files(visible.take(limit))
  }

  /** Le voci di [ids] che il catalogo conosce, nell'ordine dato. Ogni id e' gia' nella vista: lo garantiscono i chiamanti. */
  private suspend fun files(candidates: List<MediaId>): List<DocumentEntry> {
    if (candidates.isEmpty()) return emptyList()
    // Il filtro finale: la vista puo' avere ancora un media nascosto un attimo fa (I4).
    val hidden = hiddenNow(candidates)
    if (hidden.isNotEmpty()) stopHidden(hidden)
    val ids = candidates.filter { it !in hidden }
    if (ids.isEmpty()) return emptyList()
    val infos = library.fileInfos(ids)
    return ids.mapNotNull { id -> infos[id]?.let { file(table.idFor(DocumentTarget.Media(id)), id, it) } }
  }

  // --- Voci -------------------------------------------------------------------------------------------------------------

  private fun directory(id: DocumentId, name: String): DocumentEntry =
    DocumentEntry(id, name, DocumentsContract.Document.MIME_TYPE_DIR, 0L, null, isFolder = true)

  /**
   * La voce di un media. Con "Rimuovi la posizione" acceso descrive la **copia**, non l'originale: nome, tipo e dimensione veri se la copia c'e' gia',
   * altrimenti nome e tipo come saranno (un HEIC diventa JPEG) e **nessuna dimensione** (la copia e' piu' piccola: una dimensione sbagliata farebbe
   * leggere un file troncato a chi si fida della colonna).
   */
  private fun file(id: DocumentId, media: MediaId, info: MediaFileInfo): DocumentEntry {
    if (!preparer.removeLocation()) {
      return DocumentEntry(id, safeName(info.displayName, FALLBACK_FILE), safeMime(info.mime), info.size.coerceAtLeast(0), info.modifiedAtMillis, isFolder = false)
    }
    preparer.infoOf(media)?.let {
      return DocumentEntry(id, safeName(it.displayName, FALLBACK_FILE), safeMime(it.mime), it.size.coerceAtLeast(0), info.modifiedAtMillis, isFolder = false)
    }
    val mime = if (info.mime.lowercase().let { m -> m == "image/heic" || m == "image/heif" }) "image/jpeg" else info.mime
    return DocumentEntry(
      id, safeName(ShareNames.of(info.displayName, mime), FALLBACK_FILE), safeMime(mime), DocumentEntry.UNKNOWN_SIZE, info.modifiedAtMillis, isFolder = false,
    )
  }

  // --- Visibilita' (E-H1) -----------------------------------------------------------------------------------------------

  private fun view(): TimelineView = library.items.view.value

  private fun isVisible(id: MediaId): Boolean = view().viewIndexOf(id) >= 0

  /**
   * Vero se il media di [token] e' ancora nella vista **e** il filtro finale non lo dice nascosto adesso (I4); altrimenti lo conta, revoca il token e
   * dice di no.
   */
  private suspend fun stillVisible(token: DocumentId, id: MediaId): Boolean {
    if (isVisible(id) && hiddenNow(listOf(id)).isEmpty()) return true
    IntegrationCounters.add(IntegrationCounter.HIDDEN_STOPPED)
    table.revoke(listOf(token))
    // Un media nascosto non lascia nemmeno la sua copia senza posizione in cache.
    preparer.release(listOf(id))
    return false
  }

  /** Quali di [ids] sono nascosti **adesso** per il filtro finale. Un errore di lettura vale "tutti nascosti" (si fallisce chiusi). */
  private suspend fun hiddenNow(ids: Collection<MediaId>): Set<MediaId> {
    if (ids.isEmpty()) return emptySet()
    return try {
      hiddenMask.hiddenAmong(ids)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      ids.toSet()
    }
  }

  /** I media [hidden] non escono: li conta e toglie ogni loro id e ogni loro copia. */
  private fun stopHidden(hidden: Set<MediaId>) {
    IntegrationCounters.add(IntegrationCounter.HIDDEN_STOPPED, hidden.size.toLong())
    table.revokeMedia(hidden)
    preparer.release(hidden)
  }

  private suspend fun found(id: DocumentId): DocumentResolution.Found? = when (val r = table.resolve(id)) {
    is DocumentResolution.Found -> r
    DocumentResolution.Expired -> {
      IntegrationCounters.add(IntegrationCounter.PROVIDER_EXPIRED)
      null
    }
    DocumentResolution.Unknown -> rediscoverFolder(id)
  }

  /**
   * IN-2: un id sconosciuto puo' essere quello stabile di una cartella che DocumentsUI ricorda da prima del riavvio del processo (la tabella e' in
   * memoria e riparte vuota). Si ricalcola l'id di ogni cartella nota alla Libreria con la stessa chiave: se ce n'e' una, la tabella la ricorda e si
   * risponde come se non fosse mai sparita. Un id che non e' di nessuna cartella (un media scaduto, un id inventato) resta sconosciuto: nessun
   * oracolo, e la ricerca non si ripete piu' di una volta ogni pochi secondi (un id sbagliato dopo l'altro non fa leggere il catalogo ogni volta).
   */
  private suspend fun rediscoverFolder(id: DocumentId): DocumentResolution.Found? {
    val now = clock.elapsedMs()
    if (lastRediscoveryAt != Long.MIN_VALUE && now - lastRediscoveryAt < REDISCOVER_GAP_MS) return null
    lastRediscoveryAt = now
    for (folder in library.nasFolderIndex()) {
      if (table.folderIdOf(folder.key) != id) continue
      table.rememberFolder(id, folder.key, safeName(folder.name, FALLBACK_FOLDER))
      return table.resolve(id) as? DocumentResolution.Found
    }
    return null
  }

  companion object {
    /** L'id della radice per il sistema: una costante dell'app, non un dato. */
    const val ROOT_ID: String = "mcg-root"

    /** Fra due ricerche di una cartella fra tutte quelle note (IN-2). */
    private const val REDISCOVER_GAP_MS = 5_000L

    private const val FALLBACK_FILE = "media"
    private const val FALLBACK_FOLDER = "cartella"
    private const val MAX_NAME_LENGTH = 200
    private val MIME = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}$")

    /**
     * Un nome da mostrare e da usare come nome di file dall'altra app: senza separatori di percorso, senza caratteri di controllo, mai solo
     * punti, al massimo [MAX_NAME_LENGTH] caratteri. Il contenuto resta quello dell'utente (non e' un id).
     */
    internal fun safeName(raw: String, fallback: String): String {
      val cleaned = buildString {
        for (c in raw) {
          when {
            c == '/' || c == '\\' -> append('_')
            c.isISOControl() || c == ' ' || c == ' ' || c == '‮' -> Unit
            else -> append(c)
          }
        }
      }.trim().take(MAX_NAME_LENGTH).trim()
      return if (cleaned.isEmpty() || cleaned.all { it == '.' }) fallback else cleaned
    }

    internal fun safeMime(raw: String): String {
      val lower = raw.trim().lowercase()
      return if (MIME.matches(lower)) lower else "application/octet-stream"
    }
  }
}
