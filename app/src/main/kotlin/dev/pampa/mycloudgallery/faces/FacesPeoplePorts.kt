package dev.pampa.mycloudgallery.faces

import dev.pampa.mycloudgallery.albums.AlbumsLive
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.PeopleClass
import dev.pampa.mycloudgallery.core.albums.contract.PeopleMembership
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.smart.LookupPeopleMembership
import dev.pampa.mycloudgallery.core.faces.contract.MatchLevel
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.PeopleCells
import dev.pampa.mycloudgallery.core.faces.contract.PeopleIndex
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.core.faces.contract.SearchHistoryPurge
import dev.pampa.mycloudgallery.core.faces.contract.SmartAlbumsPeople
import dev.pampa.mycloudgallery.core.faces.people.PeopleVisibility
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.PersonEntry
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.SearchHistory
import dev.pampa.mycloudgallery.core.search.noop.NoPeopleLookup
import dev.pampa.mycloudgallery.feature.albums.contract.PeopleChoices
import dev.pampa.mycloudgallery.feature.albums.contract.PeopleRuleInfo
import dev.pampa.mycloudgallery.feature.albums.contract.PersonChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// LE PERSONE PER LA RICERCA E PER GLI ALBUM SMART (Fase 8, P13; docs/fase8-progetto.md §1.4, §2.7, §2.8, D26, D27, D28; "Ganci per P13" di P10): `PeopleLookup`
// sopra l'indice delle persone, le celle e la visibilita'; `PeopleMembership` sopra la stessa porta; `PeopleChoices` per l'editor degli album; il
// ponte che li lega ai moduli che li usano prima che le persone siano montate; "Disattiva e cancella" verso gli album e la cronologia. Cerca e gli album
// partono con i null-object e **falliscono chiusi** (il nome resta testo, la regola non inserisce niente) finche' le persone non sono montate e attive qui.
// Mai nomi, id o conteggi per persona nei log (D37).

/**
 * La ricerca per persona sopra l'indice delle persone (la forma che la sonda `search-people` del registro dei Nascosti ha provato): per ogni persona
 * della scelta le chiavi di contenuto al livello chiesto ([PeopleIndex.contentWith]), portate alle **celle** ([PeopleCells]: un membro di un gruppo e'
 * la sua copertina) e tenute solo se [PeopleVisibility] dice che si possono mostrare adesso (`VisibleMedia`, filtro finale `HiddenMask`, D28); poi "una
 * qualunque" unisce le celle e "tutte" le interseca **per cella**. Il dizionario e' quello dell'indice: nomi di persone non nascoste ne' dimenticate, con
 * almeno un media mostrabile. Si cerca per nome, mai per somiglianza (D26): nessuna firma prende vettori, volti o chiavi di contenuto.
 */
class AppPeopleLookup(
  private val index: PeopleIndex,
  private val cells: PeopleCells,
  private val visibility: PeopleVisibility,
  override val progressPercent: StateFlow<Int?> = PeopleLookup.NO_PROGRESS,
) : PeopleLookup {
  override val available: StateFlow<Boolean> get() = index.available

  override suspend fun dictionary(): PeopleDictionary {
    if (!index.available.value) return PeopleDictionary.EMPTY
    return PeopleDictionary(index.dictionary().map { PersonEntry(PeopleDictionary.normalize(it.name), it.id, it.name, it.visibleMedia) })
  }

  override suspend fun cells(option: PersonOption, includeLikely: Boolean): Set<MediaId> {
    if (!index.available.value) return emptySet()
    val level = if (includeLikely) MatchLevel.LIKELY else MatchLevel.SURE
    val perPerson = option.ids.map { id -> cellsOf(id, level) }
    if (perPerson.isEmpty()) return emptySet()
    return when (option.mode) {
      PeopleMode.ANY -> perPerson.flatten().toSet()
      PeopleMode.ALL -> perPerson.reduce { acc, set -> acc intersect set }
    }
  }

  private suspend fun cellsOf(id: PersonId, level: MatchLevel): Set<MediaId> {
    val keys = index.contentWith(setOf(id), PeopleMode.ANY, level).keys
    if (keys.isEmpty()) return emptySet()
    return visibility.shown(cells.cellsWith(keys).values.flatten())
  }

  override fun toString(): String = "AppPeopleLookup"
}

/**
 * Il ponte fra le persone (montate dopo) e chi le usa (ricerca e album smart, nati prima): la stessa istanza serve [PeopleLookup] a Cerca e a
 * [PeopleMembership] agli album. Finche' non e' legato, o dopo [unbind], risponde come i null-object (nessun dizionario, nessun candidato, la regola
 * delle persone non inserisce niente): **fallisce chiuso** (E-F37).
 */
class PeopleBridge : PeopleLookup, PeopleMembership {
  @Volatile private var lookup: PeopleLookup = NoPeopleLookup

  @Volatile private var membership: PeopleMembership? = null

  private val availableFlow = MutableStateFlow(false)
  private val progressFlow = MutableStateFlow<Int?>(null)
  private var watching: Job? = null

  override val available: StateFlow<Boolean> get() = availableFlow.asStateFlow()

  override val progressPercent: StateFlow<Int?> get() = progressFlow.asStateFlow()

