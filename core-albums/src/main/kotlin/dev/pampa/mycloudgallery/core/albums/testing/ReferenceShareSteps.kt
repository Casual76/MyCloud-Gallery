package dev.pampa.mycloudgallery.core.albums.testing

import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmation
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.TicketState
import dev.pampa.mycloudgallery.core.albums.contract.TransferItemOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferRefusal
import dev.pampa.mycloudgallery.core.albums.contract.TransferStart
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.CancellationException

/** I passi di [InMemoryShareRunner] (§2.5), separati per leggibilita'. Ogni passo si salva nel diario prima di quello dopo. */
internal class ReferenceShareSteps(private val p: ShareRunnerPorts, private val live: Map<ShareOpId, ShareConfirmation>) {

  /** Un errore di una porta e' passeggero (si riprova), salvo la morte simulata del processo, che deve arrivare al banco. */
  private inline fun <T> transient(block: () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: SimulatedDeath) {
    throw e
  } catch (e: Exception) {
    null
  }

  /** Album nuovo: la definizione `active` (senza elementi) e il livello, accodati e idempotenti (§2.5 passo 3). */
  suspend fun ensureDefinition(op: ShareOpRecord) {
    val nonce = op.nonceHex ?: return
    val folder = op.folder ?: return
    val me = p.family.me() ?: return
    val changes = buildList {
      add(SharedAlbumChange.Definition(op.title, folder.path, SharedState.ACTIVE, me, nonce, null))
      op.level?.let { add(SharedAlbumChange.Level(it)) }
    }
    p.publisher.stage(op.album, changes)
  }

  private fun confirmationFor(op: ShareOpId): ShareConfirmation? =
    live[op]?.takeIf { p.clocks.monotonicMs() - it.issuedAtMonotonicMs <= dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationRules.MAX_AGE_MS }

  /** I trasferimenti chiesti per gli elementi `PLANNED` con una chiave: ritrovati per chiave, o avviati con la conferma ancora valida. */
  suspend fun startTransfers(op: ShareOpRecord) {
    val planned = p.diary.items(op.id).filter { it.state == ShareItemState.PLANNED && it.transferKey != null }
    for ((key, group) in planned.groupBy { it.transferKey!! }) {
      // SD-2 (docs/prova-500.md §E6): "mai partito" lo dice solo una risposta di `find`; un suo errore vale "non so" (si richiede al giro dopo).
      val found = try {
        p.tickets.find(key)
      } catch (e: CancellationException) {
        throw e
      } catch (e: SimulatedDeath) {
        throw e
      } catch (e: Exception) {
        continue
      }
      if (found != null) {
        group.forEach { p.diary.update(it.copy(state = ShareItemState.TRANSFERRING, ticket = found, wait = null)) }
        continue
      }
      val confirmation = confirmationFor(op.id)
      if (confirmation == null) {
        // La conferma non c'e' piu' (processo nuovo o scaduta) e il trasferimento non e' mai partito: niente si e' mosso.
        group.forEach { p.diary.update(it.copy(state = ShareItemState.FAILED, failure = ShareFailure.NOT_STARTED, wait = null)) }
        continue
      }
      val start = when (group.first().action) {
        ShareItemAction.MOVE_IN -> {
          // D31: la cartella d'origine nei dati personali **prima** dello spostamento ("Riporta" deve sapere dove tornare).
          val recorded = transient { group.forEach { item -> item.source?.let { p.origins.record(item.media, ShareFolder(it.share, it.path.parent() ?: it.path)) } } }
          if (recorded == null) null else transient { p.mover.move(key, group.mapNotNull { it.source }, op.folder!!, emptySet(), confirmation) }
        }
        ShareItemAction.UPLOAD -> transient { p.uploader.upload(key, group.map { it.media }, op.folder!!, confirmation) }
        ShareItemAction.MOVE_OUT -> {
          val dest = p.origins.originOf(group.first().media) ?: ShareFolder(p.family.privateShare()!!, SharePath.parse(p.family.backupFolderName()))
          transient { p.mover.move(key, group.mapNotNull { it.source }, dest, emptySet(), confirmation) }
        }
        else -> null
      }
      when (start) {
        is TransferStart.Started -> group.forEach { p.diary.update(it.copy(state = ShareItemState.TRANSFERRING, ticket = start.ticket, wait = null)) }
        is TransferStart.Refused -> {
          val wait = waitOf(start.reason)
          group.forEach { item ->
            p.diary.update(
              if (wait == null) item.copy(state = ShareItemState.FAILED, failure = ShareFailure.TRANSFER_FAILED, wait = null) else item.copy(wait = wait),
            )
          }
        }
        null -> group.forEach { p.diary.update(it.copy(wait = ShareWait.NAS_UNREACHABLE)) }
      }
    }
  }

