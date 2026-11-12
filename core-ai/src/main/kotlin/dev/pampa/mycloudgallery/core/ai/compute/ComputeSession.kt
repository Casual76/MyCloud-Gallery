package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidate
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiLive
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.AiRunResult
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.KeepAliveResult
import dev.pampa.mycloudgallery.core.index.contract.UnitClaim
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WorkUnitKey
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Una tappa del calcolo (§2.2, tabella "La tappa di un'unita'"). Vive per una chiamata di [AiComputeEngine.run]: tempo, regole,
 * conteggi. Le tre strade: [share] (unita' con prenotazione, o solo buffer con `LOCAL_BUFFER_ONLY`), [phoneOnly].
 */
internal class ComputeSession(
  private val parts: AiComputeParts,
  computer: MediaComputer,
  private val recipe: AiRecipe,
  private val config: AiComputeConfig,
  private val counters: AiComputeCounters,
  private val nanos: () -> Long,
  private val pause: suspend (Duration) -> Unit,
  private val budget: Duration,
  private val control: AiRunControl,
  private val state: MutableStateFlow<AiStatus>,
  /** IX-7: gli errori passeggeri del motore per chiave, per tutto il processo (li tiene il motore). */
  strikes: MutableMap<ContentKey, Int> = HashMap(),
  /** Pacchetto X: il calcolo mentre scorre, per la pagina "Analisi delle foto" (solo in memoria, mai nei log). */
  private val live: MutableStateFlow<AiLive> = MutableStateFlow(AiLive.EMPTY),
  /**
   * XS-3: la tappa e' solo per i media chiesti dall'utente ("Analizza adesso"): lavora quelli e si ferma, il resto resta al lavoro in
   * background con le sue regole. Di serie falso: la tappa lavora tutto, come sempre.
   */
  private val only: Boolean = false,
) {
  private val start = nanos()
  private val tally = Tally()
  private val worker = ItemWorker(parts, computer, recipe, counters, strikes)
  private var pace: AiPace.Go = AiPace.Go()

  private fun elapsed(): Duration = (nanos() - start).nanoseconds

  private fun left(): Duration = budget - elapsed()

  fun result(stop: AiStop?): AiRunResult =
    AiRunResult(tally.computed, tally.skipped, tally.waiting, tally.failed, stop, parts.images.backend.value)

  /** E-A17: sotto 1 GiB libero il buffer si svuota e il calcolo si ferma. */
  suspend fun storageLow(): Boolean {
    if (parts.freeSpace.usableBytes() >= AiBuffer.MIN_FREE_BYTES) return false
    val buffer = parts.buffer
    if (buffer is DiskAiBuffer) buffer.clear() else buffer.evict(tally.finished)
    counters.storageLow.incrementAndGet()
    // IX-4: un problema con la sua azione ("Libera spazio"), non solo un'attesa.
    state.value = AiStatus.Problem(AiDiag.AI_STORAGE_LOW)
    return true
  }

  /** Il controllo prima di ogni lotto: tempo, spazio, ruolo, regole. Null = si continua (con [pace] aggiornato). */
  private suspend fun gate(): AiStop? {
    if (!parts.roles.indexer.value) return AiStop.ROLE_OFF
    if (!left().isPositive()) return AiStop.BUDGET
    if (storageLow()) return AiStop.STORAGE_LOW
    return when (val p = control.check()) {
      is AiPace.Halt -> p.stop
      is AiPace.Go -> {
        pace = p
        null
      }
    }
  }

  /** Il controllo prima di ogni media (E-A13: il ruolo spento ferma al media dopo; il tempo idem). */
  private fun quickGate(): AiStop? = when {
    !parts.roles.indexer.value -> AiStop.ROLE_OFF
    !left().isPositive() -> AiStop.BUDGET
    else -> null
  }

  private fun batchSize() = if (pace.reduced) config.reducedBatch else config.batch

  private suspend fun breath() {
    if (pace.reduced && config.reducedPause.isPositive()) pause(config.reducedPause)
  }

  // ---- Share -----------------------------------------------------------------------------------------------------------

  /**
   * AI-D1 (trovato da P10): una morte fra il salvataggio di un media e lo svuotamento di fine unita' (o di fine tappa del telefono)
   * lasciava le sue anteprime nel buffer per sempre, perche' [Tally.finished] conosce solo i media di questa tappa e un media salvato
   * non torna piu' candidato. A inizio tappa si tolgono quindi i blob dei media che [AiComputeParts.results] dice finiti qui; in una
   * share con la rete anche quelli che un altro indicizzatore ha gia' fatto (un'unita' finita da lui non torna piu' da noi).
   */
  private suspend fun pruneFinished(share: String?) {
    val buffer = parts.buffer as? DiskAiBuffer ?: return
    val done = try {
      val keys = buffer.keys().toList()
      val others = if (share == null || keys.isEmpty()) emptyMap() else othersOf(share, keys)
      keys.filter { worker.finishedHere(it, share, others[it].orEmpty()) }
    } catch (e: IOException) {
      return
    }
    if (done.isNotEmpty()) evictUnlessKept(done)
  }

  /**
   * Fase 8 (D17): il buffer non butta un'anteprima che serve ancora ai volti. [AiComputeParts.keepers] dice quali delle chiavi che stiamo per
   * togliere il calcolo dei volti chiede ancora (di serie nessuna: il comportamento della Fase 6); quelle restano, le altre si tolgono. Un
   * guasto di chi tiene non ferma niente: vale come "nessuno tiene niente". Vale per ogni svuotamento del buffer dopo un calcolo (inizio
   * tappa, fine unita', fine tappa del telefono); sotto 1 GiB libero (E-A17) il buffer si svuota comunque.
   */
  private suspend fun evictUnlessKept(keys: Collection<ContentKey>) {
    if (keys.isEmpty()) return
    val kept = try {
      parts.keepers.stillWanted(keys)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      emptySet()
    }
    parts.buffer.evict(if (kept.isEmpty()) keys else keys.filterNot { it in kept })
  }

  suspend fun share(share: String): AiRunResult {
    gate()?.let { return finish(it) }
    pruneFinished(share.takeUnless { pace.localOnly })
    if (pace.localOnly) return bufferOnly(share)
    releasePending(share)
    // Le unita' si lasciano tutte a fine tappa (cosi' un'unita' con media ancora in attesa non torna in questo giro e non blocca le
    // altre). Una morte del processo le lascia prenotate fino alla scadenza (E-I2): nessun `finally`.
    val ends = ArrayList<Pair<UnitClaim, UnitOutcome>>()
    var stop: AiStop? = null
    var exhausted = false
    val seen = HashSet<WorkUnitKey>()
    for (round in 0 until config.maxUnitsPerRun) {
      // Le regole si chiedono una volta per lotto: la prima l'ha gia' chiesta l'inizio della tappa.
      if (round > 0) stop = gate()
      if (stop != null) break
      if (pace.localOnly) {
        stop = AiStop.BUFFER_ONLY
        break
      }
      val claim = try {
        parts.units.next(share, WorkPass.AI, parts.priority)
      } catch (e: IOException) {
        stop = AiStop.NAS_UNREACHABLE
        break
      }
      if (claim == null) {
        exhausted = true
        break
      }
      if (!seen.add(claim.key)) {
        ends += claim to UnitOutcome.Interrupted("AGAIN")
        break
      }
      if (only && urgentKeys().toSet().let { askedNow -> claim.items.none { it.contentKey in askedNow } }) {
        // XS-3: il giro e' solo per i media chiesti. Le unita' che li contengono vengono per prime (priorita' delle urgenze): questa non ne
        // ha, quindi quelle chieste sono finite. Si lascia subito (nessun lavoro fatto) e la tappa si chiude.
        ends += claim to UnitOutcome.Completed(0, 0)
        exhausted = true
        break
      }
      seenKeys += claim.items.map { it.contentKey }
      val end = unit(share, claim)
      when {
        end.lost -> Unit
        end.stop != null -> {
          ends += end.claim to UnitOutcome.Interrupted(end.stop.name)
          stop = end.stop
        }
        else -> ends += end.claim to UnitOutcome.Completed(end.computed, end.failed)
      }
      if (stop != null) break
    }
    for ((claim, outcome) in ends) safeFinish(claim, outcome)
    if (exhausted) reportBlocked(share)
    if (stop == AiStop.BUFFER_ONLY) return bufferOnly(share)
    return finish(stop)
  }

  /** Le chiavi di tutti i media delle unita' prese in questa tappa (per dire quali media chiesti nessuna unita' ha portato). */
  private val seenKeys = HashSet<ContentKey>()

  /**
   * XS-4: a fine giro, i media chiesti che [share] contiene e che nessuna unita' presa in questa tappa ha portato stanno in un'unita' che
   * un altro dispositivo sta lavorando (la prenotazione **non si sottrae**, D13): lo stato dice "in attesa" e la richiesta resta (il
   * registro la mette dietro agli altri). Un guasto della porta vale come "nessuno".
   */
  private suspend fun reportBlocked(share: String) {
    val asked = urgentKeys()
    if (asked.isEmpty()) return
    val mine = try {
      parts.requestedIn(AiScope.Share(share), asked.toSet())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return
    }
    for (key in mine) if (key !in seenKeys) reportWaiting(key, UrgentWait.OTHER_DEVICE)
  }

  /** D8, F4-D31: le voci in attesa di una share che ora si apre si pubblicano, **senza ricalcolo**. */
  private suspend fun releasePending(share: String) {
    val moved = try {
      parts.publisher.releasePending(share)
    } catch (e: IOException) {
      0
    }
    if (moved > 0) {
      counters.pendingReleased.addAndGet(moved)
      flush(share, FlushReason.UNIT_FINISHED)
    }
  }

  private class UnitEnd(val claim: UnitClaim, val stop: AiStop?, val lost: Boolean, val computed: Int, val failed: Int)

  private suspend fun unit(share: String, first: UnitClaim): UnitEnd {
    var claim = first
    var renewedAt = nanos()
    val computedBefore = tally.computed
    val failedBefore = tally.failed
    val keys = claim.items.map { it.contentKey }
    val others = othersOf(share, keys)
    val sources = try {
      parts.sources.sources(keys)
    } catch (e: IOException) {
      emptyMap()
    }
    // Pacchetto X: i media chiesti adesso dall'utente davanti, dentro questa unita' (i suoi confini non cambiano). XS-4: l'ultimo chiesto
    // per primo; con [only] solo i chiesti.
    var asked = urgentKeys()
    var queue = ordered(claim.items, asked) { it.contentKey }
    var queueKeys = queue.map { it.contentKey }
    var doneInUnit = 0
    showLive(null, queueKeys, -1)
    var index = 0
    var stop: AiStop? = null
    var lastDone: ContentKey? = null
    while (index < queue.size) {
      // Il primo lotto dell'unita' parte con il verdetto appena chiesto da chi ha preso l'unita'.
      if (index > 0) stop = gate()
      if (stop != null) break
      if (pace.localOnly) {
        stop = AiStop.BUFFER_ONLY
        break
      }
      if ((nanos() - renewedAt).nanoseconds >= config.keepAliveEvery) {
        renewedAt = nanos()
        when (val r = runCatchingIo { parts.units.keepAlive(claim) }) {
          is KeepAliveResult.Renewed -> {
            claim = r.claim
            val still = r.claim.items.mapTo(HashSet()) { it.contentKey }
            queue = queue.take(index) + queue.drop(index).filter { it.contentKey in still }
            queueKeys = queue.map { it.contentKey }
          }
          KeepAliveResult.Lost -> {
            counters.unitsLost.incrementAndGet()
            flush(share, FlushReason.UNIT_FINISHED)
            return UnitEnd(claim, null, lost = true, 0, 0)
          }
          null -> Unit
        }
      }
      // XS-4: le richieste si rileggono al confine del lotto: arrivata una richiesta (o cambiato l'ordine dei tocchi) si riordina **solo**
      // cio' che non e' ancora stato lavorato, mai quello che e' gia' passato.
      if (index > 0) {
        val again = urgentKeys()
        if (again != asked) {
          asked = again
          val worked = queue.take(index).mapTo(HashSet()) { it.contentKey }
          queue = queue.take(index) + ordered(claim.items.filter { it.contentKey !in worked }, asked) { it.contentKey }
          queueKeys = queue.map { it.contentKey }
          if (index >= queue.size) break
        }
      }
      val batch = queue.subList(index, minOf(queue.size, index + batchSize()))
      // IX-6: le voci del lotto (ripubblicate senza ricalcolo o appena calcolate) vanno nell'outbox insieme: M3 una volta per lotto.
      val staged = ArrayList<dev.pampa.mycloudgallery.core.index.ai.AiEntry>()
      val planned = batch.map { item ->
        val missing = item.missing.mapNotNullTo(LinkedHashSet()) { AiClass.of(it) }
        worker.plan(share, item.contentKey, item.kind, sources[item.contentKey] ?: MediaSource.OTHER, missing, others[item.contentKey].orEmpty(), staged)
      }
      worker.prefetch(share, planned, left(), config.fetchBatch)
      for (item in planned) {
        quickGate()?.let { stop = it }
        if (stop != null) break
        showLive(item.key, queueKeys, doneInUnit)
        val end = worker.process(share, item, localOnly = false, staged = staged)
        if (end == ItemEnd.MODEL_MISSING) {
          stop = AiStop.MODEL_MISSING
          break
        }
        counted(end, item.key)
        doneInUnit++
        lastDone = item.key
        running(queue.size)
      }
      index += batch.size
      stageAll(share, staged)
      flush(share, FlushReason.PERIODIC)
      control.checkpoint(AiCheckpoint(share, claim.key.resource, lastDone?.hex))
      if (stop != null) break
      breath()
    }
    flush(share, FlushReason.UNIT_FINISHED)
    evictUnlessKept(tally.finished)
    return UnitEnd(claim, stop, lost = false, tally.computed - computedBefore, tally.failed - failedBefore)
  }

  private suspend fun othersOf(share: String, keys: List<ContentKey>): Map<ContentKey, Set<AiClass>> = try {
    val vec = parts.doneByOthers.done(share, keys, recipe.version)
    if (recipe.ocrVersion == recipe.version) {
      vec
    } else {
      val ocr = parts.doneByOthers.done(share, keys, recipe.ocrVersion)
      keys.associateWith { k -> vec[k].orEmpty().filter { it == AiClass.VEC }.toSet() + ocr[k].orEmpty().filter { it == AiClass.OCR } }
    }
  } catch (e: IOException) {
    emptyMap()
  }

  /** IX-6: le voci di un lotto nell'outbox in una chiamata (M3 una volta). Una morte prima lascia i risultati salvati: si ripubblicano. */
  private suspend fun stageAll(share: String, staged: List<dev.pampa.mycloudgallery.core.index.ai.AiEntry>) {
    if (staged.isEmpty()) return
    try {
      parts.publisher.stage(share, staged)
    } catch (e: IOException) {
      counters.flushFailures.incrementAndGet()
    }
  }

  private suspend fun flush(share: String, reason: FlushReason) {
    try {
      parts.publisher.flush(share, reason)
    } catch (e: IOException) {
      counters.flushFailures.incrementAndGet()
    }
  }

  private suspend fun safeFinish(claim: UnitClaim, outcome: UnitOutcome) {
    runCatchingIo { parts.units.finish(claim, outcome) }
  }

  private suspend fun <T> runCatchingIo(block: suspend () -> T): T? = try {
    block()
  } catch (e: IOException) {
    null
  }

  // ---- Solo buffer e telefono --------------------------------------------------------------------------------------------

  /**
   * E-A6: senza rete utile si calcola cio' che c'e' gia' nel buffer per questa share (dal catalogo locale, [AiCandidate]); niente
   * unita', niente NAS. I risultati vanno nell'outbox e si pubblicano al primo giro con il NAS.
   */
  private suspend fun bufferOnly(share: String): AiRunResult {
    val list = parts.candidates.forShare(share, recipe.version, config.localBatchLimit, urgentKeys().toSet())
    val others = othersOf(share, list.map { it.contentKey })
    val stop = local(share, list, others) { candidate -> parts.buffer.get(candidate.contentKey, inputClassOf(candidate)) != null }
    if (stop != null && stop != AiStop.BUFFER_ONLY) return finish(stop)
    restLive()
    state.value = AiStatus.BufferOnlyDone(tally.computed)
    return result(AiStop.BUFFER_ONLY)
  }

  /** I media del solo telefono: dai byte del telefono, risultati solo locali (nessuna pubblicazione). */
  suspend fun phoneOnly(): AiRunResult {
    gate()?.let { return finish(it) }
    pruneFinished(null)
    val list = parts.candidates.phoneOnly(recipe.version, config.localBatchLimit, urgentKeys().toSet())
    return finish(local(null, list, emptyMap()) { true })
  }

  private suspend fun local(share: String?, list: List<AiCandidate>, others: Map<ContentKey, Set<AiClass>>, usable: suspend (AiCandidate) -> Boolean): AiStop? {
    // Pacchetto X: i media chiesti adesso dall'utente davanti anche nelle liste locali (solo telefono, solo buffer). XS-4: l'ultimo chiesto
    // per primo, e le richieste si rileggono al confine del lotto; con [only] solo i chiesti.
    var asked = urgentKeys()
    val usableList = list.filter { usable(it) }
    var chosen = ordered(usableList, asked) { it.contentKey }
    var chosenKeys = chosen.map { it.contentKey }
    var doneInList = 0
    showLive(null, chosenKeys, -1)
    var index = 0
    while (index < chosen.size) {
      if (index > 0) gate()?.let { return it }
      if (index > 0) {
        val again = urgentKeys()
        if (again != asked) {
          asked = again
          val worked = chosen.take(index).mapTo(HashSet()) { it.contentKey }
          chosen = chosen.take(index) + ordered(usableList.filter { it.contentKey !in worked }, asked) { it.contentKey }
          chosenKeys = chosen.map { it.contentKey }
          if (index >= chosen.size) break
        }
      }
      val batch = chosen.subList(index, minOf(chosen.size, index + batchSize()))
      val staged = ArrayList<dev.pampa.mycloudgallery.core.index.ai.AiEntry>()
      for (candidate in batch) {
        quickGate()?.let {
          if (share != null) stageAll(share, staged)
          return it
        }
        val planned = worker.plan(share, candidate.contentKey, candidate.kind, candidate.source, candidate.missing, others[candidate.contentKey].orEmpty(), staged)
        showLive(candidate.contentKey, chosenKeys, doneInList)
        val end = worker.process(share, planned, localOnly = true, staged = staged)
        if (end == ItemEnd.MODEL_MISSING) {
          if (share != null) stageAll(share, staged)
          return AiStop.MODEL_MISSING
        }
        counted(end, candidate.contentKey)
        doneInList++
        running(chosen.size)
      }
      if (share != null) stageAll(share, staged)
      index += batch.size
      control.checkpoint(AiCheckpoint(share ?: PHONE, null, batch.lastOrNull()?.contentKey?.hex))
      breath()
    }
    evictUnlessKept(tally.finished)
    return null
  }

  private fun inputClassOf(candidate: AiCandidate) =
    Planned(candidate.contentKey, candidate.kind, candidate.source, emptySet()).inputClass

  /** Le ultime finite qui (le piu' recenti prima), da una tappa all'altra dello stesso processo. */
  private val recentKeys: ArrayDeque<ContentKey> = ArrayDeque(live.value.recent)

  /** Le urgenze di adesso, **l'ultima chiesta per prima**; un guasto del registro non ferma mai il calcolo (vale come nessuna). */
  private fun urgentKeys(): List<ContentKey> = try {
    parts.urgent.urgent().toList()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    emptyList()
  }

  /**
   * Le chiavi urgenti davanti nell'ordine delle richieste (l'ultima toccata per prima, XS-4), il resto nell'ordine di sempre (ordinamento
   * stabile). Cambia solo l'ordine di lavoro **dentro** questa lista: unita', prenotazioni, cancelli e salvataggi restano identici, e la
   * ripresa (che e' `AiResults.saved`, non la posizione) non ne sa niente. Senza richieste la lista torna **la stessa** (identica a prima).
   * Con [only] (XS-3) restano solo i media chiesti.
   */
  private fun <T> ordered(items: List<T>, asked: List<ContentKey>, key: (T) -> ContentKey): List<T> {
    if (asked.isEmpty()) return if (only) emptyList() else items
    val rank = HashMap<ContentKey, Int>(asked.size * 2)
    asked.forEachIndexed { at, k -> rank[k] = at }
    val picked = if (only) items.filter { key(it) in rank } else items
    return picked.sortedBy { rank[key(it)] ?: Int.MAX_VALUE }
  }

  /** XS-2/XS-7: un media chiesto che non si e' potuto lavorare; un guasto del registro non ferma mai il calcolo. */
  private fun reportWaiting(key: ContentKey, why: UrgentWait) {
    try {
      parts.urgent.waiting(key, why)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Una comodita': la richiesta resta com'era.
    }
  }

  /** Conta un media, e se e' finito qui toglie la sua urgenza (chi resta "in attesa" la tiene: sara' il primo quando si potra'). */
  private fun counted(end: ItemEnd, key: ContentKey) {
    tally.count(end, key)
    if (end == ItemEnd.COMPUTED) {
      recentKeys.remove(key)
      recentKeys.addFirst(key)
      while (recentKeys.size > AiLive.RECENT) recentKeys.removeLast()
    }
    if (end == ItemEnd.WAITING) reportWaiting(key, UrgentWait.PREVIEW)
    if (end == ItemEnd.RETRY) reportWaiting(key, UrgentWait.RETRY)
    if (end == ItemEnd.COMPUTED || end == ItemEnd.SKIPPED || end == ItemEnd.FAILED) {
      try {
        parts.urgent.done(key)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Una comodita': al giro dopo il media non e' piu' candidato e l'urgenza cade da sola.
      }
    }
  }

  /** Il media di adesso e i prossimi della coda [keys] dopo [currentAt] (la posizione del media di adesso), per la pagina "Analisi". */
  private fun showLive(current: ContentKey?, keys: List<ContentKey>, currentAt: Int) {
    val next = if (keys.isEmpty()) emptyList() else keys.subList(minOf(currentAt + 1, keys.size), minOf(keys.size, currentAt + 1 + AiLive.NEXT))
    live.value = AiLive(current, recentKeys.toList(), next)
  }

  /** A tappa ferma: niente media di adesso ne' prossimi; le ultime finite restano (la pagina le mostra ferme). */
  private fun restLive() {
    live.value = AiLive(null, recentKeys.toList(), emptyList())
  }

  private fun running(total: Int) {
    val done = tally.computed + tally.skipped + tally.failed + tally.waiting
    val seconds = elapsed().inWholeMilliseconds / 1000.0
    val rate = if (seconds > 0 && tally.computed > 0) tally.computed / seconds else 0.0
    val eta = if (rate > 0) ((total - done).coerceAtLeast(0) / rate / 60.0).toInt() else null
    state.value = AiStatus.Running(done, maxOf(total, done), eta, parts.images.backend.value)
  }

  private fun finish(stop: AiStop?): AiRunResult {
    restLive()
    state.value = if (stop == null) AiStatus.Idle(null) else AiComputeEngine.waitingFor(stop)
    return result(stop)
  }

  private companion object {
    const val PHONE = "telefono"
  }
}
