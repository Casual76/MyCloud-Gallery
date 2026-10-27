package dev.pampa.mycloudgallery.core.albums.share

import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AttestRequest
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.NasItemRef
import dev.pampa.mycloudgallery.core.albums.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmation
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationRules
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpRecord
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumLimits
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.TicketState
import dev.pampa.mycloudgallery.core.albums.contract.TransferItemOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferRefusal
import dev.pampa.mycloudgallery.core.albums.contract.TransferStart
import dev.pampa.mycloudgallery.core.albums.contract.TransferTicket
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import kotlinx.coroutines.CancellationException

/**
 * I passi di [ShareOrchestrator] (docs/fase7-progetto.md §2.5 passi 3-7, §2.6 "Riporta"). **Ogni passo si scrive nel diario prima di quello
 * dopo**; ogni passo e' idempotente e si riprende dal diario dopo la morte del processo. Le porte si chiamano solo con la conferma del foglio
 * (in memoria: dopo la morte del processo un trasferimento mai partito diventa `FAILED(NOT_STARTED)`, "Riprova" fa un piano nuovo). Un errore
 * di una porta e' passeggero (si riprova al giro dopo); un errore del diario o del catalogo risale (lo gestisce chi guida il lavoro).
 */
internal class ShareSteps(
  private val p: ShareOrchestratorPorts,
  private val live: Map<ShareOpId, ShareConfirmation>,
) {
  /** Le definizioni degli album nuovi gia' accodate in questo processo (il pubblicatore e' idempotente: una ripresa le riaccoda). */
  private val defined = HashSet<ShareOpId>()

  /** Gli album gia' ripubblicati per intero in questo processo (`FlushReason.RESUME` la prima volta, poi `SYNC_ROUND`). */
  private val resumed = HashSet<dev.pampa.mycloudgallery.core.model.AlbumId>()

  /** Un errore di una porta e' passeggero: null, si riprova. L'annullamento e gli `Error` (la morte del processo nei banchi) passano. */
  private inline fun <T> transient(block: () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    GalleryLog.d(TAG) { "porta non disponibile (${e.javaClass.simpleName}): si riprova" }
    null
  }

  private suspend fun save(item: ShareItemRecord) = p.diary.update(item)

  // --- Passo 3: la definizione di un album nuovo -------------------------------------------------------------------------------------

  /**
   * Album nuovo: la definizione `active` (senza elementi) e il livello, accodati e idempotenti (§2.5 passo 3). Le voci degli elementi si accodano
   * solo dopo. Vero se e' in coda (in questo processo). Una definizione che il pubblicatore rifiuta (un errore di programmazione: titolo e
   * cartella li ha gia' controllati il piano) ferma gli elementi che aspettano la voce: i file restano dove sono arrivati.
   */
  suspend fun ensureDefinition(op: ShareOpRecord): Boolean {
    val nonce = op.nonceHex ?: return true
    if (op.id in defined) return true
    val folder = op.folder ?: return false
    val me = p.family.me() ?: return false
    val changes = buildList {
      add(SharedAlbumChange.Definition(op.title, folder.path, SharedState.ACTIVE, me, nonce, null))
      op.level?.let { add(SharedAlbumChange.Level(it)) }
    }
    try {
      p.publisher.stage(op.album, changes)
    } catch (e: CancellationException) {
      throw e
    } catch (e: IllegalArgumentException) {
      GalleryLog.e(TAG) { "definizione d'album rifiutata dal pubblicatore: elementi fermi" }
      for (item in p.diary.items(op.id).filter { !it.state.terminal && it.state != ShareItemState.PLANNED && it.state != ShareItemState.TRANSFERRING }) {
        save(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.TRANSFER_FAILED, wait = null))
      }
      return false
    } catch (e: Exception) {
      // Senza l'ora del NAS (livello) o senza famiglia pronta: si riprova al giro dopo.
      return false
    }
    defined += op.id
    afterStage(op)
    return true
  }

  /** La vista locale dell'album si ricalcola dopo una modifica accodata (un errore li' non ferma niente: si ricalcola al giro dopo). */
  private suspend fun afterStage(op: ShareOpRecord) {
    transient { p.afterStage(op.album) }
  }

  // --- Passo 4: i trasferimenti --------------------------------------------------------------------------------------------------------

  private fun confirmationFor(op: ShareOpId): ShareConfirmation? =
    live[op]?.takeIf { p.clocks.monotonicMs() - it.issuedAtMonotonicMs in 0..ShareConfirmationRules.MAX_AGE_MS }

  /**
   * I trasferimenti degli elementi `PLANNED` con una chiave, per chiave: ritrovati per chiave (un processo morto dopo l'avvio), oppure, se il
   * catalogo vede gia' lo stesso contenuto nell'area comune (E-SH12: un altro familiare l'ha aggiunto), nessuna copia; oppure avviati con la
   * conferma ancora valida. Per uno spostamento verso l'area comune la cartella d'origine si registra **prima** (D31).
   *
   * SD-2 (docs/prova-500.md §E6): "mai partito" lo dice solo una risposta di `find` (nessun trasferimento con quella chiave). Un errore di `find`
   * vale "non so": il gruppo resta `PLANNED` com'e' e si richiede al giro dopo; niente controllo E-SH12, niente avvio, niente `NOT_STARTED`. Un
   * trasferimento gia' partito (la morte fra l'avvio e il salvataggio) si ritrova cosi' sempre, anche dopo un guasto del diario della porta.
   */
  suspend fun startTransfers(op: ShareOpRecord) {
    val planned = p.diary.items(op.id).filter { it.state == ShareItemState.PLANNED && it.transferKey != null }
    for ((key, unordered) in planned.groupBy { it.transferKey!! }) {
      val group = unordered.sortedBy { it.index }
      val found = when (val lookup = lookUp(key)) {
        TicketLookup.Unknown -> continue
        is TicketLookup.Known -> lookup.ticket
      }
      if (found != null) {
        group.forEach { save(it.copy(state = ShareItemState.TRANSFERRING, ticket = found, wait = null)) }
        continue
      }
      val action = group.first().action
      val toSend = if (action == ShareItemAction.MOVE_OUT) group else withoutAlreadyInCommon(op, group)
      if (toSend.isEmpty()) continue
      val confirmation = confirmationFor(op.id)
      if (confirmation == null) {
        // La conferma non c'e' piu' (processo nuovo o scaduta) e il trasferimento non e' mai partito: niente si e' mosso.
        toSend.forEach { save(it.copy(state = ShareItemState.FAILED, failure = ShareFailure.NOT_STARTED, wait = null)) }
        continue
      }
      val start = when (action) {
        ShareItemAction.MOVE_IN -> moveIn(op, toSend, key, confirmation)
        ShareItemAction.UPLOAD -> {
          val folder = op.folder
          if (folder == null) null else transient { p.uploader.upload(key, toSend.map { it.media }, folder, confirmation) }
        }
        ShareItemAction.MOVE_OUT -> {
          val dest = toSend.first().dest?.let { ShareFolder(it.share, it.path) }
          val sources = toSend.mapNotNull { it.source }
          if (dest == null || sources.size != toSend.size) null else transient { p.mover.move(key, sources, dest, emptySet(), confirmation) }
        }
        ShareItemAction.REFERENCE, ShareItemAction.ADOPT -> null
      }
      when (start) {
        is TransferStart.Started -> toSend.forEach { save(it.copy(state = ShareItemState.TRANSFERRING, ticket = start.ticket, wait = null)) }
        is TransferStart.Refused -> {
          val wait = waitOf(start.reason)
          toSend.forEach { item ->
            save(if (wait == null) item.copy(state = ShareItemState.FAILED, failure = ShareFailure.TRANSFER_FAILED, wait = null) else item.copy(wait = wait))
          }
        }
        null -> toSend.forEach { if (it.wait != ShareWait.NAS_UNREACHABLE) save(it.copy(wait = ShareWait.NAS_UNREACHABLE)) }
      }
    }
  }

  /** La risposta di `TicketStatus.find`: [Known] (un biglietto, o null = nessun trasferimento con quella chiave) o [Unknown] (la porta ha fallito). */
  sealed interface TicketLookup {
    class Known(val ticket: TransferTicket?) : TicketLookup

    data object Unknown : TicketLookup
  }

  /** SD-2: `find` senza ridurre un errore a "non c'e'" (l'annullamento e gli `Error`, la morte del processo nei banchi, passano). */
  suspend fun lookUp(key: TransferKey): TicketLookup = try {
    TicketLookup.Known(p.tickets.find(key))
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    GalleryLog.d(TAG) { "biglietto non ritrovabile adesso (${e.javaClass.simpleName}): si richiede al giro dopo" }
    TicketLookup.Unknown
  }

  /** D31: la cartella d'origine di ogni media nei miei dati personali, poi lo spostamento. Null se non si e' potuto (si riprova). */
  private suspend fun moveIn(op: ShareOpRecord, items: List<ShareItemRecord>, key: TransferKey, confirmation: ShareConfirmation): TransferStart? {
    val folder = op.folder ?: return null
    val sources = items.mapNotNull { it.source }
    if (sources.size != items.size) return null
    val recorded = transient {
      for (item in items) {
        val source = item.source ?: continue
        ShareRules.originOf(source.path)?.let { p.origins.record(item.media, ShareFolder(source.share, it)) }
      }
    }
    if (recorded == null) return null
    return transient { p.mover.move(key, sources, folder, emptySet(), confirmation) }
  }

  /**
   * E-SH12: gli elementi il cui contenuto il catalogo vede **gia'** nell'area comune non si copiano: passano a `IN_COMMON` con il file che c'e'
   * (la voce citera' quello). Restituisce quelli da trasferire. Una verifica che non riesce vale "non c'e'" (si trasferisce, come prima).
   */
  private suspend fun withoutAlreadyInCommon(op: ShareOpRecord, group: List<ShareItemRecord>): List<ShareItemRecord> {
    val keys = group.mapNotNull { it.contentKey }
    if (keys.isEmpty()) return group
    // I1: con l'hash completo noto al mio catalogo, "gia' nell'area comune" vuol dire gli stessi byte, non solo la stessa chiave rapida.
    val full = transient { p.facts.facts(group.map { it.media }) }?.mapValues { it.value.sha256 }.orEmpty()
    val requests = group.mapNotNull { item -> item.contentKey?.let { AttestRequest(it, full[item.media]) } }.distinct()
    val attested = transient { p.facts.attest(requests) } ?: return group
    if (attested.isEmpty()) return group
    val common = p.family.commonShare() ?: return group
    val there = transient { p.facts.facts(attested.values.toSet()) } ?: return group
    val remaining = ArrayList<ShareItemRecord>()
    for (item in group) {
      val m = item.contentKey?.let { attested[it] }?.let { there[it] }
      val nas = m?.nas
      if (m != null && nas != null && m.place == MediaPlace.COMMON && nas.share.equals(common, ignoreCase = true)) {
        save(item.copy(state = ShareItemState.IN_COMMON, dest = nas, wait = null))
      } else {
        remaining += item
      }
    }
    if (remaining.size != group.size) GalleryLog.d(TAG) { "gia' nell'area comune: ${group.size - remaining.size} elementi senza copia (E-SH12)" }
    return remaining
  }

  private fun waitOf(reason: TransferRefusal): ShareWait? = when (reason) {
    TransferRefusal.NO_SPACE -> ShareWait.NO_SPACE
    TransferRefusal.WRITES_BLOCKED, TransferRefusal.READ_ONLY -> ShareWait.WRITES_BLOCKED
    TransferRefusal.NAS_UNREACHABLE -> ShareWait.NAS_UNREACHABLE
    TransferRefusal.CONFIRMATION_REJECTED, TransferRefusal.PLAN_INVALID -> null
  }

  // --- Passi 5-7: completamento, voce, al sicuro ---------------------------------------------------------------------------------------

  /** Avanza ogni elemento non terminale di [op] di quanti passi puo' (§2.5 passi 4-7, §2.6 "Riporta"). */
  suspend fun advance(op: ShareOpRecord) {
    pollTransfers(op)
    val defined = ensureDefinition(op)
    val albumState = p.shared.state(op.album)
    for (item in p.diary.items(op.id)) {
      when (item.state) {
        ShareItemState.PLANNED -> if (item.transferKey == null) arrivedInCommon(item)
        ShareItemState.IN_COMMON -> {
          requestSafeCopyIfNeeded(item)
          if (defined) stageItem(op, current(item))
        }
        ShareItemState.ENTRY_STAGED -> requestSafeCopyIfNeeded(item)
        ShareItemState.IN_PRIVATE -> bringBackEntry(op, item, albumState)
        else -> Unit
      }
    }
    publishStaged(op)
    for (item in p.diary.items(op.id).filter { it.state == ShareItemState.PUBLISHED }) finish(item)
  }

  private suspend fun current(item: ShareItemRecord): ShareItemRecord = p.diary.items(item.op).first { it.index == item.index }

  /** Un media gia' nell'area comune (riferimento, adozione): `IN_COMMON` solo se il catalogo lo vede li' adesso. */
  private suspend fun arrivedInCommon(item: ShareItemRecord) {
    val file = commonFileOf(item)
    if (file == null) {
      save(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.SOURCE_GONE, wait = null))
    } else {
      save(item.copy(state = ShareItemState.IN_COMMON, dest = file.ref, contentKey = file.contentKey, wait = null))
    }
  }

  private class CommonFile(val ref: NasItemRef, val contentKey: ContentKey, val version: VersionId?, val sha256: Sha256?)

  /**
   * Il file dell'area comune dell'elemento, come lo vede **adesso** il mio catalogo (D9, invariante 6): il media stesso se e' nell'area comune
   * (lo spostamento l'ha collegato allo stesso `MediaId`, D29), altrimenti il file dell'area comune con la stessa chiave di contenuto
   * (E-SH12). Null se il catalogo non vede nessun file li'.
   */
  private suspend fun commonFileOf(item: ShareItemRecord): CommonFile? {
    val common = p.family.commonShare() ?: return null
    val f = p.facts.facts(listOf(item.media))[item.media]
    if (f != null && f.place == MediaPlace.COMMON && f.nas != null && f.contentKey != null && f.nas.share.equals(common, ignoreCase = true)) {
      return CommonFile(f.nas, f.contentKey, f.version, f.sha256)
    }
    val key = item.contentKey ?: f?.contentKey ?: return null
    val attested = p.facts.attest(listOf(AttestRequest(key, f?.sha256)))[key] ?: return null
    val other = p.facts.facts(listOf(attested))[attested] ?: return null
    val nas = other.nas ?: return null
    if (other.place != MediaPlace.COMMON || !nas.share.equals(common, ignoreCase = true)) return null
    return CommonFile(nas, key, f?.version ?: other.version, other.sha256 ?: f?.sha256)
  }

  private suspend fun pollTransfers(op: ShareOpRecord) {
    val all = p.diary.items(op.id)
    val transferring = all.filter { it.state == ShareItemState.TRANSFERRING && it.ticket != null }
    for ((ticket, group) in transferring.groupBy { it.ticket!! }) {
      val ordered = group.sortedBy { it.index }
      when (val state = transient { p.tickets.status(ticket) } ?: continue) {
        is TicketState.InProgress -> ordered.forEach { if (it.wait != null) save(it.copy(wait = null)) }
        is TicketState.Waiting -> ordered.forEach { if (it.wait != state.reason) save(it.copy(wait = state.reason)) }
        is TicketState.Ended -> {
          // Una parte della richiesta e' ancora `PLANNED` (la morte a meta' dei salvataggi dell'avvio, poi `find` senza risposta): prende il
          // biglietto al giro dopo, e solo allora gli esiti si assegnano, tutti insieme.
          val key = ordered.first().transferKey
          if (key != null && all.any { it.state == ShareItemState.PLANNED && it.transferKey == key }) continue
          settleEnded(all.filter { it.ticket == ticket }, ordered, state.outcomes)
        }
        TicketState.Unknown -> ordered.forEach { settle(it, TransferItemOutcome.ARRIVED, unknownTicket = true) }
      }
    }
  }

  /**
   * SD-1 (docs/prova-500.md §E6): gli esiti di un trasferimento finito sono uno per file **nell'ordine della richiesta**, cioe' di tutti gli
   * elementi partiti con quel biglietto ([request]: in qualunque stato, per indice; il biglietto resta sull'elemento in ogni passo dopo), non dei
   * soli rimasti in `TRANSFERRING` ([pending]): se un'interruzione ne ha gia' assestati alcuni, ognuno dei rimasti prende ancora il **suo** esito.
   * Una lista che non torna con la richiesta (una porta che ne ha perso uno) non si applica per posizione: ogni elemento rimasto si decide sul
   * catalogo, come per un biglietto sconosciuto (arrivato solo se il catalogo lo vede al suo posto, altrimenti un trasferimento fallito: i file
   * sono interi dove la porta li ha lasciati).
   */
  private suspend fun settleEnded(request: List<ShareItemRecord>, pending: List<ShareItemRecord>, outcomes: List<TransferItemOutcome>) {
    val ordered = request.sortedBy { it.index }
    if (outcomes.size != ordered.size) {
      GalleryLog.w(TAG) { "esiti di un trasferimento che non tornano con la richiesta (${outcomes.size} su ${ordered.size}): decide il catalogo" }
      pending.forEach { settle(it, TransferItemOutcome.ARRIVED, unknownTicket = true) }
      return
    }
    val slot = ordered.withIndex().associate { (position, item) -> item.index to position }
    for (item in pending) settle(item, outcomes[slot.getValue(item.index)])
  }

  /**
   * L'esito di un file: arrivato solo se il catalogo locale lo vede dove deve essere (D29: stesso `MediaId`; E-SH12: lo stesso contenuto);
   * altrimenti si aspetta il giro dopo (il catalogo non ha ancora il completamento). Un biglietto sconosciuto (diario della porta azzerato)
   * senza il file al suo posto e' un trasferimento fallito: i file sono dove la porta li ha lasciati, interi.
   */
  private suspend fun settle(item: ShareItemRecord, outcome: TransferItemOutcome, unknownTicket: Boolean = false) {
    when (outcome) {
      TransferItemOutcome.ARRIVED, TransferItemOutcome.ALREADY_THERE -> {
        if (item.action == ShareItemAction.MOVE_OUT) {
          val f = p.facts.facts(listOf(item.media))[item.media]
          val private = p.family.privateShare()
          when {
            f != null && f.place == MediaPlace.MY_PRIVATE && f.nas != null && private != null && f.nas.share.equals(private, ignoreCase = true) ->
              save(item.copy(state = ShareItemState.IN_PRIVATE, dest = f.nas, wait = null))
            unknownTicket -> save(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.TRANSFER_FAILED, wait = null))
            else -> if (item.wait != ShareWait.RULES) save(item.copy(wait = ShareWait.RULES))
          }
        } else {
          val file = commonFileOf(item)
          when {
            file != null -> save(item.copy(state = ShareItemState.IN_COMMON, dest = file.ref, contentKey = file.contentKey, wait = null))
            unknownTicket -> save(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.TRANSFER_FAILED, wait = null))
            else -> if (item.wait != ShareWait.RULES) save(item.copy(wait = ShareWait.RULES))
          }
        }
      }
      TransferItemOutcome.SOURCE_CHANGED, TransferItemOutcome.SOURCE_GONE, TransferItemOutcome.FAILED, TransferItemOutcome.CANCELLED -> {
        // Il motore ferma un file anche DOPO averlo pubblicato e collegato al catalogo: il cestino della sorgente non riesce (spazio finito, la
        // sorgente cambiata dopo la verifica: E-D13, restano le due copie). Il file e' arrivato e il catalogo lo sa: decide il catalogo (D29),
        // non l'esito, come per un file arrivato. Mai un elemento fallito o annullato con il suo media gia' dall'altra parte.
        if (movedByTransfer(item)) {
          settle(item, TransferItemOutcome.ARRIVED)
          return
        }
        when (outcome) {
          TransferItemOutcome.SOURCE_CHANGED -> save(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.SOURCE_CHANGED, wait = null))
          TransferItemOutcome.SOURCE_GONE -> save(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.SOURCE_GONE, wait = null))
          TransferItemOutcome.CANCELLED -> save(item.copy(state = ShareItemState.CANCELLED, wait = null))
          else -> save(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.TRANSFER_FAILED, wait = null))
        }
      }
    }
  }

  /**
   * Il media dell'elemento sta gia' dove il trasferimento lo doveva portare, secondo il catalogo locale: lo **stesso** `MediaId` nell'area comune
   * (spostamento o caricamento verso l'album) o nella mia area privata ("Riporta"). Prima del trasferimento stava dall'altra parte, e un media
   * e' in una sola operazione aperta: ce l'ha portato questo trasferimento (porta di completamento, D29).
   */
  private suspend fun movedByTransfer(item: ShareItemRecord): Boolean {
    val f = p.facts.facts(listOf(item.media))[item.media] ?: return false
    val nas = f.nas ?: return false
    return if (item.action == ShareItemAction.MOVE_OUT) {
      val private = p.family.privateShare() ?: return false
      f.place == MediaPlace.MY_PRIVATE && nas.share.equals(private, ignoreCase = true)
    } else {
      val common = p.family.commonShare() ?: return false
      f.place == MediaPlace.COMMON && f.contentKey != null && nas.share.equals(common, ignoreCase = true)
    }
  }

  /**
   * §2.5 passo 6: la voce `item:` solo per un file che il catalogo vede **adesso** nell'area comune (invariante 6) e, per un album che c'era
   * gia', solo se la tabella lo permette ancora (E-SH13: altrimenti l'elemento si ferma con il motivo, il file resta, "Riporta" e' offerto).
   */
  private suspend fun stageItem(op: ShareOpRecord, item: ShareItemRecord) {
    if (item.state != ShareItemState.IN_COMMON) return
    val file = commonFileOf(item)
    if (file == null) {
      save(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.SOURCE_GONE, wait = null))
      return
    }
    if (op.nonceHex == null) {
      // La replica non conosce (ancora) l'album: appena ripartito il processo, prima del primo ricalcolo. Si aspetta, non si decide.
      val state = p.shared.state(op.album)
      if (state == null) {
        if (item.wait != ShareWait.RULES) save(item.copy(wait = ShareWait.RULES))
        return
      }
      val opKind = if (op.kind == SharePlanKind.ADOPT) AlbumOp.ADOPT_FOLDER_FILES else AlbumOp.ADD_MEDIA
      if (state != SharedState.ACTIVE || !p.allowed(op.album, opKind, null)) {
        save(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.NOT_ALLOWED, wait = null))
        return
      }
    }
    if (file.ref.path.value.toByteArray(Charsets.UTF_8).size > SharedAlbumLimits.MAX_PATH_BYTES) {
      save(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.TRANSFER_FAILED, wait = null))
      return
    }
    val me = p.family.me() ?: return
    val position = (transient { p.lastPosition(op.album) } ?: (item.index.toLong() * ShareRules.POSITION_STEP)) + ShareRules.POSITION_STEP
    // I1: la voce porta l'hash completo quando il mio catalogo lo conosce: chi legge lo confronta (D9) e un file diverso con la stessa chiave
    // rapida non si mostra al posto di quello aggiunto.
    val change = SharedAlbumChange.Item(item.media, file.version, file.contentKey, file.ref.path, position, me, file.sha256)
    val ticket = try {
      p.publisher.stage(op.album, listOf(change))
    } catch (e: CancellationException) {
      throw e
    } catch (e: IllegalArgumentException) {
      // Una voce che chi legge rifiuterebbe (un errore di programmazione): niente in coda, il file resta nell'area comune.
      GalleryLog.e(TAG) { "voce rifiutata dal pubblicatore: elemento fermo" }
      save(item.copy(state = ShareItemState.FAILED, failure = ShareFailure.TRANSFER_FAILED, wait = null))
      return
    } catch (e: Exception) {
      return
    }
    save(item.copy(state = ShareItemState.ENTRY_STAGED, stageSeq = ticket.seq, dest = file.ref, contentKey = file.contentKey, wait = null))
    afterStage(op)
  }

  /** §2.6 "Riporta": il media e' tornato nell'area privata. Lapide `item:` se l'album e' ancora attivo e lui ne era un elemento vivo. */
  private suspend fun bringBackEntry(op: ShareOpRecord, item: ShareItemRecord, albumState: SharedState?) {
    if (albumState == null) {
      // La replica non conosce (ancora) l'album: si aspetta il ricalcolo per sapere se serve la lapide.
      if (item.wait != ShareWait.RULES) save(item.copy(wait = ShareWait.RULES))
      return
    }
    val published = if (albumState == SharedState.ACTIVE) transient { p.publishedId(op.album, item.media) } else null
    if (published == null) {
      save(item.copy(state = ShareItemState.DONE, wait = null))
      return
    }
    val ticket = transient { p.publisher.stage(op.album, listOf(SharedAlbumChange.ItemRemoved(published))) } ?: return
    save(item.copy(state = ShareItemState.ENTRY_STAGED, stageSeq = ticket.seq, wait = null))
    afterStage(op)
  }

  /** Una prova di pubblicazione per giro (il pubblicatore decide se e' presto); poi cio' che e' dentro una testa e' pubblicato. */
  private suspend fun publishStaged(op: ShareOpRecord) {
    val staged = p.diary.items(op.id).filter { it.state == ShareItemState.ENTRY_STAGED }
    if (staged.isEmpty()) return
    val reason = if (resumed.add(op.album)) FlushReason.RESUME else FlushReason.SYNC_ROUND
    val wait = when (val outcome = transient { p.publisher.flush(op.album, reason) }) {
      null, PublishOutcome.Failed -> ShareWait.NAS_UNREACHABLE
      is PublishOutcome.Blocked -> if (outcome.reason == AlbumWriteBlock.NO_NAS) ShareWait.NAS_UNREACHABLE else ShareWait.WRITES_BLOCKED
      is PublishOutcome.TooSoon -> ShareWait.RULES
      is PublishOutcome.Published, PublishOutcome.NothingToPublish -> null
    }
    val upTo = transient { p.publisher.publishedUpTo(op.album).seq } ?: return
    for (item in p.diary.items(op.id).filter { it.state == ShareItemState.ENTRY_STAGED }) {
      when {
        (item.stageSeq ?: Long.MAX_VALUE) <= upTo -> save(item.copy(state = ShareItemState.PUBLISHED, wait = null))
        item.wait != wait -> save(item.copy(wait = wait))
      }
    }
  }

  /**
   * D28 (Q4 (b), correzioni dal campo A, D2): il guardiano **subito** per i miei media **spostati** dall'area privata che il telefono non ha piu'
   * (la condizione di Q4 (b) e' gia' vera). Un media caricato dal telefono ha la copia del telefono (vale Q4 (b) quando sparira'); uno che era gia'
   * nell'area comune (riferimento, E-SH12) non e' stato spostato. Si chiede appena il file e' nell'area comune; il guardiano decide con le sue
   * regole (un media mai stato sul telefono si ignora).
   */
  private fun needsSafeCopy(item: ShareItemRecord): Boolean =
    item.action == ShareItemAction.MOVE_IN && item.mine && item.ticket != null && !item.safeCopyRequested

  private suspend fun requestSafeCopyIfNeeded(item: ShareItemRecord) {
    if (!needsSafeCopy(item)) return
    val onPhone = p.facts.facts(listOf(item.media))[item.media]?.onPhone ?: return
    if (onPhone) return
    if (transient { p.safeCopies.request(listOf(item.media)) } == null) return
    save(current(item).copy(safeCopyRequested = true))
  }

  /** §2.5 passo 7: dopo la voce pubblicata, il guardiano (se serve e non e' gia' chiesto), poi DONE. */
  private suspend fun finish(item: ShareItemRecord) {
    if (needsSafeCopy(item)) {
      val onPhone = p.facts.facts(listOf(item.media))[item.media]?.onPhone ?: true
      if (!onPhone) {
        if (transient { p.safeCopies.request(listOf(item.media)) } == null) return
        save(item.copy(safeCopyRequested = true))
      }
    }
    save(current(item).copy(state = ShareItemState.DONE, wait = null))
  }

  private companion object {
    const val TAG = "AlbumCondivisione"
  }
}
