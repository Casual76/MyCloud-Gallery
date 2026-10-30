package dev.pampa.mycloudgallery.core.faces.erase

import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseOps
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocalStore
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseCheck
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseReason
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseRound
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.SearchHistoryPurge
import dev.pampa.mycloudgallery.core.faces.contract.SmartAlbumsPeople
import dev.pampa.mycloudgallery.core.faces.store.LocalErase
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.FacesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.HeadValue
import dev.pampa.mycloudgallery.core.personal.contract.PersonalHeadsView
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

/**
 * "FaultPlan a ogni chiamata" (§4.3) per "Disattiva e cancella": ogni porta della cancellazione passa da [tick]; la chiamata numero [failAt] fallisce
 * (un `IOException`, come un disco, un database o un NAS che non rispondono), **prima** dell'effetto o, con [after], **dopo** (fatto, ma chi chiama
 * non lo sa). [calls] conta le chiamate (un giro pulito da' quante sono).
 */
internal class PortFaults {
  var failAt = -1
  var after = false
  var calls = 0
    private set
  val fired = ArrayList<String>()

  suspend fun <T> tick(port: String, block: suspend () -> T): T {
    calls++
    val here = calls
    if (here == failAt && !after) {
      fired += port
      throw IOException("guasto prima: $port")
    }
    val result = block()
    if (here == failAt && after) {
      fired += port
      throw IOException("guasto dopo: $port")
    }
    return result
  }

  fun <T> tickSync(port: String, block: () -> T): T {
    calls++
    val here = calls
    if (here == failAt && !after) {
      fired += port
      throw IOException("guasto prima: $port")
    }
    val result = block()
    if (here == failAt && after) {
      fired += port
      throw IOException("guasto dopo: $port")
    }
    return result
  }
}

internal class FaultySettings(private val inner: FacesPersonalSettings, private val faults: PortFaults) : FacesPersonalSettings {
  override val setting: StateFlow<FacesSetting?> get() = inner.setting
  override val syncPending: StateFlow<Boolean> get() = inner.syncPending

  override fun current(): FacesSetting? = inner.current()

  override suspend fun set(setting: FacesSetting): Boolean = faults.tick("settings.set") { inner.set(setting) }
}

internal class FaultyLocalStore(private val inner: FacesLocalStore, private val faults: PortFaults) : FacesLocalStore {
  override val local: StateFlow<FacesLocal> get() = inner.local

  override suspend fun update(transform: (FacesLocal) -> FacesLocal): FacesLocal = faults.tick("local.update") { inner.update(transform) }
}

internal class FaultyLocalData(private val inner: FacesLocalData, private val faults: PortFaults) : FacesLocalData {
  override suspend fun generationsOnDisk(): List<FaceGeneration> = faults.tick("data.generations") { inner.generationsOnDisk() }

  override suspend fun hasData(generation: FaceGeneration): Boolean = faults.tick("data.has") { inner.hasData(generation) }

  override suspend fun people(generation: FaceGeneration): Map<PeopleKey, PeopleRecord>? = faults.tick("data.people") { inner.people(generation) }

  override suspend fun erase(generation: FaceGeneration): LocalErase = faults.tick("data.erase") { inner.erase(generation) }
}

internal class FaultyAlbums(private val inner: SmartAlbumsPeople, private val faults: PortFaults) : SmartAlbumsPeople {
  override suspend fun albumsWithPeople(): Int = faults.tick("albums.count") { inner.albumsWithPeople() }

  override suspend fun release(choice: PeopleAlbumsChoice): Int = faults.tick("albums.release") { inner.release(choice) }
}

internal class FaultyHistory(private val inner: SearchHistoryPurge, private val faults: PortFaults) : SearchHistoryPurge {
  override suspend fun purge(names: Collection<String>, persons: Collection<PersonId>): Int = faults.tick("history.purge") { inner.purge(names, persons) }

  override suspend fun purgePersonSearches(): Int = faults.tick("history.purgePersons") { inner.purgePersonSearches() }
}

internal class FaultyOps(private val inner: FacesEraseOps, private val faults: PortFaults) : FacesEraseOps {
  override suspend fun generations(): List<FaceGeneration>? = faults.tick("nas.generations") { inner.generations() }

  override suspend fun eraseGeneration(generation: FaceGeneration, reason: NasEraseReason, budget: Duration): NasEraseRound =
    faults.tick("nas.eraseGeneration") { inner.eraseGeneration(generation, reason, budget) }

  override suspend fun eraseDevice(generation: FaceGeneration, deviceId: String, reason: NasEraseReason, budget: Duration): NasEraseRound =
    faults.tick("nas.eraseDevice") { inner.eraseDevice(generation, deviceId, reason, budget) }

  override suspend fun verify(generation: FaceGeneration): NasEraseCheck? = faults.tick("nas.verify") { inner.verify(generation) }
}

internal class FaultyAnnouncer(private val inner: EraseAnnouncer, private val faults: PortFaults) : EraseAnnouncer {
  override suspend fun announce(budget: Duration): AnnounceResult = faults.tick("announce") { inner.announce(budget) }
}

internal class FaultyHeads(private val inner: PersonalHeadsView, private val faults: PortFaults) : PersonalHeadsView {
  override suspend fun latestValueOf(key: PersonalKey): Map<String, HeadValue> = faults.tick("heads") { inner.latestValueOf(key) }
}

/** Il diario che non si scrive alla chiamata [PortFaults.failAt] (disco pieno): un `IOException`, come `StateFiles`. */
internal class FaultyJournal(private val inner: EraseJournalStore, private val faults: PortFaults) : EraseJournalStore {
  override fun load(): EraseJournalRead = inner.load()

  override fun save(journal: EraseJournal) {
    faults.tickSync("journal.save") { inner.save(journal) }
  }

  override fun clear(): Boolean = inner.clear()
}
