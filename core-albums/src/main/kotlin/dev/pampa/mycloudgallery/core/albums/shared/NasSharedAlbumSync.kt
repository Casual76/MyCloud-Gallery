package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.AlbumSyncReport
import dev.pampa.mycloudgallery.core.albums.contract.PublishRules
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumSync
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.AcceptedAlbumHead
import dev.pampa.mycloudgallery.core.trust.data.AlbumDocs
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumIds
import dev.pampa.mycloudgallery.core.trust.data.AlbumSegmentContext
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * LA REPLICA DEGLI ALBUM CONDIVISI (§2.5 "Ricevere", D8; `SharedAlbumSync` del §1.2), nel giro dell'indice dopo indice e dati personali (P13).
 * Un giro:
 *
 * 1. registro accettato, area comune del registro, recinzione D30 ([readGate]), ora del NAS;
 * 2. gli album di `albums/` (al piu' [AlbumSyncLimits.maxAlbumsListed]; con l'elenco inondato si leggono comunque quelli gia' noti), a
 *    rotazione da dove si era fermato il giro prima;
 * 3. per ogni album i dispositivi approvati o dismessi del registro con una cartella; i contributi di revocati, persi, sconosciuti o
 *    dismessi con una testa non congelata escono dalla fusione (A7, E-SH20: restano solo come memoria locale per il ripristino);
 * 4. per ogni contributo la testa piu' recente per nome ([AlbumHeadSeries]: anti-rollback ed equivocazione), i tetti per dispositivo **prima
 *    di scaricare** (R13), i segmenti nuovi (gli altri si riusano), le regole del contributo intero ([AlbumDocs.checkContribution]), l'ora (al
 *    piu' 2 minuti nel futuro, altrimenti si aspetta), le teste e le voci retrodatate (§2.4, L59, R7), i tetti di voci; ogni violazione rifiuta
 *    **solo quel contributo** (E-SH14), gli altri valgono;
 * 5. la proiezione degli album toccati ([SharedAlbumReplica]: fusione con i permessi e attestazione D9).
 *
 * **Isolamento** (revisione A della Fase 6; R2 della revisione di sicurezza della Fase 7): un errore qualunque (non un annullamento) resta
 * dentro l'album che l'ha prodotto, si conta in [syncErrors] e il giro va avanti; `sync` non lancia mai. Un contributo che adesso non si legge
 * (un segmento elencato e sparito, un file che il NAS nega: [ioErrors]) **aspetta** a intervalli crescenti ([ReplicaHold], durevole) e non
 * rende il giro "incompleto": niente riscaricamenti in ciclo. Solo un errore della sessione (il NAS non risponde) ferma il giro. Il cursore
 * della rotazione va oltre l'album finito o fallito; i byte letti si contano ([AlbumSyncReport.bytesRead]) e hanno un tetto per giro e per
 * dispositivo. **Mai un'azione sui file**: questa classe non vede nessuna porta che sposta, carica o copia (§4.3, C10, E-S11).
 */
class NasSharedAlbumSync(
  private val identity: AlbumIdentity,
  private val fileSystem: (String) -> NasFileSystem,
  private val store: AlbumReplicaStore,
  private val replica: SharedAlbumReplica,
  private val clocks: AlbumClocks,
  private val readGate: AlbumReadGate,
  private val limits: AlbumSyncLimits = AlbumSyncLimits(),
  /** Gli album che questo dispositivo conosce da se' (i propri: il pubblicatore), letti anche con l'elenco inondato. */
  private val ownAlbums: suspend () -> Set<AlbumId> = { emptySet() },
  private val io: CoroutineDispatcher = Dispatchers.IO,
  /** R2: quali errori di I/O sono di un file (quel contributo aspetta) e quali della sessione (il giro si ferma). */
  private val ioErrors: AlbumIoErrors = AlbumIoErrors.SESSION,
  /** R2: un segmento della **mia** testa manca dal NAS (tolto dal PC): il pubblicatore lo rimette uguale (`NasAlbumPublisher.repairSegments`). */
  private val ownSegmentMissing: suspend (AlbumId) -> Unit = {},
) : SharedAlbumSync {

  private val mutex = Mutex()

  /** Errori isolati (un album saltato in un giro, si riprova al giro dopo). */
  @Volatile var syncErrors: Int = 0
    private set

  /** L'ultimo giro ha visto una testa che cita un registro piu' nuovo del nostro: va riletto (P13). */
  @Volatile var registryBehind: Boolean = false
    private set

  /** L'elenco di `albums/` era oltre il tetto nell'ultimo giro (inondazione: Diagnostica). */
  @Volatile var listingTruncated: Boolean = false
    private set

  /** R7: teste rifiutate perche' dichiarano un'ora prima di cio' che questo dispositivo ha gia' visto (Diagnostica: solo il numero). */
  @Volatile var retrodatedHeads: Int = 0
    private set

  /** Da dove riparte il giro dopo (rotazione: con il tempo o le letture finite, nessun album resta indietro per sempre). */
  private var cursor = 0

  /** I byte letti dal NAS in un giro (teste e segmenti): il conto del traffico del lavoro (R2). */
  private class CountingFileSystem(private val delegate: NasFileSystem) : NasFileSystem {
    var bytesRead: Long = 0
      private set

    override val shareName: String get() = delegate.shareName

    override fun list(dir: SharePath, limit: Int): NasListing = delegate.list(dir, limit)

    override fun read(path: SharePath, maxBytes: Int): NasRead = delegate.read(path, maxBytes).also { if (it is NasRead.Data) bytesRead += it.bytes.size }

    override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult = delegate.publishExclusive(path, bytes)
  }

  private class Round(val registry: RegistryState, val fs: CountingFileSystem, val nasNow: Long, val timeUp: () -> Boolean, maxReads: Int) {
    val budget = AlbumReadBudget(maxReads)

    /** I byte dei segmenti scaricati nel giro, in tutto e per dispositivo (i tetti di R2). */
    var bytes = 0L
    val bytesByDevice = HashMap<String, Long>()
    var read = 0
    var rejected = 0

    /** DG-3: i rifiuti del giro per motivo ([AlbumRejections]); la somma e' [rejected]. */
    val rejectedBy = HashMap<String, Int>()
    var held = 0
    var incomplete = false
    val changed = LinkedHashSet<AlbumId>()

    fun rejection(reason: String) {
      rejected++
      rejectedBy.merge(reason, 1, Int::plus)
    }
  }

  /** Come e' finito un album nel giro: fatto (anche con contributi rifiutati o in attesa), fermo per il budget, NAS che non risponde. */
  private enum class Pass { DONE, BUDGET, NAS_DOWN }

  override suspend fun sync(budget: Duration): AlbumSyncReport = mutex.withLock {
    val started = clocks.monotonicMs()
    val timeUp = { clocks.monotonicMs() - started >= budget.inWholeMilliseconds }
    val registry = identity.registry() ?: return@withLock report(null, incomplete = true)
    val share = registry.registry.config.commonShare
    if (!readGate.mayRead(share)) return@withLock report(null, incomplete = false)
    val nasNow = clocks.nasNowMs() ?: return@withLock report(null, incomplete = true)
    val round = Round(registry, CountingFileSystem(fileSystem(share)), nasNow, timeUp, limits.maxHeadReads)
    registryBehind = false
    // L'elenco di `albums/`: un errore del NAS non e' un errore dei dati (si riprova al giro dopo), il resto si conta.
    val albums = guarded {
      try {
        albumsToRead(round)
      } catch (e: IOException) {
        null
      }
    }
    if (albums == null) {
      round.incomplete = true
    } else {
      val order = if (albums.isEmpty()) albums else albums.indices.map { albums[(it + cursor) % albums.size] }
      // R2: il cursore va oltre gli album finiti **e** oltre quelli falliti (un album che sbaglia non resta il primo di ogni giro); si ferma
      // sul primo rimasto a meta' per il budget, che al giro dopo riparte per primo con il budget intero.
      var advance = 0
      var deferred = false
      for ((index, album) in order.withIndex()) {
        if (round.timeUp() || round.budget.remaining <= 0) {
          round.incomplete = true
          break
        }
        val pass = guarded { syncAlbum(round, album) }
        if (pass == null || pass == Pass.BUDGET) round.incomplete = true
        if (pass == Pass.BUDGET) deferred = true else if (!deferred) advance = index + 1
        if (pass == Pass.NAS_DOWN) {
          // Il NAS non risponde: inutile provare gli altri album in questo giro.
          round.incomplete = true
          break
        }
      }
      cursor = if (albums.isEmpty()) 0 else (cursor + advance) % albums.size
    }
    guarded { replica.recompute(round.changed) }
    report(round, round.incomplete)
  }

  /** Un errore che non e' un annullamento resta qui: si conta e si va avanti (null = errore). */
  private inline fun <T> guarded(block: () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    syncErrors++
    null
  } catch (e: OutOfMemoryError) {
    syncErrors++
    null
  }

  private suspend fun <T> io(block: () -> T): T = withContext(io) { block() }

  private suspend fun albumsToRead(round: Round): List<AlbumId> {
    val listing = io { round.fs.list(NasLayout.ALBUMS_DIR, limits.maxAlbumsListed) }
    listingTruncated = listing.truncated
    val listed = listing.names.mapNotNull(AlbumIds::parseAlbum)
    // I nomi che non sono id si ignorano (un attaccante puo' riempire la cartella, non farli interpretare).
    return (listed + store.albums() + ownAlbums()).distinct().sortedBy { AlbumIds.text(it) }
  }

  /** Un album: [Pass.NAS_DOWN] se il NAS non risponde (si ferma il giro), [Pass.BUDGET] se un contributo aspetta il budget del giro dopo. */
  private suspend fun syncAlbum(round: Round, album: AlbumId): Pass {
    val text = AlbumIds.text(album)
    val registry = round.registry
    val stored = store.contributions(album).associateBy { it.device }
    // A7, E-SH20: i contributi che il registro di adesso non ammette piu' escono dalla fusione (restano solo come memoria per il ripristino,
    // D11 ed E-SH20); i file restano dove sono.
    for ((_, contribution) in stored) {
      if (!contribution.barred && !SharedAlbumReplica.trusted(registry, contribution)) {
        store.put(contribution.copy(barred = true))
        round.changed += album
      }
    }
    val listing = try {
      io { round.fs.list(NasLayout.albumDir(text), TrustLimits.MAX_INDEX_DEVICES_PER_SHARE) }
    } catch (e: IOException) {
      // R2: la cartella di un album che il NAS nega (un permesso tolto dal PC) ferma solo quell'album.
      if (!ioErrors.isFileError(e)) return Pass.NAS_DOWN
      syncErrors++
      return Pass.DONE
    }
    val readable = registry.registry.devices.filter { it.status == DeviceStatus.APPROVED || it.status == DeviceStatus.RETIRED }.map { it.id }.toSet()
    val listed = listing.names.filter { TrustIds.isValid(it) && it in readable }.toSet()
    // R7: i dispositivi del registro senza una cartella qui e senza un contributo: adesso non hanno nessuna testa.
    if (!listing.truncated) {
      for (device in readable - listed - stored.keys) markSeen(round, album, device)
    }
    val devices = (if (listing.truncated) readable else listed) + stored.keys.filter { it in readable }
    var pass = Pass.DONE
    for (device in devices.sorted()) {
      if (round.timeUp() || round.budget.remaining <= 0) return Pass.BUDGET
      when (readContribution(round, album, device, stored[device])) {
        Pass.DONE -> Unit
        Pass.BUDGET -> pass = Pass.BUDGET
        Pass.NAS_DOWN -> return Pass.NAS_DOWN
      }
    }
    return pass
  }

  /**
   * Un contributo. R2: un errore di I/O di **un file** (testa o segmento) fa aspettare solo questo contributo ([holdAgain]); uno della
   * sessione ferma il giro. Un contributo in attesa non si rilegge finche' l'attesa non scade, salvo che compaia una testa diversa.
   */
  private suspend fun readContribution(round: Round, album: AlbumId, device: String, stored: StoredContribution?): Pass {
    val hold = store.hold(album, device)
    if (hold != null && hold.mark == null && round.nasNow < hold.retryAt) {
      round.held++
      return Pass.DONE
    }
    var current: HeadMark? = null
    return try {
      val read = io { AlbumHeadSeries.readLatest(round.fs, round.registry, album, device, stored?.mark, round.budget) }
      // Le teste si rileggono: un'attesa per una testa illeggibile e' finita.
      if (hold != null && hold.mark == null) store.setHold(album, device, null)
      when (read) {
        AlbumHeadRead.None, AlbumHeadRead.NoChange -> {
          // R7: adesso la serie non ha niente di piu' nuovo di cio' che tengo.
          markSeen(round, album, device)
          Pass.DONE
        }
        AlbumHeadRead.OutOfBudget -> Pass.BUDGET
        is AlbumHeadRead.Rejected -> {
          round.rejection(read.reason.name)
          Pass.DONE
        }
        is AlbumHeadRead.Accepted -> {
          current = read.head.mark
          accept(round, album, device, stored, read.head, hold?.takeIf { it.mark != null })
        }
      }
    } catch (e: IOException) {
      if (!ioErrors.isFileError(e)) return Pass.NAS_DOWN
      holdAgain(round, album, device, hold, current)
      Pass.DONE
    }
  }

  /**
   * La testa [accepted] di [device]: i controlli che costano poco prima di scaricare (ora della testa, R7; tetti per dispositivo, R13; budget
   * del giro e del dispositivo, R2), poi i segmenti nuovi e le regole del contributo intero e delle voci.
   */
  private suspend fun accept(round: Round, album: AlbumId, device: String, stored: StoredContribution?, accepted: AcceptedAlbumHead, hold: ReplicaHold?): Pass {
    if (accepted.registryBehind) registryBehind = true
    val head = accepted.head
    val mark = accepted.mark
    // La stessa testa gia' rifiutata o in attesa: non si riscarica.
    if (store.rejectedHead(album, device) == mark) return Pass.DONE
    if (hold != null && hold.mark == mark && round.nasNow < hold.retryAt) {
      round.held++
      return Pass.DONE
    }
    val skew = TrustLimits.MAX_FUTURE_SKEW_MILLIS
    if (head.createdAt > round.nasNow + skew) {
      holdUntil(round, album, device, mark, head.createdAt - skew)
      return Pass.DONE
    }
    // R7 (L59): l'ora che la testa dichiara non scende sotto quella della testa di prima, ne' sotto l'ultima volta che questo dispositivo ha
    // visto la serie senza di lei ([AlbumReplicaStore.seenAt]): una testa che esisteva gia' l'avrei letta allora.
    val anchor = store.seenAt(album, device)
    if ((stored != null && head.createdAt < stored.createdAt) || (anchor != null && head.createdAt < anchor - PublishRules.HEAD_TIME_TOLERANCE_MS)) {
      retrodatedHeads++
      return reject(round, album, device, mark, AlbumRejections.RETRODATED)
    }
    // R13: i tetti per dispositivo prima di scaricare (le dimensioni le dichiara la testa firmata): album e byte nella replica.
    val footprint = store.footprint(device)
    if (stored == null && footprint.albums + 1 > limits.maxAlbumsPerDevice) return reject(round, album, device, mark, AlbumRejections.OVER_LIMITS)
    if (footprint.bytes - (stored?.bytes ?: 0L) + head.segments.sumOf { it.size } > limits.maxBytesPerDevice) {
      return reject(round, album, device, mark, AlbumRejections.OVER_LIMITS)
    }
    val cached = stored?.entries.orEmpty().groupBy { it.segment }
    val fresh = head.segments.filter { it.hash !in cached }
    val needed = fresh.sumOf { it.size }
    // R2: il tetto del giro e quello di un dispositivo per giro (il suo primo contributo del giro passa comunque): si riprende al giro dopo.
    val deviceSoFar = round.bytesByDevice[device] ?: 0L
    if (round.bytes + needed > limits.maxBytesPerRound || (deviceSoFar > 0 && deviceSoFar + needed > limits.maxBytesPerDeviceRound)) return Pass.BUDGET
    val context = AlbumSegmentContext(album, device, accepted.account)
    val entries = ArrayList<Pair<AlbumEntry, String>>()
    for (ref in head.segments) {
      val reuse = cached[ref.hash]
      if (reuse != null) {
        reuse.forEach { entries += it.entry to ref.hash }
        continue
      }
      val bytes = when (val got = io { round.fs.read(NasLayout.albumSegment(AlbumIds.text(album), device, ref.hash), ref.size.toInt()) }) {
        // R2: un segmento elencato ma assente: il contributo di prima resta (E-I17) e questa testa aspetta, a intervalli crescenti; il giro
        // non e' "incompleto" per questo (niente riscaricamenti in ciclo).
        NasRead.NotFound -> {
          holdAgain(round, album, device, hold, mark)
          if (device == identity.deviceId) guarded { ownSegmentMissing(album) }
          return Pass.DONE
        }
        NasRead.TooLarge -> return reject(round, album, device, mark, RejectReason.TOO_LARGE.name)
        is NasRead.Data -> got.bytes
      }
      round.bytes += ref.size
      round.bytesByDevice[device] = (round.bytesByDevice[device] ?: 0L) + ref.size
      when (val verdict = AlbumDocs.decodeSegment(bytes, ref, context)) {
        is Verdict.Accepted -> verdict.value.forEach { entries += it to ref.hash }
        is Verdict.Rejected -> return reject(round, album, device, mark, verdict.reason.name)
      }
    }
    val whole = AlbumDocs.checkContribution(album, entries.map { it.first })
    if (whole is Verdict.Rejected) return reject(round, album, device, mark, whole.reason.name)
    val future = entries.map { it.first.at }.filter { it > round.nasNow + skew }
    if (future.isNotEmpty()) {
      holdUntil(round, album, device, mark, future.min() - skew)
      return Pass.DONE
    }
    // Tetti per dispositivo: un dispositivo compromesso non riempie la replica creando album o voci senza fine.
    val entriesAfter = footprint.entries - (stored?.entries?.size ?: 0) + entries.size
    if (entriesAfter > limits.maxEntriesPerDevice) return reject(round, album, device, mark, AlbumRejections.OVER_LIMITS)
    // §2.4, L59, R7: una voce nuova (assente o diversa nella testa precedente) piu' vecchia del riferimento e' retrodatata: si scarta, e
    // scartata resta nelle teste dopo (la voce identica eredita il segno). Il riferimento e' l'ora della testa precedente e, piu' forte,
    // l'ultima volta che questo dispositivo ha visto la serie senza le voci nuove meno l'eta' massima di una modifica
    // (`PublishRules.MAX_ENTRY_AGE_MS`: chi pubblica non tiene in coda una modifica piu' a lungo). Senza nessuno dei due (la prima testa letta
    // da un dispositivo che non aveva mai guardato) non si sa niente: L59.
    val reference = listOfNotNull(stored?.createdAt?.minus(skew), anchor?.minus(PublishRules.MAX_ENTRY_AGE_MS + skew)).maxOrNull()
    val before = stored?.entries.orEmpty().associateBy { it.entry.key }
    val result = entries.map { (entry, segment) ->
      val old = before[entry.key]
      val retrodated = when {
        old != null && old.entry == entry -> old.retrodated
        reference == null -> false
        else -> entry.at < reference
      }
      StoredEntry(entry, segment, retrodated)
    }
    store.put(StoredContribution(album, device, accepted.account, mark, head.createdAt, head.segments, result))
    store.setSeenAt(album, device, round.nasNow)
    store.setRejectedHead(album, device, null)
    if (hold != null) store.setHold(album, device, null)
    round.read++
    round.changed += album
    return Pass.DONE
  }

  /** R7: la serie di [device] non ha niente di piu' nuovo di cio' che tengo, adesso (si scrive al piu' ogni [AlbumSyncLimits.seenRefreshMs]). */
  private suspend fun markSeen(round: Round, album: AlbumId, device: String) {
    val seen = store.seenAt(album, device)
    if (seen == null || round.nasNow - seen >= limits.seenRefreshMs) store.setSeenAt(album, device, round.nasNow)
  }

  /** Un contributo rifiutato per [reason] (DG-3): il segno resta, col suo motivo, e il rifiuto si conta nel giro. */
  private suspend fun reject(round: Round, album: AlbumId, device: String, mark: HeadMark, reason: String): Pass {
    store.setRejectedHead(album, device, mark, reason)
    round.rejection(reason)
    return Pass.DONE
  }

  /** Voci o testa "dal futuro": si aspetta che l'ora del NAS ci arrivi (non e' un errore: l'attesa non cresce). */
  private suspend fun holdUntil(round: Round, album: AlbumId, device: String, mark: HeadMark, retryAt: Long) {
    store.setHold(album, device, ReplicaHold(mark, retryAt, attempts = 0))
    round.held++
    round.rejection(AlbumRejections.FUTURE_DATED)
  }

  /** R2: un contributo che adesso non si legge aspetta, ogni volta il doppio (fino al tetto); [mark] null se nemmeno la testa si e' letta. */
  private suspend fun holdAgain(round: Round, album: AlbumId, device: String, previous: ReplicaHold?, mark: HeadMark?) {
    val attempts = (previous?.takeIf { it.mark == mark }?.attempts ?: 0) + 1
    store.setHold(album, device, ReplicaHold(mark, round.nasNow + limits.holdFor(attempts), attempts))
    round.held++
  }

  private suspend fun report(round: Round?, incomplete: Boolean): AlbumSyncReport {
    val views = replica.views.value.values
    return AlbumSyncReport(
      albums = views.size,
      contributionsRead = round?.read ?: 0,
      contributionsRejected = round?.rejected ?: 0,
      entriesDenied = views.sumOf { it.merged.denied.size },
      itemsUnattested = views.sumOf { it.unattested },
      incomplete = incomplete,
      bytesRead = round?.fs?.bytesRead ?: 0,
      contributionsHeld = round?.held ?: 0,
      rejectedByReason = round?.rejectedBy?.toMap().orEmpty(),
    )
  }
}
