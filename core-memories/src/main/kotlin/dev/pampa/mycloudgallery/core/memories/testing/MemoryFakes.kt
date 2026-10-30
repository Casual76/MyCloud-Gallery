package dev.pampa.mycloudgallery.core.memories.testing

import dev.pampa.mycloudgallery.core.memories.contract.ClipSource
import dev.pampa.mycloudgallery.core.memories.contract.LookAlike
import dev.pampa.mycloudgallery.core.memories.contract.MemoryAlbumSignals
import dev.pampa.mycloudgallery.core.memories.contract.MemoryClips
import dev.pampa.mycloudgallery.core.memories.contract.MemoryClock
import dev.pampa.mycloudgallery.core.memories.contract.MemoryCutouts
import dev.pampa.mycloudgallery.core.memories.contract.MemoryLookAlikes
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPixels
import dev.pampa.mycloudgallery.core.memories.contract.MemoryTrips
import dev.pampa.mycloudgallery.core.memories.contract.MemoryVectors
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.MusicSettings
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedger
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedgerStore
import dev.pampa.mycloudgallery.core.memories.contract.NotifyMark
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.NotifySettings
import dev.pampa.mycloudgallery.core.memories.contract.PersonDigest
import dev.pampa.mycloudgallery.core.memories.contract.PixelProbe
import dev.pampa.mycloudgallery.core.memories.contract.TripDigestView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I fake delle porte dei ricordi (Fase 9, P0, §5.2): per le suite di contratto, P6-P11 e le prove sul campo (`FakeMemoryClock` sposta "oggi").
// Fedeli alla regola di ogni porta: persone nascoste o senza nome mai, solo media visibili, nessuna coordinata, nessun nome salvato.

/** "Oggi" e l'ora scelti dal test (§5.2); l'orologio monotono avanza solo con [advance]. */
class FakeMemoryClock(var today: LocalDate = LocalDate.of(2026, 10, 27), var time: LocalTime = LocalTime.of(19, 0)) : MemoryClock {
  private var monotonic = 1_000L

  override fun today(): LocalDate = today

  override fun nowLocal(): LocalDateTime = today.atTime(time)

  override fun monotonicMs(): Long = monotonic

  fun advance(ms: Long) {
    monotonic += ms
  }
}

/** I viaggi del mondo finto (gia' senza scartati e con i soli media visibili: lo fa la porta vera, `TripMemoriesContract`). */
class FakeMemoryTrips(var trips: List<TripDigestView> = emptyList()) : MemoryTrips {
  var calls: Int = 0
    private set

  override suspend fun past(until: LocalDate, budget: Duration): List<TripDigestView> {
    calls++
    return trips.filter { it.days.to.isBefore(until) }.sortedByDescending { it.days.from }
  }
}

/**
 * Le persone finte (D12): [people] persona -> media in cui compare (con nome), [hidden] persone nascoste o dimenticate (non ci sono mai),
 * [aliases] le unioni (la vecchia -> la nuova), [names] i nomi da mostrare. [visible] filtra i media (i nascosti della Libreria).
 */
class FakeMemoryPeople(
  available: Boolean = true,
  private val visible: suspend (Collection<MediaId>) -> Set<MediaId> = { it.toSet() },
) : MemoryPeople {
  override val available: MutableStateFlow<Boolean> = MutableStateFlow(available)

  val people: MutableMap<PersonId, MutableSet<MediaId>> = LinkedHashMap()
  val hidden: MutableSet<PersonId> = HashSet()
  val aliases: MutableMap<PersonId, PersonId> = HashMap()
  val names: MutableMap<PersonId, String> = HashMap()

  fun person(id: PersonId, name: String, media: Collection<MediaId>): FakeMemoryPeople = apply {
    people.getOrPut(id) { LinkedHashSet() } += media
    names[id] = name
  }

  override suspend fun named(): List<PersonDigest> {
    if (!available.value) return emptyList()
    return people.entries.filter { it.key !in hidden && it.key !in aliases && names[it.key] != null }
      .map { (id, media) -> PersonDigest(id, media.filter { it in visible(media) }) }
      .sortedBy { it.person }
  }

  override suspend fun peopleIn(ids: Collection<MediaId>): Map<MediaId, Set<PersonId>> {
    if (!available.value) return emptyMap()
    val shown = visible(ids)
    val out = HashMap<MediaId, MutableSet<PersonId>>()
    for ((person, media) in people) {
      if (person in hidden || person in aliases || names[person] == null) continue
      for (id in ids) if (id in shown && id in media) out.getOrPut(id) { HashSet() } += person
    }
    return out
  }

  override suspend fun resolve(person: PersonId): PersonId? {
    if (!available.value) return null
    var current = person
    repeat(8) { current = aliases[current] ?: return@repeat }
    return current.takeIf { it !in hidden && names[it] != null }
  }

  override suspend fun label(person: PersonId): String? = resolve(person)?.let { names[it] }
}

