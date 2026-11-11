package dev.pampa.mycloudgallery.core.models.manager

import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.ExtraModelSources
import dev.pampa.mycloudgallery.core.models.contract.FetchRefusal
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelFreeSpace
import dev.pampa.mycloudgallery.core.models.contract.ModelGateReason
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
import dev.pampa.mycloudgallery.core.models.contract.ShelfResult
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.contract.ModelWait
import dev.pampa.mycloudgallery.core.models.contract.ModelUsage
import dev.pampa.mycloudgallery.core.models.contract.PinnedUrl
import dev.pampa.mycloudgallery.core.models.noop.NoExtraModelSources
import dev.pampa.mycloudgallery.core.models.store.ModelStore
import dev.pampa.mycloudgallery.core.models.store.PartialWriter
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
// Fase 10 (P1, D29): fra gli HTTPS, prima gli indirizzi fissati del catalogo, poi quelli aggiuntivi del manifest firmato ([ExtraModelSources],
// gia' filtrati con il catalogo e `PinnedUrl.of`); `RoutingModelFetcher` ricontrolla ogni indirizzo prima di aprire la connessione.

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
  private val extra: ExtraModelSources = NoExtraModelSources,
) : ModelManager {

  override val states get() = store.states

  private val guards: Map<ModelId, Mutex> = store.catalog.associate { it.id to Mutex() }
  private val running = ConcurrentHashMap<ModelId, Job>()

  // --- ensure -------------------------------------------------------------------------------------------------------------

  override suspend fun ensure(id: ModelId, trigger: ModelTrigger, budget: Duration): EnsureResult {
    val spec = store.spec(id) ?: return EnsureResult.Failed(ModelFailCode.FETCH_FAILED)
    // MO-3: "Rimuovi" dura. I lavori in background (le regole) non riscaricano un modello che l'utente ha tolto; lo riprende solo lui con
    // "Scarica ora", o l'accensione del ruolo Indicizzatore per il suo modello.
    if (trigger == ModelTrigger.RULES && withContext(io) { store.removedByUser(spec) }) return EnsureResult.Deferred(store.stateOf(id))
    val guard = guards.getValue(id)
    // Una richiesta alla volta per modello: chi arriva mentre un'altra lavora non aspetta, vede lo stato.
    if (!guard.tryLock()) return EnsureResult.Deferred(store.stateOf(id))
    // Un lavoro figlio che non trascina con se' chi chiama: se `acquire` fallisce l'errore arriva da `withContext`, e `remove` puo' annullarlo da solo.
    val job = SupervisorJob(currentCoroutineContext()[Job])
    running[id] = job
    try {
      if (trigger != ModelTrigger.RULES) withContext(io) { store.setRemovedByUser(spec, false) }
      return withContext(job) { acquire(spec, trigger, budget) }
    } catch (e: CancellationException) {
      store.set(id, ModelState.Queued(trigger))
      // Annullata da `remove` (la nostra coroutine e' viva): non e' un'uscita dalla chiamata, e' "non adesso".
      if (currentCoroutineContext().isActive) return EnsureResult.Deferred(store.stateOf(id))
      throw e
    } catch (e: Exception) {
      // R10: un guasto imprevisto (un orologio che lancia, un disco che risponde male) e' un fallimento di questa richiesta, mai un'eccezione
      // che cade sul lavoro e salta gli altri modelli. Il motivo e' un codice, non il testo dell'eccezione.
      store.counters.fetchFailed()
      return fail(spec, ModelFailCode.FETCH_FAILED, null)
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

    var restarts = 0
    while (true) {
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
        val needed = missing + ModelLimits.spaceMargin(spec)
        val usable = withContext(io) {
          store.root.mkdirs()
          freeSpace.usableBytes(store.root)
        }
        if (usable < needed) return fail(spec, ModelFailCode.NO_SPACE, needed)
      }

      // 4. Le sorgenti, in ordine.
      val tally = Tally()
      var restart = false
      // I5: dopo il primo errore su una copia del NAS non se ne provano altre; si passa a HTTPS.
      var nasFailed = false
      for (source in sourcesFor(spec)) {
        if (source is ModelSource.NasCopy && nasFailed) continue
        val key = strikeKey(spec, source)
        if (key != null && !trigger.isUserRequest && strikes.isQuarantined(key)) {
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
          is SourceOutcome.Restart -> {
            restart = true
            tally.mismatched = true
            if (source is ModelSource.NasCopy) nasFailed = true
          }
          is SourceOutcome.Next -> {
            tally.add(outcome)
            if (source is ModelSource.NasCopy) nasFailed = true
          }
        }
        if (restart) break
      }
      // R13: byte sbagliati scritti da piu' sorgenti insieme: il parziale e' buttato senza dare la colpa a nessuna, e si riprova da zero una
      // volta sola (se da sola una sorgente sbaglia, la colpa e' sua).
      if (restart && restarts++ == 0) continue

      // 5. Nessuna sorgente ha dato il file.
      val confirmBytes = tally.confirmBytes
      val wait = tally.waiting
      return when {
        tally.transient -> deferred(spec, trigger)
        confirmBytes != null -> needsConfirmation(spec, confirmBytes)
        wait != null -> waiting(spec, trigger, wait)
        tally.mismatched -> fail(spec, ModelFailCode.MISMATCH, null)
        else -> {
          store.counters.fetchFailed()
          fail(spec, ModelFailCode.FETCH_FAILED, null)
        }
      }
    }
  }

  private sealed interface SourceOutcome {
    data object Ready : SourceOutcome

    data object OutOfTime : SourceOutcome

    class NoSpace(val needed: Long) : SourceOutcome

    /** Byte sbagliati che non si sa di chi sono (piu' sorgenti hanno scritto nel parziale): buttato, nessuna colpa, da capo (R13). */
    data object Restart : SourceOutcome

    /** Questa sorgente non ha dato il file: perche', per decidere cosa dire se nessun'altra lo da'. */
    class Next(val why: Why, val confirmBytes: Long? = null, val waiting: ModelWait? = null) : SourceOutcome
  }

  private enum class Why { TRANSIENT, GATE_REFUSED, GATE_CONFIRM, MISMATCH, ABSENT }

  private class Tally {
    var transient = false
    var waiting: ModelWait? = null
    var confirmBytes: Long? = null
    var mismatched = false

    fun add(next: SourceOutcome.Next) {
      when (next.why) {
        Why.TRANSIENT -> transient = true
        Why.GATE_REFUSED -> waiting = waiting ?: next.waiting ?: ModelWait.NETWORK
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
        // R11: il cancello di rete vale per OGNI sorgente che usa la rete (HTTPS, copie sul NAS, cartella dal PC), non solo per HTTPS: fuori
        // casa con la VPN accesa il NAS e' raggiungibile anche sui dati mobili, e 157-370 MB non si leggono senza conferma, mai in roaming.
        // Il cancello si rilegge a ogni fetta (la fetta e' al massimo di 30 s). L'APK non passa dalla rete.
        if (source !is ModelSource.Bundled) {
          when (val verdict = gate.verdict(trigger, spec.sizeBytes - length, source.kind)) {
            ModelNetworkVerdict.Allowed -> {}
            is ModelNetworkVerdict.NeedsConfirmation -> return SourceOutcome.Next(Why.GATE_CONFIRM, confirmBytes = verdict.bytes)
            is ModelNetworkVerdict.Refused -> return SourceOutcome.Next(Why.GATE_REFUSED, waiting = waitOf(verdict.reason))
          }
        }
        withContext(io) { store.writePartialSource(spec, PartialWriter.of(source)) }
        store.set(spec.id, ModelState.Fetching(source.kind, length, spec.sizeBytes))
        val slice = if (source !is ModelSource.Bundled) minOf(left, ModelLimits.HTTPS_SLICE) else left
        val result = withProgress(spec, source.kind, partial) { fetcher.fetch(spec, source, partial, slice) }
        withContext(io) { store.tidyPartialNotes(spec) }
        when (result) {
          FetchResult.Done -> {}
          is FetchResult.Partial -> {
            val now = withContext(io) { partialLength(partial, spec) }
            // Progresso = il parziale e' cresciuto. Un `200` al posto di `206` riparte da zero e arriva meno in la' di prima: ripartire non e'
            // avanzare, e due fette senza avanzare fermano la sorgente (R13: un CDN che cambia ETag a ogni richiesta non deve far scaricare
            // gli stessi byte per sempre).
            if (now > length) stalled = 0 else if (++stalled >= 2) return SourceOutcome.Next(Why.TRANSIENT)
            continue
          }
          FetchResult.Mismatch -> return rejected(spec, source, key)
          // Un'altra dimensione prima di leggere un byte: la sorgente sbaglia, ma il parziale (di un'altra sorgente) non c'entra (R13).
          FetchResult.SizeMismatch -> return rejected(spec, source, key, discardPartial = false)
          FetchResult.NotFound -> return SourceOutcome.Next(Why.ABSENT)
          is FetchResult.Refused -> {
            if (!FetchRefusal.isHostile(result.reason)) return SourceOutcome.Next(Why.ABSENT)
            return rejected(spec, source, key, discardPartial = false)
          }
          FetchResult.NoSpace -> return SourceOutcome.NoSpace(spec.sizeBytes - length + ModelLimits.spaceMargin(spec))
          FetchResult.NetworkError -> return SourceOutcome.Next(Why.TRANSIENT)
        }
      }
      // Il file e' tutto qui: l'impronta dice se e' quello del catalogo (E-A2).
      return if (finalize(spec, source.kind)) SourceOutcome.Ready else rejected(spec, source, key)
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

  /**
   * Un file sbagliato: errore contato e (per i lavori automatici) segnato alla sorgente **che l'ha scritto**. Con [discardPartial] il parziale
   * si butta: se l'hanno scritto piu' sorgenti (byte sbagliati letti a meta' dalla cartella dal PC e completati da HTTPS) non si sa di chi sono, e
   * la colpa non va all'ultima: si riparte da zero una volta, senza colpa ([SourceOutcome.Restart]). Senza [discardPartial] (la sorgente ha
   * mentito prima di dare un byte) la colpa e' sua e basta.
   */
  private suspend fun rejected(spec: ModelSpec, source: ModelSource, key: String?, discardPartial: Boolean = true): SourceOutcome {
    val writers = if (discardPartial) withContext(io) { store.readPartialWriters(spec) } else emptyList()
    if (discardPartial) withContext(io) { store.deletePartial(spec) }
    store.counters.mismatch()
    val me = PartialWriter.of(source)
    if (writers.any { it.id != me.id }) return SourceOutcome.Restart
    key?.let(strikes::strike)
    return SourceOutcome.Next(Why.MISMATCH)
  }

  /** Il motivo del cancello di rete come attesa da mostrare (MO-8): in coda, non un errore da riprovare. */
  private fun waitOf(reason: String): ModelWait =
    when (reason) {
      ModelGateReason.ROAMING -> ModelWait.ROAMING
      ModelGateReason.METERED -> ModelWait.WIFI
      else -> ModelWait.NETWORK
    }

  // --- sorgenti -----------------------------------------------------------------------------------------------------------

  /** D17: copie sul NAS, poi HTTPS (fissati, poi aggiuntivi del manifest: Fase 10 D29), poi la cartella dal PC. Il ritaglio sta nell'APK e basta. */
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
      var tried = 0
      for (copy in copies.filter { it.sha256 == spec.sha256 }.sortedBy { it.device }) {
        // Una copia con un'altra dimensione e' un file diverso: scartata senza leggerla (E-A2).
        if (copy.sizeBytes != spec.sizeBytes) {
          store.counters.mismatch()
        } else if (tried < ModelLimits.MAX_SHELF_TRIES) {
          // I5: al massimo 2-3 copie provate; una copia che non torna costa un tentativo, non tutti.
          sources += ModelSource.NasCopy(copy.share, copy.device)
          tried++
        }
      }
    }
    spec.https.forEach { sources += ModelSource.Https(it) }
    extraUrls(spec).forEach { sources += ModelSource.Https(it) }
    if (share != null) sources += ModelSource.PcFolder(share)
    return sources
  }

  /** Gli indirizzi aggiuntivi (Fase 10, D29) che non sono gia' fissati; un errore della porta vale "nessuno" (fallire chiusi). */
  private fun extraUrls(spec: ModelSpec): List<PinnedUrl> = try {
    extra.urls(spec.id, spec.sha256).filter { it !in spec.https }.distinct()
  } catch (e: RuntimeException) {
    emptyList()
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

  /** MO-2: sui dati mobili serve la conferma con la dimensione; la pagina la chiede e ripete la richiesta con [ModelTrigger.USER_CONFIRMED]. */
  private fun needsConfirmation(spec: ModelSpec, bytes: Long): EnsureResult {
    store.set(spec.id, ModelState.NeedsConfirmation(bytes))
    return EnsureResult.Failed(ModelFailCode.NETWORK_REFUSED, bytes)
  }

  /** MO-8: il cancello di rete non lascia scaricare adesso (nessuna rete, solo Wi-Fi, roaming): in coda con il motivo. */
  private fun waiting(spec: ModelSpec, trigger: ModelTrigger, wait: ModelWait): EnsureResult {
    val state = ModelState.Queued(trigger, wait)
    store.set(spec.id, state)
    return EnsureResult.Deferred(state)
  }

  private fun fail(spec: ModelSpec, code: ModelFailCode, neededBytes: Long?): EnsureResult {
    if (code == ModelFailCode.NO_SPACE) store.counters.noSpace()
    store.set(spec.id, ModelState.Failed(code))
    return EnsureResult.Failed(code, neededBytes)
  }

  // --- lo scaffale sul NAS (D18, MO-4) ------------------------------------------------------------------------------------------

  /**
   * D18: il primo dispositivo che ha un modello verificato lo pubblica sul NAS (nella propria cartella, scrittura esclusiva, nessuna
   * sostituzione), cosi' gli altri non vanno su Internet; poi toglie le **proprie** copie che l'app non elenca piu'. Solo in background e solo
   * se il cancello di rete lascia usare il NAS (mai in roaming, mai i dati mobili), e per ogni modello solo se **nessun dispositivo** della
   * famiglia ha gia' una copia (altrimenti ogni dispositivo ne metterebbe una sua da 370 MiB). Il cancello di scrittura (F4-D31) lo applica lo
   * scaffale. Mai un'eccezione. Restituisce quante copie ha scritto.
   */
  suspend fun shareOnNas(): Int {
    val share = try {
      area.commonShare()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    } ?: return 0
    var written = 0
    for (spec in store.catalog) {
      if (spec.bundledAsset != null) continue
      if (!withContext(io) { store.finalFile(spec).isFile }) continue
      if (gate.verdict(ModelTrigger.RULES, spec.sizeBytes, ModelSourceKind.NAS) != ModelNetworkVerdict.Allowed) break
      try {
        if (shelf.copies(share, spec.sha256).any { it.sizeBytes == spec.sizeBytes }) continue
        if (shelf.publish(spec, share) == ShelfResult.Published) written++
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        continue
      }
    }
    try {
      shelf.pruneOwn(share, store.catalog.filter { it.bundledAsset == null }.mapTo(HashSet()) { it.sha256 })
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Le copie vecchie si tolgono al giro dopo.
    }
    return written
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
        // MO-3: la scelta dell'utente resta finche' non tocca "Scarica ora" (o accende il ruolo): il giro periodico non lo riprende.
        store.setRemovedByUser(spec, true)
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
          verifier.refreshVerified(spec)
        }
      }
    }
  }
}