  private fun waitOf(reason: TransferRefusal): ShareWait? = when (reason) {
    TransferRefusal.NO_SPACE -> ShareWait.NO_SPACE
    TransferRefusal.WRITES_BLOCKED, TransferRefusal.READ_ONLY -> ShareWait.WRITES_BLOCKED
    TransferRefusal.NAS_UNREACHABLE -> ShareWait.NAS_UNREACHABLE
    TransferRefusal.CONFIRMATION_REJECTED, TransferRefusal.PLAN_INVALID -> null
  }

  /** Avanza ogni elemento non terminale di [op] di quanti passi puo' (§2.5 passi 4-7). */
  suspend fun advance(op: ShareOpRecord) {
    pollTransfers(op)
    val albumState = p.shared.state(op.album)
    for (item in p.diary.items(op.id)) {
      when (item.state) {
        ShareItemState.PLANNED -> if (item.transferKey == null) arrivedInCommon(op, item)
        ShareItemState.IN_COMMON -> stageItem(op, item)
        ShareItemState.IN_PRIVATE -> if (albumState == SharedState.ACTIVE) {
          val ticket = p.publisher.stage(op.album, listOf(SharedAlbumChange.ItemRemoved(item.media)))
          p.diary.update(item.copy(state = ShareItemState.ENTRY_STAGED, stageSeq = ticket.seq))
        } else {
          p.diary.update(item.copy(state = ShareItemState.DONE))
        }
        else -> Unit
      }
    }
    // Una prova di pubblicazione per giro (il pubblicatore decide se e' presto); poi cio' che e' dentro una testa e' pubblicato.
    if (p.diary.items(op.id).any { it.state == ShareItemState.ENTRY_STAGED }) transient { p.publisher.flush(op.album, FlushReason.SYNC_ROUND) }
    val upTo = p.publisher.publishedUpTo(op.album).seq
    for (item in p.diary.items(op.id)) {
      if (item.state == ShareItemState.ENTRY_STAGED && (item.stageSeq ?: Long.MAX_VALUE) <= upTo) {
        p.diary.update(item.copy(state = ShareItemState.PUBLISHED))
      }
    }
    for (item in p.diary.items(op.id).filter { it.state == ShareItemState.PUBLISHED }) finish(item)
  }

