package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.albums.contract.AlbumMover
import dev.pampa.mycloudgallery.core.albums.contract.AlbumUploader
import dev.pampa.mycloudgallery.core.albums.contract.NasItemRef
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmation
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationRules
import dev.pampa.mycloudgallery.core.albums.contract.ShareDiary
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpRecord
import dev.pampa.mycloudgallery.core.albums.share.ShareTransferKeys
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.core.albums.contract.TicketState
import dev.pampa.mycloudgallery.core.albums.contract.TicketStatus
import dev.pampa.mycloudgallery.core.albums.contract.TransferItemOutcome
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferRefusal
import dev.pampa.mycloudgallery.core.albums.contract.TransferStart
import dev.pampa.mycloudgallery.core.albums.contract.TransferTicket
import dev.pampa.mycloudgallery.core.backup.contract.IncomingDestination
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.upload.AlbumFolderUploads
import dev.pampa.mycloudgallery.core.backup.upload.AlbumUploadItem
import dev.pampa.mycloudgallery.core.backup.upload.AlbumUploadOutcome
import dev.pampa.mycloudgallery.core.backup.upload.AlbumUploadStart
import dev.pampa.mycloudgallery.core.backup.upload.AlbumUploadStatus
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationIssuer
import dev.pampa.mycloudgallery.core.nasops.contract.LeaveBehind
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.PlanHash
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

// LE PORTE CHE SPOSTANO E CARICANO PER GLI ALBUM CONDIVISI (Fase 7, P13; §1.4, §2.5 passo 4, D12, D29, C26). core-albums non vede core-nasops ne'
// core-backup: le sue porte le lega solo :app, qui. **La terza chiave** di C26: `NasOperations.start` vuole una `UserConfirmation` per **quel** piano,
// e la emette `ConfirmationIssuer` solo se la conferma della condivisione vale (eta' sotto i 10 minuti; l'impronta del piano della condivisione l'ha
// gia' controllata l'orchestratore). Nessuna di queste classi si chiama da un dato letto dal NAS: le chiama solo l'orchestratore, dopo una conferma.
// Dal diario di core-nasops si ritrova ogni trasferimento per chiave anche dopo la morte del processo (la chiave si lega al piano **prima** di avviarlo).

/**
 * Le chiavi dei trasferimenti avviati e il piano di core-nasops a cui sono legate (durevole, scritto prima di `start`). R9: un file che c'e'
 * ma non si legge vale "non so" ([planOf] lancia: SD-2 lascia il gruppo `PLANNED` invece di dirlo "mai partito"), e non si sovrascrive.
 */
internal class TransferPlans(file: File) {
  private val records = object : DurableRecords<String, String>(file, "piani dei trasferimenti degli album", DurableRecords.Unreadable.UNKNOWN) {
    override fun encode(out: DataOutputStream, records: Map<String, String>) {
      out.writeInt(1)
      out.writeInt(records.size)
      for ((key, hash) in records) {
        out.writeUTF(key)
        out.writeUTF(hash)
      }
    }

    override fun decode(input: DataInputStream): LinkedHashMap<String, String> {
      if (input.readInt() != 1) throw IOException("versione")
      val out = LinkedHashMap<String, String>()
      repeat(input.count(4)) { out[input.readUTF()] = input.readUTF() }
      return out
    }
  }

  suspend fun planOf(key: TransferKey): PlanHash? = records.read { it[key.value] }?.let { runCatching { PlanHash(it) }.getOrNull() }

  suspend fun bind(key: TransferKey, plan: PlanHash) = records.update { it[key.value] = plan.hex }

  suspend fun unbind(key: TransferKey) = records.update { it.remove(key.value) }

  /** Le chiavi legate adesso (la prima meta' di [TransferRegisters.prune]). */
  suspend fun keys(): Set<String> = records.read { it.keys.toSet() }

  /** Toglie i legami di [keys] (chiavi che nessuna operazione aperta della condivisione cita piu'): il registro non cresce senza fine (R9). */
  suspend fun forget(keys: Set<String>): Int = if (keys.isEmpty()) 0 else records.update { map -> keys.count { map.remove(it) != null } }

