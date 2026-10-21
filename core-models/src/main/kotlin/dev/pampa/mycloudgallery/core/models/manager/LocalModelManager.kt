package dev.pampa.mycloudgallery.core.models.manager

import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.FetchRefusal
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelFreeSpace
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.models.contract.ModelNasArea
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkGate
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkVerdict
import dev.pampa.mycloudgallery.core.models.contract.ModelShelf
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.contract.ModelUsage
import dev.pampa.mycloudgallery.core.models.store.ModelStore
import dev.pampa.mycloudgallery.core.models.verify.ModelVerifier
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Il GESTORE dei modelli (docs/fase6-progetto.md §2.1, D16-D20, E-A1, E-A2, E-A3, E-A11, E-S16): stato, scaricamento con ripresa da
// piu' sorgenti, verifica, rimozione. Le garanzie, in ordine di importanza:
//  1. In `models/<sha256>` entra solo un file la cui dimensione e il cui SHA-256 (ricalcolato sul file FINITO, su disco) sono quelli
//     del catalogo: promozione con rinomina atomica dal parziale, mai prima, mai con una nota di una sorgente. Una sorgente che dice
//     "e' a posto" non conta: conta il catalogo.
//  2. Un file che non torna (lunghezza, impronta) si butta con il suo parziale e si passa alla sorgente dopo (E-A2, E-S16); dopo due
//     errori della stessa sorgente in un giorno i lavori automatici la saltano (E-A2); "Scarica ora" riprova sempre.
//  3. Mai a vuoto: spazio controllato prima (dimensione mancante + 300 MB, E-A3), tempo del chiamante rispettato, cancello di rete
//     riletto ogni 30 s per HTTPS (dati mobili e roaming si fermano), una sola richiesta alla volta per modello.
//  4. Tutto lo stato sta su disco (parziale, nota di provenienza, validatore): la morte del processo in qualunque punto lascia una
//     cartella da cui `ensure` riprende senza rifare cio' che c'e' (E-A1).
// Ordine delle sorgenti (D17): copie sul NAS dei dispositivi della famiglia, HTTPS, cartella dal PC; per il ritaglio solo l'APK.

