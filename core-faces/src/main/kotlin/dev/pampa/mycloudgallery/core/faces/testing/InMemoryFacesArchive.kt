package dev.pampa.mycloudgallery.core.faces.testing

import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.Float16

/**
 * L'archivio dei volti in memoria (P0, il "fake di P6" per P3, P4, P5, P8, P9, P12): le stesse regole del contratto [FacesArchive] senza
 * database e senza cifratura (la cifratura e la prova "nessun vettore o nome in chiaro nel file" sono di P6). [close] simula la chiave
 * distrutta o la generazione cambiata: da quel momento ogni chiamata lancia `IllegalStateException`. [saves] conta i salvataggi (il banco di
 * P12 controlla che un media salvato non si rifaccia dopo una morte del processo).
 */
class InMemoryFacesArchive(override val generation: FaceGeneration) : FacesArchive {
  private val bodies = HashMap<ContentKey, MutableMap<String, FaceBody>>()
  private val unsupported = HashMap<ContentKey, Int>()
  private var state: GroupingState = GroupingState.EMPTY
  private var records: Map<PeopleKey, PeopleRecord> = emptyMap()

  @Volatile var closed: Boolean = false
    private set

  @Volatile var saves: Int = 0
    private set

  /** La chiave e' distrutta (o la generazione e' cambiata): l'archivio non si usa piu'. */
  fun close() {
    closed = true
  }

  private fun open() = check(!closed) { "Archivio dei volti chiuso." }

  override suspend fun saveBody(body: FaceBody): Unit = synchronized(this) {
    open()
    bodies.getOrPut(body.contentKey) { HashMap() }[body.author] = body
    saves++
  }

  override suspend fun winners(keys: Collection<ContentKey>): Map<ContentKey, FaceBody> = synchronized(this) {
    open()
    keys.mapNotNull { key -> bodies[key]?.values?.reduce(FaceBody::winner)?.let { key to it } }.toMap()
  }

  override suspend fun analyzed(keys: Collection<ContentKey>, recipe: Int): Set<ContentKey> = synchronized(this) {
    open()
    keys.filterTo(LinkedHashSet()) { key ->
      bodies[key].orEmpty().values.any { it.recipe >= recipe } || (unsupported[key] ?: 0) >= recipe
    }
  }

  override suspend fun markUnsupported(key: ContentKey, recipe: Int): Unit = synchronized(this) {
    open()
    unsupported[key] = maxOf(unsupported[key] ?: 0, recipe)
  }

  override suspend fun groupFaces(): List<GroupFace> = synchronized(this) {
    open()
    bodies.keys.sortedBy { it.hex }.flatMap { key ->
      val body = bodies.getValue(key).values.reduce(FaceBody::winner)
      if (body.space != FaceSpaceCode.CURRENT) return@flatMap emptyList()
      body.faces.filter { it.tier != FaceTier.NONE }.map { entry ->
        GroupFace(body.refOf(entry.index), FaceLocator(key, entry.frame, entry.box), entry.tier, Float16.decode(entry.vector!!))
      }
    }
  }

  override suspend fun unrecognizable(keys: Collection<ContentKey>): Map<ContentKey, Int> = synchronized(this) {
    open()
    keys.mapNotNull { key ->
      val body = bodies[key]?.values?.reduce(FaceBody::winner) ?: return@mapNotNull null
      val none = body.faces.count { it.tier == FaceTier.NONE }
      if (none == 0) null else key to none
    }.toMap()
  }

  override suspend fun grouping(): GroupingState = synchronized(this) {
    open()
    state
  }

  override suspend fun saveGrouping(state: GroupingState): Unit = synchronized(this) {
    open()
    this.state = state
  }

  override suspend fun people(): Map<PeopleKey, PeopleRecord> = synchronized(this) {
    open()
    records
  }

  override suspend fun addPeople(records: List<PeopleRecord>): Unit = synchronized(this) {
    open()
    this.records = PeopleRecord.merge(this.records, records)
  }

  override fun toString(): String = "InMemoryFacesArchive(${bodies.size} contenuti, chiuso=$closed)"
}
