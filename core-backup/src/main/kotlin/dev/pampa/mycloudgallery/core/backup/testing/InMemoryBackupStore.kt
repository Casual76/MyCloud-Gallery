package dev.pampa.mycloudgallery.core.backup.testing

import dev.pampa.mycloudgallery.core.backup.contract.BackupDiaryCounts
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * Il diario del backup in memoria (Fase 5A, P0): **fa le stesse verifiche** dell'adattatore Room di P2. Rifiuta ogni transizione fuori
 * da [UploadStateMachine], un elemento sconosciuto, un cambio d'identita' (tipo, media, versione, share, destinazione prevista) e i tipi
 * della 5B; e' idempotente per versione e tipo (nessun secondo elemento aperto). Sopravvive alla "morte del processo": e' il disco.
 * Tiene il registro delle transizioni ([transitions]) per il controllo del diario del banco (§5.4, invariante 8).
 */
class InMemoryBackupStore : BackupStore {

  /** Una transizione scritta: (elemento, da, a). */
  data class Transition(val item: BackupItemId, val from: UploadState, val to: UploadState)

  private val lock = Any()
  private val items = LinkedHashMap<Long, BackupItem>()
  private var nextId = 1L
  private val log = ArrayList<Transition>()

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
        log += Transition(item.id, UploadState.PLANNED, UploadState.PLANNED)
        item
      }
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
      items[item.id.value] = item
      log += Transition(item.id, before.state, item.state)
    }
  }

  override suspend fun openFor(versionId: VersionId): BackupItem? = synchronized(lock) { openForLocked(versionId) }

  private fun openForLocked(versionId: VersionId): BackupItem? =
    items.values.firstOrNull { it.versionId == versionId && !it.state.terminal }

  override suspend fun citedTemps(): Set<SharePath> = synchronized(lock) {
    items.values.filter { !it.state.terminal }.mapNotNullTo(LinkedHashSet()) { it.temp }
  }

  override suspend fun counts(): BackupDiaryCounts = synchronized(lock) {
    BackupDiaryCounts(
      byState = items.values.groupingBy { it.state }.eachCount(),
      bytesOpen = items.values.filter { !it.state.terminal }.sumOf { it.size },
    )
  }
}
