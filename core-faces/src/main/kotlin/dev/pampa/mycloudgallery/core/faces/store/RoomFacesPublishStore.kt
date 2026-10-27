package dev.pampa.mycloudgallery.core.faces.store

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.faces.dao.ChunkRef
import dev.pampa.mycloudgallery.core.database.faces.dao.FacesSyncDao
import dev.pampa.mycloudgallery.core.database.faces.dao.PublishWrite
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesChunkEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.PublishDiaryEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.PublishStateEntity
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublication
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublishState
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublishStore
import dev.pampa.mycloudgallery.core.faces.sync.StagedSnapshot
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.security.faces.FacesDataKey
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.SnapshotMark
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Il pubblicatore dei volti sul database (Fase 8, P6; `docs/fase8-progetto.md` D14, D15, D21, §2.4): `FacesPublishStore` di P3 con lo stato che
 * **sopravvive alla morte del processo** e che e' l'outbox: le chiavi dei corpi calcolati qui, l'istantanea che il custode ha chiesto di
 * pubblicare, l'ultima testa pubblicata, le modifiche in coda, il diario dei segmenti pubblicati da qui (la prova "e' mio" della pulizia dei
 * propri file, M6) e i due segni ([FacesPublishState.adopted], [FacesPublishState.uncertain]). Senza, una morte del processo fra i segmenti e la
 * testa lascerebbe segmenti che nessuno riconosce per suoi e un contributo mai pubblicato.
 *
 * Come la replica ([RoomFacesReplicaStore]): per la generazione del negozio; **cifrato** (le voci dell'istantanea in coda, con i centroidi,
 * stanno solo in `faces_chunk` sigillate); ogni [save] una transazione con la sola differenza rispetto all'ultimo stato scritto, che si tiene in
 * memoria; chiuso = `IllegalStateException`. Il diario tiene al piu' [FacesPublishState.MAX_DIARY] impronte (le piu' recenti), in ordine.
 */