  /** Quante volte il file non si e' potuto leggere (Diagnostica). */
  val unreadable: Int get() = records.unreadable
}

/**
 * I due registri dei trasferimenti verso gli album (legami chiave -> piano di core-nasops, intenzioni dei caricamenti dal telefono) e la loro
 * potatura (R9): le chiavi che nessuna operazione aperta della condivisione cita piu' si tolgono, cosi' i registri non crescono senza fine
 * verso il tetto (oltre il quale varrebbero "non so"). Prima si leggono le chiavi legate, poi quelle del diario: una chiave legata dopo non si
 * tocca, e una legata prima e' gia' nel diario (che la scrive sempre prima di chiamare la porta).
 */
internal class TransferRegisters(val plans: TransferPlans, val uploads: FileAlbumUploadJournal) {
  /** Quante volte uno dei due registri non si e' potuto leggere (Diagnostica, `ALBUM_REGISTRY_UNREADABLE`: solo il numero). */
  val unreadable: Int get() = plans.unreadable + uploads.unreadable

  /** Toglie le chiavi che [openKeys] (il diario della condivisione) non cita piu'; quante. */
  suspend fun prune(openKeys: suspend () -> Set<String>): Int {
    val bound = plans.keys()
    val intents = uploads.keys()
    if (bound.isEmpty() && intents.isEmpty()) return 0
    val keep = openKeys()
    return plans.forget(bound - keep) + uploads.forget(intents - keep)
  }
}

/**
 * Revisione B, I3 (C26): la seconda chiave non e' piu' una conseguenza della prima. Prima `OpsAlbumMover` emetteva la `UserConfirmation` per
 * **qualunque** piano gli arrivasse con una `ShareConfirmation` giovane; adesso solo per un trasferimento che il **diario** della condivisione conosce
 * (la terza chiave, scritta allo `start` prima di chiamare la porta): l'operazione e' aperta, ha l'impronta del piano che l'utente ha confermato,
 * la chiave e' la sua, i file chiesti sono le sorgenti dei suoi elementi e la destinazione e' la sua. Una chiamata da un altro punto di :app, anche
 * con una conferma valida, non sposta niente.
 */
class ShareTransferBinding(private val diary: ShareDiary) {

  /** Uno spostamento ([items] verso [dest]) che il diario conosce per [key], confermato con [confirmation]. */
  suspend fun moveMatches(key: TransferKey, confirmation: ShareConfirmation, items: List<NasItemRef>, dest: ShareFolder): Boolean {
    val (op, records) = opFor(key, confirmation) ?: return false
    val expected = when (records.first().action) {
      ShareItemAction.MOVE_IN -> op.folder
      ShareItemAction.MOVE_OUT -> records.first().dest?.let { ShareFolder(it.share, it.path) }
      else -> null
    } ?: return false
    if (!sameFolder(expected, dest)) return false
    val sources = records.mapNotNullTo(HashSet()) { it.source }
    return items.isNotEmpty() && items.all { it in sources }
  }

  /** Un caricamento ([media] verso [dest]) che il diario conosce per [key], confermato con [confirmation]. */
  suspend fun uploadMatches(key: TransferKey, confirmation: ShareConfirmation, media: List<MediaId>, dest: ShareFolder): Boolean {
    val (op, records) = opFor(key, confirmation) ?: return false
    if (records.first().action != ShareItemAction.UPLOAD) return false
    val folder = op.folder ?: return false
    if (!sameFolder(folder, dest)) return false
    val wanted = records.mapTo(HashSet()) { it.media }
    return media.isNotEmpty() && media.all { it in wanted }
  }

  private suspend fun opFor(key: TransferKey, confirmation: ShareConfirmation): Pair<ShareOpRecord, List<ShareItemRecord>>? {
    val id = ShareTransferKeys.opOf(key) ?: return null
    val op = diary.openOps().firstOrNull { it.id == id } ?: return null
    if (op.planHash != confirmation.planHash) return null
    val records = diary.items(id).filter { it.transferKey == key }
    return if (records.isEmpty()) null else op to records
  }

  private fun sameFolder(a: ShareFolder, b: ShareFolder): Boolean = a.share.equals(b.share, ignoreCase = true) && a.path == b.path
}