/**
 * Vettori finti normalizzati, messi dal test (doppioni seminati: stesso vettore o quasi, `SyntheticVectors`). Fedele alla porta (suite
 * `MemoryVectorsContract`): risponde solo per gli id chiesti e che conosce, e ogni risposta porta **copie** (chi la riceve puo' scriverci senza
 * toccare lo stato della sorgente, come un adattatore che legge dal database). [calls] conta le richieste, [asked] gli id chiesti in tutto.
 */
class FakeMemoryVectors(val vectors: MutableMap<MediaId, FloatArray> = HashMap()) : MemoryVectors {
  var calls: Int = 0
    private set
  var asked: Int = 0
    private set

  override suspend fun vectors(ids: Collection<MediaId>): Map<MediaId, FloatArray> {
    calls++
    asked += ids.size
    val out = LinkedHashMap<MediaId, FloatArray>()
    for (id in ids) vectors[id]?.let { out[id] = it.copyOf() }
    return out
  }
}

/** La firma visiva finta: [pairs] le coppie "quasi lo stesso scatto"; le altre "non si sa" se [unknownElse], altrimenti "no". */
class FakeMemoryLookAlikes(private val pairs: Set<Set<MediaId>>, private val unknownElse: Boolean = false) : MemoryLookAlikes {
  override suspend fun among(ids: Collection<MediaId>): LookAlike = LookAlike { a, b ->
    when {
      setOf(a, b) in pairs -> true
      unknownElse -> null
      else -> false
    }
  }
}

/**
 * Misure dei pixel da tabella (foto buie, mosse). Fedele alla porta (suite `MemoryPixelsContract`): risponde solo per gli id chiesti e che conosce,
 * e rispetta il budget: ogni misura costa [costPerId] e, quando il budget finisce, si ferma (le misure gia' fatte restano, quelle dopo no).
 * Con il costo a zero risponde sempre, anche a budget zero (una misura in cache non costa niente). [asked] conta gli id chiesti in tutto.
 */
class FakeMemoryPixels(
  val probes: MutableMap<MediaId, PixelProbe> = HashMap(),
  private val costPerId: Duration = Duration.ZERO,
) : MemoryPixels {
  var calls: Int = 0
    private set
  var asked: Int = 0
    private set

  override suspend fun probe(ids: Collection<MediaId>, budget: Duration): Map<MediaId, PixelProbe> {
    calls++
    asked += ids.size
    val out = LinkedHashMap<MediaId, PixelProbe>()
    var spent = Duration.ZERO
    for (id in ids) {
      val probe = probes[id] ?: continue
      if (spent + costPerId > budget) break
      spent += costPerId
      out[id] = probe
    }
    return out
  }
}

class FakeMemoryAlbumSignals(val inAlbums: MutableSet<MediaId> = HashSet()) : MemoryAlbumSignals {
  override suspend fun inManualAlbums(ids: Collection<MediaId>): Set<MediaId> = ids.filter { it in inAlbums }.toSet()
}

class FakeMemoryCutouts(val ids: MutableSet<MediaId> = HashSet()) : MemoryCutouts {
  override suspend fun cutouts(): Set<MediaId> = ids.toSet()
}

/** Clip finti: solo per i media in [phoneVideos] (i video del solo NAS non ne hanno, E-MEM17). */
class FakeMemoryClips(val phoneVideos: MutableSet<MediaId> = HashSet()) : MemoryClips {
  override suspend fun clip(id: MediaId): ClipSource? = if (id in phoneVideos) ClipSource("clip-finto", 8_000) else null
}

class InMemoryNotifySettings(initial: NotifyPrefs = NotifyPrefs()) : NotifySettings {
  private val state = MutableStateFlow(initial)
  override val prefs: StateFlow<NotifyPrefs> = state

  override suspend fun set(prefs: NotifyPrefs) {
    state.value = prefs
  }
}

/** Il registro del giorno in memoria (E-NT5): [failNextRecord] simula la morte del processo prima della scrittura. */
class InMemoryNotifyLedgerStore(private var ledger: NotifyLedger = NotifyLedger()) : NotifyLedgerStore {
  var failNextRecord: Boolean = false

  override suspend fun read(): NotifyLedger = ledger

  override suspend fun record(mark: NotifyMark, today: LocalDate) {
    if (failNextRecord) {
      failNextRecord = false
      throw IllegalStateException("morte simulata prima della scrittura")
    }
    ledger = ledger.with(mark, today)
  }

  override suspend fun clear() {
    ledger = NotifyLedger()
  }
}

class InMemoryMusicSettings(initial: MusicChoice = MusicChoice.None) : MusicSettings {
  private val state = MutableStateFlow(initial)
  override val choice: StateFlow<MusicChoice> = state

  override suspend fun set(choice: MusicChoice) {
    state.value = choice
  }
}
