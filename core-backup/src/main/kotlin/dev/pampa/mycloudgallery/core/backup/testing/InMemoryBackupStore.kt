package dev.pampa.mycloudgallery.core.backup.testing

import dev.pampa.mycloudgallery.core.backup.contract.BackupDiaryCounts
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * Il diario del backup in memoria (Fase 5A, P0): **fa le stesse verifiche** dell'adattatore Room di P2. Rifiuta ogni transizione fuori
 * da [UploadStateMachine], un elemento sconosciuto, un cambio d'identita' (tipo, media, versione, share, destinazione prevista) e i tipi
 * della 5B; e' idempotente per versione e tipo (nessun secondo elemento aperto). Sopravvive alla "morte del processo": e' il disco.
 * Tiene il registro delle transizioni ([transitions]) per il controllo del diario del banco (§5.4, invariante 8).
 *
 * D-13: come la colonna `updated_at` di Room, ricorda l'ora del NAS di [nasNow] all'ultimo passo di ogni elemento ([lastStepAt]); il temporaneo
 * cambia solo per passare a un tentativo dopo dello stesso elemento, e i tentativi di prima restano citati ([citedTemps]).
 */
class InMemoryBackupStore(private val nasNow: () -> Long? = { null }) : BackupStore {

  /** Una transizione scritta: (elemento, da, a). */
  data class Transition(val item: BackupItemId, val from: UploadState, val to: UploadState)

  private val lock = Any()
  private val items = LinkedHashMap<Long, BackupItem>()
  private var nextId = 1L
  private val log = ArrayList<Transition>()
  private val stepAt = HashMap<Long, Long>()

  /** Tutte le transizioni scritte, in ordine (anche le riscritture dello stesso stato). */
  val transitions: List<Transition> get() = synchronized(lock) { log.toList() }

  /** Tutti gli elementi, terminali compresi, per id. */
  fun all(): List<BackupItem> = synchronized(lock) { items.values.toList() }

  override suspend fun create(items: List<NewBackupItem>): List<BackupItem> = synchronized(lock) {
    items.map { new ->
      require(!new.kind.phase5B) { "Un elemento della 5B (${new.kind.code}) non si crea nella 5A." }
      openForLocked(new.versionId)?.takeIf { it.kind == new.kind } ?: run {
        val item = BackupItem(
          id = BackupItemId(nextId++),
          kind = new.kind,
          state = UploadState.PLANNED,
          mediaId = new.mediaId,
          versionId = new.versionId,
          size = new.size,
          phone = new.phone,
          expected = new.expected,
          share = new.share,
          planned = new.planned,
          quickHash = new.quickHash,
          sha256 = new.sha256,
          guardSource = new.guardSource,
        )
        this.items[item.id.value] = item
        nasNow()?.let { stepAt[item.id.value] = it }
        log += Transition(item.id, UploadState.PLANNED, UploadState.PLANNED)
        item
      }
    }
  }

  override suspend fun createIncoming(item: NewBackupItem): BackupItem = synchronized(lock) {
    // Un file in arrivo da un'altra app (la copia locale, con il suo SHA-256) o, Fase 7 (E-SH1), un media del telefono verso la cartella di un album
    // condiviso (la riga del telefono e il suo timbro: l'impronta la legge il motore, come per una copia nuova).
    require(item.kind == UploadKind.INCOMING && ((item.phone == null && item.sha256 != null) || (item.phone != null && item.expected != null))) {
      "Un file in arrivo con il suo SHA-256, o un media del telefono con la sua riga."
    }
    // R14: lo stesso arrivo e' quello della stessa versione nella stessa share e nella stessa cartella (come `RoomBackupStore`).
    items.values.firstOrNull { it.versionId == item.versionId && !it.state.terminal && it.kind == UploadKind.INCOMING && it.sameDestinationAs(item) } ?: run {
      val created = BackupItem(
        id = BackupItemId(nextId++),
        kind = UploadKind.INCOMING,
        state = UploadState.PLANNED,
        mediaId = item.mediaId,
        versionId = item.versionId,
        size = item.size,
        phone = item.phone,
        expected = item.expected,
        share = item.share,
        planned = item.planned,
        sha256 = item.sha256,
        quickHash = item.quickHash,
      )
      items[created.id.value] = created
      nasNow()?.let { stepAt[created.id.value] = it }
      log += Transition(created.id, UploadState.PLANNED, UploadState.PLANNED)
      created
    }
  }

  override suspend fun item(id: BackupItemId): BackupItem? = synchronized(lock) { items[id.value] }

  override suspend fun itemsToResume(limit: Int): List<BackupItem> = synchronized(lock) {
    items.values.filter { it.state in UploadStateMachine.resumable }.take(limit)
  }

  override suspend fun update(item: BackupItem) {
    synchronized(lock) {
      val before = items[item.id.value] ?: throw IllegalArgumentException("Elemento del diario sconosciuto.")
      require(
        before.kind == item.kind && before.mediaId == item.mediaId && before.versionId == item.versionId &&
          before.share == item.share && before.planned == item.planned && before.size == item.size,
      ) { "L'identita' di un elemento del diario non cambia." }
      check(UploadStateMachine.canTransition(before.flow, before.state, item.state)) {
        "Transizione non ammessa: ${before.state.code} -> ${item.state.code} (${before.flow.code})."
      }
      // D-13: come Room, il temporaneo cambia solo per il tentativo dopo dello stesso elemento.
      val oldTemp = before.temp
      require(oldTemp == null || oldTemp == item.temp || (item.temp != null && oldTemp in NasBackupPaths.earlierAttempts(item.temp))) {
        "Il temporaneo di un elemento non cambia una volta scritto."
      }
      items[item.id.value] = item
      nasNow()?.let { stepAt[item.id.value] = it }
      log += Transition(item.id, before.state, item.state)
    }
  }

  override suspend fun openFor(versionId: VersionId): BackupItem? = synchronized(lock) { openForLocked(versionId) }

  // R15: gli arrivi non contano (come `RoomBackupStore.openFor`): un file in arrivo per un'altra share non blocca il backup della versione.
  private fun openForLocked(versionId: VersionId): BackupItem? =
    items.values.firstOrNull { it.versionId == versionId && !it.state.terminal && it.kind != UploadKind.INCOMING }

  private fun BackupItem.sameDestinationAs(other: NewBackupItem): Boolean =
    share.equals(other.share, ignoreCase = true) && planned.components.dropLast(1).map { it.lowercase() } == other.planned.components.dropLast(1).map { it.lowercase() }

  override suspend fun citedTemps(): Set<SharePath> = synchronized(lock) {
    items.values.filter { !it.state.terminal }.mapNotNull { it.temp }.flatMapTo(LinkedHashSet()) { listOf(it) + NasBackupPaths.earlierAttempts(it) }
  }

  override suspend fun lastStepAt(id: BackupItemId): Long? = synchronized(lock) { stepAt[id.value] }

  override suspend fun counts(): BackupDiaryCounts = synchronized(lock) {
    BackupDiaryCounts(
      byState = items.values.groupingBy { it.state }.eachCount(),
      bytesOpen = items.values.filter { !it.state.terminal }.sumOf { it.size },
    )
  }
}
