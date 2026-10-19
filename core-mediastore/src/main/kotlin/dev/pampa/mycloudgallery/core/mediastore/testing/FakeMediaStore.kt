package dev.pampa.mycloudgallery.core.mediastore.testing

import android.media.MediaDataSource
import dev.pampa.mycloudgallery.core.mediastore.contract.DeletedRow
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreSource
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.sync.MediaStoreRowLookup
import dev.pampa.mycloudgallery.core.mediastore.sync.OriginalBytesUnavailableException
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Un MediaStore in memoria: il simulatore con cui P2 prova tutta la sincronizzazione senza dispositivo (docs/fase3-progetto.md
 * §5.1). Simula cio' che il MediaProvider fa e che la lettura deve reggere:
 * - volumi montati e smontati (microSD, E-P6), ognuno con versione, generazione che sale a ogni scrittura e `deleted_files`;
 * - versione che cambia con rinumerazione degli `_ID`, anche scambiati (E-P12, E-P22);
 * - righe `IS_PENDING` (invisibili finche' non sono pubblicate), cestino di sistema (`IS_TRASHED`: invisibili, elencate
 *   fra le tolte) e **cestino di Samsung** (la riga sparisce davvero e torna come riga nuova al ripristino, E-P11);
 * - accesso parziale (solo le righe selezionate), permessi ritirati (nessuna riga, aprire e' vietato: E-P1, E-P15) e
 *   permesso della posizione negato (le foto non si aprono: E-P21; [redactedOpenAttempts] conta chi ci prova);
 * - guasti: file che non si aprono, query che falliscono (riavvio a meta' scansione, E-P4);
 * - (Fase 5A, P0, `docs/fase5-progetto.md` §5.2) righe che **cambiano durante una lettura** ([duringRead]): dopo n byte letti il
 *   file viene riscritto sul posto (Samsung "Salva", E-B15), la riga sparisce (E-B4) o il MediaStore si ricostruisce e la riga
 *   cambia `_ID` e versione (E-P12). Lo stato del telefono finto sopravvive alla "morte del processo" dell'app: e' il telefono.
 *
 * **Contenuto.** Senza byte espliciti una riga ha un contenuto "virtuale" della dimensione dichiarata, generato a
 * richiesta (nessun megabyte in memoria): una testa da media (JPEG o MP4) e poi byte pseudo-casuali da un seme che
 * dipende da posto, dimensione e data di modifica. Lo stesso file rimesso uguale ha gli stessi byte; un file modificato
 * (altra dimensione o data) ha byte diversi.
 */
class FakeMediaStore : MediaStoreSource, MediaStoreRowLookup {

  private sealed interface Content {
    val size: Long

    fun read(position: Long, length: Int): ByteArray
  }

  private class Explicit(val bytes: ByteArray) : Content {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, length: Int): ByteArray {
      if (position >= bytes.size || length <= 0) return ByteArray(0)
      val end = minOf(bytes.size.toLong(), position + length).toInt()
      return bytes.copyOfRange(position.toInt(), end)
    }
  }

  private class Virtual(val seed: Long, override val size: Long, val header: ByteArray) : Content {
    override fun read(position: Long, length: Int): ByteArray {
      if (position >= size || length <= 0) return ByteArray(0)
      val n = minOf(size - position, length.toLong()).toInt()
      return ByteArray(n) { i -> FakeMediaContent.byteAt(seed, header, position + i) }
    }
  }

  private class FakeVolume(var version: String, var mounted: Boolean = true) {
    var generation: Long = 1
    val rows = LinkedHashMap<Long, MediaStoreRow>()
    val content = HashMap<Long, Content>()
    val deleted = ArrayList<DeletedRow>()
    val pending = HashSet<Long>()

    /** `_ID` cestinati (cestino di sistema) e la generazione del cestino. */
    val trashed = HashMap<Long, Long>()

    /** La chiave d'ordine di ogni `_ID` mai visto: la ripresa dopo una riga sparita continua da li' (come la keyset). */
    val sortKeys = HashMap<Long, Long>()
    var selection: Set<Long>? = null
  }

  /** Un file nel cestino di Samsung Gallery: fuori dal MediaStore, con i suoi byte (E-P11). */
  class SamsungTrashed internal constructor(internal val row: MediaStoreRow, internal val content: Any)

  /** Una query ricevuta, per i test che controllano ripresa e incrementale. */
  data class QueryCall(val volume: String, val sinceGeneration: Long?, val afterId: Long?, val limit: Int)

  private val volumeMap = LinkedHashMap<String, FakeVolume>()
  private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

  /** Se falso, [generation] e [deletedSince] rispondono come su API 29 (nessuna generazione). */
  var supportsGenerations: Boolean = true

  /** Se falso, [deletedSince] risponde null come senza l'estensione S 23 (`deleted_files` non c'e'). */
  var supportsDeletedFiles: Boolean = true

  /**
   * Se vero, `deleted_files` risponde ma vuota (solo il cestino di sistema): e' cio' che fa il MediaProvider dell'emulatore
   * API 37 con un'app normale, mentre dalla shell elenca le righe.
   */
  var deletedFilesEmptyForApps: Boolean = false

  /** Permessi ritirati mentre l'app lavora (E-P15): nessuna riga e aprire lancia `SecurityException`. */
  var accessRevoked: Boolean = false

  /** Permessi ritirati fra la query e l'apertura: le query rispondono, aprire lancia `SecurityException`. */
  var openRevoked: Boolean = false

  /** `ACCESS_MEDIA_LOCATION`: se falso le foto non si aprono (D9). */
  var mediaLocationGranted: Boolean = true

  /** Gli `_ID` che non si aprono (I/O). */
  val failingOpens: MutableSet<Long> = HashSet()

  /** Se valorizzato, la query numero [failQueryAt] (contando da 1) lancia un errore di I/O: un riavvio a meta'. */
  var failQueryAt: Int? = null

  /** Quante volte e' stata chiamata [open]: la scorciatoia del timbro non deve aprire nessun file. */
  var opens: Int = 0
    private set

  /** Quante volte qualcuno ha provato ad aprire una foto senza il permesso della posizione: deve restare zero (D9). */
  var redactedOpenAttempts: Int = 0
    private set

  /** Byte letti in tutto: nessuna lettura a file interi durante lo scorrimento. */
  var bytesRead: Long = 0
    private set

  /** Le query ricevute, in ordine. */
  val queries: MutableList<QueryCall> = ArrayList()

  /**
   * Fase 5A (§5.2): che cosa succede a una riga **mentre qualcuno la sta leggendo**. Scatta una volta sola, quando la lettura di
   * quella riga (su un `MediaBytes` aperto con [open]) supera [DuringRead.afterBytes] byte.
   */
  sealed interface MidRead {
    /**
     * Il file viene riscritto sul posto (Samsung "Salva", E-B15): la riga ha dimensione, data di modifica e generazione nuove, e chi
     * sta leggendo vede i byte nuovi da quel punto in poi (stesso file sul disco). Senza [content] i byte nuovi sono virtuali.
     */
    class Rewritten(val size: Long, val dateModifiedMs: Long, val content: ByteArray? = null) : MidRead

    /** La riga sparisce (cancellata, E-B4): la lettura successiva fallisce con un errore di I/O. */
    data object Vanished : MidRead

    /**
     * Il MediaStore si ricostruisce (E-P12, E-P22): versione [newVersion] e la riga diventa [newId]. La lettura in corso continua
     * (il descrittore aperto resta valido), ma il vecchio `_ID` non vale piu'.
     */
    class Renumbered(val newVersion: String, val newId: Long) : MidRead
  }

  /** Un evento programmato su una riga. */
  class DuringRead(val volume: String, val id: Long, val afterBytes: Long, val event: MidRead) {
    @Volatile var fired: Boolean = false
      internal set
  }

  private val duringReads = java.util.concurrent.CopyOnWriteArrayList<DuringRead>()

  /** Programma [event] sulla riga [id] di [volume] dopo [afterBytes] byte letti (anche da letture diverse della stessa apertura). */
  fun duringRead(volume: String, id: Long, afterBytes: Long, event: MidRead): DuringRead {
    require(afterBytes >= 0) { "Byte negativi." }
    return DuringRead(volume, id, afterBytes, event).also { duringReads += it }
  }

  /** Gli eventi gia' scattati, in ordine di programmazione. */
  fun firedDuringReads(): List<DuringRead> = duringReads.filter { it.fired }

  /** Applica l'evento: restituisce il contenuto che la lettura in corso vede da qui in poi (null = la riga non c'e' piu'). */
  private fun fire(trigger: DuringRead, current: Content): Content? {
    trigger.fired = true
    val v = volumeMap.getValue(trigger.volume)
    return when (val event = trigger.event) {
      is MidRead.Rewritten -> {
        val old = v.rows[trigger.id] ?: return null
        val row = MediaStoreRow(
          volume = old.volume, id = old.id, type = old.type, displayName = old.displayName, relativePath = old.relativePath,
          mime = old.mime, size = event.size, dateModifiedMs = event.dateModifiedMs, dateTakenMs = old.dateTakenMs, width = old.width,
          height = old.height, orientation = old.orientation, durationMs = old.durationMs, ownerPackage = old.ownerPackage,
        )
        put(row, event.content)
        v.content.getValue(trigger.id)
      }
      MidRead.Vanished -> {
        remove(trigger.volume, trigger.id)
        null
      }
      is MidRead.Renumbered -> {
        bumpVersion(trigger.volume, event.newVersion, mapOf(trigger.id to event.newId))
        current
      }
    }
  }

  fun addVolume(name: String, version: String = "v1", mounted: Boolean = true) {
    volumeMap[name] = FakeVolume(version, mounted)
  }

  /** Monta o smonta un volume (microSD tolta e rimessa, E-P6). */
  fun setMounted(name: String, mounted: Boolean) {
    volumeMap.getValue(name).mounted = mounted
    changed.tryEmit(Unit)
  }

  /** Accesso parziale (E-P1): solo questi `_ID` del volume sono visibili; null = tutti. */
  fun setSelection(volume: String, ids: Set<Long>?) {
    volumeMap.getValue(volume).selection = ids?.toSet()
  }

  /** La riga visibile o no con questo `_ID`, com'e' adesso. */
  fun row(volume: String, id: Long): MediaStoreRow? = volumeMap.getValue(volume).rows[id]

  /**
   * Aggiunge o aggiorna una riga: la sua `GENERATION_MODIFIED` e' la generazione del volume, che sale. Senza [content]
   * il contenuto e' virtuale, della dimensione dichiarata.
   */
  fun put(row: MediaStoreRow, content: ByteArray? = null): MediaStoreRow {
    val volume = volumeMap.getValue(row.volume)
    val stored = store(volume, row, if (content != null) Explicit(content) else virtualFor(row))
    volume.pending -= row.id
    volume.trashed -= row.id
    changed.tryEmit(Unit)
    return stored
  }

  /** Una riga in scrittura (`IS_PENDING=1`): esiste ma nessuno la vede finche' non e' [publish]. */
  fun putPending(row: MediaStoreRow, content: ByteArray? = null): MediaStoreRow {
    val volume = volumeMap.getValue(row.volume)
    val stored = store(volume, row, if (content != null) Explicit(content) else virtualFor(row))
    volume.pending += row.id
    changed.tryEmit(Unit)
    return stored
  }

  /** L'app che scriveva ha finito: `IS_PENDING=0`, la riga diventa visibile con una generazione nuova. */
  fun publish(volume: String, id: Long): MediaStoreRow {
    val v = volumeMap.getValue(volume)
    v.pending -= id
    return touch(v, id)
  }

  /** Toglie una riga: finisce in `deleted_files` con la generazione corrente. */
  fun remove(volume: String, id: Long) {
    val v = volumeMap.getValue(volume)
    v.generation++
    if (v.rows.remove(id) != null) v.deleted += DeletedRow(volume, id, if (supportsGenerations) v.generation else null)
    v.content.remove(id)
    v.pending -= id
    v.trashed -= id
    changed.tryEmit(Unit)
  }

  /** Cestino di sistema (`createTrashRequest`, D14): la riga resta ma non si vede piu' nelle query. */
  fun trash(volume: String, id: Long) {
    val v = volumeMap.getValue(volume)
    require(id in v.rows) { "Riga sconosciuta." }
    v.generation++
    v.trashed[id] = v.generation
    changed.tryEmit(Unit)
  }

  /** Ripristino dal cestino di sistema: la riga torna visibile, stesso `_ID`, generazione nuova. */
  fun untrash(volume: String, id: Long): MediaStoreRow {
    val v = volumeMap.getValue(volume)
    v.trashed -= id
    return touch(v, id)
  }

  /**
   * Cestino di Samsung Gallery (docs/android.md, scoperta 8): il file va in `Android/.Trash/...` con `.nomedia`, quindi
   * per il MediaStore la riga **sparisce** (in `deleted_files`), non risulta cestinata.
   */
  fun samsungTrash(volume: String, id: Long): SamsungTrashed {
    val v = volumeMap.getValue(volume)
    val row = v.rows.getValue(id)
    val content = v.content.getValue(id)
    remove(volume, id)
    return SamsungTrashed(row, content)
  }

  /**
   * Ripristino dal cestino di Samsung: il file torna (nello stesso posto, o in [relativePath]) con la stessa data di
   * modifica e gli stessi byte, ma come **riga nuova** con [newId] (T-09: l'`_ID` non e' garantito).
   */
  fun samsungRestore(trashed: SamsungTrashed, newId: Long, relativePath: String = trashed.row.relativePath): MediaStoreRow {
    val old = trashed.row
    val v = volumeMap.getValue(old.volume)
    val row = MediaStoreRow(
      volume = old.volume, id = newId, type = old.type, displayName = old.displayName, relativePath = relativePath,
      mime = old.mime, size = old.size, dateModifiedMs = old.dateModifiedMs, dateTakenMs = old.dateTakenMs, width = old.width,
      height = old.height, orientation = old.orientation, durationMs = old.durationMs, ownerPackage = old.ownerPackage,
    )
    val stored = store(v, row, trashed.content as Content)
    changed.tryEmit(Unit)
    return stored
  }

  /**
   * Il MediaStore si e' ricostruito (aggiornamento di One UI o Mainline, E-P22): nuova versione, generazioni che
   * ripartono, e gli `_ID` rinumerati secondo [renumber] (vecchio → nuovo; gli id non elencati restano).
   */
  fun bumpVersion(volume: String, newVersion: String, renumber: Map<Long, Long> = emptyMap()) {
    val v = volumeMap.getValue(volume)
    val oldRows = v.rows.values.toList()
    val oldContent = HashMap(v.content)
    val oldPending = HashSet(v.pending)
    val oldTrashed = HashSet(v.trashed.keys)
    v.version = newVersion
    v.generation = 1
    v.rows.clear()
    v.content.clear()
    v.deleted.clear()
    v.pending.clear()
    v.trashed.clear()
    v.sortKeys.clear()
    for (row in oldRows) {
      val newId = renumber[row.id] ?: row.id
      v.generation++
      val stored = MediaStoreRow(
        volume = row.volume, id = newId, type = row.type, displayName = row.displayName, relativePath = row.relativePath,
        mime = row.mime, size = row.size, dateModifiedMs = row.dateModifiedMs, dateTakenMs = row.dateTakenMs,
        width = row.width, height = row.height, orientation = row.orientation, durationMs = row.durationMs,
        generationModified = if (supportsGenerations) v.generation else null, ownerPackage = row.ownerPackage,
      )
      v.rows[newId] = stored
      v.sortKeys[newId] = sortKey(stored)
      v.content[newId] = oldContent[row.id] ?: Explicit(ByteArray(0))
      if (row.id in oldPending) v.pending += newId
      if (row.id in oldTrashed) v.trashed[newId] = v.generation
    }
    changed.tryEmit(Unit)
  }

  // --- Fase 5B (P0-B, docs/fase5-progetto.md §5.2 "Dialoghi di sistema"): i dialoghi di sistema simulati e la riscrittura ------------

  /** Le richieste di sistema con cui l'app tocca i file del telefono: cestino e ripristino, eliminazione, permesso di scrittura. */
  enum class SystemRequest { TRASH, UNTRASH, DELETE, WRITE }

  /** Come risponde l'utente al dialogo di sistema simulato. */
  sealed interface DialogAnswer {
    /** Accetta tutto. */
    data object Accept : DialogAnswer

    /** Rifiuta: niente cambia. */
    data object Refuse : DialogAnswer

    /** Accetta solo una parte: gli `_ID` elencati (il dialogo di sistema non permette di togliere voci, ma la risposta puo' essere parziale). */
    data class AcceptOnly(val ids: Set<Long>) : DialogAnswer

    /**
     * L'app e' stata uccisa mentre il dialogo era aperto: il dialogo e' del sistema e l'utente risponde lo stesso ([userAccepts]), ma
     * l'app **non riceve** la risposta ([DialogResult.delivered] falso). Lo stato del telefono cambia se ha accettato.
     */
    data class AppKilled(val userAccepts: Boolean) : DialogAnswer
  }

  /** Cosa e' successo nel dialogo: [granted] sono le righe su cui la richiesta ha avuto effetto; [delivered] falso se l'app era morta. */
  class DialogResult(val granted: List<MediaRef.Phone>, val delivered: Boolean) {
    override fun toString(): String = "DialogResult(${granted.size} righe, delivered=$delivered)"
  }

  /** Le richieste risolte, in ordine: (tipo, numero di righe chieste). Per provare i lotti da 2.000 e che non si chiede mai piu' del dovuto. */
  val resolvedRequests: MutableList<Pair<SystemRequest, Int>> = ArrayList()

  private val writeGrants = HashSet<Pair<String, Long>>()

  /**
   * Risolve una richiesta di sistema ([PhoneMediaIntents.trashRequest]/`deleteRequest`/`writeRequest`) come la risolverebbe l'utente: gli
   * effetti sul telefono sono quelli di Android (cestino: la riga resta invisibile; eliminazione: la riga sparisce in `deleted_files`;
   * scrittura: il permesso per la riga, [hasWriteGrant]). Le righe che non ci sono (piu') non hanno effetto, come nel dialogo vero.
   */
  fun resolveRequest(request: SystemRequest, refs: List<MediaRef.Phone>, answer: DialogAnswer): DialogResult {
    require(refs.size <= PhoneMediaIntents.MAX_REQUEST_URIS) { "Una richiesta di sistema ha al massimo ${PhoneMediaIntents.MAX_REQUEST_URIS} URI." }
    resolvedRequests += request to refs.size
    val accepted: List<MediaRef.Phone> = when (answer) {
      DialogAnswer.Accept -> refs
      DialogAnswer.Refuse -> emptyList()
      is DialogAnswer.AcceptOnly -> refs.filter { it.mediaStoreId in answer.ids }
      is DialogAnswer.AppKilled -> if (answer.userAccepts) refs else emptyList()
    }
    val applied = accepted.filter { apply(request, it) }
    return DialogResult(applied, delivered = answer !is DialogAnswer.AppKilled)
  }

  private fun apply(request: SystemRequest, ref: MediaRef.Phone): Boolean {
    val v = volumeMap[ref.volume] ?: return false
    if (!v.mounted || ref.mediaStoreId !in v.rows) return false
    when (request) {
      SystemRequest.TRASH -> trash(ref.volume, ref.mediaStoreId)
      SystemRequest.UNTRASH -> untrash(ref.volume, ref.mediaStoreId)
      SystemRequest.DELETE -> remove(ref.volume, ref.mediaStoreId)
      SystemRequest.WRITE -> writeGrants += ref.volume to ref.mediaStoreId
    }
    return true
  }

  /** Se l'app ha il permesso di scrivere sulla riga (dopo un `createWriteRequest` accettato). */
  fun hasWriteGrant(volume: String, id: Long): Boolean = (volume to id) in writeGrants

  /** Il permesso decade con il processo (e col riavvio del telefono): dopo una morte l'app deve chiederlo di nuovo. */
  fun clearWriteGrants() {
    writeGrants.clear()
  }

  /**
   * Riscrive i byte della riga (Samsung "Salva", `PhoneMediaRewriter`): stessa riga e stesso `_ID`, dimensione e data di modifica nuove,
   * generazione che sale. Non cambia il cestino ne' lo stato in attesa. Lancia se la riga non c'e'.
   */
  fun rewrite(volume: String, id: Long, content: ByteArray, dateModifiedMs: Long): MediaStoreRow {
    val v = volumeMap.getValue(volume)
    val old = v.rows.getValue(id)
    val row = MediaStoreRow(
      volume = old.volume, id = old.id, type = old.type, displayName = old.displayName, relativePath = old.relativePath,
      mime = old.mime, size = content.size.toLong(), dateModifiedMs = dateModifiedMs, dateTakenMs = old.dateTakenMs, width = old.width,
      height = old.height, orientation = old.orientation, durationMs = old.durationMs, ownerPackage = old.ownerPackage,
    )
    val stored = store(v, row, Explicit(content.copyOf()))
    changed.tryEmit(Unit)
    return stored
  }

  /** I byte della riga adesso (per i test con file piccoli), o null se la riga non c'e'. */
  fun bytesOf(volume: String, id: Long): ByteArray? {
    val content = volumeMap[volume]?.content?.get(id) ?: return null
    return content.read(0, content.size.toInt())
  }

  /** Se la riga e' nel cestino di sistema (invisibile, ma c'e'). */
  fun isTrashed(volume: String, id: Long): Boolean = volumeMap[volume]?.trashed?.containsKey(id) == true

  // --- MediaStoreSource --------------------------------------------------------------------------------

  override suspend fun volumes(): Set<String> = volumeMap.filterValues { it.mounted }.keys.toSet()

  override suspend fun version(volume: String): String = mounted(volume).version

  override suspend fun generation(volume: String): Long? = if (supportsGenerations) mounted(volume).generation else null

  override suspend fun query(volume: String, sinceGeneration: Long?, afterId: Long?, limit: Int): List<MediaStoreRow> {
    queries += QueryCall(volume, sinceGeneration, afterId, limit)
    failQueryAt?.let { if (queries.size == it) throw IOException("Query finta fallita.") }
    val v = mounted(volume)
    if (accessRevoked) return emptyList()
    // Dalle piu' recenti: DATE_TAKEN DESC, poi _ID DESC (l'ordine di scansione del §2.1).
    var ordered = visible(v).sortedWith(ORDER)
    if (sinceGeneration != null) ordered = ordered.filter { (it.generationModified ?: Long.MAX_VALUE) > sinceGeneration }
    if (afterId != null) {
      val key = v.sortKeys[afterId]
      if (key != null) ordered = ordered.filter { sortKey(it) < key || (sortKey(it) == key && it.id < afterId) }
    }
    return ordered.take(limit)
  }

  override suspend fun deletedSince(volume: String, generation: Long): List<DeletedRow>? {
    if (!supportsGenerations || !supportsDeletedFiles) return null
    val v = mounted(volume)
    val removed = if (deletedFilesEmptyForApps) emptyList() else v.deleted.filter { (it.generationModified ?: 0) > generation }
    val trashed = v.trashed.filter { it.value > generation }.map { DeletedRow(volume, it.key, it.value) }
    return removed + trashed
  }

  override suspend fun allIds(volume: String): LongArray {
    val v = mounted(volume)
    if (accessRevoked) return LongArray(0)
    return visible(v).map { it.id }.toLongArray()
  }

  override suspend fun rowsById(volume: String, ids: Collection<Long>): List<MediaStoreRow> {
    val v = mounted(volume)
    if (accessRevoked) return emptyList()
    val wanted = ids.toSet()
    return visible(v).filter { it.id in wanted }
  }

  override suspend fun open(row: MediaStoreRow): MediaBytes {
    opens++
    if (accessRevoked || openRevoked) throw SecurityException("Permessi ritirati.")
    val v = volumeMap[row.volume]
    if (v == null || !v.mounted) throw FileNotFoundException("Volume non montato.")
    if (row.id in failingOpens) throw IOException("Lettura finta fallita.")
    if (row.type == RowType.IMAGE && !mediaLocationGranted) {
      redactedOpenAttempts++
      throw OriginalBytesUnavailableException()
    }
    val content = v.content[row.id] ?: throw FileNotFoundException("Riga sparita.")
    return FakeBytes(content, row.volume, row.id)
  }

  override fun changes(): Flow<Unit> = changed

  // --- Interni -----------------------------------------------------------------------------------------

  private fun mounted(volume: String): FakeVolume {
    val v = volumeMap[volume] ?: throw IllegalStateException("Volume sconosciuto.")
    if (!v.mounted) throw IllegalStateException("Volume non montato.")
    return v
  }

  private fun visible(v: FakeVolume): List<MediaStoreRow> {
    val selection = v.selection
    return v.rows.values.filter { it.id !in v.pending && it.id !in v.trashed && (selection == null || it.id in selection) }
  }

  private fun store(v: FakeVolume, row: MediaStoreRow, content: Content): MediaStoreRow {
    v.generation++
    val stored = withGeneration(row, if (supportsGenerations) v.generation else null)
    v.rows[row.id] = stored
    v.content[row.id] = content
    v.sortKeys[row.id] = sortKey(stored)
    return stored
  }

  private fun touch(v: FakeVolume, id: Long): MediaStoreRow {
    v.generation++
    val stored = withGeneration(v.rows.getValue(id), if (supportsGenerations) v.generation else null)
    v.rows[id] = stored
    changed.tryEmit(Unit)
    return stored
  }

  private fun virtualFor(row: MediaStoreRow): Content {
    val seed = FakeMediaContent.seedOf(row.relativePath + row.displayName, row.size, row.dateModifiedMs)
    return Virtual(seed, row.size.coerceAtLeast(0), FakeMediaContent.headerFor(row.type))
  }

  private inner class FakeBytes(
    private var content: Content,
    private val volume: String = "",
    private val id: Long = 0,
  ) : MediaBytes, AutoCloseable {
    /** Byte letti da questa apertura (per gli eventi di [duringRead]). */
    private var readHere = 0L

    /** La riga e' sparita durante la lettura: ogni lettura successiva fallisce. */
    private var vanished = false

    override val size: Long get() = content.size

    override suspend fun read(range: LongRange): ByteArray {
      if (vanished) throw IOException("Riga sparita durante la lettura.")
      if (range.isEmpty() || range.first >= content.size) return ByteArray(0)
      val from = maxOf(range.first, 0L)
      val to = minOf(range.last, content.size - 1)
      val chunk = content.read(from, (to - from + 1).toInt())
      bytesRead += chunk.size
      readHere += chunk.size
      for (trigger in duringReads) {
        if (trigger.fired || trigger.volume != volume || trigger.id != id || readHere < trigger.afterBytes) continue
        val next = fire(trigger, content)
        if (next == null) vanished = true else content = next
      }
      return chunk
    }

    override fun dataSource(): MediaDataSource = object : MediaDataSource() {
      override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= content.size) return -1
        val chunk = content.read(position, size)
        System.arraycopy(chunk, 0, buffer, offset, chunk.size)
        return chunk.size
      }

      override fun getSize(): Long = content.size

      override fun close() = Unit
    }

    override fun close() = Unit
  }

  private companion object {
    fun sortKey(row: MediaStoreRow): Long = row.dateTakenMs ?: row.dateModifiedMs

    val ORDER: Comparator<MediaStoreRow> = compareByDescending<MediaStoreRow> { sortKey(it) }.thenByDescending { it.id }

    fun withGeneration(row: MediaStoreRow, generation: Long?): MediaStoreRow = MediaStoreRow(
      volume = row.volume, id = row.id, type = row.type, displayName = row.displayName, relativePath = row.relativePath,
      mime = row.mime, size = row.size, dateModifiedMs = row.dateModifiedMs, dateTakenMs = row.dateTakenMs, width = row.width,
      height = row.height, orientation = row.orientation, durationMs = row.durationMs, generationModified = generation,
      ownerPackage = row.ownerPackage,
    )
  }
}