  /** Un media gia' nell'area comune (riferimento, adozione): IN_COMMON solo se il catalogo lo vede li' adesso. */
  private suspend fun arrivedInCommon(op: ShareOpRecord, item: ShareItemRecord) {
    val f = p.facts.facts(listOf(item.media))[item.media]
    if (f == null || f.place != MediaPlace.COMMON) {
      p.diary.update(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.SOURCE_GONE))
    } else {
      p.diary.update(item.copy(state = ShareItemState.IN_COMMON, dest = f.nas, contentKey = f.contentKey))
    }
  }

  private suspend fun pollTransfers(op: ShareOpRecord) {
    val all = p.diary.items(op.id)
    val transferring = all.filter { it.state == ShareItemState.TRANSFERRING && it.ticket != null }
    for ((ticket, group) in transferring.groupBy { it.ticket!! }) {
      val ordered = group.sortedBy { it.index }
      when (val state = transient { p.tickets.status(ticket) } ?: continue) {
        is TicketState.InProgress -> ordered.forEach { if (it.wait != null) p.diary.update(it.copy(wait = null)) }
        is TicketState.Waiting -> ordered.forEach { if (it.wait != state.reason) p.diary.update(it.copy(wait = state.reason)) }
        // SD-1 (docs/prova-500.md §E6): un esito per file nell'ordine della richiesta, cioe' di TUTTI gli elementi con quel biglietto (in qualunque
        // stato), non dei soli rimasti; con una parte della richiesta ancora `PLANNED` si aspetta; una lista che non torna decide il catalogo.
        is TicketState.Ended -> {
          val key = ordered.first().transferKey
          if (key != null && all.any { it.state == ShareItemState.PLANNED && it.transferKey == key }) continue
          val request = all.filter { it.ticket == ticket }.sortedBy { it.index }
          if (state.outcomes.size != request.size) {
            ordered.forEach { settle(it, TransferItemOutcome.ARRIVED, unknownTicket = true) }
          } else {
            val slot = request.withIndex().associate { (position, item) -> item.index to position }
            ordered.forEach { settle(it, state.outcomes[slot.getValue(it.index)]) }
          }
        }
        TicketState.Unknown -> ordered.forEach { settle(it, TransferItemOutcome.ARRIVED, unknownTicket = true) }
      }
    }
  }

  /** L'esito di un file: arrivato solo se il catalogo locale lo vede dove deve essere (D29); altrimenti aspetta il giro dopo. */
  private suspend fun settle(item: ShareItemRecord, outcome: TransferItemOutcome, unknownTicket: Boolean = false) {
    when (outcome) {
      TransferItemOutcome.ARRIVED, TransferItemOutcome.ALREADY_THERE -> {
        val f = p.facts.facts(listOf(item.media))[item.media]
        val target = if (item.action == ShareItemAction.MOVE_OUT) MediaPlace.MY_PRIVATE else MediaPlace.COMMON
        val next = if (item.action == ShareItemAction.MOVE_OUT) ShareItemState.IN_PRIVATE else ShareItemState.IN_COMMON
        when {
          f != null && f.place == target -> p.diary.update(item.copy(state = next, dest = f.nas, contentKey = f.contentKey, wait = null))
          unknownTicket -> p.diary.update(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.TRANSFER_FAILED, wait = null))
          else -> if (item.wait != ShareWait.RULES) p.diary.update(item.copy(wait = ShareWait.RULES))
        }
      }
      TransferItemOutcome.SOURCE_CHANGED, TransferItemOutcome.SOURCE_GONE, TransferItemOutcome.FAILED, TransferItemOutcome.CANCELLED -> {
        // Un file fermato DOPO la pubblicazione e il collegamento (il cestino della sorgente non riesce) e' arrivato: decide il catalogo (D29).
        val f = p.facts.facts(listOf(item.media))[item.media]
        val target = if (item.action == ShareItemAction.MOVE_OUT) MediaPlace.MY_PRIVATE else MediaPlace.COMMON
        if (f != null && f.place == target) {
          settle(item, TransferItemOutcome.ARRIVED)
          return
        }
        when (outcome) {
          TransferItemOutcome.SOURCE_CHANGED -> p.diary.update(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.SOURCE_CHANGED, wait = null))
          TransferItemOutcome.SOURCE_GONE -> p.diary.update(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.SOURCE_GONE, wait = null))
          TransferItemOutcome.CANCELLED -> p.diary.update(item.copy(state = ShareItemState.CANCELLED, wait = null))
          else -> p.diary.update(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.TRANSFER_FAILED, wait = null))
        }
      }
    }
  }

  /** §2.5 passo 6: la voce solo per un file nell'area comune e solo se il livello lo permette ancora (E-SH13). */
  private suspend fun stageItem(op: ShareOpRecord, item: ShareItemRecord) {
    val f = p.facts.facts(listOf(item.media))[item.media]
    if (f == null || f.place != MediaPlace.COMMON || f.nas == null || f.contentKey == null) {
      p.diary.update(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.SOURCE_GONE))
      return
    }
    val newAlbum = op.nonceHex != null
    val opKind = if (op.kind == SharePlanKind.ADOPT) AlbumOp.ADOPT_FOLDER_FILES else AlbumOp.ADD_MEDIA
    if (!newAlbum && !p.allowed(op.album, opKind, null)) {
      p.diary.update(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.NOT_ALLOWED))
      return
    }
    val me = p.family.me() ?: return
    val change = SharedAlbumChange.Item(item.media, f.version, f.contentKey, f.nas.path, (item.index + 1) * POSITION_STEP, me)
    val ticket = p.publisher.stage(op.album, listOf(change))
    p.diary.update(item.copy(state = ShareItemState.ENTRY_STAGED, stageSeq = ticket.seq, dest = f.nas, contentKey = f.contentKey))
  }

  /**
   * §2.5 passo 7: il guardiano subito per i miei media **spostati** dall'area privata che il telefono non ha (D28), poi DONE. Un media
   * caricato dal telefono ha la copia del telefono (vale Q4 (b)); uno gia' nell'area comune non e' stato spostato.
   */
  private suspend fun finish(item: ShareItemRecord) {
    val needsSafeCopy = item.action == ShareItemAction.MOVE_IN && item.mine && !item.safeCopyRequested &&
      p.facts.facts(listOf(item.media))[item.media]?.onPhone == false
    if (needsSafeCopy) {
      if (transient { p.safeCopies.request(listOf(item.media)) } == null) return
      p.diary.update(item.copy(safeCopyRequested = true))
    }
    p.diary.update(p.diary.items(item.op).first { it.index == item.index }.copy(state = ShareItemState.DONE))
  }

  companion object {
    /** Le posizioni degli elementi a intervalli (D21). */
    const val POSITION_STEP: Long = 1_000

    /**
     * La chiave dei trasferimenti di un'operazione: una per tipo (spostamento, caricamento) e, per "Riporta", una per cartella d'origine
     * ([group]); null se non si trasferisce.
     */
    fun keyFor(op: ShareOpId, action: ShareItemAction, group: Int = 0): TransferKey? = when (action) {
      ShareItemAction.MOVE_IN -> TransferKey("${op.value}:in")
      ShareItemAction.UPLOAD -> TransferKey("${op.value}:up")
      ShareItemAction.MOVE_OUT -> TransferKey("${op.value}:out:$group")
      ShareItemAction.REFERENCE, ShareItemAction.ADOPT -> null
    }

    /** Lo stato per la UI di [op] (§1.5 `ShareProgress`). */
    fun stateOf(op: ShareOpRecord, items: List<ShareItemRecord>): ShareOpState {
      val done = items.count { it.state == ShareItemState.DONE }
      if (items.all { it.state.terminal }) {
        val failures = items.filter { it.state == ShareItemState.FAILED }.groupingBy { it.failure!! }.eachCount()
        val cancelled = items.count { it.state == ShareItemState.CANCELLED }
        return if (done == items.size) ShareOpState.Done(op.id, op.album, op.kind, done) else ShareOpState.PartlyDone(op.id, op.album, op.kind, done, failures, cancelled)
      }
      val wait = items.firstOrNull { !it.state.terminal && it.wait != null && it.wait != ShareWait.RULES }?.wait
      return if (wait != null) ShareOpState.Paused(op.id, op.album, op.kind, wait, done, items.size) else ShareOpState.Running(op.id, op.album, op.kind, done, items.size)
    }
  }
}
