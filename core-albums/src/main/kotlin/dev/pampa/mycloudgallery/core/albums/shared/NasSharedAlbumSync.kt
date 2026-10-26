package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.AlbumSyncReport
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumSync
import dev.pampa.mycloudgallery.core.model.AlbumId
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
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
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
 * 4. per ogni contributo la testa piu' recente per nome ([AlbumHeadSeries]: anti-rollback ed equivocazione), i segmenti nuovi (gli altri
 *    si riusano), le regole del contributo intero ([AlbumDocs.checkContribution]), l'ora (al piu' 2 minuti nel futuro, altrimenti si aspetta),
 *    le voci retrodatate (§2.4, L59), i tetti per dispositivo; ogni violazione rifiuta **solo quel contributo** (E-SH14), gli altri valgono;
 * 5. la proiezione degli album toccati ([SharedAlbumReplica]: fusione con i permessi e attestazione D9).
 *
 * **Isolamento** (revisione A della Fase 6): un errore qualunque (non un annullamento) resta dentro l'album che l'ha prodotto, si conta in
 * [syncErrors] e il giro va avanti; `sync` non lancia mai, cosi' un album ostile o enorme non ferma la sincronizzazione dell'indice.
 * **Mai un'azione sui file**: questa classe non vede nessuna porta che sposta, carica o copia (§4.3, C10, E-S11).
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

  /** Da dove riparte il giro dopo (rotazione: con il tempo o le letture finite, nessun album resta indietro per sempre). */
  private var cursor = 0

  /** Contributi con voci "dal futuro": finche' l'ora del NAS non arriva a [retryAt] non si riscaricano. */
  private class Hold(val mark: HeadMark, val retryAt: Long)

  private val holds = HashMap<Pair<AlbumId, String>, Hold>()

  private class Round(val registry: RegistryState, val fs: NasFileSystem, val nasNow: Long, val timeUp: () -> Boolean, maxReads: Int) {
    val budget = AlbumReadBudget(maxReads)
    var bytes = 0L
    var read = 0
    var rejected = 0
    var incomplete = false
    val changed = LinkedHashSet<AlbumId>()
  }

  override suspend fun sync(budget: Duration): AlbumSyncReport = mutex.withLock {
    val started = clocks.monotonicMs()
    val timeUp = { clocks.monotonicMs() - started >= budget.inWholeMilliseconds }
    val registry = identity.registry() ?: return@withLock report(null, incomplete = true)
    val share = registry.registry.config.commonShare
    if (!readGate.mayRead(share)) return@withLock report(null, incomplete = false)
    val nasNow = clocks.nasNowMs() ?: return@withLock report(null, incomplete = true)
    val round = Round(registry, fileSystem(share), nasNow, timeUp, limits.maxHeadReads)
    registryBehind = false
    val albums = guarded { albumsToRead(round) }
    if (albums == null) {
      round.incomplete = true
    } else {
      val order = if (albums.isEmpty()) albums else albums.indices.map { albums[(it + cursor) % albums.size] }
      var done = 0
      for (album in order) {
        if (round.timeUp() || round.budget.remaining <= 0) {
          round.incomplete = true
          break
        }
        val ok = guarded { syncAlbum(round, album) }
        if (ok == null) round.incomplete = true
        if (ok == false) {
          // Il NAS non risponde: inutile provare gli altri album in questo giro.
          round.incomplete = true
          break
        }
        done++
      }
      cursor = if (albums.isEmpty()) 0 else (cursor + done) % albums.size
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

  /** Un album: false = il NAS non risponde (si ferma il giro), true = fatto (anche con contributi rifiutati). */
  private suspend fun syncAlbum(round: Round, album: AlbumId): Boolean {
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
      return false
    }
    val readable = registry.registry.devices.filter { it.status == DeviceStatus.APPROVED || it.status == DeviceStatus.RETIRED }.map { it.id }.toSet()
    val listed = listing.names.filter { TrustIds.isValid(it) && it in readable }.toSet()
    val devices = (if (listing.truncated) readable else listed) + stored.keys.filter { it in readable }
    for (device in devices.sorted()) {
      if (round.timeUp() || round.budget.remaining <= 0) {
        round.incomplete = true
        return true
      }
      try {
        readContribution(round, album, device, store.contribution(album, device))
      } catch (e: IOException) {
        return false
      }
    }
    return true
  }

  private suspend fun readContribution(round: Round, album: AlbumId, device: String, stored: StoredContribution?) {
    val read = io { AlbumHeadSeries.readLatest(round.fs, round.registry, album, device, stored?.mark, round.budget) }
    when (read) {
      AlbumHeadRead.None, AlbumHeadRead.NoChange -> Unit
      AlbumHeadRead.OutOfBudget -> round.incomplete = true
      is AlbumHeadRead.Rejected -> round.rejected++
      is AlbumHeadRead.Accepted -> accept(round, album, device, stored, read.head)
    }
  }

  private suspend fun accept(round: Round, album: AlbumId, device: String, stored: StoredContribution?, accepted: AcceptedAlbumHead) {
    if (accepted.registryBehind) registryBehind = true
    val head = accepted.head
    val mark = accepted.mark
    // La stessa testa gia' rifiutata o in attesa: non si riscarica.
    if (store.rejectedHead(album, device) == mark) return
    holds[album to device]?.let { hold -> if (hold.mark == mark && round.nasNow < hold.retryAt) return }
    val skew = TrustLimits.MAX_FUTURE_SKEW_MILLIS
    if (head.createdAt > round.nasNow + skew) {
      hold(round, album, device, mark, head.createdAt - skew)
      return
    }
    val cached = stored?.entries.orEmpty().groupBy { it.segment }
    val fresh = head.segments.filter { it.hash !in cached }
    val needed = fresh.sumOf { it.size }
    if (round.bytes + needed > limits.maxBytesPerRound) {
      // Il tetto e' del giro, non del contributo: si riprende al giro dopo.
      round.incomplete = true
      return
    }
    val context = AlbumSegmentContext(album, device, accepted.account)
    val entries = ArrayList<Pair<AlbumEntry, String>>()
    for (ref in head.segments) {
      val reuse = cached[ref.hash]
      if (reuse != null) {
        reuse.forEach { entries += it.entry to ref.hash }
        continue
      }
      val bytes = when (val got = io { round.fs.read(NasLayout.albumSegment(AlbumIds.text(album), device, ref.hash), ref.size.toInt()) }) {
        // Un segmento elencato ma assente: la testa si riprova al giro dopo, il contributo di prima resta (E-I17).
        NasRead.NotFound -> {
          round.incomplete = true
          return
        }
        NasRead.TooLarge -> return reject(round, album, device, mark)
        is NasRead.Data -> got.bytes
      }
      round.bytes += ref.size
      when (val verdict = AlbumDocs.decodeSegment(bytes, ref, context)) {
        is Verdict.Accepted -> verdict.value.forEach { entries += it to ref.hash }
        is Verdict.Rejected -> return reject(round, album, device, mark)
      }
    }
    if (AlbumDocs.checkContribution(album, entries.map { it.first }) is Verdict.Rejected) return reject(round, album, device, mark)
    val future = entries.map { it.first.at }.filter { it > round.nasNow + skew }
    if (future.isNotEmpty()) {
      hold(round, album, device, mark, future.min() - skew)
      return
    }
    // Tetti per dispositivo: un dispositivo compromesso non riempie la replica creando album o voci senza fine.
    val (deviceEntries, deviceAlbums) = store.footprint(device)
    val entriesAfter = deviceEntries - (stored?.entries?.size ?: 0) + entries.size
    val albumsAfter = deviceAlbums + (if (stored == null) 1 else 0)
    if (entriesAfter > limits.maxEntriesPerDevice || albumsAfter > limits.maxAlbumsPerDevice) return reject(round, album, device, mark)
    // §2.4, L59: una voce nuova (assente o diversa nella testa precedente) con `at` anteriore alla `createdAt` di quella testa meno 2 minuti
    // e' retrodatata: si scarta, e scartata resta nelle teste dopo (la voce identica eredita il segno).
    val before = stored?.entries.orEmpty().associateBy { it.entry.key }
    val result = entries.map { (entry, segment) ->
      val old = before[entry.key]
      val retrodated = when {
        old != null && old.entry == entry -> old.retrodated
        stored == null -> false
        else -> entry.at < stored.createdAt - skew
      }
      StoredEntry(entry, segment, retrodated)
    }
    store.put(StoredContribution(album, device, accepted.account, mark, head.createdAt, head.segments, result))
    store.setRejectedHead(album, device, null)
    holds.remove(album to device)
    round.read++
    round.changed += album
  }

  private suspend fun reject(round: Round, album: AlbumId, device: String, mark: HeadMark) {
    store.setRejectedHead(album, device, mark)
    round.rejected++
  }

  private fun hold(round: Round, album: AlbumId, device: String, mark: HeadMark, retryAt: Long) {
    holds[album to device] = Hold(mark, retryAt)
    round.rejected++
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
    )
  }
}
