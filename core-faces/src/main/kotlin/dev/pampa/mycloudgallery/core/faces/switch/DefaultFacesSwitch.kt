package dev.pampa.mycloudgallery.core.faces.switch

import dev.pampa.mycloudgallery.core.faces.contract.ActivateOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocalStore
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.contract.ReconcileOutcome
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesPersonalSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * L'INTERRUTTORE DI PERSONE (docs/fase8-progetto.md §2.1, D3-D5, D30, D34; riga P7 del §4.4), sopra la voce `faces` dell'account
 * ([account]), lo stato locale del dispositivo ([localStore], il file `faces-local`) e la cancellazione dei dati locali ([eraser]). Lo stesso
 * modello della lettura del testo (F6-D41, revisione B), con le sue correzioni:
 *
 * - **La scelta vale subito qui, anche senza l'ora del NAS** ([FacesPersonalSettings] legge lo stato effettivo): "Attiva" cambia
 *   [currentState] prima di tornare, la voce resta "da datare" ([syncPending]) e viaggia alla prima sincronizzazione.
 * - **Ogni attivazione crea una generazione nuova** (128 bit casuali): i dati di un'attivazione vecchia non possono tornare dopo una
 *   cancellazione (E-F33). L'unica eccezione e' non farne una seconda quando l'account e' **gia' acceso** (un altro dispositivo, o un doppio
 *   tocco): il consenso vale come avviso visto per quella generazione.
 * - **Gli altri dispositivi aspettano l'avviso** ([FacesState.AwaitingNotice]): finche' l'utente non lo vede qui niente si scarica, si calcola
 *   o si mostra ([FacesState.activeHere] e' falso). Un testo piu' nuovo lo richiede di nuovo.
 * - **"Non su questo dispositivo"** e' locale (mai una voce): vale subito, poi i dati locali di quella generazione si tolgono con
 *   [FacesLocalDataEraser] (chiave per prima). Se la cancellazione non finisce, la generazione resta ricordata e si riprova (avvio, voce che
 *   cambia, [reconcile]).
 * - **Nessuna cancellazione senza una prova** ([FacesStateRules.replacedBy]): solo la voce spenta, una generazione diversa o la decisione
 *   dell'utente di escludere questo dispositivo tolgono dati. Una voce sconosciuta (E-F12), assente (uno store non ancora aperto) o un
 *   argomento piu' vecchio della scelta fatta qui non cancellano mai niente.
 * - **Freschezza** (D30, [FacesFreshness]): [onPersonalRoundCompleted] la accende.
 *
 * Tutte le operazioni che cambiano lo stato sono serializzate. Mai la generazione, i nomi o le chiavi nei log (D37): questa classe non scrive
 * niente nei log.
 */
class DefaultFacesSwitch(
  scope: CoroutineScope,
  private val account: FacesPersonalSettings,
  private val localStore: FacesLocalStore,
  private val requirements: FacesRequirements,
  private val eraser: FacesLocalDataEraser,
  private val clock: FacesClock,
  private val textVersion: Int = FacesSwitch.CURRENT_TEXT_VERSION,
  private val freshMillis: Long = FacesSwitch.FRESH_MILLIS,
  private val newGeneration: () -> FaceGeneration = { FaceGeneration.random() },
) : FacesSwitch {

  private val job = SupervisorJob(scope.coroutineContext[Job])
  private val own = CoroutineScope(scope.coroutineContext + job)
  private val mutex = Mutex()
  private val stateLock = Any()
  private val freshness = FacesFreshness(clock, freshMillis) { account.current() }
  private var expiry: Job? = null

  @Volatile private var stopped = false

  /** La recinzione delle share vere (D34), ricordata dall'ultima lettura dei requisiti. Finche' non si sa, vale la piu' stretta. */
  private val fenced = MutableStateFlow(true)

  private val mutableState = MutableStateFlow<FacesState>(FacesState.Off)
  override val state: StateFlow<FacesState> = mutableState.asStateFlow()
  override val fresh: StateFlow<Boolean> = freshness.flow
  override val syncPending: StateFlow<Boolean> get() = account.syncPending

  /** Lo stato di adesso, sincrono: cambia prima di tornare da ogni scelta (R7 della revisione di sicurezza della Fase 6). */
  override fun currentState(): FacesState =
    if (stopped) FacesState.Off else FacesStateRules.derive(account.current(), localStore.local.value, fenced.value, textVersion)

  init {
    refresh()
    // Lo stato segue la voce dell'account, lo stato locale e la recinzione; si ricalcola sempre dai valori di adesso, mai da quelli
    // dell'emissione (una emissione in ritardo non puo' riportare uno stato vecchio).
    own.launch { merge(account.setting, localStore.local, fenced).collect { refresh() } }
    // All'avvio e a ogni voce nuova dall'account: i dati di una generazione non piu' corrente, o di un'esclusione interrotta, si finiscono di togliere.
    own.launch {
      refreshRequirements()
      account.setting.collect { settle() }
    }
  }

  override suspend fun activate(consent: FacesActivationConsent): ActivateOutcome = mutex.withLock {
    if (stopped) return@withLock ActivateOutcome.Unavailable
    val setting = account.current()
    if (setting == FacesSetting.Unknown) return@withLock ActivateOutcome.NeedsUpdate
    // Una cancellazione in corso (P8) finisce prima: la generazione nuova non nasce sopra una che si sta togliendo.
    if (localStore.local.value.erasing != null) return@withLock ActivateOutcome.Unavailable
    if (consent.textVersion != textVersion) return@withLock ActivateOutcome.StaleConsent
    refreshRequirements()
    val outcome = try {
      if (setting is FacesSetting.On) join(setting) else start()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Un guasto dello stato locale o dei requisiti non e' un'eccezione per chi chiama: la pagina dice "non disponibile" e si puo' riprovare.
      ActivateOutcome.Unavailable
    }
    refresh()
    outcome
  }

  /** L'account e' spento: una generazione nuova, se i requisiti ci sono (D5). */
  private suspend fun start(): ActivateOutcome {
    val area = requirements.privateArea()
    if (area !is PrivateAreaState.Declared) return ActivateOutcome.NoPrivateArea
    val indexers = requirements.indexers()
    if (indexers.accountIndexers == 0 && !indexers.thisDevice) return ActivateOutcome.NoIndexer(indexers.canBecomeIndexer)
    if (!eraseStale(keep = null)) return ActivateOutcome.Unavailable
    val generation = newGeneration()
    // Prima il locale (avviso visto, generazione dei dati), poi la voce: nel mezzo lo stato resta spento e un crollo non lascia niente da trattare.
    localStore.update {
      it.copy(excluded = false, noticedGeneration = generation, noticedTextVersion = textVersion, dataGeneration = generation)
    }
    val written = try {
      account.set(FacesSetting.On(generation, textVersion))
    } catch (e: CancellationException) {
      withContext(NonCancellable) { forgetDataMarker(generation) }
      throw e
    } catch (e: Exception) {
      null
    }
    return when (written) {
      true -> ActivateOutcome.Activated(generation)
      false -> {
        forgetDataMarker(generation)
        ActivateOutcome.NeedsUpdate
      }
      null -> {
        forgetDataMarker(generation)
        ActivateOutcome.Unavailable
      }
    }
  }

  /** L'account e' gia' acceso: niente generazione nuova; il consenso della pagina vale come avviso visto qui. */
  private suspend fun join(setting: FacesSetting.On): ActivateOutcome {
    if (setting.textVersion > textVersion) return ActivateOutcome.NeedsUpdate
    if (!eraseStale(keep = setting.generation)) return ActivateOutcome.Unavailable
    markNoticed(setting.generation)
    raiseAccountVersion(setting)
    return ActivateOutcome.Activated(setting.generation)
  }

  override suspend fun acceptNotice(textVersion: Int) {
    guarded {
      if (stopped) return@guarded
      val awaiting = currentState() as? FacesState.AwaitingNotice ?: return@guarded
      if (textVersion != awaiting.textVersion) return@guarded
      if (!eraseStale(keep = awaiting.generation)) return@guarded
      markNoticed(awaiting.generation)
      (account.current() as? FacesSetting.On)?.takeIf { it.generation == awaiting.generation }?.let { raiseAccountVersion(it) }
      refreshRequirements()
      refresh()
    }
  }

  override suspend fun excludeThisDevice() {
    guarded {
      if (stopped) return@guarded
      // Prima la scelta (vale subito, anche se i dati non si riescono a togliere adesso), poi i dati.
      localStore.update { it.copy(excluded = true) }
      refresh()
      localStore.local.value.dataGeneration?.let { eraseLocalData(it, LocalEraseWhy.EXCLUDED) }
      refresh()
    }
  }

  override suspend fun includeThisDevice() {
    guarded {
      if (stopped || !localStore.local.value.excluded) return@guarded
      // Un'esclusione interrotta a meta' si finisce prima: non si riprende con dati a meta'.
      localStore.local.value.dataGeneration?.let { if (!eraseLocalData(it, LocalEraseWhy.EXCLUDED)) return@guarded }
      val setting = account.current()
      localStore.update { current ->
        val seen = setting is FacesSetting.On && setting.textVersion <= textVersion &&
          FacesStateRules.noticeSeen(current, setting.generation, textVersion)
        // Se l'avviso e' gia' stato visto il dispositivo torna attivo: la generazione dei dati si ricorda prima che lo stato lo dica.
        current.copy(excluded = false, dataGeneration = if (seen) (setting as FacesSetting.On).generation else current.dataGeneration)
      }
      refreshRequirements()
      refresh()
    }
  }

  override suspend fun reconcile(setting: FacesSetting?): ReconcileOutcome = mutex.withLock { settleLocked(setting) }

  /** Un giro **completo** dei dati personali e' finito in questo processo (lo chiama chi lo esegue, dopo `PersonalSync.sync`): accende [fresh]. */
  fun onPersonalRoundCompleted() {
    if (stopped) return
    synchronized(stateLock) {
      freshness.completed()
      expiry?.cancel()
      // Allo scadere si avvisa chi raccoglie [fresh]; chi la legge con `.value` la trova gia' calcolata sul tempo di adesso.
      expiry = freshness.millisLeft()?.let { left -> own.launch { delay(left); freshness.tick() } }
    }
  }

  /** Rilegge i requisiti (area privata dichiarata e recinzione): lo chiama chi cambia il profilo del dispositivo. */
  suspend fun refreshRequirements() {
    val fence = try {
      when (val area = requirements.privateArea()) {
        is PrivateAreaState.Declared -> requirements.localOnly(area.share)
        PrivateAreaState.Missing, PrivateAreaState.SameAsCommon -> true
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      true
    }
    fenced.value = fence
  }

  /** Ferma l'interruttore prima dell'azzeramento (I13): niente piu' emissioni, stato spento. Lo stato locale non si tocca (lo toglie chi azzera). */
  fun stop() {
    stopped = true
    synchronized(stateLock) { expiry?.cancel() }
    job.cancel()
    freshness.clear()
    refresh()
  }

  // --- Interni ---------------------------------------------------------------------------------------------------------------------------

  private fun refresh() {
    synchronized(stateLock) {
      mutableState.value = currentState()
      freshness.tick()
    }
  }

  /** Una scelta dell'utente: serializzata, e un guasto dello stato locale non e' un'eccezione per la schermata (lo stato resta quello di prima). */
  private suspend fun guarded(block: suspend () -> Unit) {
    mutex.withLock {
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        refresh()
      }
    }
  }

  private suspend fun settle() {
    mutex.withLock { settleLocked(account.current()) }
  }

  /**
   * L'allineamento dei dati locali con la voce [evidence] (D30). Cancella i dati di [FacesLocal.dataGeneration] solo se: l'utente ha escluso
   * questo dispositivo, oppure [evidence] e' una prova (voce spenta o altra generazione) **e** la voce di adesso non nomina quella generazione
   * accesa (un argomento piu' vecchio di una scelta fatta qui non cancella i dati appena nati). Falso solo se la cancellazione e' fallita.
   */
  private suspend fun settleLocked(evidence: FacesSetting?): ReconcileOutcome {
    if (stopped) return ReconcileOutcome.Done
    val local = localStore.local.value
    val data = local.dataGeneration
    if (data != null) {
      val current = account.current()
      val stillNamed = current is FacesSetting.On && current.generation == data
      val why = when {
        local.excluded -> LocalEraseWhy.EXCLUDED
        stillNamed || !FacesStateRules.replacedBy(evidence, data) -> null
        evidence is FacesSetting.Off -> LocalEraseWhy.ACCOUNT_OFF
        else -> LocalEraseWhy.GENERATION_CHANGED
      }
      if (why != null && !eraseLocalData(data, why)) return ReconcileOutcome.Failed(ERASE_PENDING)
    }
    refresh()
    return ReconcileOutcome.Done
  }

  /** Toglie i dati locali di una generazione diversa da [keep] (una generazione vecchia non convive con la nuova). Vero se non resta niente. */
  private suspend fun eraseStale(keep: FaceGeneration?): Boolean {
    val data = localStore.local.value.dataGeneration ?: return true
    if (data == keep) return true
    return eraseLocalData(data, LocalEraseWhy.GENERATION_CHANGED)
  }

  private suspend fun eraseLocalData(generation: FaceGeneration, why: LocalEraseWhy): Boolean {
    val done = try {
      eraser.erase(generation, why)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      false
    }
    // La generazione si dimentica solo dopo che la cancellazione ha detto "non resta niente": altrimenti si riprova.
    if (done) localStore.update { if (it.dataGeneration == generation) it.copy(dataGeneration = null) else it }
    return done
  }

  /** L'avviso di [generation] e' visto qui per la versione di questa app; il dispositivo si include e ricorda i suoi dati. */
  private suspend fun markNoticed(generation: FaceGeneration) {
    localStore.update {
      val sameGeneration = it.noticedGeneration == generation
      it.copy(
        excluded = false,
        noticedGeneration = generation,
        noticedTextVersion = maxOf(textVersion, if (sameGeneration) it.noticedTextVersion ?: 0 else 0),
        dataGeneration = generation,
      )
    }
  }

  /** Il testo e' cambiato e l'utente l'ha riletto: l'account passa alla versione nuova (stessa generazione), cosi' anche gli altri tornano all'avviso. */
  private suspend fun raiseAccountVersion(setting: FacesSetting.On) {
    if (setting.textVersion >= textVersion) return
    try {
      account.set(FacesSetting.On(setting.generation, textVersion))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // La versione dell'account resta quella vecchia: qui l'avviso e' visto, gli altri dispositivi lo vedranno al prossimo tentativo.
    }
  }

  private suspend fun forgetDataMarker(generation: FaceGeneration) {
    localStore.update {
      if (it.dataGeneration == generation) it.copy(noticedGeneration = null, noticedTextVersion = null, dataGeneration = null) else it
    }
  }

  companion object {
    /** Il codice di `PrePublish.Hold` quando i dati di una generazione non piu' corrente non si sono potuti togliere (D30): quel giro non pubblica. */
    const val ERASE_PENDING: String = "FACES_ERASE_PENDING"
  }
}
