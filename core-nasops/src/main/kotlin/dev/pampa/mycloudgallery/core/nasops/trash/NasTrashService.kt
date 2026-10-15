package dev.pampa.mycloudgallery.core.nasops.trash

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nasops.contract.LocalTrashRecord
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrash
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpsGate
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeReport
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.nasops.contract.PurgedTally
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.nasops.engine.CatalogPresence
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsLock
import dev.pampa.mycloudgallery.core.nasops.engine.ShareAccessView
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.KeepReason
import dev.pampa.mycloudgallery.core.trust.data.LocalTrashEntry
import dev.pampa.mycloudgallery.core.trust.data.PurgeDecision
import dev.pampa.mycloudgallery.core.trust.data.PurgeRound
import dev.pampa.mycloudgallery.core.trust.data.TrashItemInfo
import dev.pampa.mycloudgallery.core.trust.data.TrashPurgePolicy
import dev.pampa.mycloudgallery.core.trust.data.TrashRecordReason
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import java.util.Locale
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock

/**
 * Il cestino del NAS dell'app (D25, formato §5.7, §9 punto 3; E-D2, E-D4, E-S11, F20).
 *
 * **"Eliminati di recente"** ([refresh], solo lettura): le voci del **diario locale** il cui file c'e' ancora nel proprio cestino, piu' gli
 * elementi dei cestini degli altri dispositivi con il loro record firmato (o "senza record valido"). Le voci proprie si possono ripristinare
 * ed "eliminare subito" (Q8); quelle degli altri soltanto ripristinare.
 *
 * **La scadenza dei 30 giorni** ([purgeExpired]) ha come ingressi **solo** il diario locale, il file nel proprio cestino (percorso derivato
 * da `TrashPurgePolicy`, mai letto) e l'ora del NAS: un record remoto, vero o finto, un cestino finto o un cestino di un altro dispositivo
 * non fanno eliminare niente. Tetti di 200 file e 20 GiB per giro; ferma con congelamento, salto dell'ora (D32) ed emergenza; una voce
 * citata da un'operazione in corso (un ripristino) non si tocca; ogni eliminazione con un solo handle (`deleteIfUnchanged`).
 */
