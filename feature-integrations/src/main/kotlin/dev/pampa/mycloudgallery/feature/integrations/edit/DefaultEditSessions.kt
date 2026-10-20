package dev.pampa.mycloudgallery.feature.integrations.edit

import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.EditBegin
import dev.pampa.mycloudgallery.core.backup.contract.EditOutcome
import dev.pampa.mycloudgallery.core.backup.contract.EditSession
import dev.pampa.mycloudgallery.core.backup.contract.EditSessionState
import dev.pampa.mycloudgallery.core.backup.contract.EditSessions
import dev.pampa.mycloudgallery.core.backup.contract.EditTarget
import dev.pampa.mycloudgallery.core.backup.contract.EditUnavailable
import dev.pampa.mycloudgallery.core.backup.contract.IncomingLimits
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.versions.StagedVersionFiles
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreSource
import dev.pampa.mycloudgallery.core.mediastore.sync.MediaStoreRowLookup
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingMime
import dev.pampa.mycloudgallery.feature.integrations.share.CacheUris
import dev.pampa.mycloudgallery.feature.integrations.share.EditCounter
import dev.pampa.mycloudgallery.feature.integrations.share.FlowCounters
import dev.pampa.mycloudgallery.feature.integrations.share.ShareCache
import dev.pampa.mycloudgallery.feature.integrations.share.UriRevoker
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Fase 5B (P11, docs/fase5-progetto.md §1.2 "Sessioni (5B)", §2.7 "Modifica con…", D18, D25, D31, E-E3, E-E5, E-E7, E-X1, E-X3, §6.1 "Un editor esterno
// che restituisce un file ostile o rovinato"; contratto `EditSessions` di P0-B): le sessioni di "Modifica con…". Samsung Gallery non risponde ad
// `ACTION_EDIT` (scoperta 11): si apre con `ACTION_VIEW` e l'esito si legge dal MediaStore. Tre vie:
//  - [EditTarget.PHONE_GALLERY]: media del telefono → Samsung Gallery. La riga cambiata e' una versione (D18, la lettura del telefono la riconosce);
//    una riga **nuova** nella stessa cartella con la stessa data di scatto durante la sessione e' una versione dello stesso media (E-X3);
//  - [EditTarget.NAS_CACHE]: media solo-NAS → copia in `cacheDir/edit/` servita da `FileProvider` in lettura e scrittura (`ACTION_EDIT`: Google Foto,
//    OneDrive); byte cambiati → versione (E-E3), un file nuovo altrove con la stessa data di scatto → ricollegato nella sessione;
//  - [EditTarget.NAS_DOWNLOAD]: "Scarica e modifica in Galleria" (copia in `Pictures/MyCloud Gallery`, F4-D20) e poi come il telefono: la modifica
//    diventa una versione del media del NAS (E-E7), non un media nuovo.
//
// **Il risultato di un editor e' input non fidato.** Diventa solo una versione nuova, mai una sostituzione: l'originale resta com'e' (sul telefono, nel
// catalogo, sul NAS). Un file di una copia in cache che non e' un media, e' vuoto, e' oltre i tetti o cambia genere (una foto che diventa video) si
// rifiuta senza toccare niente (RESULT_REFUSED) e la sessione resta aperta. Il percorso di una versione da un solo-NAS e' quello di P8:
// `StagedVersionFiles.keep`, poi la versione nel catalogo, poi `NasVersionEngine.planStaged`.
//
// **Il tempo e' del NAS** (D31): la sessione dura 30 minuti di ora del NAS e sopravvive alla morte del processo (e' nel database). Oltre, un
// cambiamento non e' piu' della sessione: lo sara' di una versione normale (E-E5), che la lettura del telefono riconosce da sola. MC-6 della verifica
// funzionale 5B: una foto del telefono aperta in Galleria **senza** l'ora del NAS (fuori casa a processo freddo) ha una sessione di 30 minuti di
// orologio monotono di questo processo, che un riavvio chiude; le copie dal NAS vogliono comunque l'ora del NAS. Nel dubbio non si
// unisce (piu' di una candidata): un media nuovo costa al piu' un doppione, un'unione sbagliata e' il danno peggiore. Nessun log: solo i contatori.

class DefaultEditSessions(
  private val phone: MediaStoreSource,
  private val rows: MediaStoreRowLookup,
  private val catalog: EditCatalog,
  private val store: EditSessionStore,
  private val nasCopies: EditNasCopies,
  private val downloads: GalleryDownloads,
  private val staged: StagedVersionFiles,
  private val planner: StagedVersionPlanner,
  private val cache: ShareCache,
  private val clock: BackupClock,
  /** Per revocare i permessi sulla copia in cache a sessione finita; senza, il file si toglie lo stesso. */
  private val uris: CacheUris? = null,
  private val revoker: UriRevoker? = null,
  /** D13: in debug con gli account di prova "Modifica con…" tocca solo le cartelle recintate; di default tutte. */
  private val phoneFolderAllowed: (relativePath: String) -> Boolean = { true },
  private val io: CoroutineContext = Dispatchers.IO,
) : EditSessions {

  private val mutex = Mutex()

  /**
   * MC-6 della verifica funzionale 5B: le sessioni di una foto del telefono aperte **senza l'ora del NAS** (fuori casa a processo freddo, o prima
   * del primo contatto SMB) si misurano con l'orologio monotono di questo processo: la scadenza sta qui, non nel database, e un riavvio la chiude.
   */
  private val monotonicDeadlines = HashMap<Long, Long>()

  // --- begin ---------------------------------------------------------------------------------------------------------------------

  override suspend fun begin(mediaId: MediaId, target: EditTarget): EditBegin = mutex.withLock {
    val result = try {
      doBegin(mediaId, target)
    } catch (e: IOException) {
      EditBegin.Unavailable(EditUnavailable.PHONE_FULL)
    } catch (e: IllegalStateException) {
      // Un volume sparito (microSD tolta, E-P6) non e' un errore del programma: la riga non c'e' piu'.
      EditBegin.Unavailable(EditUnavailable.GONE_FROM_PHONE)
    }
    FlowCounters.edit.add(if (result is EditBegin.Started) EditCounter.BEGUN else EditCounter.BEGIN_UNAVAILABLE)
    result
  }

  private suspend fun doBegin(mediaId: MediaId, target: EditTarget): EditBegin {
    // MC-6: una foto del telefono in Galleria Samsung non ha bisogno del NAS. Senza l'ora del NAS la sessione si misura in monotono (sopra); prima
    // rispondeva "Il NAS non risponde" anche fuori casa per una foto che sta sul telefono. Le copie dal NAS vogliono l'ora del NAS (D31).
    val now = clock.nasNowMs()
    if (now == null && target != EditTarget.PHONE_GALLERY) return EditBegin.Unavailable(EditUnavailable.NAS_UNREACHABLE)
    val media = catalog.media(mediaId) ?: return EditBegin.Unavailable(EditUnavailable.NOT_MINE)
    if (!media.mine) return EditBegin.Unavailable(EditUnavailable.NOT_MINE)
    // Una sola sessione aperta per media: la nuova sostituisce la vecchia (che si chiude e toglie la sua copia), ma solo se la nuova nasce davvero:
    // un tentativo che non riesce non deve chiudere una sessione che sta funzionando.
    val previous = store.open().filter { it.session.mediaId == mediaId }
    val started = when (target) {
      EditTarget.PHONE_GALLERY -> beginOnPhone(media, target, media.phoneRefs, now)
      EditTarget.NAS_DOWNLOAD -> when (val download = downloads.downloadForEdit(mediaId)) {
        is GalleryDownload.Saved -> beginOnPhone(media, target, listOf(download.ref), checkNotNull(now))
        GalleryDownload.Unreachable -> EditBegin.Unavailable(EditUnavailable.NAS_UNREACHABLE)
        GalleryDownload.Unavailable -> EditBegin.Unavailable(EditUnavailable.NOT_AVAILABLE)
        GalleryDownload.Full -> EditBegin.Unavailable(EditUnavailable.PHONE_FULL)
      }
      EditTarget.NAS_CACHE -> beginInCache(media, checkNotNull(now))
    }
    if (started is EditBegin.Started) for (old in previous) finish(old, EditSessionState.CANCELLED)
    return started
  }

  private suspend fun beginOnPhone(media: EditMedia, target: EditTarget, refs: List<PhoneRef>, now: Long?): EditBegin {
    for (ref in refs) {
      // Un `_ID` letto con un'altra versione del MediaStore non prova niente (E-P22): la riga non si apre.
      if (ref.mediaStoreVersion != phone.version(ref.volume)) continue
      val row = rows.rowsById(ref.volume, listOf(ref.mediaStoreId)).firstOrNull() ?: continue
      if (!phoneFolderAllowed(row.relativePath)) continue
      val snapshot = EditSnapshot(
        startSize = row.size,
        startModifiedMs = row.dateModifiedMs,
        startGeneration = phone.generation(ref.volume),
        relativePath = row.relativePath,
        takenAtUtcMs = row.dateTakenMs,
        cacheSha256 = null,
      )
      val started = now ?: MONOTONIC_MARK
      val expires = if (now == null) MONOTONIC_MARK else now + EditAttribution.WINDOW_MS
      val session = store.create(EditSessionDraft(media.mediaId, media.currentVersion, target, ref, null, started, expires, snapshot))
      if (now == null) monotonicDeadlines[session.id.value] = clock.monotonicMs() + EditAttribution.WINDOW_MS
      return EditBegin.Started(session)
    }
    return EditBegin.Unavailable(EditUnavailable.GONE_FROM_PHONE)
  }

  /** MC-6: una sessione misurata in monotono (aperta senza l'ora del NAS): inizio e scadenza nel database sono [MONOTONIC_MARK]. */
  private fun isMonotonic(session: EditSession): Boolean =
    session.startedAtNasMillis == MONOTONIC_MARK && session.expiresAtNasMillis == MONOTONIC_MARK

  /**
   * La sessione e' scaduta adesso: con l'ora del NAS (D31), o in monotono per una sessione aperta senza (MC-6). Una sessione monotona che questo
   * processo non conosce (un riavvio, una morte del processo) e' scaduta: il monotono riparte da zero.
   */
  private fun expiredNow(session: EditSession, nasNow: Long?): Boolean = if (isMonotonic(session)) {
    monotonicDeadlines[session.id.value]?.let { clock.monotonicMs() > it } ?: true
  } else {
    nasNow != null && EditAttribution.expired(nasNow, session.expiresAtNasMillis)
  }

  private suspend fun beginInCache(media: EditMedia, now: Long): EditBegin {
    if (!media.hasNasCopy) return EditBegin.Unavailable(EditUnavailable.NOT_AVAILABLE)
    val name = cache.newEditName()
    val file = cache.editFile(name) ?: return EditBegin.Unavailable(EditUnavailable.NOT_AVAILABLE)
    val copied = withContext(io) {
      if (!file.parentFile!!.mkdirs() && !file.parentFile!!.isDirectory) throw IOException("Cartella delle copie non creata.")
      nasCopies.copyCurrent(media.mediaId, file)
    }
    val done = when (copied) {
      is NasCopy.Done -> copied
      NasCopy.Unreachable -> return EditBegin.Unavailable(EditUnavailable.NAS_UNREACHABLE)
      NasCopy.Unavailable -> return EditBegin.Unavailable(EditUnavailable.NOT_AVAILABLE)
      NasCopy.Full -> {
        cache.deleteTree(file)
        return EditBegin.Unavailable(EditUnavailable.PHONE_FULL)
      }
    }
    val snapshot = EditSnapshot(
      startSize = done.size,
      startModifiedMs = 0,
      startGeneration = phone.generation(PRIMARY_VOLUME),
      relativePath = null,
      takenAtUtcMs = media.takenAtUtcMs,
      cacheSha256 = done.sha256,
    )
    val session = store.create(
      EditSessionDraft(media.mediaId, media.currentVersion, EditTarget.NAS_CACHE, null, name, now, now + EditAttribution.WINDOW_MS, snapshot),
    )
    return EditBegin.Started(session)
  }

  // --- settle --------------------------------------------------------------------------------------------------------------------

  override suspend fun settle(session: EditSession): EditOutcome = mutex.withLock {
    val stored = store.find(session.id) ?: return@withLock EditOutcome.Expired
    val outcome = doSettle(stored)
    when (outcome) {
      EditOutcome.Unchanged -> FlowCounters.edit.add(EditCounter.SETTLED_UNCHANGED)
      is EditOutcome.NewVersion -> FlowCounters.edit.add(EditCounter.SETTLED_NEW_VERSION)
      is EditOutcome.NewVersionFromCopy -> FlowCounters.edit.add(EditCounter.SETTLED_FROM_COPY)
      EditOutcome.NewMedia -> FlowCounters.edit.add(EditCounter.SETTLED_NEW_MEDIA)
      EditOutcome.RestoredOriginal -> FlowCounters.edit.add(EditCounter.SETTLED_RESTORED)
      EditOutcome.Expired -> FlowCounters.edit.add(EditCounter.EXPIRED)
      EditOutcome.Unreadable -> FlowCounters.edit.add(EditCounter.UNREADABLE)
    }
    outcome
  }

  private suspend fun doSettle(stored: StoredEditSession): EditOutcome {
    val session = stored.session
    if (session.state.terminal) return if (session.state == EditSessionState.SETTLED) EditOutcome.Unchanged else EditOutcome.Expired
    if (expiredNow(session, clock.nasNowMs())) {
      finish(stored, EditSessionState.EXPIRED)
      return EditOutcome.Expired
    }
    val outcome = try {
      when (session.target) {
        EditTarget.PHONE_GALLERY, EditTarget.NAS_DOWNLOAD -> settleOnPhone(stored)
        EditTarget.NAS_CACHE -> settleInCache(stored)
      }
    } catch (e: IOException) {
      EditOutcome.Unreadable
    } catch (e: SecurityException) {
      // Permessi ritirati: la sessione resta aperta, si riprova (E-X1).
      EditOutcome.Unreadable
    } catch (e: IllegalStateException) {
      // Un volume non montato (microSD tolta): l'esito non si legge adesso, la sessione resta aperta.
      EditOutcome.Unreadable
    }
    // Un esito vero chiude la sessione; "nessun cambiamento" e "non leggibile" la lasciano aperta (l'editor puo' essere ancora aperto).
    if (outcome !is EditOutcome.Unchanged && outcome != EditOutcome.Unreadable && outcome != EditOutcome.Expired) finish(stored, EditSessionState.SETTLED)
    return outcome
  }

  // --- Sul telefono (Samsung Gallery) ---------------------------------------------------------------------------------------------

  private suspend fun settleOnPhone(stored: StoredEditSession): EditOutcome {
    val session = stored.session
    val ref = session.phone ?: return EditOutcome.Unreadable
    // 1. La lettura del telefono si mette in pari: riconosce da sola "Salva" (riga cambiata) e "Ripristina originale" (E-E2).
    catalog.refreshPhone()
    val current = catalog.current(session.mediaId) ?: return EditOutcome.Unreadable
    if (current.versionId != session.versionId) {
      return when (current.kind) {
        VersionKind.ORIGINAL -> EditOutcome.RestoredOriginal
        VersionKind.EDITED -> EditOutcome.NewVersion(current.versionId)
        // Una correzione fatta dall'app nel frattempo non e' del programma di modifica: non e' un esito di sessione.
        VersionKind.CORRECTED -> EditOutcome.Unchanged
      }
    }
    // 2. Nessuna riga cambiata: una copia ("Salva copia") nella stessa cartella con la stessa data di scatto, nata durante la sessione (E-X3).
    val isVideo = catalog.media(session.mediaId)?.isVideo ?: false
    return attributeCopy(stored, isVideo, ownId = ref.mediaStoreId, volumes = setOf(ref.volume), sameFolderOnly = true, toleranceMs = 0)
      ?: ownRowGone(ref)
  }

  /** La riga di partenza non c'e' piu' e non c'e' una copia: l'esito non si legge (E-B4), la sessione resta aperta. */
  private suspend fun ownRowGone(ref: PhoneRef): EditOutcome =
    if (rows.rowsById(ref.volume, listOf(ref.mediaStoreId)).isEmpty()) EditOutcome.Unreadable else EditOutcome.Unchanged

  // --- Copia in cache (Google Foto, OneDrive) --------------------------------------------------------------------------------------

  private suspend fun settleInCache(stored: StoredEditSession): EditOutcome {
    val session = stored.session
    val name = session.cacheName ?: return EditOutcome.Unreadable
    val file = cache.editFile(name) ?: return EditOutcome.Unreadable
    val original = stored.snapshot.cacheSha256 ?: return EditOutcome.Unreadable
    if (!file.isFile) return EditOutcome.Unreadable
    val isVideo = catalog.media(session.mediaId)?.isVideo ?: false
    val digest = withContext(io) { digestOf(file) } ?: return EditOutcome.Unreadable
    if (digest.sha256 == original) {
      // I byte non sono cambiati: l'editor puo' aver salvato altrove (Google Foto di solito scrive un file nuovo). Stessa data di scatto, nata dopo.
      return attributeCopy(stored, isVideo, ownId = null, volumes = setOf(PRIMARY_VOLUME), sameFolderOnly = false, toleranceMs = EditAttribution.NAS_TAKEN_TOLERANCE_MS)
        ?: EditOutcome.Unchanged
    }
    // I byte sono cambiati: l'editor ha scritto nella copia. E' input non fidato: si controlla prima di farne una versione.
    if (!acceptable(file, digest.size, isVideo)) {
      FlowCounters.edit.add(EditCounter.RESULT_REFUSED)
      return EditOutcome.Unreadable
    }
    return recordEdited(session, file, digest)
  }

  /** Il risultato dell'editor e' un media dello stesso genere, non vuoto, sotto i tetti. */
  private suspend fun acceptable(file: File, size: Long, isVideo: Boolean): Boolean {
    if (size <= 0 || size > IncomingLimits.MAX_FILE_BYTES) return false
    val head = withContext(io) { file.inputStream().use { it.readNBytesCompat(IncomingMime.HEAD_BYTES) } }
    val mime = IncomingMime.of(head, null) ?: return false
    return mime.startsWith("video/") == isVideo
  }

  /** `StagedVersionFiles.keep`, poi la versione nel catalogo, poi il lavoro `VERSION` (P8): in questo ordine, ogni passo si puo' ripetere. */
  private suspend fun recordEdited(session: EditSession, file: File, digest: Digest): EditOutcome {
    val work = staged.newWorkFile()
    val kept = withContext(io) {
      try {
        file.copyTo(work, overwrite = false)
        staged.keep(work, digest.sha256, session.mediaId)
      } catch (e: IOException) {
        work.delete()
        null
      }
    } ?: return EditOutcome.Unreadable
    val version = catalog.addEditedVersion(session.mediaId, session.versionId, digest.sha256, digest.quickHash, digest.size)
    if (version == null) {
      // Il media non c'e' piu': il file preparato non serve a nessuno.
      staged.discard(digest.sha256)
      kept.delete()
      return EditOutcome.Unreadable
    }
    planner.plan(session.mediaId, version, digest.sha256, digest.size)
    return EditOutcome.NewVersion(version)
  }

  // --- Una copia salvata dall'editor (E-X3) ------------------------------------------------------------------------------------------

  /**
   * Cerca le righe nate durante la sessione che possono essere la copia dell'editor. Nessuna → null (niente da dire); una sola → si ricollega come
   * versione ([EditOutcome.NewVersionFromCopy]); piu' di una, o il catalogo rifiuta → [EditOutcome.NewMedia] (nel dubbio non si unisce).
   */
  private suspend fun attributeCopy(
    stored: StoredEditSession,
    isVideo: Boolean,
    ownId: Long?,
    volumes: Set<String>,
    sameFolderOnly: Boolean,
    toleranceMs: Long,
  ): EditOutcome? {
    val session = stored.session
    // Un media solo-NAS senza generazioni (Android 10) non ha modo di dire "nata dopo l'inizio": meglio nessun ricollegamento che uno sbagliato.
    if (ownId == null && stored.snapshot.startGeneration == null) return null
    val candidates = ArrayList<MediaStoreRow>()
    for (volume in volumes) {
      if (volume !in phone.volumes()) continue
      var after: Long? = null
      var page = 0
      while (page++ < EditAttribution.SEARCH_PAGES) {
        val rowsPage = phone.query(volume, stored.snapshot.startGeneration, after, EditAttribution.SEARCH_PAGE)
        if (rowsPage.isEmpty()) break
        candidates += rowsPage.filter { EditAttribution.isCopyCandidate(stored.snapshot, it, isVideo, ownId, sameFolderOnly, toleranceMs) }
        // Con le generazioni la pagina e' solo cio' che e' cambiato: una basta. Senza, si scorre il volume.
        if (stored.snapshot.startGeneration != null || rowsPage.size < EditAttribution.SEARCH_PAGE) break
        after = rowsPage.last().id
      }
    }
    if (candidates.isEmpty()) return null
    val only = candidates.singleOrNull() ?: return EditOutcome.NewMedia
    val version = phone.version(only.volume)
    val copy = catalog.mediaOfPhoneRow(PhoneRef(only.volume, only.id, version)) ?: return EditOutcome.Unreadable
    if (copy == session.mediaId) return EditOutcome.Unchanged
    val linked = catalog.linkCopyAsVersion(session.mediaId, copy, session.versionId) ?: return EditOutcome.NewMedia
    return EditOutcome.NewVersionFromCopy(linked)
  }

  // --- Chiudere, annullare, pulire ---------------------------------------------------------------------------------------------------

  /** L'utente rinuncia a una sessione: si chiude, la copia in cache si toglie e i permessi si revocano. */
  suspend fun cancel(session: EditSession) = mutex.withLock {
    val stored = store.find(session.id) ?: return@withLock
    if (!stored.session.state.terminal) finish(stored, EditSessionState.CANCELLED)
  }

  /**
   * Alla partenza dell'app e quando serve: chiude le sessioni scadute (ora del NAS), togliendone le copie in cache. Restituisce i nomi delle copie delle
   * sessioni ancora aperte, per `ShareCache.sweepAtStart(keepEdit = ...)`.
   */
  suspend fun expireStale(): Set<String> = mutex.withLock {
    val now = clock.nasNowMs()
    val keep = HashSet<String>()
    for (stored in store.open()) {
      if (expiredNow(stored.session, now)) {
        finish(stored, EditSessionState.EXPIRED)
        FlowCounters.edit.add(EditCounter.EXPIRED)
      } else {
        stored.session.cacheName?.let { keep += it }
      }
    }
    keep
  }

  /** Chiude [stored] nello stato [state]: revoca i permessi sulla copia e la toglie. Mai un file fuori da `edit/`. */
  private suspend fun finish(stored: StoredEditSession, state: EditSessionState) {
    stored.session.cacheName?.let { name ->
      cache.editFile(name)?.let { file ->
        if (uris != null && revoker != null) {
          try {
            revoker.revoke(uris.uriFor(file))
          } catch (_: IllegalArgumentException) {
            // Un indirizzo che il FileProvider non sa fare: il file si toglie lo stesso.
          }
        }
        withContext(NonCancellable + io) { cache.deleteTree(file) }
      }
    }
    store.setState(stored.session.id, state)
    monotonicDeadlines.remove(stored.session.id.value)
  }

  // --- Piccole cose --------------------------------------------------------------------------------------------------------------------

  private class Digest(val sha256: Sha256, val quickHash: QuickHash, val size: Long)

  /** SHA-256 e `qh1` di un file locale, a blocchi. */
  private fun digestOf(file: File): Digest? = try {
    val size = file.length()
    val sha = MessageDigest.getInstance("SHA-256")
    val regions = QuickHashAlgorithm.regions(size)
    val head = ByteArray(if (regions.size <= 1) size.toInt() else QuickHashAlgorithm.CHUNK_BYTES)
    val tail = ByteArray(if (regions.size <= 1) 0 else QuickHashAlgorithm.CHUNK_BYTES)
    RandomAccessFile(file, "r").use { raf ->
      val buffer = ByteArray(BUFFER)
      var position = 0L
      while (position < size) {
        val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), size - position).toInt())
        if (n <= 0) throw IOException("Lettura accorciata.")
        sha.update(buffer, 0, n)
        overlap(buffer, n, position, head, 0L)
        if (tail.isNotEmpty()) overlap(buffer, n, position, tail, size - tail.size)
        position += n
      }
    }
    Digest(Sha256(sha.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }), QuickHashAlgorithm.compute(size, head, tail), size)
  } catch (_: IOException) {
    null
  }

  /** Copia in [target] (che comincia all'offset [targetStart] del file) la parte di [chunk] (che comincia all'offset [chunkStart]). */
  private fun overlap(chunk: ByteArray, length: Int, chunkStart: Long, target: ByteArray, targetStart: Long) {
    val from = maxOf(chunkStart, targetStart)
    val to = minOf(chunkStart + length, targetStart + target.size)
    if (from >= to) return
    System.arraycopy(chunk, (from - chunkStart).toInt(), target, (from - targetStart).toInt(), (to - from).toInt())
  }

  private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var total = 0
    while (total < limit) {
      val n = read(buffer, total, limit - total)
      if (n < 0) break
      total += n
    }
    return buffer.copyOf(total)
  }

  companion object {
    /** Il volume principale: dove un editor esterno salva le sue copie. */
    const val PRIMARY_VOLUME: String = "external_primary"

    /**
     * MC-6: inizio e scadenza nel database di una sessione aperta senza l'ora del NAS (un'ora del NAS vera non e' mai 0): la sua scadenza sta in
     * memoria, misurata in monotono, e con un riavvio la sessione e' scaduta.
     */
    const val MONOTONIC_MARK: Long = 0L

    private const val BUFFER = 256 * 1024
  }
}