internal class LocalModelManager(
  private val store: ModelStore,
  private val verifier: ModelVerifier,
  private val fetcher: ModelFetcher,
  private val shelf: ModelShelf,
  private val area: ModelNasArea,
  private val gate: ModelNetworkGate,
  private val freeSpace: ModelFreeSpace,
  private val strikes: SourceStrikes,
  private val io: CoroutineDispatcher,
  private val progressPoll: Duration,
) : ModelManager {

  override val states get() = store.states

  private val guards: Map<ModelId, Mutex> = store.catalog.associate { it.id to Mutex() }
  private val running = ConcurrentHashMap<ModelId, Job>()

  // --- ensure -------------------------------------------------------------------------------------------------------------

  override suspend fun ensure(id: ModelId, trigger: ModelTrigger, budget: Duration): EnsureResult {
    val spec = store.spec(id) ?: return EnsureResult.Failed(ModelFailCode.FETCH_FAILED)
    val guard = guards.getValue(id)
    // Una richiesta alla volta per modello: chi arriva mentre un'altra lavora non aspetta, vede lo stato.
    if (!guard.tryLock()) return EnsureResult.Deferred(store.stateOf(id))
    // Un lavoro figlio che non trascina con se' chi chiama: se `acquire` fallisce l'errore arriva da `withContext`, e `remove` puo' annullarlo da solo.
    val job = SupervisorJob(currentCoroutineContext()[Job])
    running[id] = job
    try {
      return withContext(job) { acquire(spec, trigger, budget) }
    } catch (e: CancellationException) {
      store.set(id, ModelState.Queued(trigger))
      // Annullata da `remove` (la nostra coroutine e' viva): non e' un'uscita dalla chiamata, e' "non adesso".
      if (currentCoroutineContext().isActive) return EnsureResult.Deferred(store.stateOf(id))
      throw e
    } finally {
      running.remove(id)
      job.complete()
      guard.unlock()
    }
  }

  private suspend fun acquire(spec: ModelSpec, trigger: ModelTrigger, budget: Duration): EnsureResult {
    val started = store.nanos()
    val partial = store.partialFile(spec)

    // 1. Gia' qui e giusto? La verifica rilegge il file: un modello pronto risponde senza rete.
    if (store.finalFile(spec).isFile) {
      val opened = store.lock.withLock { withContext(io) { verifier.openVerified(spec) } }
      if (opened is ModelVerifier.Opened.Verified) {
        opened.model.close()
        withContext(io) { store.deletePartial(spec) }
        return EnsureResult.Ready
      }
    }
    store.set(spec.id, ModelState.Queued(trigger))
    if (budget <= Duration.ZERO) return EnsureResult.Deferred(ModelState.Queued(trigger))

    // 2. Un parziale gia' completo (il processo e' morto fra l'ultimo blocco e la rinomina) non si riscarica: si verifica. Se non torna
    //    non e' colpa di nessuna sorgente di adesso (non si segna a nessuno), si butta e si riparte.
    var have = withContext(io) { partialLength(partial, spec) }
    if (have == spec.sizeBytes) {
      val kind = withContext(io) { store.readPartialSource(spec) } ?: ModelSourceKind.HTTPS
      if (finalize(spec, kind)) return EnsureResult.Ready
      withContext(io) { store.deletePartial(spec) }
      store.counters.mismatch()
      have = 0
    }

    // 3. Spazio (E-A3): quanto manca piu' il margine.
    val missing = spec.sizeBytes - have
    if (missing > 0) {
      val needed = missing + ModelLimits.SPACE_MARGIN_BYTES
      val usable = withContext(io) {
        store.root.mkdirs()
        freeSpace.usableBytes(store.root)
      }
      if (usable < needed) return fail(spec, ModelFailCode.NO_SPACE, needed)
    }

    // 4. Le sorgenti, in ordine.
    val tally = Tally()
    for (source in sourcesFor(spec)) {
      val key = strikeKey(spec, source)
      if (key != null && trigger != ModelTrigger.USER_NOW && strikes.isQuarantined(key)) {
        store.counters.skippedSource()
        continue
      }
      when (val outcome = runSource(spec, source, key, trigger, started, budget, partial)) {
        is SourceOutcome.Ready -> {
          key?.let(strikes::clear)
          return EnsureResult.Ready
        }
        is SourceOutcome.OutOfTime -> return deferred(spec, trigger)
        is SourceOutcome.NoSpace -> return fail(spec, ModelFailCode.NO_SPACE, outcome.needed)
        is SourceOutcome.Next -> tally.add(outcome)
      }
    }

    // 5. Nessuna sorgente ha dato il file.
    return when {
      tally.transient -> deferred(spec, trigger)
      tally.confirmBytes != null -> fail(spec, ModelFailCode.NETWORK_REFUSED, tally.confirmBytes)
      tally.gateRefused -> fail(spec, ModelFailCode.NETWORK_REFUSED, null)
      tally.mismatched -> fail(spec, ModelFailCode.MISMATCH, null)
      else -> {
        store.counters.fetchFailed()
        fail(spec, ModelFailCode.FETCH_FAILED, null)
      }
    }
  }

  private sealed interface SourceOutcome {
    data object Ready : SourceOutcome

    data object OutOfTime : SourceOutcome

    class NoSpace(val needed: Long) : SourceOutcome

    /** Questa sorgente non ha dato il file: perche', per decidere cosa dire se nessun'altra lo da'. */
    class Next(val why: Why, val confirmBytes: Long? = null) : SourceOutcome
  }

  private enum class Why { TRANSIENT, GATE_REFUSED, GATE_CONFIRM, MISMATCH, ABSENT }

  private class Tally {
    var transient = false
    var gateRefused = false
    var confirmBytes: Long? = null
    var mismatched = false

    fun add(next: SourceOutcome.Next) {
      when (next.why) {
        Why.TRANSIENT -> transient = true
        Why.GATE_REFUSED -> gateRefused = true
        Why.GATE_CONFIRM -> confirmBytes = next.confirmBytes
        Why.MISMATCH -> mismatched = true
        Why.ABSENT -> {}
      }
    }
  }

  /** Scarica da UNA sorgente finche' arriva in fondo, finisce il tempo, o la sorgente non da' piu' niente. */
  private suspend fun runSource(
    spec: ModelSpec,
    source: ModelSource,
    key: String?,
    trigger: ModelTrigger,
    started: Long,
    budget: Duration,
    partial: File,
  ): SourceOutcome {
    var stalled = 0
    while (true) {
      val length = withContext(io) { partialLength(partial, spec) }
      // Un file arrivato in fondo si verifica anche a tempo finito: la verifica e' locale, e rimandarla non farebbe che ripeterla.
      val left = remaining(budget, started)
      if (length < spec.sizeBytes && left <= Duration.ZERO) return SourceOutcome.OutOfTime
      if (length < spec.sizeBytes) {
        // Il cancello di rete guarda solo HTTPS (il NAS ha il suo, dentro core-nas), e si rilegge a ogni fetta.
        if (source is ModelSource.Https) {
          when (val verdict = gate.verdict(trigger, spec.sizeBytes - length)) {
            ModelNetworkVerdict.Allowed -> {}
            is ModelNetworkVerdict.NeedsConfirmation -> return SourceOutcome.Next(Why.GATE_CONFIRM, verdict.bytes)
            is ModelNetworkVerdict.Refused -> return SourceOutcome.Next(Why.GATE_REFUSED)
          }
        }
        withContext(io) { store.writePartialSource(spec, source.kind) }
        store.set(spec.id, ModelState.Fetching(source.kind, length, spec.sizeBytes))
        val slice = if (source is ModelSource.Https) minOf(left, ModelLimits.HTTPS_SLICE) else left
        val result = withProgress(spec, source.kind, partial) { fetcher.fetch(spec, source, partial, slice) }
        withContext(io) { store.tidyPartialNotes(spec) }
        when (result) {
          FetchResult.Done -> {}
          is FetchResult.Partial -> {
            val now = withContext(io) { partialLength(partial, spec) }
            // Progresso = il parziale e' cambiato (anche accorciato: un `200` al posto di `206` riparte da zero e puo' arrivare meno in la' di prima).
            if (now != length) stalled = 0 else if (++stalled >= 2) return SourceOutcome.Next(Why.TRANSIENT)
            continue
          }
          FetchResult.Mismatch -> return rejected(spec, key)
          FetchResult.NotFound -> return SourceOutcome.Next(Why.ABSENT)
          is FetchResult.Refused -> {
            if (!FetchRefusal.isHostile(result.reason)) return SourceOutcome.Next(Why.ABSENT)
            return rejected(spec, key, discardPartial = false)
          }
          FetchResult.NoSpace -> return SourceOutcome.NoSpace(spec.sizeBytes - length + ModelLimits.SPACE_MARGIN_BYTES)
          FetchResult.NetworkError -> return SourceOutcome.Next(Why.TRANSIENT)
        }
      }
      // Il file e' tutto qui: l'impronta dice se e' quello del catalogo (E-A2).
      return if (finalize(spec, source.kind)) SourceOutcome.Ready else rejected(spec, key)
    }
  }

  /** Il file completo viene verificato (dimensione, SHA-256 sul file finito) e promosso con una rinomina atomica. True se e' pronto. */
  private suspend fun finalize(spec: ModelSpec, kind: ModelSourceKind): Boolean {
    store.set(spec.id, ModelState.Verifying)
    val partial = store.partialFile(spec)
    val ok = store.lock.withLock {
      withContext(io) {
        if (!verifier.matches(spec, partial)) {
          false
        } else {
          try {
            store.promote(spec)
            store.writeSource(spec, kind)
            store.deletePartial(spec)
            true
          } catch (e: IOException) {
            false
          }
        }
      }
    }
    if (ok) store.set(spec.id, ModelState.Ready(spec.sizeBytes, kind, store.nanos()))
    return ok
  }

  /** Un file sbagliato: parziale tolto, errore contato e (per i lavori automatici) segnato alla sorgente. */
  private suspend fun rejected(spec: ModelSpec, key: String?, discardPartial: Boolean = true): SourceOutcome {
    if (discardPartial) withContext(io) { store.deletePartial(spec) }
    store.counters.mismatch()
    key?.let(strikes::strike)
    return SourceOutcome.Next(Why.MISMATCH)
  }

  // --- sorgenti -----------------------------------------------------------------------------------------------------------

  /** D17: copie sul NAS, poi HTTPS, poi la cartella dal PC. Il ritaglio sta nell'APK e basta. */
  private suspend fun sourcesFor(spec: ModelSpec): List<ModelSource> {
    spec.bundledAsset?.let { return listOf(ModelSource.Bundled(it)) }
    val share = try {
      area.commonShare()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
    val sources = ArrayList<ModelSource>()
    if (share != null) {
      val copies = try {
        shelf.copies(share, spec.sha256)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        emptyList()
      }
      for (copy in copies.filter { it.sha256 == spec.sha256 }.sortedBy { it.device }) {
        // Una copia con un'altra dimensione e' un file diverso: scartata senza leggerla (E-A2).
        if (copy.sizeBytes == spec.sizeBytes) sources += ModelSource.NasCopy(copy.share, copy.device) else store.counters.mismatch()
      }
    }
    spec.https.forEach { sources += ModelSource.Https(it) }
    if (share != null) sources += ModelSource.PcFolder(share)
    return sources
  }

  /**
   * La chiave con cui si contano gli errori di una sorgente (per modello). La cartella dal PC e l'APK non si bloccano mai: la prima la
   * cura l'utente (copiare di nuovo il file giusto deve bastare), il secondo e' l'app stessa.
   */
  private fun strikeKey(spec: ModelSpec, source: ModelSource): String? =
    when (source) {
      is ModelSource.NasCopy -> "nas:${source.device}:${spec.id}"
      is ModelSource.Https -> "https:${source.url.host}:${spec.id}"
      is ModelSource.PcFolder, is ModelSource.Bundled -> null
    }

  // --- supporto -----------------------------------------------------------------------------------------------------------

  /** Quanto resta del tempo concesso, con l'orologio monotono (D39). */
  private fun remaining(budget: Duration, started: Long): Duration =
    if (budget.isInfinite()) budget else budget - (store.nanos() - started).nanoseconds

  /** La lunghezza del parziale (grezza: lo allinea il fetcher). Un parziale piu' lungo del modello e' rovinato e si toglie. */
  private fun partialLength(partial: File, spec: ModelSpec): Long {
    if (!partial.isFile) return 0
    val length = partial.length()
    if (length > spec.sizeBytes) {
      store.deletePartial(spec)
      return 0
    }
    return length
  }

  /** Mentre un fetcher lavora, la pagina vede l'avanzamento: lo si legge dal parziale ogni [progressPoll] (lo scrive a blocchi). */
  private suspend fun <T> withProgress(spec: ModelSpec, kind: ModelSourceKind, partial: File, block: suspend () -> T): T {
    if (progressPoll <= Duration.ZERO) return block()
    return coroutineScope {
      val poller = launch {
        while (isActive) {
          delay(progressPoll)
          store.set(spec.id, ModelState.Fetching(kind, partial.length().coerceIn(0, spec.sizeBytes), spec.sizeBytes))
        }
      }
      try {
        block()
      } finally {
        poller.cancel()
      }
    }
  }

  private fun deferred(spec: ModelSpec, trigger: ModelTrigger): EnsureResult {
    val state = ModelState.Queued(trigger)
    store.set(spec.id, state)
    return EnsureResult.Deferred(state)
  }

  private fun fail(spec: ModelSpec, code: ModelFailCode, neededBytes: Long?): EnsureResult {
    if (code == ModelFailCode.NO_SPACE) store.counters.noSpace()
    store.set(spec.id, ModelState.Failed(code))
    return EnsureResult.Failed(code, neededBytes)
  }

  // --- remove, usage, refresh -----------------------------------------------------------------------------------------------

  override suspend fun remove(id: ModelId): Boolean {
    val spec = store.spec(id) ?: return false
    // Se lo stanno scaricando si ferma subito (la connessione si chiude), poi si toglie tutto.
    running[id]?.let {
      it.cancel()
      it.join()
    }
    return store.lock.withLock {
      withContext(io) {
        val existed = store.finalFile(spec).isFile
        store.deletePartial(spec)
        val gone = store.deleteVerified(spec)
        if (gone) store.set(id, ModelState.Absent)
        existed && gone
      }
    }
  }

  override suspend fun usage(): ModelUsage =
    withContext(io) {
      ModelUsage(
        bytesById = store.catalog.associate { it.id to store.verifiedBytes(it) }.filterValues { it > 0 },
        partialBytes = store.partialBytes(),
      )
    }

  /**
   * Da chiamare una volta all'avvio dell'`AiGraph` (e quando si apre la pagina Ricerca e modelli): toglie i modelli che l'app non
   * elenca piu' (D20) e verifica quelli presenti, cosi' lo stato dice il vero (`Verifying` → `Ready`, o tolto).
   */
  suspend fun refresh() {
    store.lock.withLock {
      withContext(io) {
        store.removeUnlisted()
        for (spec in store.catalog) {
          if (!store.finalFile(spec).isFile) {
            val state = store.stateOf(spec.id)
            if (state is ModelState.Ready || state == ModelState.Verifying) store.set(spec.id, ModelState.Absent)
            continue
          }
          (verifier.openVerified(spec) as? ModelVerifier.Opened.Verified)?.model?.close()
        }
      }
    }
  }
}
