package dev.pampa.mycloudgallery.core.faces.testing

import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchiveLimits
import dev.pampa.mycloudgallery.core.faces.contract.FacesTotals
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
class InMemoryFacesArchive(
  override val generation: FaceGeneration,
  /** R3: i tetti delle voci `people` che arrivano dagli altri (le prove li abbassano). */
  private val maxPeople: Int = FacesArchiveLimits.MAX_PEOPLE,
  private val maxPeoplePerSigner: Int = FacesArchiveLimits.MAX_PEOPLE_PER_SIGNER,
) : FacesArchive {
  private val bodies = HashMap<ContentKey, MutableMap<String, FaceBody>>()
  private val unsupported = HashMap<ContentKey, Int>()
  private var state: GroupingState = GroupingState.EMPTY
  private var records: Map<PeopleKey, PeopleRecord> = emptyMap()

  /** R3: la provenienza di ogni voce (chi ha firmato il segmento da cui e' arrivata; l'autore per le scelte fatte qui). */
  private var via: Map<PeopleKey, String> = emptyMap()

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

  override suspend fun bodiesOf(author: String, keys: Collection<ContentKey>): Map<ContentKey, FaceBody> = synchronized(this) {
    open()
    keys.mapNotNull { key -> bodies[key]?.get(author)?.let { key to it } }.toMap()
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
    merge(null, records)
  }

  override suspend fun addPeopleFrom(via: String, records: List<PeopleRecord>, own: String?): Int = synchronized(this) {
    open()
    merge(via, records, own)
  }

  /** Le stesse regole di `PeopleTables.addFrom`: chi vince porta la sua provenienza, l'autore la riprende per una voce uguale, tetti per [from]. */
  private fun merge(from: String?, incoming: List<PeopleRecord>, own: String? = null): Int {
    val out = HashMap(records)
    val origin = HashMap(via)
    var refused = 0
    for (record in incoming) {
      val provenance = from ?: record.author
      val current = out[record.key]
      if (current == null) {
        val fromCount = origin.values.count { it == provenance }
        if (from != null && (out.size >= maxPeople || fromCount >= maxPeoplePerSigner)) {
          refused++
          continue
        }
        out[record.key] = record
        origin[record.key] = provenance
        continue
      }
      // Una mia scelta non la sostituisce una copia a mio nome portata da un altro.
      if (own != null && provenance != own && record.author == own && current.author == own && origin[record.key] == own) continue
      val winner = PeopleRecord.winner(current, record)
      if (winner !== current) {
        out[record.key] = winner
        origin[record.key] = provenance
      } else if (current == record && provenance == record.author && origin[record.key] != record.author) {
        origin[record.key] = provenance
      }
    }
    records = out
    via = origin
    return refused
  }

  override suspend fun directPeople(): Map<PeopleKey, PeopleRecord> = synchronized(this) {
    open()
    records.filter { (key, record) -> (via[key] ?: record.author) == record.author }
  }

  /** Per le prove: la provenienza della voce [key] (null se non c'e'). */
  fun viaOf(key: PeopleKey): String? = synchronized(this) { via[key] }

  override suspend fun bodyTotals(author: String?, keys: Collection<ContentKey>?): FacesTotals = synchronized(this) {
    open()
    val wanted = keys?.toSet()
    val chosen = bodies.filterKeys { wanted == null || it in wanted }.values
      .flatMap { byAuthor -> if (author == null) byAuthor.values else listOfNotNull(byAuthor[author]) }
    FacesTotals(chosen.size, chosen.sumOf { it.faces.size.toLong() })
  }

  override suspend fun datePeople(nasNow: Long): Int = synchronized(this) {
    open()
    var dated = 0
    records = records.mapValues { (_, record) ->
      if (record.atNas != null) {
        record
      } else {
        dated++
        record.copy(atNas = nasNow)
      }
    }
    dated
  }

  override suspend fun dropAuthors(authors: Set<String>): Int = synchronized(this) {
    open()
    var dropped = 0
    for (byAuthor in bodies.values) {
      for (author in authors) if (byAuthor.remove(author) != null) dropped++
    }
    bodies.entries.removeAll { it.value.isEmpty() }
    val before = records.size
    records = records.filter { (key, record) -> record.author !in authors && (via[key] ?: record.author) !in authors }
    via = via.filterKeys { it in records }
    dropped + before - records.size
  }

  override fun toString(): String = "InMemoryFacesArchive(${bodies.size} contenuti, chiuso=$closed)"
}
