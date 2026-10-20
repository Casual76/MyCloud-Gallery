package dev.pampa.mycloudgallery.feature.backup.free

import dev.pampa.mycloudgallery.core.backup.contract.FreeSpace
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceBlock
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpacePlan
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRun
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunId
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Correzioni 5B (20/10; docs/review/funzionale-5b.md LS-2, LS-5, LS-8, LS-10, LS-11): il GIRO di "Libera spazio" vive fuori dalla pagina. Prima
// la verifica girava nel `viewModelScope` e moriva con la pagina ("Lascia aperta questa pagina"): niente lavoro avviato dall'utente, niente
// notifica "Pronto", niente ripresa. Adesso la pagina chiede il giro a questa porta, che `:app` lega a un oggetto del processo (con il lavoro
// avviato dall'utente, la notifica e la ripresa dal diario dopo la morte del processo); senza porta, [FreeSpaceDriver] fa lo stesso nello
// scope della pagina. **Non decide niente**: chiama il servizio (`FreeSpace`) nell'ordine del §2.5 e racconta cio' che risponde.

/** Il giro di "Libera spazio" fuori dalla pagina (LS-2). Lo lega `:app`; i test e la pagina senza porta usano [FreeSpaceDriver]. */
interface FreeSpaceRuns {

  /** LS-10: le parti di "Libera spazio" ci sono (il backup e' partito). Prima la pagina non calcola un piano (sarebbe vuoto) e lo dice. */
  val ready: StateFlow<Boolean>

  /**
   * LS-11: l'ora del NAS e' nota. Senza, ogni media datato conta come "degli ultimi giorni" (fallire chiusi): la pagina lo dice e ricalcola il
   * riepilogo quando arriva.
   */
  val nasTimeKnown: StateFlow<Boolean>

  /** LS-8: il guardiano aspetta che il backup finisca il file che sta caricando (uno alla volta con il backup). */
  val waitingForBackup: StateFlow<Boolean>

  /** Il giro di adesso, come lo dice il servizio; null = nessuno. */
  val current: StateFlow<FreeSpaceRun?>

  /** LS-11: la pagina si apre; senza l'ora del NAS si prova a raggiungerlo (non blocca: [nasTimeKnown] cambia quando arriva). */
  fun prepare() = Unit

  /**
   * All'apertura della pagina: il giro rimasto aperto (in questo processo, o ripreso dal diario dopo la morte del processo, nello stesso avvio
   * e entro le 6 ore), che riparte da dove era; uno appena scaduto si dice una volta. Null = nessuno: la pagina mostra il riepilogo.
   */
  suspend fun resumeActive(): FreeSpaceRun?

  /** Il tocco di conferma: il giro comincia e va avanti anche fuori dalla pagina. */
  fun start(plan: FreeSpacePlan, confirmation: PhoneConfirmation)

  /** "Annulla" durante la verifica: il giro si chiude e non riparte piu'. */
  suspend fun cancel()

  /** La pagina ha portato avanti il giro (un lotto e la sua risposta): il giro com'e' adesso. */
  fun update(run: FreeSpaceRun)
}

/**
 * Il giro portato avanti in [scope] (la pagina senza porta, i test; in `:app` dentro l'oggetto del processo): `verify`, poi `resume` a tappe di
 * [stepBudget] finche' guardiano o verifica lavorano. **Un giro alla volta, un solo conduttore**: chi chiede la ripresa mentre un giro va gia'
 * avanti lo trova, non ne fa partire un secondo.
 *
 * **LS-5.** Se il servizio restituisce il giro che gli si e' passato, identico, non ha fatto niente (il null-object, quando le parti del backup
 * si tolgono a meta'): il giro si ferma "non disponibile". Prima il ciclo girava all'infinito sul thread principale. Un giro uguale ma nuovo
 * (il guardiano che aspetta il turno del backup, LS-8) non e' fermo: si riprova dopo una pausa, mai a vuoto.
 *
 * @param activeRun il giro rimasto aperto nel diario (in `:app`: `SafeFreeSpace.active`), null = nessuno.
 * @param abandonRun chiude il giro nel diario ("Annulla"; in `:app`: `SafeFreeSpace.abandon`).
 * @param onDriving il giro va avanti a tappe (in `:app`: il lavoro avviato dall'utente che lo ospita, con la notifica).
 * @param onChanged ogni giro raccontato (in `:app`: la notifica "Pronto").
 */