/**
 * [AlbumMover] sopra `NasOperations` (§2.5 passo 4, "Riporta" del §2.6): un `OpRequest.Move` verso la cartella, con i nascosti lasciati fuori come
 * `leaveBehind` (R11), avviato con la conferma di `ConfirmationIssuer` per **quel** piano. Copia lato server, verifica, pubblicazione esclusiva, porta
 * di completamento ([CatalogMoveCompletionSink]) e sorgente nel cestino: tutto in core-nasops. Idempotente per chiave ([TransferPlans]).
 */
class OpsAlbumMover internal constructor(
  private val operations: NasOperations,
  private val store: OpsStore,
  private val catalog: suspend () -> MediaCatalog?,
  private val account: () -> AccountId?,
  private val plans: TransferPlans,
  private val monotonicMs: () -> Long,
  /** Revisione B, I3: il diario della condivisione che deve conoscere il trasferimento. */
  private val binding: ShareTransferBinding,
) : AlbumMover {

  override suspend fun move(key: TransferKey, items: List<NasItemRef>, dest: ShareFolder, leave: Set<NasItemRef>, confirmation: ShareConfirmation): TransferStart {
    plans.planOf(key)?.let { hash -> store.opByPlanHash(hash)?.let { return TransferStart.Started(ticketOf(it.id)) } }
    if (monotonicMs() - confirmation.issuedAtMonotonicMs !in 0..ShareConfirmationRules.MAX_AGE_MS) {
      return TransferStart.Refused(TransferRefusal.CONFIRMATION_REJECTED)
    }
    // I3: niente conferma di core-nasops per un trasferimento che il diario non conosce (operazione, impronta, chiave, file, destinazione).
    if (!binding.moveMatches(key, confirmation, items, dest)) return TransferStart.Refused(TransferRefusal.CONFIRMATION_REJECTED)
    val c = catalog() ?: return TransferStart.Refused(TransferRefusal.NAS_UNREACHABLE)
    val me = account() ?: return TransferStart.Refused(TransferRefusal.NAS_UNREACHABLE)
    val nasItems = items.map { ref -> itemOf(c, me, ref) ?: return TransferStart.Refused(TransferRefusal.PLAN_INVALID) }
    val request = OpRequest.Move(nasItems, dest.share, dest.path, leave.mapTo(LinkedHashSet()) { LeaveBehind(it.share, it.path) })
    val plan = when (val outcome = operations.plan(request)) {
      is PlanOutcome.Ready -> outcome.plan
      is PlanOutcome.Rejected -> return TransferStart.Refused(refusalOf(outcome.reason))
    }
    plans.bind(key, plan.hash)
    val now = monotonicMs()
    val started = when (val out = operations.start(plan, ConfirmationIssuer.issue(plan, plan.requiredConfirmations, now))) {
      is StartOutcome.Started -> TransferStart.Started(ticketOf(out.id))
      is StartOutcome.AlreadyStarted -> TransferStart.Started(ticketOf(out.id))
      is StartOutcome.ConfirmationRejected -> TransferStart.Refused(TransferRefusal.CONFIRMATION_REJECTED)
      is StartOutcome.PlanInvalid -> TransferStart.Refused(TransferRefusal.PLAN_INVALID)
      StartOutcome.WritesBlocked -> TransferStart.Refused(TransferRefusal.WRITES_BLOCKED)
    }
    if (started is TransferStart.Refused) plans.unbind(key)
    return started
  }

  private suspend fun itemOf(c: MediaCatalog, me: AccountId, ref: NasItemRef): NasItem? {
    val info = c.nasLocationAt(Location.Nas(me, ref.share, NasPath.of(ref.path.value))) ?: return null
    if (info.state != LocationState.PRESENT) return null
    return NasItem(share = ref.share, path = ref.path, isFolder = false, size = info.stamp.size, stamp = info.stamp)
  }

  private fun refusalOf(reason: PlanRejection): TransferRefusal = when (reason) {
    PlanRejection.NOT_ENOUGH_SPACE -> TransferRefusal.NO_SPACE
    PlanRejection.WRITES_BLOCKED -> TransferRefusal.WRITES_BLOCKED
    PlanRejection.READ_ONLY -> TransferRefusal.READ_ONLY
    PlanRejection.NAS_UNREACHABLE -> TransferRefusal.NAS_UNREACHABLE
    else -> TransferRefusal.PLAN_INVALID
  }

  suspend fun find(key: TransferKey): TransferTicket? = plans.planOf(key)?.let { store.opByPlanHash(it) }?.let { ticketOf(it.id) }

  suspend fun status(ticket: TransferTicket): TicketState {
    val id = OpId(ticket.value.removePrefix(OP))
    val op = store.op(id) ?: return TicketState.Unknown
    val items = store.items(id).filter { !it.isFolder }.sortedBy { it.index }
    val done = items.count { it.state.terminal }
    if (items.all { it.state.terminal }) {
      return TicketState.Ended(
        items.map { item ->
          when (item.state) {
            OpItemState.DONE -> TransferItemOutcome.ARRIVED
            OpItemState.CANCELLED -> TransferItemOutcome.CANCELLED
            else -> when (item.errorCode) {
              OpErrorCode.SOURCE_CHANGED -> TransferItemOutcome.SOURCE_CHANGED
              OpErrorCode.SOURCE_MISSING -> TransferItemOutcome.SOURCE_GONE
              else -> TransferItemOutcome.FAILED
            }
          }
        },
      )
    }
    if (op.state == OpState.PAUSED) {
      val wait = when {
        op.errorCode == OpErrorCode.NO_SPACE || op.errorCode == OpErrorCode.QUOTA -> ShareWait.NO_SPACE
        op.errorCode == OpErrorCode.LOCKED -> ShareWait.LOCKED
        op.pauseReason == PauseReason.FROZEN || op.pauseReason == PauseReason.WRITES_STOPPED || op.pauseReason == PauseReason.CLOCK_JUMP -> ShareWait.WRITES_BLOCKED
        op.pauseReason == PauseReason.NAS_UNREACHABLE -> ShareWait.NAS_UNREACHABLE
        // Revisione B (I2): la copia e' arrivata, il catalogo non la riconosce ancora: un motivo che la striscia dice (prima "in corso").
        op.pauseReason == PauseReason.CATALOG -> ShareWait.CATALOG
        else -> ShareWait.RULES
      }
      return TicketState.Waiting(wait, done, items.size)
    }
    return TicketState.InProgress(done, items.size)
  }

  suspend fun cancel(ticket: TransferTicket) = operations.cancel(OpId(ticket.value.removePrefix(OP)))

  internal companion object {
    const val OP = "op:"

    fun ticketOf(id: OpId): TransferTicket = TransferTicket(OP + id.value)
  }
}

