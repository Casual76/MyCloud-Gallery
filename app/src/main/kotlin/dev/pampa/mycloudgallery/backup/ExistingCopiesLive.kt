package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.ExistingCopiesState
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStatus
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WorkRequest

/**
 * Fase I (12/11; BS-3, BF-4 della critica del primo backup): **che cosa sta succedendo adesso** al lavoro che il primo backup aspetta, sopra il riepilogo
 * (anche quello in cache) e senza toccarne i numeri. Pura: le regole le da' chi chiama ([decide], di norma il motore delle regole della Diagnostica), cosi'
 * si prova senza il grafo.
 *
 * - **La ricerca** (l'area privata non e' ancora scandita): il motivo che ferma la scansione (`INDEX_SYNC`) e quanti file del NAS ha gia' guardato, se gira.
 * - **Il confronto** delle copie simili (ricerca finita, foto da confrontare): un motivo **solo se ci sono anteprime che il confronto aspetta**
 *   ([awaitingPreviews]), chiesto alla preparazione delle anteprime (`PREPARATION`, che scrive sul NAS). Giro 2 (BS2-4): un'anteprima che manca puo' essere
 *   non ancora prodotta (Preparazione) o soltanto non ancora copiata sul telefono (basta l'indice): la pagina **non attribuisce la causa** e non promette
 *   niente, dice solo «alcune anteprime non sono ancora pronte». Senza anteprime in attesa nessun motivo, la riga neutra.
 *
 * Il motivo e' quello del contratto ([WaitReason]); un motivo che il backup non nomina (congelamento, attivita' spenta, profilo, limiti di Android) e'
 * [WaitReason.NEXT_RUN]: la pagina dice che e' fermo e che il motivo e' nella Diagnostica, mai «cerco» con la barra che passa. Un verdetto `Run` dice che
 * le regole lasciano partire, non che il lavoro stia girando: per questo «gira» lo dice solo lo scanner ([NasScanStatus.Scanning]).
 */
internal object ExistingCopiesLive {

  /** La richiesta delle regole per la scansione dell'area privata (la stessa delle tappe della scansione, `NasStages.scan`). */
  val SEARCH: WorkRequest = WorkRequest.Background(BackgroundActivity.INDEX_SYNC)

  /** La richiesta delle regole per la preparazione delle anteprime (la stessa di `NasStages.prepare`: scrive sul NAS). */
  val PREVIEWS: WorkRequest = WorkRequest.Background(BackgroundActivity.PREPARATION, writesToNas = true)

  suspend fun of(
    state: ExistingCopiesState,
    scan: NasScanStatus?,
    awaitingPreviews: Boolean,
    decide: suspend (WorkRequest) -> RuleDecision,
  ): ExistingCopiesState {
    if (!state.privateAreaScanned) {
      val scanning = scan as? NasScanStatus.Scanning
      return state.copy(heldBy = heldOf(decide(SEARCH)), scanSeen = scanning?.filesSeen, scanTotal = scanning?.filesTotal)
    }
    val held = if (state.similarPending > 0 && awaitingPreviews) heldOf(decide(PREVIEWS)) else null
    return state.copy(heldBy = held, scanSeen = null, scanTotal = null)
  }

  /** Il motivo di un verdetto: null se le regole lasciano partire; [WaitReason.NEXT_RUN] per un motivo che il backup non nomina. */
  fun heldOf(decision: RuleDecision): WaitReason? = when (decision) {
    is RuleDecision.Run, is RuleDecision.NeedsConfirmation -> null
    is RuleDecision.Wait, is RuleDecision.Stop -> BackupStatusReducers.waitReasonOf(decision)
  }
}