/**
 * Byte che sembrano un media, per i test: una testa vera (JPEG o MP4) e poi byte pseudo-casuali deterministici dal
 * [seed]. Due contenuti con lo stesso seme e la stessa dimensione sono identici byte per byte.
 */
object FakeMediaContent {

  /** Testa di un JPEG con un segmento APP1 (EXIF). */
  val JPEG_HEADER: ByteArray = bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x10, 0x45, 0x78, 0x69, 0x66, 0x00, 0x00)

  /** Testa di un MP4 (`ftyp mp42`). */
  val MP4_HEADER: ByteArray = bytes(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x6D, 0x70, 0x34, 0x32, 0x00, 0x00, 0x00, 0x00)

  fun headerFor(type: RowType): ByteArray = if (type == RowType.VIDEO) MP4_HEADER else JPEG_HEADER

  fun jpeg(size: Int, seed: Long): ByteArray = ByteArray(size) { i -> byteAt(seed, JPEG_HEADER, i.toLong()) }

  fun mp4(size: Int, seed: Long): ByteArray = ByteArray(size) { i -> byteAt(seed, MP4_HEADER, i.toLong()) }

  internal fun seedOf(place: String, size: Long, modified: Long): Long =
    (place.hashCode().toLong() * 31 + size) * 1_000_003 + modified

  internal fun byteAt(seed: Long, header: ByteArray, position: Long): Byte {
    if (position < header.size) return header[position.toInt()]
    // SplitMix64 sul blocco da 8 byte: deterministico e senza stato.
    var z = seed + (position ushr 3) * -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    z = z xor (z ushr 31)
    return (z ushr (((position and 7L).toInt()) * 8)).toByte()
  }

  private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
