package dev.pampa.mycloudgallery.core.faces.testing

import dev.pampa.mycloudgallery.core.faces.contract.FacesArea
import dev.pampa.mycloudgallery.core.faces.contract.FacesCatalog
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseOps
import dev.pampa.mycloudgallery.core.faces.contract.FacesItem
import dev.pampa.mycloudgallery.core.faces.contract.FacesLease
import dev.pampa.mycloudgallery.core.faces.contract.FacesLeases
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocalStore
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.IndexerState
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseCheck
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseReason
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseRound
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.contract.SearchHistoryPurge
import dev.pampa.mycloudgallery.core.faces.contract.SmartAlbumsPeople
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I fake semplici delle porte di core-faces (Fase 8, P0, §5.2): orologi, stato locale, requisiti, catalogo, album, cronologia, e le porte
// che **falliscono se chiamate** (§4.3: "porte finte che falliscono se chiamate fuori dalle regole"). In src/main/testing per i pacchetti
// P3-P13; mai nella release (tools/check.ps1).

/** Gli orologi finti (D36): il monotono e l'ora del NAS avanzano solo quando il test lo dice; [nasNow] null = nessuna ora del NAS. */
class FakeFacesClock(var monotonic: Long = 1_000_000_000L, var nasNow: Long? = 1_800_000_000_000L) : FacesClock {
  override fun monotonicNanos(): Long = monotonic

  override fun nasNowMillis(): Long? = nasNow

  fun advanceMillis(millis: Long) {
    monotonic += millis * 1_000_000
    nasNow = nasNow?.plus(millis)
  }
}

/** Lo stato locale in memoria (D4): ogni [update] e' subito "durevole"; [updates] le conta. */
class InMemoryFacesLocalStore(initial: FacesLocal = FacesLocal.EMPTY) : FacesLocalStore {
  private val state = MutableStateFlow(initial)
  override val local: StateFlow<FacesLocal> = state

  var updates: Int = 0
    private set

  override suspend fun update(transform: (FacesLocal) -> FacesLocal): FacesLocal = synchronized(this) {
    updates++
    transform(state.value).also { state.value = it }
  }
}

/** I requisiti a mano (D5, D34). */
class FakeFacesRequirements(
  var privateArea: PrivateAreaState = PrivateAreaState.Declared("test"),
  var indexers: IndexerState = IndexerState(accountIndexers = 1, thisDevice = true, canBecomeIndexer = true),
  var localOnly: Boolean = false,
) : FacesRequirements {
  override suspend fun privateArea(): PrivateAreaState = privateArea

  override suspend fun indexers(): IndexerState = indexers

  override suspend fun localOnly(share: String): Boolean = localOnly
}

/** Il catalogo dei volti in memoria: i media per area e mese, le chiavi delle celle (con i membri) e il contrario. */
class FakeFacesCatalog : FacesCatalog {
  val items: MutableList<FacesItem> = mutableListOf()
  val members: MutableMap<MediaId, Set<ContentKey>> = mutableMapOf()

  override suspend fun months(area: FacesArea): List<String> = items.filter { it.area == area }.map { it.month }.distinct().sortedDescending()

  override suspend fun items(area: FacesArea, month: String): List<FacesItem> =
    items.filter { it.area == area && it.month == month }.sortedBy { it.contentKey.hex }

  override suspend fun cellKeys(media: Collection<MediaId>): Map<MediaId, Set<ContentKey>> =
    media.associateWith { id -> members[id] ?: items.filter { it.media == id }.map { it.contentKey }.toSet() }

  override suspend fun mediaWith(keys: Collection<ContentKey>): Map<ContentKey, Set<MediaId>> =
    keys.associateWith { key -> items.filter { it.contentKey == key }.map { it.media }.toSet() + members.filter { key in it.value }.keys }
}

/** Gli album smart con le persone finti: quanti e cosa e' stato chiesto. */
class FakeSmartAlbumsPeople(var albums: Int = 0) : SmartAlbumsPeople {
  val released: MutableList<PeopleAlbumsChoice> = mutableListOf()