internal class RoomFacesPublishStore(
  private val generation: FaceGeneration,
  private val dao: FacesSyncDao,
  private val key: FacesDataKey,
) : FacesPublishStore {

  private val lock = Mutex()

  /** L'ultimo stato letto o scritto: cio' che il database contiene. */
  private var mirror: FacesPublishState? = null

  /** L'ordine piu' alto del diario scritto: i nuovi arrivi proseguono da li'. */
  private var maxOrd = 0L

  /** La riga degli scalari c'e' gia' nel database (la prima volta si scrive sempre, anche se uguale a quella di "niente"). */
  private var rowExists = false

  @Volatile private var closed = false

  fun retire() {
    closed = true
    mirror = null
  }

  override suspend fun load(generation: FaceGeneration): FacesPublishState {
    check(!closed) { "Pubblicatore dei volti chiuso." }
    if (generation != this.generation) return FacesPublishState.EMPTY
    return lock.withLock { current() }
  }

  override suspend fun save(generation: FaceGeneration, state: FacesPublishState) {
    check(!closed) { "Pubblicatore dei volti chiuso." }
    check(generation == this.generation) { "Lo stato di un'altra generazione non si salva in questo database." }
    lock.withLock {
      val old = current()
      if (old === state) return
      val (write, newMax) = diff(old, state)
      dao.writePublish(write)
      mirror = state
      maxOrd = newMax
      if (write.state != null) rowExists = true
    }
  }

  private suspend fun current(): FacesPublishState = mirror ?: read().also { mirror = it }

  // --- Lettura ---------------------------------------------------------------------------------------------------------------------

  private suspend fun read(): FacesPublishState {
    val row = dao.publishState()
    rowExists = row != null
    val keys = dao.publishKeys().mapNotNullTo(LinkedHashSet()) { runCatching { ContentKey(it) }.getOrNull() }
    val segments = dao.publishSegments().toCollection(LinkedHashSet())
    val diaryRows = dao.publishDiary()
    maxOrd = diaryRows.maxOfOrNull { it.ord } ?: 0L
    val diary = diaryRows.mapTo(LinkedHashSet()) { it.hash }
    var snapshot: StagedSnapshot? = null
    if (row?.snapSeq != null && row.snapBase != null) {
      val mark = SnapshotMark(row.snapSeq!!, row.snapBase!!)
      val chunkKey = ClusterChunks.Key(ClusterChunks.PUBLISH_SNAPSHOT, OWNER, SNAPSHOT_NAME)
      val entries = ClusterChunks.decode(chunkKey, dao.chunks(ClusterChunks.PUBLISH_SNAPSHOT), key)
      if (entries == null) {
        // Un'istantanea che non si legge si lascia andare: il custode ne chiede un'altra al prossimo salvataggio (la `seq` non basta a tenerla).
        GalleryLog.w(TAG) { "l'istantanea in coda non si legge: si lascia" }
      } else {
        snapshot = StagedSnapshot(mark, entries)
      }
    }
    val publication = if (row?.pubVersion != null && row.pubMarkVersion != null && row.pubMarkHash != null && row.pubAtNas != null) {
      FacesPublication(
        version = row.pubVersion!!,
        mark = HeadMark(row.pubMarkVersion!!, row.pubMarkHash!!),
        publishedAtNas = row.pubAtNas!!,
        segments = segments,
        snapshot = if (row.pubSnapSeq != null && row.pubSnapBase != null) SnapshotMark(row.pubSnapSeq!!, row.pubSnapBase!!) else null,
        entries = row.pubEntries ?: 0,
      )
    } else {
      null
    }
    return FacesPublishState(
      keys = keys,
      snapshot = snapshot,
      publication = publication,
      pending = row?.pending ?: 0,
      diary = diary,
      adopted = row?.adopted ?: false,
      uncertain = row?.uncertain ?: false,
    )
  }

  // --- Scrittura: la differenza -------------------------------------------------------------------------------------------------------

  private fun diff(old: FacesPublishState, new: FacesPublishState): Pair<PublishWrite, Long> {
    var next = maxOrd
    val addedKeys = ArrayList<String>()
    val removedKeys = ArrayList<String>()
    if (new.keys !== old.keys) {
      for (k in new.keys) if (k !in old.keys) addedKeys += k.hex
      for (k in old.keys) if (k !in new.keys) removedKeys += k.hex
    }
    val addedDiary = ArrayList<PublishDiaryEntity>()
    val removedDiary = ArrayList<String>()
    if (new.diary !== old.diary) {
      for (hash in new.diary) if (hash !in old.diary) addedDiary += PublishDiaryEntity(hash, ++next)
      for (hash in old.diary) if (hash !in new.diary) removedDiary += hash
    }
    val rewriteSegments = old.publication?.segments != new.publication?.segments
    val removedChunks = ArrayList<ChunkRef>()
    val chunks = ArrayList<FacesChunkEntity>()
    if (!sameSnapshot(old.snapshot, new.snapshot)) {
      removedChunks += ChunkRef(ClusterChunks.PUBLISH_SNAPSHOT, OWNER, SNAPSHOT_NAME)
      new.snapshot?.let { chunks += ClusterChunks.encode(ClusterChunks.Key(ClusterChunks.PUBLISH_SNAPSHOT, OWNER, SNAPSHOT_NAME), it.entries, key) }
    }
    val entity = entityOf(new)
    val state = if (!rowExists || !sameScalars(entityOf(old), entity)) entity else null
    val write = PublishWrite(
      state = state,
      addedKeys = addedKeys,
      removedKeys = removedKeys,
      rewriteSegments = rewriteSegments,
      segments = new.publication?.segments?.toList().orEmpty(),
      addedDiary = addedDiary,
      removedDiary = removedDiary,
      removedChunks = removedChunks,
      chunks = chunks,
    )
    return write to next
  }

  private fun entityOf(state: FacesPublishState): PublishStateEntity {
    val pub = state.publication
    return PublishStateEntity(
      snapSeq = state.snapshot?.mark?.seq,
      snapBase = state.snapshot?.mark?.base,
      pubVersion = pub?.version,
      pubMarkVersion = pub?.mark?.version,
      pubMarkHash = pub?.mark?.contentHash,
      pubAtNas = pub?.publishedAtNas,
      pubSnapSeq = pub?.snapshot?.seq,
      pubSnapBase = pub?.snapshot?.base,
      pubEntries = pub?.entries,
      pending = state.pending,
      adopted = state.adopted,
      uncertain = state.uncertain,
    )
  }

  private fun sameScalars(a: PublishStateEntity, b: PublishStateEntity): Boolean =
    a.snapSeq == b.snapSeq && a.snapBase == b.snapBase && a.pubVersion == b.pubVersion && a.pubMarkVersion == b.pubMarkVersion &&
      a.pubMarkHash == b.pubMarkHash && a.pubAtNas == b.pubAtNas && a.pubSnapSeq == b.pubSnapSeq && a.pubSnapBase == b.pubSnapBase &&
      a.pubEntries == b.pubEntries && a.pending == b.pending && a.adopted == b.adopted && a.uncertain == b.uncertain

  /** Un'istantanea in coda con la stessa `seq` e `base` e lo stesso numero di voci e' la stessa (il pubblicatore non ne rimpiazza una con la stessa `seq`). */
  private fun sameSnapshot(a: StagedSnapshot?, b: StagedSnapshot?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    return a.mark == b.mark && a.entries.size == b.entries.size
  }

  override fun toString(): String = "RoomFacesPublishStore"

  private companion object {
    const val TAG = "FacesPublish"
    const val OWNER = ""
    const val SNAPSHOT_NAME = "0"
  }
}