class NasTrashService internal constructor(
  private val deviceId: String,
  private val accountId: String,
  /** Le share dell'utente (area privata e comune). */
  private val shares: () -> List<String>,
  private val store: OpsStore,
  private val fileOps: NasFileOps,
  private val reader: NasMediaReader,
  private val clocks: OpClocks,
  private val gate: OpsGate,
  private val access: ShareAccessView = ShareAccessView.ALL_WRITABLE,
  private val remote: RemoteTrashSource = RemoteTrashSource.NONE,
  private val lock: NasOpsLock = NasOpsLock(),
  /** Il nome del dispositivo che ha cestinato, se il registro lo conosce (solo da mostrare). */
  private val authorName: (String) -> String? = { null },
  /** R3: cio' che il catalogo vede ancora presente fuori dal cestino (un collegamento non si attraversa). */
  private val presence: CatalogPresence = CatalogPresence.NONE,
  /**
   * Quanto resta una voce nel cestino: 30 giorni. **Solo la build debug** lo accorcia (prova sul campo F20, `DeveloperTools` di `app/src/debug`,
   * assente dal dex di release: tools/check.ps1); mai piu' lungo di 30 giorni ne' piu' corto di un minuto.
   */
  private val retentionMillis: Long = TrustLimits.TRASH_RETENTION_MILLIS,
) : NasTrash {

  init {
    require(TrustIds.isValid(deviceId)) { "Id di dispositivo non valido." }
    require(fileOps.ownerDeviceId == deviceId) { "Le primitive devono essere di questo dispositivo." }
    require(retentionMillis in MIN_RETENTION_MS..TrustLimits.TRASH_RETENTION_MILLIS) { "Durata del cestino fuori dai limiti." }
  }

  private val state = MutableStateFlow<NasTrashState>(NasTrashState.Loading)
  override val items: StateFlow<NasTrashState> = state

  private val policy = TrashPurgePolicy(deviceId)
  private val purger = OwnTrashPurger(deviceId, fileOps, reader, store, presence)

  /**
   * R1, R5 (revisione 4B): da quando contano i 30 giorni di una voce. Mai da prima che il file sia entrato nel cestino (`trashedAtNas`, che
   * l'esecutore sposta solo in avanti) e mai da prima della fine dell'ultimo salto dell'ora del NAS accettato (D32): dopo un salto il
   * cestino riparte con i suoi 30 giorni pieni.
   */
  private fun countFrom(entry: LocalTrashRecord): Long = maxOf(entry.trashedAtNas, clocks.clockTrustedSinceNasMs() ?: Long.MIN_VALUE)

  private suspend fun ownEntries(): List<LocalTrashRecord> =
    store.expiredTrash(Long.MAX_VALUE, Int.MAX_VALUE).filter { it.state == TrashEntryState.TRASHED && it.accountId == accountId }

  // --- "Eliminati di recente" ----------------------------------------------------------------------------------------------

  override suspend fun refresh() {
    val nasNow = clocks.nasNowMs()
    val own = ownEntries()
    val ownIds = own.mapTo(HashSet()) { it.trashId }
    val all = (shares() + own.map { it.shareName }).distinctBy { it.lowercase(Locale.ROOT) }
    val shown = ArrayList<NasTrashItem>()
    for (share in all) {
      // Le voci proprie: il file c'e' ancora nel proprio cestino (un elenco per share, non una lettura per voce).
      val present = HashSet<String>()
      val listed = reader.list(share, NasLayout.ROOT.child("trash").child(deviceId).child("files"), Purpose.INTERACTIVE) { page ->
        page.forEach { present += it.name.lowercase(Locale.ROOT) }
      }
      if (listed is NasResult.Err && listed.error != NasError.NotFound) {
        state.value = if (listed.error == NasError.PermissionDenied) NasTrashState.Denied else NasTrashState.Offline(own.map { ownItem(it, nasNow) })
        return
      }
      own.filter { it.shareName.equals(share, ignoreCase = true) && it.trashId.lowercase(Locale.ROOT) in present }.forEach { shown += ownItem(it, nasNow) }
      // Gli altri (e i propri senza voce nel diario, per esempio dopo un azzeramento: L31): solo da mostrare e ripristinare.
      when (val read = remote.read(share)) {
        RemoteTrashRead.Unreachable -> {
          state.value = NasTrashState.Offline(own.map { ownItem(it, nasNow) })
          return
        }
        is RemoteTrashRead.Entries -> for (entry in read.entries) {
          if (entry.ownerDeviceId == deviceId && entry.trashId in ownIds) continue
          shown += foreignItem(entry, nasNow)
        }
      }
    }
    state.value = NasTrashState.Ready(shown, nasNow, writable = all.any { !access.readOnly(it) })
  }

  private fun ownItem(entry: LocalTrashRecord, nasNow: Long?): NasTrashItem = NasTrashItem(
    share = entry.shareName,
    ownerDeviceId = deviceId,
    trashId = entry.trashId,
    name = entry.originalPath.name,
    originalFolder = entry.originalPath.parent(),
    originalPath = entry.originalPath,
    size = entry.size,
    daysLeft = daysLeft(countFrom(entry), nasNow),
    author = authorName(deviceId),
    reason = entry.reason,
    isFolder = entry.isFolder,
    fileCount = entry.fileCount,
    withoutValidRecord = false,
    restorable = !access.readOnly(entry.shareName),
    deletableNow = true,
  )

  private fun foreignItem(entry: RemoteTrashEntry, nasNow: Long?): NasTrashItem {
    val record = entry.record
    return NasTrashItem(
      share = entry.share,
      ownerDeviceId = entry.ownerDeviceId,
      trashId = entry.trashId,
      name = record?.originalPath?.name,
      originalFolder = record?.originalPath?.parent(),
      originalPath = record?.originalPath,
      size = record?.size ?: 0,
      daysLeft = record?.let { daysLeft(it.trashedAt, nasNow) },
      author = authorName(entry.ownerDeviceId),
      reason = record?.reason?.let { if (it == TrashRecordReason.MOVE) TrashReason.MOVE else TrashReason.DELETE },
      isFolder = entry.isFolder,
      fileCount = record?.count,
      withoutValidRecord = record == null,
      restorable = record != null && !access.readOnly(entry.share),
      // Le voci degli altri dispositivi (e le proprie senza diario) si ripristinano soltanto: la scadenza e "elimina subito" sono di
      // chi ha cestinato, dal suo diario (D25).
      deletableNow = false,
    )
  }

  private fun daysLeft(trashedAtNas: Long, nasNow: Long?): Int? {
    nasNow ?: return null
    val leftMs = trashedAtNas + retentionMillis - nasNow
    return if (leftMs <= 0) 0 else ((leftMs + DAY_MS - 1) / DAY_MS).toInt()
  }

  // --- Scadenza dei 30 giorni (E-D4) ---------------------------------------------------------------------------------------

  override suspend fun purgeExpired(budget: Duration): PurgeReport = lock.mutex.withLock { purgeLocked(budget) }

  private suspend fun purgeLocked(budget: Duration): PurgeReport {
    val nasNow = clocks.nasNowMs() ?: return PurgeReport(0, 0, emptyMap(), 0, 0, PurgeStop.NO_NAS_TIME)
    val deadline = clocks.monotonicMs() + budget.inWholeMilliseconds
    // R4: il tetto vale per GIORNO (24 ore di ora del NAS), non per chiamata: cio' che si e' gia' eliminato oggi conta. Raggiunto, la
    // pulizia si ferma fino al giorno dopo (ROUND_CAP: il lavoro giornaliero non ne chiede un'altra).
    val today = store.purgedSince(nasNow - DAY_MS + 1)
    if (today.files >= TrustLimits.MAX_PURGE_ITEMS_PER_ROUND || today.bytes >= TrustLimits.MAX_PURGE_BYTES_PER_ROUND) {
      return PurgeReport(0, 0, emptyMap(), 0, 0, PurgeStop.ROUND_CAP)
    }
    val round = PurgeRound()
    var purged = 0
    var bytes = 0L
    var files = 0
    var alreadyGone = 0
    var deferred = 0
    val kept = HashMap<KeepReason, Int>()
    var stop = PurgeStop.COMPLETE
    // Una voce citata da un'operazione non chiusa (un ripristino, "elimina subito", un "elimina" non ancora datato) resta a quell'operazione.
    val busy = activeTrashIds()
    val shortening = TrustLimits.TRASH_RETENTION_MILLIS - retentionMillis
    val limit = nasNow - retentionMillis + 1
    // Solo il diario locale: le voci proprie i cui 30 giorni (contati da countFrom) sono passati, in ora del NAS. Nessun record remoto.
    val candidates = store.expiredTrash(limit, Int.MAX_VALUE)
      .filter { countFrom(it) < limit }
      .sortedBy { countFrom(it) }
      .take(TrustLimits.MAX_PURGE_ITEMS_PER_ROUND + 1)
    for (entry in candidates) {
      if (clocks.monotonicMs() >= deadline) {
        stop = PurgeStop.BUDGET
        break
      }
      if (gate.statusOf(entry.shareName).blocksScheduled) {
        stop = PurgeStop.BLOCKED
        break
      }
      // La regola di core-trust vede la voce con l'ora da cui contano i suoi 30 giorni (R1, R5); in debug, spostata della durata accorciata.
      val local = LocalTrashEntry(entry.shareName, entry.trashId, entry.size, countFrom(entry) - shortening)
      // Il posto lo deriva la regola dall'id, e deve coincidere con il diario; l'account deve essere quello di adesso.
      val path = policy.itemPath(local)
      if (entry.accountId != accountId || entry.trashedPath != path || entry.trashId in busy) {
        kept.merge(KeepReason.NO_LOCAL_ACTION, 1, Int::plus)
        continue
      }
      val found = when (val r = reader.stat(entry.shareName, path, Purpose.BACKGROUND)) {
        is NasResult.Err -> if (r.error == NasError.NotFound) null else {
          stop = PurgeStop.NAS_UNREACHABLE
          break
        }
        is NasResult.Ok -> r.value
      }
      // Una cartella cestinata non ha una dimensione propria da confrontare: conta che sia ancora una cartella.
      val info = when {
        found == null -> null
        found.isDirectory != entry.isFolder || found.isReparsePoint -> {
          kept.merge(KeepReason.CHANGED, 1, Int::plus)
          continue
        }
        entry.isFolder -> TrashItemInfo(entry.size)
        else -> TrashItemInfo(found.size)
      }
      when (val decision = policy.decide(local, info, nasNow, round)) {
        is PurgeDecision.Purge -> {
          // R4: nel tetto del giorno contano i FILE (una cartella conta i suoi) e i byte; il primo del giorno passa sempre (B7).
          val entryFiles = if (entry.isFolder) entry.fileCount ?: PurgedTally.UNCOUNTED_FOLDER_FILES else 1
          val usedFiles = today.files + files
          val usedBytes = today.bytes + bytes
          val overDay = usedFiles + entryFiles > TrustLimits.MAX_PURGE_ITEMS_PER_ROUND ||
            usedBytes + entry.size > TrustLimits.MAX_PURGE_BYTES_PER_ROUND
          if (usedFiles > 0 && overDay) {
            deferred++
            stop = PurgeStop.ROUND_CAP
            break
          }
          when (val r = purger.purge(entry, Purpose.BACKGROUND)) {
            OwnTrashPurger.Result.Done -> {
              store.markTrash(entry.trashId, TrashEntryState.PURGED, nasNow)
              purged++
              files += entryFiles
              bytes += entry.size
            }
            OwnTrashPurger.Result.Locked -> deferred++
            OwnTrashPurger.Result.Changed -> kept.merge(KeepReason.CHANGED, 1, Int::plus)
            is OwnTrashPurger.Result.Error -> {
              stop = PurgeStop.NAS_UNREACHABLE
              break
            }
          }
        }
        is PurgeDecision.Keep -> kept.merge(decision.why, 1, Int::plus)
        PurgeDecision.AlreadyGone -> {
          store.markTrash(entry.trashId, TrashEntryState.PURGED, nasNow)
          alreadyGone++
        }
        PurgeDecision.Defer -> {
          // Il tetto del giro di core-trust: il resto al giro dopo, che trova il tetto del giorno (ROUND_CAP) se e' stato raggiunto.
          deferred++
          stop = PurgeStop.BUDGET
          break
        }
      }
    }
    if (purged > 0) GalleryLog.i(TAG) { "cestino: $purged voci scadute eliminate" }
    return PurgeReport(purged, bytes, kept, alreadyGone, deferred, stop)
  }

  private suspend fun activeTrashIds(): Set<String> {
    val out = HashSet<String>()
    for (op in store.ops(setOf(OpState.PLANNED, OpState.RUNNING, OpState.PAUSED))) {
      store.items(op.id).filter { !it.state.terminal }.mapNotNullTo(out) { it.trashId }
    }
    return out
  }

  private companion object {
    const val TAG = "NasTrash"
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** La durata piu' corta che la build debug puo' chiedere (F20: la pulizia si prova in minuti). */
    const val MIN_RETENTION_MS = 60_000L
  }
}
