package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.PeopleClass
import dev.pampa.mycloudgallery.core.albums.contract.PeopleMembership
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import kotlinx.coroutines.flow.StateFlow

// LE PERSONE NEGLI ALBUM SMART (docs/fase8-progetto.md §2.7 "Album smart", D27, D28, E-F23, E-F27, E-F28, E-F37). Il motore (`RulesSmartEngine`) chiede a [PeopleMembership]
// la classe di ogni candidato -- **solo celle visibili**, mai un nascosto -- e [SmartClassifier] le combina "in e" con le altre regole.

/**
 * Cio' che la regola delle persone dice dei candidati caricati: la classe di ognuno ([PeopleClass.SURE] o [PeopleClass.LIKELY]); un media che non c'e' non e' di nessuna persona
 * della regola. [NONE] = niente regola delle persone. Mai gli id nei log.
 */
class PeopleMatches(private val classes: Map<MediaId, PeopleClass>) {
  fun classOf(media: MediaId): PeopleClass? = classes[media]

  override fun toString(): String = "PeopleMatches(${classes.size})"

  companion object {
    val NONE = PeopleMatches(emptyMap())
  }
}

/**
 * [PeopleMembership] sopra la porta della ricerca [PeopleLookup]: la regola delle persone e' la scelta di persone di un chip "Persona" (`PersonOption`), quindi le celle "sicure" e
 * quelle con anche il "forse" le da' la stessa porta che `:app` lega per la ricerca (P13: una porta sola). Un media e' **sicuro** se sta fra le celle sicure di quella scelta
 * ("tutte" o "una qualunque", come il chip), **suggerito** se sta solo fra quelle con il "forse"; senza volti qui ([available] falso) non classifica niente (E-F37).
 *
 * [resolve] segue gli alias e dice null per una persona dimenticata o sconosciuta (`PeopleIndex.resolve`, E-F23, E-F27): serve solo a contare le persone non piu'
 * riconosciute ([unrecognized]); le classi le risolve la porta. Di serie ogni persona si riconosce.
 */
class LookupPeopleMembership(
  private val lookup: PeopleLookup,
  private val resolve: (PersonId) -> PersonId? = { it },
) : PeopleMembership {

  override val available: StateFlow<Boolean> get() = lookup.available

  override suspend fun classify(rule: PeopleRule, candidates: Collection<MediaId>): Map<MediaId, PeopleClass> {
    if (!lookup.available.value || candidates.isEmpty()) return emptyMap()
    val option = PersonOption(rule.ids, rule.mode, label = "")
    val sure = lookup.cells(option, includeLikely = false)
    val likely = lookup.cells(option, includeLikely = true)
    val out = LinkedHashMap<MediaId, PeopleClass>()
    for (media in candidates) {
      when {
        media in sure -> out[media] = PeopleClass.SURE
        media in likely -> out[media] = PeopleClass.LIKELY
      }
    }
    return out
  }

  override suspend fun unrecognized(rule: PeopleRule): Int = rule.ids.count { resolve(it) == null }

  override fun toString(): String = "LookupPeopleMembership"
}
