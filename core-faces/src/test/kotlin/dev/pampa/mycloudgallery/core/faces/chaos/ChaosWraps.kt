package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocalStore
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.erase.EraseJournal
import dev.pampa.mycloudgallery.core.faces.erase.EraseJournalRead
import dev.pampa.mycloudgallery.core.faces.erase.EraseJournalStore
import dev.pampa.mycloudgallery.core.faces.store.FacesUnitsDone
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublishState
import dev.pampa.mycloudgallery.core.faces.sync.FacesPublishStore
import dev.pampa.mycloudgallery.core.faces.sync.FacesReplicaState
import dev.pampa.mycloudgallery.core.faces.sync.FacesReplicaStore
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import kotlinx.coroutines.flow.StateFlow

// I SALVATAGGI DURATURI CON LA MORTE DEL PROCESSO (docs/fase8-progetto.md §5.5: "morte del processo a ogni salvataggio: database, outbox,
// istantanea, diario della cancellazione, registro dei dati personali"). Ogni scrittura passa dall'iniettore: la morte cade PRIMA che la
// scrittura sia arrivata sul disco o DOPO (scritta, ma il processo non lo sa). Le letture controllano solo che il processo sia vivo: i vecchi
// oggetti di un processo morto non toccano piu' niente. Il contenuto dei salvataggi non cambia mai: sono decoratori.

/** L'archivio dei volti di una generazione di un dispositivo. [onBody]: ogni corpo scritto davvero (dopo il salvataggio riuscito, anche se la risposta e' persa). */
internal class ChaosArchive(
  private val inner: FacesArchive,
  private val inj: ChaosInjector,
  private val device: Int,
  private val onBody: (FaceGeneration, FaceBody) -> Unit = { _, _ -> },
) : FacesArchive {
  override val generation: FaceGeneration get() = inner.generation

  override suspend fun saveBody(body: FaceBody) = inj.aroundSuspending(device, Surface.DB_BODY) {
    inner.saveBody(body)
    onBody(inner.generation, body)
  }

  override suspend fun winners(keys: Collection<ContentKey>): Map<ContentKey, FaceBody> = alive { inner.winners(keys) }

  override suspend fun bodiesOf(author: String, keys: Collection<ContentKey>): Map<ContentKey, FaceBody> = alive { inner.bodiesOf(author, keys) }

  override suspend fun analyzed(keys: Collection<ContentKey>, recipe: Int): Set<ContentKey> = alive { inner.analyzed(keys, recipe) }

  override suspend fun markUnsupported(key: ContentKey, recipe: Int) = inj.around(device, Surface.DB_BODY) { inner.markUnsupported(key, recipe) }

  override suspend fun groupFaces(): List<GroupFace> = alive { inner.groupFaces() }

  override suspend fun unrecognizable(keys: Collection<ContentKey>): Map<ContentKey, Int> = alive { inner.unrecognizable(keys) }

  override suspend fun grouping(): GroupingState = alive { inner.grouping() }

  override suspend fun saveGrouping(state: GroupingState) = inj.around(device, Surface.DB_GROUPING) { inner.saveGrouping(state) }

  override suspend fun people(): Map<PeopleKey, PeopleRecord> = alive { inner.people() }

  override suspend fun addPeople(records: List<PeopleRecord>) = inj.around(device, Surface.DB_PEOPLE) { inner.addPeople(records) }

  override suspend fun addPeopleFrom(via: String, records: List<PeopleRecord>, own: String?): Int =
    inj.around(device, Surface.DB_PEOPLE) { inner.addPeopleFrom(via, records, own) }

  override suspend fun directPeople(): Map<PeopleKey, PeopleRecord> = alive { inner.directPeople() }

  override suspend fun bodyTotals(author: String?, keys: Collection<ContentKey>?): dev.pampa.mycloudgallery.core.faces.contract.FacesTotals =
    alive { inner.bodyTotals(author, keys) }

  override suspend fun datePeople(nasNow: Long): Int = inj.around(device, Surface.DB_PEOPLE) { inner.datePeople(nasNow) }

  override suspend fun dropAuthors(authors: Set<String>): Int = inj.around(device, Surface.DB_PEOPLE) { inner.dropAuthors(authors) }

  private inline fun <T> alive(block: () -> T): T {
    inj.checkAlive(device)
    return block()
  }

  override fun toString(): String = "ChaosArchive"
}