  /** Lega le persone vere: da qui in poi Cerca e gli album le interrogano. */
  @Synchronized
  fun bind(target: PeopleLookup, albums: PeopleMembership, scope: CoroutineScope) {
    watching?.cancel()
    lookup = target
    membership = albums
    availableFlow.value = target.available.value
    progressFlow.value = target.progressPercent.value
    watching = scope.launch {
      launch { target.available.collect { availableFlow.value = it } }
      launch { target.progressPercent.collect { progressFlow.value = it } }
    }
  }

  /** Le persone non ci sono piu' (azzeramento, stop): di nuovo i null-object. */
  @Synchronized
  fun unbind() {
    watching?.cancel()
    watching = null
    lookup = NoPeopleLookup
    membership = null
    availableFlow.value = false
    progressFlow.value = null
  }

  override suspend fun dictionary(): PeopleDictionary = lookup.dictionary()

  override suspend fun cells(option: PersonOption, includeLikely: Boolean): Set<MediaId> = lookup.cells(option, includeLikely)

  override suspend fun classify(rule: PeopleRule, candidates: Collection<MediaId>): Map<MediaId, PeopleClass> =
    membership?.classify(rule, candidates).orEmpty()

  override suspend fun unrecognized(rule: PeopleRule): Int = membership?.unrecognized(rule) ?: 0

  companion object {
    /** Le persone vere per gli album: la stessa porta della ricerca, con gli alias seguiti da [PeopleIndex.resolve]. */
    fun membershipOf(lookup: PeopleLookup, index: PeopleIndex): PeopleMembership = LookupPeopleMembership(lookup, index::resolve)
  }
}

/**
 * Le persone che l'editor degli album smart offre (D27, E-F23, E-F27, E-F28, E-F37): quelle con un nome e un media mostrabile, le piu' fotografate prima
 * (la lista di [People] le da' gia' cosi', senza nascoste); lo stato di quelle di una regola dall'indice (alias seguiti: una persona unita si riconosce
 * ancora, una dimenticata no, una nascosta vale e non si nomina).
 */
class AppPeopleChoices(private val people: People, private val index: PeopleIndex, private val hidden: suspend () -> Set<PersonId> = { emptySet() }) :
  PeopleChoices {
  override val available: StateFlow<Boolean> get() = index.available

  override suspend fun choices(): List<PersonChoice> {
    if (!index.available.value) return emptyList()
    return people.list.value.named.mapNotNull { card -> card.name?.let { PersonChoice(card.id, it, card.photos) } }
  }

  override suspend fun inspect(ids: List<PersonId>): PeopleRuleInfo {
    if (!index.available.value) return PeopleRuleInfo.EMPTY
    val hiddenNow = hidden()
    val named = people.list.value.named.associateBy { it.id }
    val names = LinkedHashMap<PersonId, String>()
    val lost = LinkedHashSet<PersonId>()
    for (id in ids) {
      val target = index.resolve(id)
      if (target == null) {
        lost += id
        continue
      }
      // Una persona nascosta vale ancora e non si nomina (E-F28).
      if (target in hiddenNow) continue
      named[target]?.name?.let { names[id] = it }
    }
    return PeopleRuleInfo(names, lost)
  }
}

/**
 * "Disattiva e cancella" verso gli album smart con le persone (§2.8 passo 3, D27): quanti sono, e sciogliere la regola con la scelta dell'utente. Con
 * [PeopleAlbumsChoice.KEEP_PHOTOS] l'album si **congela** (gli elementi di adesso diventano elementi con impronta, D16) e poi la regola delle persone
 * si toglie; con [PeopleAlbumsChoice.DROP_RULE] si toglie solo la regola. Si fa **prima** di cancellare i dati dei volti. Idempotente: un album gia'
 * senza la regola non conta piu'. Senza gli album montati (famiglia non pronta) non c'e' niente che si possa sciogliere: zero.
 */
class AppSmartAlbumsPeople(private val live: () -> AlbumsLive?) : SmartAlbumsPeople {

  override suspend fun albumsWithPeople(): Int = live()?.let { withPeople(it).size } ?: 0

  override suspend fun release(choice: PeopleAlbumsChoice): Int {
    val albums = live() ?: return 0
    var done = 0
    for (album in withPeople(albums)) {
      try {
        if (choice == PeopleAlbumsChoice.KEEP_PHOTOS && !album.frozen && albums.albums.freeze(album.id) !is EditOutcome.Done) continue
        if (albums.albums.setRules(album.id, album.rules.copy(people = null)) is EditOutcome.Done) done++
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Un album che non si scioglie adesso resta com'e': il diario riprova (poi lo segna "saltato").
      }
    }
    return done
  }

  private fun withPeople(albums: AlbumsLive) = albums.albums.all.value.smart.filter { it.rules.people != null }

  override fun toString(): String = "AppSmartAlbumsPeople"
}

/** Le ricerche recenti che nominano una persona si tolgono a parole intere (§2.8 passo 4): `SearchHistory.purge`. */
class AppSearchHistoryPurge(private val history: () -> SearchHistory) : SearchHistoryPurge {
  override suspend fun purge(names: Collection<String>): Int = history().purge(names)
}