class FreeSpaceDriver(
  private val service: FreeSpace,
  private val scope: CoroutineScope,
  private val stepBudget: Duration = 20.seconds,
  private val activeRun: suspend () -> FreeSpaceRun? = { null },
  private val abandonRun: suspend (FreeSpaceRunId) -> FreeSpaceRun? = { null },
  override val ready: StateFlow<Boolean> = MutableStateFlow(true),
  override val nasTimeKnown: StateFlow<Boolean> = MutableStateFlow(true),
  override val waitingForBackup: StateFlow<Boolean> = MutableStateFlow(false),
  private val onDriving: (FreeSpaceRun) -> Unit = {},
  private val onChanged: (FreeSpaceRun) -> Unit = {},
  private val stillPause: Duration = 1.seconds,
) : FreeSpaceRuns {

  private val runFlow = MutableStateFlow<FreeSpaceRun?>(null)
  private val driving = Mutex()
  private var job: Job? = null

  override val current: StateFlow<FreeSpaceRun?> = runFlow

  override suspend fun resumeActive(): FreeSpaceRun? {
    val known = runFlow.value
    if (known != null && known.isActive) {
      if (known.isWorking && !driving.isLocked) launchDrive(known)
      return known
    }
    val found = activeRun() ?: return null
    publish(found)
    if (found.isWorking) launchDrive(found)
    return found
  }

  override fun start(plan: FreeSpacePlan, confirmation: PhoneConfirmation) {
    job?.cancel()
    job = scope.launch {
      val first = service.verify(plan, confirmation, stepBudget)
      publish(first)
      if (first.isWorking) {
        onDriving(first)
        drive(first)
      }
    }
  }

  private fun launchDrive(run: FreeSpaceRun) {
    onDriving(run)
    job = scope.launch { drive(run) }
  }

  /** Porta avanti il giro finche' guardiano o verifica lavorano; un solo conduttore alla volta. Restituisce il giro com'e'. */
  suspend fun drive(start: FreeSpaceRun): FreeSpaceRun = driving.withLock {
    // Un altro conduttore puo' averlo gia' portato avanti mentre si aspettava il turno.
    var current = runFlow.value?.takeIf { it.id == start.id } ?: start
    while (current.isWorking) {
      val next = service.resume(current, stepBudget)
      if (next === current) {
        // LS-5: il servizio non ha fatto niente (il null-object): il giro non andra' mai avanti.
        current = current.copy(state = FreeSpaceRunState.BLOCKED, blocked = FreeSpaceBlock.NOT_AVAILABLE)
        publish(current)
        break
      }
      // Uguale ma nuovo: il guardiano aspetta il turno del backup (LS-8). Si riprova dopo una pausa, mai a vuoto.
      if (next == current) delay(stillPause)
      current = next
      publish(current)
    }
    current
  }

  override suspend fun cancel() {
    // Si aspetta che il conduttore si fermi: un suo ultimo racconto non deve coprire il giro chiuso.
    job?.cancelAndJoin()
    val run = runFlow.value ?: return
    if (!run.isActive) return
    publish(abandonRun(run.id) ?: run.copy(state = FreeSpaceRunState.CANCELLED))
  }

  override fun update(run: FreeSpaceRun) = publish(run)

  /** Prima gli effetti (in `:app` la notifica e la richiesta di riaprire la pagina), poi il giro: chi lo vede cambiato li trova gia' fatti. */
  private fun publish(run: FreeSpaceRun) {
    onChanged(run)
    runFlow.value = run
  }

  private companion object {
    val FreeSpaceRun.isWorking: Boolean get() = state == FreeSpaceRunState.GUARDING || state == FreeSpaceRunState.VERIFYING
  }
}