  override suspend fun albumsWithPeople(): Int = albums

  override suspend fun release(choice: PeopleAlbumsChoice): Int {
    released += choice
    return albums.also { albums = 0 }
  }
}

/** La cronologia finta: le voci (testo e chip) e quali nomi sono stati tolti. */
class FakeSearchHistoryPurge(val entries: MutableList<String> = mutableListOf()) : SearchHistoryPurge {
  /** Le persone di cui e' stata chiesta la cancellazione per id (R2), nell'ordine delle chiamate. */
  val personsAsked: MutableList<PersonId> = mutableListOf()

  /** I nomi di cui e' stata chiesta la cancellazione, nell'ordine delle chiamate. */
  val namesAsked: MutableList<String> = mutableListOf()

  /** Se vero la cronologia "non risponde": `purge` e `purgePersonSearches` lanciano. */
  var failing: Boolean = false

  /** Quante volte e' stata chiesta la cancellazione di ogni ricerca con persone (R2: i nomi non si leggono). */
  var personSearchesPurged: Int = 0
    private set

  override suspend fun purge(names: Collection<String>, persons: Collection<PersonId>): Int {
    check(!failing) { "La cronologia non risponde." }
    personsAsked += persons
    namesAsked += names
    val lowered = names.map { it.lowercase() }
    val before = entries.size
    entries.removeAll { e -> lowered.any { it in e.lowercase() } }
    return before - entries.size
  }

  override suspend fun purgePersonSearches(): Int {
    check(!failing) { "La cronologia non risponde." }
    personSearchesPurged++
    return 0
  }
}

/** Una chiamata che le regole vietano: la prova fallisce con il nome della porta (§4.3). */
class ForbiddenCall(port: String) : AssertionError("Porta chiamata fuori dalle regole: $port")

/**
 * Le porte che **falliscono se chiamate** (§4.3): per le prove "un dato remoto falso non cancella niente fuori da `faces/`", "il calcolo
 * su un dispositivo in attesa dell'avviso non tocca niente", "con la recinzione nessuna prenotazione". Ogni chiamata lancia [ForbiddenCall].
 */
object FailingFacesPorts {
  val eraseOps: FacesEraseOps = object : FacesEraseOps {
    override suspend fun generations(): List<FaceGeneration>? = throw ForbiddenCall("FacesEraseOps.generations")

    override suspend fun eraseGeneration(generation: FaceGeneration, reason: NasEraseReason, budget: Duration): NasEraseRound =
      throw ForbiddenCall("FacesEraseOps.eraseGeneration")

    override suspend fun eraseDevice(generation: FaceGeneration, deviceId: String, reason: NasEraseReason, budget: Duration): NasEraseRound =
      throw ForbiddenCall("FacesEraseOps.eraseDevice")

    override suspend fun verify(generation: FaceGeneration): NasEraseCheck? = throw ForbiddenCall("FacesEraseOps.verify")
  }

  val leases: FacesLeases = object : FacesLeases {
    override suspend fun acquire(generation: FaceGeneration, resource: FacesResource): FacesLease = throw ForbiddenCall("FacesLeases.acquire")

    override suspend fun renew(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held): FacesLease =
      throw ForbiddenCall("FacesLeases.renew")

    override suspend fun release(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held) = throw ForbiddenCall("FacesLeases.release")
  }

  val albums: SmartAlbumsPeople = object : SmartAlbumsPeople {
    override suspend fun albumsWithPeople(): Int = throw ForbiddenCall("SmartAlbumsPeople.albumsWithPeople")

    override suspend fun release(choice: PeopleAlbumsChoice): Int = throw ForbiddenCall("SmartAlbumsPeople.release")
  }

  val history: SearchHistoryPurge = object : SearchHistoryPurge {
    override suspend fun purge(names: Collection<String>, persons: Collection<PersonId>): Int = throw ForbiddenCall("SearchHistoryPurge.purge")

    override suspend fun purgePersonSearches(): Int = throw ForbiddenCall("SearchHistoryPurge.purgePersonSearches")
  }
}
