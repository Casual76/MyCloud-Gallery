package dev.pampa.mycloudgallery.core.albums.testing

import dev.pampa.mycloudgallery.core.albums.contract.PeopleClass
import dev.pampa.mycloudgallery.core.albums.contract.PeopleMembership
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fase 8 (P0, D27): le persone finte per i test degli album smart (P10). Per ogni persona i media **sicuri** e quelli **suggeriti**; la
 * classe di un media segue la regola: con "tutte" e' sicuro se e' sicuro per ognuna, suggerito se per ognuna e' almeno suggerito; con
 * "una qualunque" sicuro se e' sicuro per una, suggerito se e' almeno suggerito per una. Con [available] falso o con [failing] vero **lancia** (R10: "non so" non vale "nessuno").
 */
class FakePeopleMembership : PeopleMembership {
  override val available = MutableStateFlow(true)

  /** Se vero i volti "non si leggono adesso" (il Keystore non risponde): `classify` lancia, anche con [available] vero. */
  @Volatile var failing: Boolean = false

  private val sure = HashMap<PersonId, MutableSet<MediaId>>()
  private val likely = HashMap<PersonId, MutableSet<MediaId>>()

  /** Quante volte e' stato chiesto (il motore lo chiede a lotti, P10). */
  var classifyCalls: Int = 0
    private set

  fun sure(id: PersonId, media: Collection<MediaId>): FakePeopleMembership = synchronized(this) {
    sure.getOrPut(id) { LinkedHashSet() } += media
    this
  }

  fun likely(id: PersonId, media: Collection<MediaId>): FakePeopleMembership = synchronized(this) {
    likely.getOrPut(id) { LinkedHashSet() } += media
    this
  }

  override suspend fun classify(rule: PeopleRule, candidates: Collection<MediaId>): Map<MediaId, PeopleClass> = synchronized(this) {
    classifyCalls++
    // R10 (correzioni D): come la porta vera, "i volti non ci sono adesso" e' "non so": lancia (il motore non scrive niente).
    check(available.value && !failing) { "Le persone non sono leggibili adesso." }
    val out = LinkedHashMap<MediaId, PeopleClass>()
    for (media in candidates) {
      val classes = rule.ids.map { classOf(it, media) }
      val result = when (rule.mode) {
        PeopleMode.ALL -> when {
          classes.all { it == PeopleClass.SURE } -> PeopleClass.SURE
          classes.all { it != null } -> PeopleClass.LIKELY
          else -> null
        }
        PeopleMode.ANY -> when {
          classes.any { it == PeopleClass.SURE } -> PeopleClass.SURE
          classes.any { it != null } -> PeopleClass.LIKELY
          else -> null
        }
      }
      if (result != null) out[media] = result
    }
    out
  }

  private fun classOf(id: PersonId, media: MediaId): PeopleClass? = when (media) {
    in sure[id].orEmpty() -> PeopleClass.SURE
    in likely[id].orEmpty() -> PeopleClass.LIKELY
    else -> null
  }
}