/** Le unita' di calcolo finite (su disco). */
internal class ChaosUnits(private val inner: FacesUnitsDone, private val inj: ChaosInjector, private val device: Int) : FacesUnitsDone {
  override suspend fun markDone(unit: FacesResource.WorkUnit, recipe: Int, doneAtNas: Long?) =
    inj.around(device, Surface.DB_UNITS) { inner.markDone(unit, recipe, doneAtNas) }

  override suspend fun isDone(unit: FacesResource.WorkUnit, recipe: Int): Boolean {
    inj.checkAlive(device)
    return inner.isDone(unit, recipe)
  }

  override suspend fun done(recipe: Int): Set<FacesResource.WorkUnit> {
    inj.checkAlive(device)
    return inner.done(recipe)
  }
}

/** L'outbox della pubblicazione. */
internal class ChaosPublishStore(private val inner: FacesPublishStore, private val inj: ChaosInjector, private val device: Int) : FacesPublishStore {
  override suspend fun load(generation: FaceGeneration): FacesPublishState {
    inj.checkAlive(device)
    return inner.load(generation)
  }

  override suspend fun save(generation: FaceGeneration, state: FacesPublishState) = inj.around(device, Surface.OUTBOX) { inner.save(generation, state) }
}

/** Lo stato della replica. */
internal class ChaosReplicaStore(private val inner: FacesReplicaStore, private val inj: ChaosInjector, private val device: Int) : FacesReplicaStore {
  override suspend fun load(generation: FaceGeneration): FacesReplicaState {
    inj.checkAlive(device)
    return inner.load(generation)
  }

  override suspend fun save(generation: FaceGeneration, state: FacesReplicaState) = inj.around(device, Surface.REPLICA) { inner.save(generation, state) }
}

/** Il diario di "Disattiva e cancella": la morte prima o dopo la scrittura (la cancellazione dei dati del diario non conta: e' la pulizia di 30 giorni). */
internal class ChaosJournal(private val inner: EraseJournalStore, private val inj: ChaosInjector, private val device: Int) : EraseJournalStore {
  override fun load(): EraseJournalRead {
    inj.checkAlive(device)
    return inner.load()
  }

  override fun save(journal: EraseJournal) = inj.around(device, Surface.JOURNAL) { inner.save(journal) }

  override fun clear(): Boolean {
    inj.checkAlive(device)
    return inner.clear()
  }
}

/** Lo stato locale `faces-local`. */
internal class ChaosLocalStore(private val inner: FacesLocalStore, private val inj: ChaosInjector, private val device: Int) : FacesLocalStore {
  override val local: StateFlow<FacesLocal> get() = inner.local

  override suspend fun update(transform: (FacesLocal) -> FacesLocal): FacesLocal = inj.around(device, Surface.LOCAL) { inner.update(transform) }
}

/** Il pubblicatore visto dal calcolo, con la cronaca del giro (solo se accesa): ogni messa in coda e ogni pubblicazione, con lo stato di Persone di quel momento. */
internal class ChaosTracingPublisher(private val inner: dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher, private val device: ChaosDevice) :
  dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher {
  override val pending get() = inner.pending

  override suspend fun stage(change: dev.pampa.mycloudgallery.core.faces.contract.FacesChange) {
    val before = device.switch.currentState().javaClass.simpleName
    inner.stage(change)
    if (change is dev.pampa.mycloudgallery.core.faces.contract.FacesChange.Faces) device.world.note("${device.name} accoda ${change.contentKey.hex.takeLast(4)} (stato $before)")
  }

  override suspend fun flush(reason: dev.pampa.mycloudgallery.core.faces.contract.FlushReason): dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome =
    inner.flush(reason).also { device.world.note("${device.name} pubblica (${reason.code}): $it") }
}
