package dev.pampa.mycloudgallery.feature.integrations.receive

import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.IncomingReject
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSaveOutcome
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSavePlan
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStage
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStageId
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import dev.pampa.mycloudgallery.core.backup.contract.IncomingUploads
import dev.pampa.mycloudgallery.core.backup.contract.NasDestination
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationCheck
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationRules
import dev.pampa.mycloudgallery.feature.integrations.share.FlowCounters
import dev.pampa.mycloudgallery.feature.integrations.share.ReceiveCounter
import java.io.IOException

// Fase 5B (P11, docs/fase5-progetto.md §1.2 "Arrivi (5B)", §2.7, D24, E-X8, E-S11; contratto `IncomingUploads` di P0-B): l'implementazione di
// riferimento dei file in arrivo. `stage` e' la copia immediata ([IncomingStager]); `save` e' il gesto dell'utente.
//
// **Niente parte da solo** (E-S11): ricevere una condivisione non avvia nessun caricamento. `save` vuole una [PhoneConfirmation] legata al piano
// ([IncomingSavePlan]: la condivisione copiata, la cartella, l'area), costruita dal foglio dopo il tocco su "Salva sul NAS"; una conferma di
// un altro piano, vecchia (oltre 10 minuti di orologio monotono) o dal futuro (un altro avvio) non vale. Poi la coda ([IncomingQueue]) mette gli
// elementi `INCOMING` nel diario del backup, con la stessa verifica e lo stesso "mai sostituire" di ogni caricamento; se il NAS non risponde le
// copie locali restano in coda e partono appena si puo' (E-X8).

/** Il diario del backup visto dai file in arrivo (lo lega `:app`, P14: `core-backup` non vede questo modulo e questo non vede il NAS). */
interface IncomingQueue {

  /** In [dest] si puo' scrivere? Un'area in sola lettura (E-N9), un'area che non e' dell'utente, un'area di prova nella release (D12/D13): no. */
  suspend fun destinationAllowed(dest: NasDestination): Boolean

  /**
   * Mette in coda un elemento `INCOMING` per ogni file di [stage] nella cartella [dest] (nome occupato → "nome (2)", mai una sostituzione), e
   * cita i file locali di [files] finche' non sono caricati. Restituisce quanti elementi ha messo in coda.
   */
  suspend fun enqueue(stage: IncomingStage, dest: NasDestination, files: IncomingFiles): Int
}

/** Nessun diario legato: non si accetta niente. */
object NoIncomingQueue : IncomingQueue {
  override suspend fun destinationAllowed(dest: NasDestination): Boolean = false

  override suspend fun enqueue(stage: IncomingStage, dest: NasDestination, files: IncomingFiles): Int = 0
}

class DefaultIncomingUploads(
  private val stager: IncomingStager,
  private val queue: IncomingQueue,
  private val clock: BackupClock,
) : IncomingUploads {

  override suspend fun stage(streams: List<IncomingStream>): IncomingStage {
    val stage = stager.stage(streams)
    FlowCounters.receive.add(ReceiveCounter.STAGED_ITEMS, stage.accepted.size.toLong())
    for ((reason, count) in stage.rejected) FlowCounters.receive.add(counterOf(reason), count.toLong())
    return stage
  }

  override suspend fun save(stage: IncomingStage, dest: NasDestination, confirmation: PhoneConfirmation): IncomingSaveOutcome {
    // I file devono essere ancora quelli copiati: nessuno li ha tolti o sostituiti dopo la copia.
    if (!stager.isIntact(stage)) return IncomingSaveOutcome.StageGone
    val plan = IncomingSavePlan(stage, dest)
    val check = PhoneConfirmationRules.check(plan, confirmation, clock.monotonicMs())
    if (check != PhoneConfirmationCheck.Valid) {
      FlowCounters.receive.add(ReceiveCounter.SAVE_REJECTED)
      return IncomingSaveOutcome.Rejected(check)
    }
    if (!queue.destinationAllowed(dest)) return IncomingSaveOutcome.DestinationNotAllowed
    val queued = try {
      queue.enqueue(stage, dest, stager)
    } catch (_: IOException) {
      return IncomingSaveOutcome.Unavailable
    }
    if (queued <= 0) return IncomingSaveOutcome.Unavailable
    FlowCounters.receive.add(ReceiveCounter.SAVED, queued.toLong())
    return IncomingSaveOutcome.Queued(queued, stage.needsUserInitiatedJob)
  }

  override suspend fun discard(stage: IncomingStageId) {
    stager.discard(stage)
    FlowCounters.receive.add(ReceiveCounter.DISCARDED)
  }

  private fun counterOf(reason: IncomingReject): ReceiveCounter = when (reason) {
    IncomingReject.FILE_SCHEME -> ReceiveCounter.REJECTED_FILE_SCHEME
    IncomingReject.OWN_PROVIDER -> ReceiveCounter.REJECTED_OWN_PROVIDER
    IncomingReject.TOO_LARGE -> ReceiveCounter.REJECTED_TOO_LARGE
    IncomingReject.TOO_MANY -> ReceiveCounter.REJECTED_TOO_MANY
    IncomingReject.STAGE_TOO_BIG -> ReceiveCounter.REJECTED_STAGE_TOO_BIG
    IncomingReject.NO_SPACE -> ReceiveCounter.REJECTED_NO_SPACE
    IncomingReject.NOT_MEDIA -> ReceiveCounter.REJECTED_NOT_MEDIA
    IncomingReject.UNREADABLE,
    IncomingReject.NOT_AVAILABLE,
    -> ReceiveCounter.REJECTED_UNREADABLE
  }
}