/**
 * [AlbumUploader] sopra il cammino `INCOMING` del backup (E-SH1, P4: `AlbumFolderUploads`): i media che stanno **solo sul telefono** si caricano nella
 * cartella dell'album, con la riga del telefono e il timbro di adesso presi dal catalogo locale. Idempotente per chiave (il registro dei caricamenti).
 * [uploads] e' null finche' il backup non e' partito: il caricamento non parte (niente si muove) e l'orchestratore riprova finche' vale la conferma.
 */
class FolderAlbumUploader(
  private val uploads: () -> AlbumFolderUploads?,
  private val catalog: suspend () -> MediaCatalog?,
  private val monotonicMs: () -> Long,
  /** Revisione B, I3: il diario della condivisione che deve conoscere il caricamento. */
  private val binding: ShareTransferBinding,
  /** Dopo un caricamento accettato: il lavoro degli arrivi (lo stesso di "Salva sul NAS"). */
  private val wake: () -> Unit,
) : AlbumUploader {

  override suspend fun upload(key: TransferKey, media: List<MediaId>, dest: ShareFolder, confirmation: ShareConfirmation): TransferStart {
    val engine = uploads() ?: return TransferStart.Refused(TransferRefusal.WRITES_BLOCKED)
    // Un caricamento gia' registrato si ritrova anche con la conferma scaduta (la chiave e' quella del diario della condivisione).
    if (engine.find(key.value) != null) return TransferStart.Started(ticketOf(key))
    if (monotonicMs() - confirmation.issuedAtMonotonicMs !in 0..ShareConfirmationRules.MAX_AGE_MS) {
      return TransferStart.Refused(TransferRefusal.CONFIRMATION_REJECTED)
    }
    // I3: niente caricamento per una chiave che il diario non conosce (operazione, impronta, media, cartella).
    if (!binding.uploadMatches(key, confirmation, media, dest)) return TransferStart.Refused(TransferRefusal.CONFIRMATION_REJECTED)
    val c = catalog() ?: return TransferStart.Refused(TransferRefusal.NAS_UNREACHABLE)
    val items = media.map { id -> itemOf(c, id) ?: return TransferStart.Refused(TransferRefusal.PLAN_INVALID) }
    return when (val start = engine.start(key.value, items, IncomingDestination.AlbumFolder(dest.share, dest.path))) {
      is AlbumUploadStart.Started -> TransferStart.Started(ticketOf(key)).also { wake() }
      is AlbumUploadStart.Refused -> TransferStart.Refused(
        when (start.reason) {
          dev.pampa.mycloudgallery.core.backup.upload.AlbumUploadRefusal.WRITES_BLOCKED -> TransferRefusal.WRITES_BLOCKED
          else -> TransferRefusal.PLAN_INVALID
        },
      )
    }
  }

  /** La riga del telefono della versione attuale (dal catalogo locale, mai dal NAS). */
  private suspend fun itemOf(c: MediaCatalog, id: MediaId): AlbumUploadItem? {
    val m = c.media(id) ?: return null
    val version = m.currentVersion
    val place = m.locations.firstOrNull { it.versionId == version.id && it.location is Location.Phone && it.state == LocationState.PRESENT } ?: return null
    val phone = place.location as Location.Phone
    val msId = phone.mediaStoreId ?: return null
    val msVersion = phone.mediaStoreVersion ?: return null
    if (version.fingerprint.size <= 0) return null
    return AlbumUploadItem(m.id, version.id, version.fingerprint.size, PhoneRef(phone.volume, msId, msVersion), place.stamp, version.fingerprint.quickHash, phone.displayName)
  }

  suspend fun find(key: TransferKey): TransferTicket? = uploads()?.find(key.value)?.let { ticketOf(key) }

  suspend fun status(ticket: TransferTicket): TicketState = when (val s = uploads()?.status(ticket.value.removePrefix(UP)) ?: return TicketState.Unknown) {
    is AlbumUploadStatus.InProgress -> TicketState.InProgress(s.done, s.total)
    is AlbumUploadStatus.Waiting -> TicketState.Waiting(ShareWait.WRITES_BLOCKED, s.done, s.total)
    is AlbumUploadStatus.Ended -> TicketState.Ended(
      s.outcomes.map {
        when (it) {
          AlbumUploadOutcome.ARRIVED -> TransferItemOutcome.ARRIVED
          AlbumUploadOutcome.ALREADY_THERE -> TransferItemOutcome.ALREADY_THERE
          AlbumUploadOutcome.SOURCE_CHANGED -> TransferItemOutcome.SOURCE_CHANGED
          AlbumUploadOutcome.SOURCE_GONE -> TransferItemOutcome.SOURCE_GONE
          AlbumUploadOutcome.FAILED -> TransferItemOutcome.FAILED
        }
      },
    )
    AlbumUploadStatus.Unknown -> TicketState.Unknown
  }

  internal companion object {
    const val UP = "up:"

    fun ticketOf(key: TransferKey): TransferTicket = TransferTicket(UP + key.value)
  }
}

/** I biglietti dei due trasferimenti insieme: spostamenti di core-nasops (`op:`) e caricamenti dal telefono (`up:`, che non si annullano a meta': L75). */
class AlbumTickets(private val mover: OpsAlbumMover, private val uploader: FolderAlbumUploader) : TicketStatus {
  override suspend fun status(ticket: TransferTicket): TicketState =
    if (ticket.value.startsWith(FolderAlbumUploader.UP)) uploader.status(ticket) else mover.status(ticket)

  override suspend fun find(key: TransferKey): TransferTicket? = mover.find(key) ?: uploader.find(key)

  override suspend fun cancel(ticket: TransferTicket) {
    if (!ticket.value.startsWith(FolderAlbumUploader.UP)) mover.cancel(ticket)
  }
}
